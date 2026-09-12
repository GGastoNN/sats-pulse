# Arquitectura

`Android -> Payment Worker -> Lightning provider / Speed API`

El cliente nunca recibe la Lightning Address configurada. En modo directo, el Worker resuelve LNURL-pay usando un secreto de entorno y devuelve solamente una factura BOLT11 envuelta en un URI `lightning:`. El token de orden también se cifra con AES-GCM para no filtrar los datos internos del proveedor.

La lista de SKU y sus precios vive en el Worker. El cliente solo solicita un SKU; no puede elegir el importe cobrado.

Después del pago, el cliente consulta `/api/status`. El Worker valida el token cifrado y pregunta al proveedor por el estado. Solo entonces la app persiste el entitlement local.

Para una versión con cuentas, mové los entitlements al servidor y asociá cada compra a un user ID autenticado, no solo a la instalación local.
