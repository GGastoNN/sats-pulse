# Arquitectura de Sats Pulse 2.0

## Android

La aplicación es un único módulo Android con Jetpack Compose. `MainActivity.kt` contiene navegación simple entre Inicio, Juego, Skin Lab y Desafíos. `GameStore.kt` usa DataStore para persistir desbloqueos, idioma, skin activa, récords, XP y estadísticas.

`Localization.kt` ofrece textos de UI en español, inglés y portugués. Los nombres y subtítulos de niveles/skins usan `LocalizedText` en `Models.kt`.

## Juego

Cada nivel define duración, meta, radio del objetivo y velocidad. El scoring agrega precisión y combo. Al llegar a combo x8 se activa Fiebre x2. Cada partida registra estadísticas y puede avanzar desafíos.

## Pagos

La aplicación nunca contiene el destinatario Lightning ni una Speed secret key. `PaymentClient.kt` sólo habla con el Worker HTTPS.

Flujo:

1. Android pide `/api/checkout` con `sku` + identificador de instalación.
2. Worker valida el SKU contra su catálogo.
3. En modo `auto`, el Worker prefiere Speed Checkout si existe `SPEED_API_KEY`; en caso contrario resuelve `RECIPIENT_LIGHTNING_ADDRESS` mediante LNURL-pay.
4. Worker firma la orden con `ORDER_SIGNING_SECRET` y devuelve `orderToken` + `checkoutUrl`.
5. Android abre el checkout y consulta `/api/status` automáticamente.
6. Sólo una orden válida y confirmada como pagada desbloquea el SKU.

El secreto `RECIPIENT_LIGHTNING_ADDRESS` debe contener el destinatario deseado, pero su valor nunca se publica. Para Speed Checkout, la API key debe pertenecer a la misma cuenta Speed que debe recibir los fondos.

## Backend

Cloudflare Worker mantiene el catálogo y actúa como frontera de seguridad. Los secrets se cargan desde GitHub Actions hacia Cloudflare. `/health` sólo informa si los secretos están configurados mediante booleanos, sin exponer valores.

Endpoints:

- `GET /health`
- `GET /api/catalog`
- `POST /api/checkout`
- `GET /api/status?orderToken=...`

## GitHub Actions

`Payment Worker` despliega el Worker y configura sus secrets. `Android APK` compila `assembleRelease`, copia el APK final y publica `SatsPulse-APK` como artifact.
