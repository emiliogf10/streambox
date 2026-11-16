# 0014. Cookie de dispositivo firmada contra el bloqueo de cuenta como arma

**Estado:** Aceptada (2026-11-16). Matiza el [0006](0006-rate-limiting-en-memoria.md) en el bloqueo de cuentas: el estado de los contadores sigue en memoria, pero el reconocimiento del titular ya no.

## Contexto
El bloqueo por cuenta (5 fallos en 15 minutos) protege de la fuerza bruta, pero cualquiera que conozca un email puede usarlo para dejar fuera a su titular, y mantenerlo bloqueado repitiéndolo. La primera mitigación fueron las «IPs conocidas» (`KnownIpRegistry`): desde una IP con un login correcto reciente, el bloqueo no afecta. Tenía tres huecos: una IP nueva (otra red, los datos del móvil) seguía bloqueada, el registro vive en memoria y se pierde al reiniciar, y bajo una IP compartida (CGNAT, empresa, VPN) se comparte el reconocimiento con desconocidos.

## Decisión
- Patrón **«device cookies» de OWASP**: tras un login correcto (contraseña verificada y cuenta confirmada), el servidor emite la cookie **`streambox_device`**, `HttpOnly`, `SameSite=Strict`, `Secure` según `streambox.auth.cookie.secure` y **`Path=/api/auth`** (solo viaja a login, refresh y logout). Vida de 30 días (`streambox.security.device-cookie.ttl`, máximo 400).
- **Valor firmado** `v1.<payload>.<firma>`: el payload es `idUsuario:tokenVersion:idDispositivo:emitidaEn` (id de dispositivo de 128 bits de `SecureRandom`; sin email ni datos personales) y la firma, **HMAC-SHA256** comparada en tiempo constante.
- **Clave derivada del `JWT_SECRET` con separación de dominio** (HMAC del secreto sobre la etiqueta `streambox:device-cookie:v1`): no hace falta una variable de entorno nueva y, aun así, una firma de JWT nunca vale como cookie de dispositivo ni al revés.
- **Uso en el login:** con una cookie válida para esa cuenta, el intento va a un **contador propio (email + dispositivo)** con los mismos límites. El bloqueo de la cuenta no le afecta y sus fallos no bloquean a nadie más. Sin cookie válida, la lógica anterior queda exactamente igual (IP conocida o contador de la cuenta), así que un atacante no gana nada.
- **Revocación persistente con `token_version`:** la cookie lleva la versión de la cuenta al emitirse. «Cerrar sesión en todos los dispositivos», cambiar o restablecer la contraseña y confirmar el correo la suben (migración V12, [0011](0011-token-corto-y-refresh-rotatorio.md)), y todas las cookies anteriores dejan de valer sin guardar nada en el servidor. Cambiar o restablecer la contraseña emite una cookie nueva al navegador que lo hace.
- Cualquier cookie que no valga (mal formada, enorme, otra versión, firma incorrecta, caducada, de otra cuenta, versión antigua) se trata **igual que si no existiera**: nunca un error ni una respuesta distinta. Las cookies se leen sin decodificar (no con `@CookieValue`, que daba 500 con un `%` mal formado).
- **Varias cookies con el mismo nombre (*cookie tossing*):** se prueban hasta 5 y vale la primera válida para la cuenta, para que una cookie plantada delante desde un subdominio hermano no anule la legítima.

## Alternativas descartadas
- **Persistir las IPs conocidas en la base de datos:** sobrevive a los reinicios, pero sigue sin servir desde una IP nueva y sigue compartiéndose bajo una IP común.
- **CAPTCHA en lugar de bloquear:** depende de un servicio externo con claves y de tener la app publicada; queda pendiente para entonces (punto 12 de seguridad del plan). Es complementario, no excluyente.
- **Guardar los dispositivos en una tabla:** permitiría listarlos y revocarlos uno a uno, pero exige una migración, consultas en cada login y limpieza. La firma más `token_version` da la revocación necesaria sin estado.
- **Una variable de entorno propia para la clave:** más separación operativa, pero obliga a configurarla en local, Docker, CI y E2E, y un olvido impediría arrancar. La derivación con etiqueta ya da claves criptográficamente independientes.
- **Quitar las IPs conocidas:** se conservan como respaldo para quien borra las cookies o entra desde otro navegador de la misma red.

## Consecuencias
- (+) El titular entra desde cualquier navegador en el que ya inició sesión, aunque su cuenta esté bloqueada, desde cualquier red y aunque el backend se haya reiniciado.
- (+) La fuerza bruta sigue limitada a 5 fallos por ventana, también con la cookie: su contador es propio, no ilimitado.
- (+) Sin migración, sin variable nueva y sin cambios en el frontend.
- (−) Una cookie por navegador: con varias cuentas en el mismo navegador, solo la del último login tiene cookie.
- (−) Quien robe la cookie no se autentica con ella, pero obtiene un presupuesto propio de 5 fallos por ventana para esa cuenta hasta que el titular cierre sesión en todos los dispositivos o cambie la contraseña.
- (−) Rotar `JWT_SECRET` invalida también todas las cookies de dispositivo (aceptable: ya cierra todas las sesiones).
- (−) Un primer login desde un navegador **nuevo** durante un bloqueo sigue rechazado: es el límite deliberado para que el bloqueo siga protegiendo. Para eso está «He olvidado mi contraseña», que además borra los fallos de la cuenta.
