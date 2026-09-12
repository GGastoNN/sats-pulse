import { CATALOG } from "./catalog.js";
import { seal, unseal } from "./crypto.js";

const SPEED_API = "https://api.tryspeed.com";
const SPEED_VERSION = "2022-10-15";
const ORDER_TTL_MS = 25 * 60 * 1000;

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
      const url = new URL(request.url);

      if (url.pathname === "/health") {
        return cors(json({
          ok: true,
          mode: env.PAYMENT_MODE || "auto",
          recipientConfigured: Boolean(env.RECIPIENT_LIGHTNING_ADDRESS),
          speedConfigured: Boolean(env.SPEED_API_KEY)
        }));
      }

      if (url.pathname === "/api/catalog" && request.method === "GET") {
        return cors(json({
          items: Object.entries(CATALOG).map(([sku, item]) => ({ sku, ...item }))
        }));
      }

      if (url.pathname === "/api/checkout" && request.method === "POST") {
        return cors(await createCheckout(request, env));
      }

      if (url.pathname === "/api/status" && request.method === "GET") {
        return cors(await checkStatus(url, env));
      }

      return cors(json({ error: "not_found" }, 404));
    } catch (error) {
      console.error("worker_error", error?.message || error);
      return cors(json({ error: safeMessage(error) }, 500));
    }
  }
};

async function createCheckout(request, env) {
  ensureCoreSecrets(env);
  const input = await readJson(request);
  const sku = String(input?.sku || "").slice(0, 64);
  const item = CATALOG[sku];
  if (!item) return json({ error: "invalid_sku" }, 400);

  const deviceId = String(input?.deviceId || "anonymous").slice(0, 128);
  const deviceHash = await shortHash(deviceId);
  const requestedMode = String(env.PAYMENT_MODE || "auto").toLowerCase();
  const mode = resolveMode(requestedMode, env);

  const provider = mode === "speed_checkout"
    ? await createSpeedCheckout(env, sku, item, deviceHash)
    : await createLightningAddressInvoice(env, item.priceSats, sku);

  const expiresAt = Date.now() + ORDER_TTL_MS;
  const tokenProvider = { ...provider };
  delete tokenProvider.checkoutUrl;

  const orderToken = await seal(env.ORDER_SIGNING_SECRET, {
    v: 2,
    mode,
    sku,
    priceSats: item.priceSats,
    deviceHash,
    expiresAt,
    provider: tokenProvider
  });

  return json({
    orderToken,
    sku,
    priceSats: item.priceSats,
    checkoutUrl: provider.checkoutUrl,
    provider: mode
  });
}

async function checkStatus(url, env) {
  ensureCoreSecrets(env);
  const token = url.searchParams.get("orderToken");
  if (!token) return json({ error: "missing_order_token" }, 400);

  let order;
  try {
    order = await unseal(env.ORDER_SIGNING_SECRET, token);
  } catch {
    return json({ error: "invalid_order_token" }, 400);
  }

  if (!CATALOG[order.sku] || CATALOG[order.sku].priceSats !== order.priceSats) {
    return json({ error: "catalog_mismatch" }, 409);
  }

  if (order.expiresAt < Date.now()) {
    return json({ paid: false, sku: order.sku, status: "expired" });
  }

  const status = order.mode === "speed_checkout"
    ? await speedCheckoutStatus(env, order.provider)
    : await lightningAddressStatus(order.provider);

  return json({ paid: status.paid, sku: order.sku, status: status.status });
}

function resolveMode(requestedMode, env) {
  if (requestedMode === "auto") return env.SPEED_API_KEY ? "speed_checkout" : "lightning_address";
  if (requestedMode === "speed_checkout") {
    if (!env.SPEED_API_KEY) throw new Error("SPEED_API_KEY is required for speed_checkout mode");
    return requestedMode;
  }
  if (requestedMode === "lightning_address") return requestedMode;
  throw new Error("Unsupported PAYMENT_MODE");
}

function ensureCoreSecrets(env) {
  const recipient = String(env.RECIPIENT_LIGHTNING_ADDRESS || "").trim();
  if (!/^([^@\s]+)@([^@\s]+)$/.test(recipient)) {
    throw new Error("RECIPIENT_LIGHTNING_ADDRESS is not configured");
  }
  if (!env.ORDER_SIGNING_SECRET || String(env.ORDER_SIGNING_SECRET).length < 24) {
    throw new Error("ORDER_SIGNING_SECRET must be configured");
  }
}

async function createLightningAddressInvoice(env, priceSats, sku) {
  const address = String(env.RECIPIENT_LIGHTNING_ADDRESS).trim();
  const [, user, domain] = /^([^@\s]+)@([^@\s]+)$/.exec(address);
  const metaUrl = `https://${domain}/.well-known/lnurlp/${encodeURIComponent(user)}`;
  const meta = await fetchJson(metaUrl);
  if (meta.status === "ERROR") throw new Error(meta.reason || "Lightning Address rejected the request");

  const amountMsat = priceSats * 1000;
  const min = Number(meta.minSendable || 0);
  const max = Number(meta.maxSendable || Number.MAX_SAFE_INTEGER);
  if (min > amountMsat || max < amountMsat) throw new Error("Lightning provider does not accept this amount");
  if (!meta.callback) throw new Error("Lightning Address did not provide an LNURL callback");

  const callback = new URL(meta.callback);
  callback.searchParams.set("amount", String(amountMsat));
  if (Number(meta.commentAllowed || 0) > 0) {
    callback.searchParams.set("comment", `Sats Pulse ${sku}`.slice(0, Number(meta.commentAllowed)));
  }

  const pay = await fetchJson(callback.toString());
  if (pay.status === "ERROR") throw new Error(pay.reason || "Could not create Lightning invoice");
  if (!pay.pr) throw new Error("Lightning provider did not return a BOLT11 invoice");

  const verifyUrl = pay.verify || pay.verifyUrl || pay.verify_url || pay.paymentVerifyUrl || null;
  return {
    kind: "lnaddress",
    checkoutUrl: `lightning:${pay.pr}`,
    verifyUrl,
    createdAt: Date.now()
  };
}

async function lightningAddressStatus(provider) {
  if (!provider.verifyUrl) return { paid: false, status: "verification_unavailable" };
  const verify = await fetchJson(provider.verifyUrl);
  const raw = String(verify.status || verify.state || "").toLowerCase();
  const paid = verify.settled === true || verify.paid === true || ["paid", "settled", "complete", "completed"].includes(raw);
  return { paid, status: paid ? "paid" : (raw || "active") };
}

async function createSpeedCheckout(env, sku, item, deviceHash) {
  if (!env.SPEED_API_KEY) throw new Error("SPEED_API_KEY is not configured");
  const ref = crypto.randomUUID();
  const body = {
    currency: "SATS",
    amount: String(item.priceSats),
    target_currency: "SATS",
    title: "Sats Pulse • ILLU ENTERTAINMENT",
    title_description: `Unlock: ${item.label}`,
    success_message: "Payment received. Return to Sats Pulse; the game will verify and unlock automatically.",
    metadata: {
      order_ref: ref,
      sku,
      device: deviceHash,
      game: "sats-pulse"
    },
    customer_collections_status: {
      is_email_enabled: false,
      is_phone_enabled: false,
      is_billing_address_enabled: false,
      is_shipping_address_enabled: false
    }
  };

  const created = await speedRequest(env, "/checkout-links", "POST", body);
  const checkoutUrl = created.url || created.default_url;
  if (!created.id || !checkoutUrl) throw new Error("Unexpected Speed checkout response");
  return { kind: "speed", checkoutUrl, id: created.id, ref, createdAt: Date.now() };
}

async function speedCheckoutStatus(env, provider) {
  if (!env.SPEED_API_KEY) throw new Error("SPEED_API_KEY is not configured");
  const query = `metadata['order_ref']:${provider.ref}`;
  const result = await speedRequest(env, "/search/checkout-links", "POST", { query, limit: 10 });
  const objects = collectObjects(result);
  const link = objects.find(x => x?.id === provider.id) || objects.find(x => x?.metadata?.order_ref === provider.ref);
  if (!link) return { paid: false, status: "active" };
  const raw = String(link.status || "active").toLowerCase();
  return { paid: raw === "paid", status: raw };
}

async function speedRequest(env, path, method, body) {
  const auth = btoa(`${env.SPEED_API_KEY}:`);
  const response = await fetch(SPEED_API + path, {
    method,
    headers: {
      "Authorization": `Basic ${auth}`,
      "Accept": "application/json",
      "Content-Type": "application/json",
      "speed-version": SPEED_VERSION
    },
    body: body ? JSON.stringify(body) : undefined
  });

  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  if (!response.ok) throw new Error(data?.message || data?.error || `Speed API HTTP ${response.status}`);
  return data;
}

async function fetchJson(url) {
  const response = await fetch(url, {
    headers: { "Accept": "application/json", "User-Agent": "SatsPulse/2.0" },
    redirect: "follow"
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { throw new Error("Lightning endpoint returned non-JSON data"); }
  if (!response.ok) throw new Error(data?.reason || `Lightning HTTP ${response.status}`);
  return data;
}

async function readJson(request) {
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.toLowerCase().includes("application/json")) throw new Error("Content-Type must be application/json");
  return request.json();
}

function collectObjects(value, out = []) {
  if (Array.isArray(value)) {
    for (const item of value) collectObjects(item, out);
  } else if (value && typeof value === "object") {
    if (typeof value.id === "string" && (value.object === "checkout.link" || value.status)) out.push(value);
    for (const child of Object.values(value)) collectObjects(child, out);
  }
  return out;
}

async function shortHash(value) {
  const bytes = new TextEncoder().encode(value || "anonymous");
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest.slice(0, 8)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function safeMessage(error) {
  const message = String(error?.message || "internal_error");
  if (/API_KEY|SIGNING_SECRET|RECIPIENT_LIGHTNING_ADDRESS/.test(message)) return message;
  return message.slice(0, 180);
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    }
  });
}

function cors(response) {
  const h = new Headers(response.headers);
  h.set("Access-Control-Allow-Origin", "*");
  h.set("Access-Control-Allow-Headers", "Content-Type");
  h.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  return new Response(response.body, { status: response.status, headers: h });
}
