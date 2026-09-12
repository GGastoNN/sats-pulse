const encoder = new TextEncoder();
const decoder = new TextDecoder();

function b64url(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function fromB64url(text) {
  const base64 = text.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - text.length % 4) % 4);
  const binary = atob(base64);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

async function aesKey(secret) {
  if (!secret || secret.length < 24) throw new Error("ORDER_SIGNING_SECRET debe tener al menos 24 caracteres");
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  return crypto.subtle.importKey("raw", digest, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function seal(secret, value) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await aesKey(secret);
  const plain = encoder.encode(JSON.stringify(value));
  const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
  const packed = new Uint8Array(iv.length + encrypted.length);
  packed.set(iv, 0);
  packed.set(encrypted, iv.length);
  return b64url(packed);
}

export async function unseal(secret, token) {
  const packed = fromB64url(token);
  if (packed.length < 29) throw new Error("token inválido");
  const iv = packed.slice(0, 12);
  const encrypted = packed.slice(12);
  const key = await aesKey(secret);
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, encrypted);
  return JSON.parse(decoder.decode(plain));
}
