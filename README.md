# SATS PULSE ⚡

Juego Android vertical, hecho con Kotlin + Jetpack Compose, diseñado para sentirse rápido y satisfactorio: núcleo luminoso, partículas, ondas de impacto, combo, vibración, tonos, fondos animados, cinco niveles y skins desbloqueables.

La monetización está expresada en **satoshis**. El primer nivel y la skin base son gratuitos; el resto se desbloquea después de que el backend confirma el pago.

## Qué incluye

- App Android 100% Compose, sin motor externo.
- Gráficos procedurales: glow multicapa, partículas, starfield, pulsos, gradientes y shockwaves.
- Haptics + audio de impacto.
- 5 niveles con dificultad creciente.
- Tienda con skins.
- Persistencia local con DataStore.
- Backend Cloudflare Worker para crear y validar pagos.
- Modo Lightning Address directo (`PAYMENT_MODE=lightning_address`).
- Modo alternativo Speed Checkout (`PAYMENT_MODE=speed_checkout`) para validación de cobro vía API de Speed.
- GitHub Actions para compilar el APK y desplegar el Worker.
- El destinatario **no está hardcodeado en el APK ni en el repositorio**.

## 1. Subir a GitHub

Descomprimí el repo y subilo a un repositorio nuevo. No hace falta agregar Gradle Wrapper: el workflow instala Gradle 8.9 en GitHub Actions.

## 2. Configurar el backend sin exponer el destinatario

En GitHub → **Settings → Secrets and variables → Actions → Secrets**, cargá:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `RECIPIENT_LIGHTNING_ADDRESS` → pegá ahí la Lightning Address de destino que querés usar. No la escribas en ningún archivo.
- `ORDER_SIGNING_SECRET` → una cadena aleatoria larga (32+ caracteres recomendado).

Luego ejecutá el workflow **Payment Worker**. El workflow envía esos valores a Cloudflare como secretos cifrados; el valor del destinatario no aparece en el código ni en la UI del juego.

### Verificación automática

El modo `lightning_address` genera una factura LNURL-pay directamente contra la Lightning Address secreta. Para desbloquear automáticamente, el proveedor debe devolver un endpoint de verificación de pago (LUD-21 o equivalente). Si devuelve `verification_unavailable`, usá el modo Speed Checkout de abajo.

### Modo Speed Checkout (alternativo)

En `backend/worker/wrangler.toml` cambiá:

```toml
PAYMENT_MODE = "speed_checkout"
```

Y agregá el secret `SPEED_API_KEY` en GitHub. Usá una key restringida si tu cuenta de Speed lo permite. El backend crea un checkout de importe fijo y consulta su estado antes de habilitar el SKU.

> Importante: la clave secreta de Speed nunca debe ir dentro del APK, BuildConfig ni el repositorio.

## 3. Conectar el APK al Worker

Después del primer deploy de Cloudflare, copiá la URL pública del Worker, por ejemplo `https://tu-worker.workers.dev`.

En GitHub → **Settings → Secrets and variables → Actions → Variables**, creá:

- `SATFLOW_API_BASE_URL` = URL HTTPS del Worker.

No pongas una barra `/` al final.

## 4. Generar el APK

Abrí **Actions → Android APK → Run workflow**. Al terminar, descargá el artifact **SatsPulse-APK**.

Por defecto, si no configurás un keystore propio, el workflow firma el build release con la clave debug para que el APK sea instalable durante pruebas.

Para una distribución real, agregá estos secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Precios incluidos

Los precios se validan del lado servidor en `backend/worker/src/catalog.js`; cambiar el APK no cambia lo que cobra el backend.

| SKU | Contenido | Precio |
|---|---|---:|
| `level_2` | Hyper Drift | 120 sats |
| `level_3` | Solar Rush | 220 sats |
| `level_4` | Quantum Rain | 360 sats |
| `level_5` | Singularity | 650 sats |
| `skin_cyber` | Cyber Aurora | 90 sats |
| `skin_magma` | Magma Pop | 160 sats |
| `skin_ice` | Zero Frost | 210 sats |

Si cambiás precios, actualizá **tanto** `catalog.js` como `Models.kt` para que la UI muestre el mismo importe que cobra el backend. El servidor sigue siendo la fuente autoritativa.

## Seguridad y producción

- El usuario ve el contenido que compra y el importe en sats antes de abrir su wallet/checkout.
- El destinatario no se muestra en ninguna pantalla del juego.
- Nunca metas secretos financieros en el APK: un APK puede decompilarse.
- Usá HTTPS y una API key restringida.
- Para producción a escala, agregá rate limiting, telemetría antifraude y un sistema de entitlements asociado a una cuenta de usuario. En esta versión los desbloqueos se guardan localmente después de una confirmación de pago válida.
- Revisá las políticas de Google Play aplicables a bienes digitales y pagos con cripto antes de publicar en Play Store; pueden requerir cambios respecto de una APK distribuida directamente.

## Desarrollo local

Android:

```bash
gradle :app:assembleDebug -PSATFLOW_API_BASE_URL=https://tu-worker.workers.dev
```

Worker:

```bash
cd backend/worker
npm ci
npx wrangler secret put RECIPIENT_LIGHTNING_ADDRESS
npx wrangler secret put ORDER_SIGNING_SECRET
npm run dev
```

## Estructura

```text
.github/workflows/android.yml   # compila y entrega APK
.github/workflows/worker.yml    # despliega backend y secretos
app/                            # juego Android
backend/worker/                 # cobros Lightning / Speed
docs/                           # notas del proyecto
```
