import { CATALOG } from "./catalog.js";
import { seal, unseal } from "./crypto.js";

const SPEED_API = "https://api.tryspeed.com";
const VERSION = "2022-10-15";

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
      const url = new URL(request.url);
      if (url.pathname === "/health") return json({ ok: true, mode: env.PAYMENT_MODE || "lightning_address" });
      if (url.pathname === "/api/checkout" && request.method === "POST") return cors(await createCheckout(request, env));
      if (url.pathname === "/api/status" && request.method === "GET") return cors(await checkStatus(url, env));
      return cors(json({ error: "not_found" }, 404));
    } catch (error) {
      console.error(error);
      return cors(json({ error: error?.message || "internal_error" }, 500));
    }
  }
};

async function createCheckout(request, env) {
  const input = await request.json();
  const sku = String(input?.sku || "");
  const item = CATALOG[sku];
  if (!item) return json({ error: "sku_invalido" }, 400);

  const deviceId = String(input?.deviceId || "").slice(0, 128);
  const deviceHash = await shortHash(deviceId || "anonymous");
  const mode = env.PAYMENT_MODE || "lightning_address";

  let provider;
  if (mode === "speed_checkout") {
    provider = await createSpeedCheckout(env, sku, item, deviceHash);
  } else if (mode === "lightning_address") {
    provider = await createLightningAddressInvoice(env, item.priceSats);
  } else {
    throw new Error("PAYMENT_MODE no soportado");
  }

  const expiresAt = Date.now() + 20 * 60 * 1000;
  const tokenProvider = { ...provider };
  delete tokenProvider.checkoutUrl;
  const orderToken = await seal(env.ORDER_SIGNING_SECRET, {
    v: 1,
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
    checkoutUrl: provider.checkoutUrl
  });
}

async function checkStatus(url, env) {
  const token = url.searchParams.get("orderToken");
  if (!token) return json({ error: "falta_orderToken" }, 400);

  let order;
  try {
    order = await unseal(env.ORDER_SIGNING_SECRET, token);
  } catch {
    return json({ error: "orderToken_invalido" }, 400);
  }

  if (order.expiresAt < Date.now()) {
    return json({ paid: false, sku: order.sku, status: "expired" });
  }

  let status;
  if (order.mode === "speed_checkout") {
    status = await speedCheckoutStatus(env, order.provider);
  } else {
    status = await lightningAddressStatus(order.provider);
  }

  return json({ paid: status.paid, sku: order.sku, status: status.status });
}

async function createLightningAddressInvoice(env, priceSats) {
  const address = String(env.RECIPIENT_LIGHTNING_ADDRESS || "").trim();
  const match = /^([^@\s]+)@([^@\s]+)$/.exec(address);
  if (!match) throw new Error("RECIPIENT_LIGHTNING_ADDRESS no configurado");
  const [, user, domain] = match;

  const metaUrl = `https://${domain}/.well-known/lnurlp/${encodeURIComponent(user)}`;
  const meta = await fetchJson(metaUrl);
  if (meta.status === "ERROR") throw new Error(meta.reason || "Lightning Address rechazado");

  const amountMsat = priceSats * 1000;
  if (Number(meta.minSendable || 0) > amountMsat || Number(meta.maxSendable || Number.MAX_SAFE_INTEGER) < amountMsat) {
    throw new Error("El proveedor Lightning no admite este importe");
  }
  if (!meta.callback) throw new Error("Lightning Address sin callback LNURL-pay");

  const callback = new URL(meta.callback);
  callback.searchParams.set("amount", String(amountMsat));
  if (Number(meta.commentAllowed || 0) > 0) callback.searchParams.set("comment", "Sats Pulse unlock".slice(0, Number(meta.commentAllowed)));
  const pay = await fetchJson(callback.toString());
  if (pay.status === "ERROR") throw new Error(pay.reason || "No se pudo crear la factura Lightning");
  if (!pay.pr) throw new Error("El proveedor no devolvió una factura BOLT11");

  const verifyUrl = pay.verify || pay.verifyUrl || pay.verify_url || pay.paymentVerifyUrl || null;
  return {
    kind: "lnaddress",
    checkoutUrl: `lightning:${pay.pr}`,
    verifyUrl,
    createdAt: Date.now()
  };
}

async function lightningAddressStatus(provider) {
  if (!provider.verifyUrl) {
    return { paid: false, status: "verification_unavailable" };
  }
  const verify = await fetchJson(provider.verifyUrl);
  const raw = String(verify.status || verify.state || "").toLowerCase();
  const paid = verify.settled === true || verify.paid === true || ["paid", "settled", "complete", "completed"].includes(raw);
  return { paid, status: paid ? "paid" : (raw || "active") };
}

async function createSpeedCheckout(env, sku, item, deviceHash) {
  if (!env.SPEED_API_KEY) throw new Error("SPEED_API_KEY no configurada");
  const ref = crypto.randomUUID();
  const body = {
    currency: "SATS",
    amount: String(item.priceSats),
    target_currency: "SATS",
    title: "Sats Pulse",
    title_description: `Desbloqueo: ${item.label}`,
    success_message: "Pago recibido. Volvé a Sats Pulse y verificá el desbloqueo.",
    metadata: { order_ref: ref, sku, device: deviceHash },
    customer_collections_status: {
      is_email_enabled: false,
      is_phone_enabled: false,
      is_billing_address_enabled: false,
      is_shipping_address_enabled: false
    }
  };
  const created = await speedRequest(env, "/checkout-links", "POST", body);
  if (!created.id || !created.url) throw new Error("Respuesta inesperada de Speed checkout");
  return { kind: "speed", checkoutUrl: created.url, id: created.id, ref };
}

async function speedCheckoutStatus(env, provider) {
  const query = `metadata['order_ref']:${provider.ref}`;
  const result = await speedRequest(env, "/search/checkout-links", "POST", { query, limit: 10 });
  const objects = collectObjects(result);
  const link = objects.find(x => x && x.id === provider.id) || objects.find(x => x && x.metadata?.order_ref === provider.ref);
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
      "speed-version": VERSION
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
  const response = await fetch(url, { headers: { "Accept": "application/json", "User-Agent": "SatsPulse/1.0" } });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { throw new Error("Respuesta Lightning no JSON"); }
  if (!response.ok) throw new Error(data?.reason || `Lightning HTTP ${response.status}`);
  return data;
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
  const bytes = new TextEncoder().encode(value);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest.slice(0, 8)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}

function cors(response) {
  const h = new Headers(response.headers);
  h.set("Access-Control-Allow-Origin", "*");
  h.set("Access-Control-Allow-Headers", "Content-Type");
  h.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  return new Response(response.body, { status: response.status, headers: h });
}
