# Sats Pulse 2.0

**ILLU ENTERTAINMENT**

Sats Pulse es un arcade Android de reflejos construido con Kotlin + Jetpack Compose. El jugador toca un núcleo móvil, construye combos, activa modo Fiebre, gana XP, completa desafíos y desbloquea niveles o skins mediante pagos Lightning.

## Qué incorpora esta versión

- Pantalla de inicio renovada con marca **ILLU ENTERTAINMENT**.
- 8 niveles con dificultad progresiva.
- 6 skins visuales.
- Sistema de precisión: PERFECTO / GENIAL / BIEN según dónde impacte el toque.
- Modo **FIEBRE x2** a partir de combo x8.
- Explosiones, partículas, grilla animada, estrellas, glow, feedback háptico y sonidos.
- Resultado por estrellas y récord por nivel.
- XP, rango, estadísticas persistentes y desafíos con recompensas de XP.
- Idiomas incluidos: español, inglés y portugués; se cambian desde la pantalla de inicio.
- Verificación automática de pagos durante el flujo de compra.
- Backend Cloudflare Worker con catálogo autoritativo y órdenes firmadas.
- Modo de pagos `auto`: usa Speed Checkout cuando existe `SPEED_API_KEY`; si no existe, usa factura directa de Lightning Address.
- El destinatario Lightning y las API keys **no aparecen en el APK ni en este repositorio**.

## Seguridad del destinatario Lightning

No escribas la dirección Lightning real en Kotlin, JavaScript, README, `BuildConfig`, `wrangler.toml` ni ningún archivo público. Debe existir únicamente como secreto:

`RECIPIENT_LIGHTNING_ADDRESS`

El Worker sólo devuelve al APK la URL/factura necesaria para pagar; nunca devuelve el destinatario configurado.

Para la verificación más fiable se recomienda también configurar `SPEED_API_KEY`. Esa clave debe pertenecer a la misma cuenta Speed en la que querés recibir los pagos. Speed Checkout crea un checkout de un solo uso y el Worker consulta su estado antes de desbloquear contenido. Si no configurás esa clave, el modo `auto` usa el Lightning Address configurado directamente; en ese modo la confirmación automática depende de que el proveedor LNURL exponga un endpoint de verificación.

## Configuración en GitHub

En **Settings → Secrets and variables → Actions → Secrets** agregá:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `RECIPIENT_LIGHTNING_ADDRESS`
- `ORDER_SIGNING_SECRET`
- `SPEED_API_KEY` — recomendado para desbloqueos automáticos fiables

Opcionales para firmar el APK con tu propia keystore:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ORDER_SIGNING_SECRET` debe ser largo y aleatorio. Un ejemplo para generarlo localmente es:

```bash
openssl rand -hex 32
```

## Orden recomendado de despliegue

1. Subí el contenido completo de este ZIP al repositorio, reemplazando los archivos anteriores.
2. Configurá los secretos de GitHub indicados arriba.
3. Ejecutá **Payment Worker** desde GitHub Actions.
4. Copiá la URL HTTPS del Worker desplegado.
5. En **Settings → Secrets and variables → Actions → Variables** creá:

   `SATFLOW_API_BASE_URL`

   con la URL HTTPS del Worker, sin una barra final.
6. Ejecutá **Android APK**.
7. Al finalizar, descargá el artifact **SatsPulse-APK**.

> Si `SATFLOW_API_BASE_URL` queda vacío, el build puede compilar pero el APK usará la URL placeholder y los pagos no funcionarán.

## Cómo funciona el pago

El APK envía únicamente el SKU y un identificador de instalación al Worker. El precio no se confía al cliente: sale del catálogo del servidor.

En `PAYMENT_MODE = "auto"`:

- Con `SPEED_API_KEY`: el Worker crea un Speed Checkout en SATS, abre la página de pago y consulta su estado hasta `paid`.
- Sin `SPEED_API_KEY`: el Worker resuelve el secreto `RECIPIENT_LIGHTNING_ADDRESS` mediante LNURL-pay y devuelve una factura Lightning. Si el proveedor ofrece una URL de verificación, también se confirma automáticamente.

El contenido se desbloquea localmente sólo cuando `/api/status` responde `paid: true` para el SKU firmado dentro de la orden.

## Catálogo

Niveles pagos:

| SKU | Contenido | Precio |
|---|---|---:|
| `level_2` | Hyper Drift | 120 sats |
| `level_3` | Solar Rush | 220 sats |
| `level_4` | Quantum Rain | 360 sats |
| `level_5` | Singularity | 650 sats |
| `level_6` | Nova Circuit | 900 sats |
| `level_7` | Void Runner | 1250 sats |
| `level_8` | Omega Pulse | 1800 sats |

Skins:

| SKU | Skin | Precio |
|---|---|---:|
| `skin_cyber` | Cyber Aurora | 90 sats |
| `skin_magma` | Magma Pop | 160 sats |
| `skin_ice` | Zero Frost | 210 sats |
| `skin_void` | Void Prism | 280 sats |
| `skin_lime` | Toxic Lime | 320 sats |

Los precios deben mantenerse sincronizados entre `Models.kt` y `backend/worker/src/catalog.js`; el Worker es la fuente autoritativa para cobrar.

## Desafíos y progresión

Los desafíos no requieren pago. Se completan con partidas, puntuación acumulada, combos, partidas perfectas y récords de niveles. Al cobrarlos suman XP. Cada 500 XP sube el rango del jugador. Toda la progresión se guarda en DataStore.

## Estructura principal

```text
.github/workflows/android.yml
.github/workflows/worker.yml
app/src/main/java/com/satspulse/game/
  AppTheme.kt
  ChallengeEngine.kt
  GameStore.kt
  Localization.kt
  MainActivity.kt
  Models.kt
  PaymentClient.kt
backend/worker/
  src/catalog.js
  src/crypto.js
  src/index.js
  wrangler.toml
docs/ARCHITECTURE.md
```

## Notas de build

- compileSdk / targetSdk: 35
- minSdk: 26
- Java: 17
- Kotlin: 2.0.21
- Android Gradle Plugin: 8.7.3
- Gradle del workflow: 8.9
- El detector `NullSafeMutableLiveData` está deshabilitado porque la combinación actual de Android Lint/Lifecycle puede provocar un crash interno durante `lintVitalAnalyzeRelease`. No se deshabilita Lint completo.
- Sin keystore de release, el workflow genera un APK release firmado con la clave debug para facilitar pruebas. Para publicación real configurá una keystore propia.
