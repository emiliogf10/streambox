# Trabajo en curso (temporal: se borra al terminar)

Si la sesión se corta: lee este archivo y `git diff`, comprueba que no queda ningún proceso vivo (backend en el 8099, Maven) y no rehagas lo que ya esté bien. Rama: `claude/stoic-wright-ac5w1k` (commits de checkpoint ahí, nunca en `main`).

## Tarea: cookie de dispositivo firmada contra el «bloqueo de cuenta como arma»

Plan de acción, 2.5 «Bloqueo de cuenta como arma (residual)». Decidido por el autor el 2026-11-16: opción 1, cookie de dispositivo firmada (patrón «device cookies» de OWASP).

### Problema
Con 5 fallos, cualquiera que conozca un email bloquea la cuenta 15 min. Hoy solo se libran las «IPs conocidas» (`KnownIpRegistry`, en memoria): falla con una IP nueva, tras un reinicio y con IPs compartidas.

### Diseño decidido (orquestador)
- **Cookie** `streambox_device`: `HttpOnly`, `SameSite=Strict`, `Secure` según `AuthCookieProperties`, `Path=/api/auth` (solo viaja a login/refresh/logout), `Max-Age` = TTL configurable (por defecto 30 días, como el de las IPs conocidas).
- **Contenido firmado (HMAC-SHA256):** versión del formato, id del usuario, `token_version` del usuario al emitirla, id aleatorio del dispositivo (≥128 bits, `SecureRandom`) y fecha de emisión. Sin email ni datos personales en claro. Comparación de la firma en tiempo constante.
- **Clave:** derivada del `JWT_SECRET` con separación de dominio (HMAC del secreto con una etiqueta fija, p. ej. `streambox:device-cookie:v1`). Sin variable de entorno nueva obligatoria (no rompe el arranque local del autor ni Docker/CI). Rotar el `JWT_SECRET` invalida también las cookies de dispositivo (aceptable: ya cierra todas las sesiones).
- **Validez:** firma correcta, no caducada, usuario existe, id coincide con el usuario del email del login y `token_version` coincide con la actual. Así «cerrar sesión en todos los dispositivos», cambiar o restablecer la contraseña y confirmar el correo (que suben `token_version`) la anulan **de forma persistente**, sin estado en memoria.
- **Uso en el login:** cookie válida para esa cuenta → contador propio (email + id de dispositivo) con los mismos límites (5 fallos/15 min): sus fallos no bloquean la cuenta para nadie y el bloqueo de la cuenta no le afecta. Sin cookie válida → lógica actual sin cambios (IP conocida → contador email+IP; si no, contador de la cuenta). Se conserva `KnownIpRegistry` como respaldo.
- **Emisión:** solo tras un login correcto (contraseña verificada, cuenta confirmada), y de nuevo tras cambiar o restablecer la contraseña (prueban la contraseña/el buzón, igual que `forgetKnownIpsExcept`), con la `token_version` ya subida.
- **Indistinguibilidad:** sin cookie válida, las respuestas son idénticas a las de hoy para un email que existe o no. Una cookie falsificada o de otra cuenta se trata como ausente.
- **Limitaciones aceptadas:** una cookie por navegador (la del último login; varias cuentas en el mismo navegador comparten una); quien robe la cookie no se autentica, solo obtiene un presupuesto propio de 5 fallos para esa cuenta.
- **Frontend:** sin cambios esperados (cookie `HttpOnly`, misma ruta que el refresh).
- **ADR nuevo:** `docs/adr/0014-cookie-de-dispositivo.md`.

### Pasos
| # | Paso | Estado |
|---|---|---|
| 1 | Línea base verificada (backend 3757/0 fallos, Vitest 1734, E2E 234) | ✅ |
| 2 | Implementación + tests (agente `security`) | ✅ commit `eeea03d`; 3823 tests, 0 fallos (+66). Tocó además `PasswordController` y `PasswordResetController` (imprescindible para entregar el Set-Cookie; aceptado por el orquestador) |
| 3 | Revisión independiente y tests de casos límite (agente `qa`) | 🔄 en curso |
| 4 | Verificación completa por el orquestador (backend, Vitest, E2E) | ⬜ |
| 5 | Docs: manual (bloqueo de cuenta), ADR 0014, plan 2.5/2.1, CLAUDE.md, OpenAPI si cambia el contrato | 🔄 hechos ADR 0014, manual 11/11.2 bis/10.x, plan 2.1/2.5, CLAUDE.md (Seguridad). Faltan: OpenAPI (regenerar con backend en 8099, tras qa), historial del plan y cifras de tests |
| 6 | Commit, push y borrar este archivo | ⬜ |

### Notas
- [security, bloque 1: código] Nuevo paquete `security/device/` (`DeviceCookieService`, `DeviceCookieProperties` `streambox.security.device-cookie.ttl=30d`, `IssuedDeviceToken`); tocados `AuthCookieService` (`deviceCookie`), `SessionTokens` (3er campo opcional), `LoginAttemptService` (`reserveAttempt(email, ip, deviceId)` + contador `deviceFailures`), `AuthenticationService` (`login(..., deviceCookie)`), `AuthController`, `PasswordChangeService`, `PasswordResetService` (`confirm` devuelve la cookie), `PasswordController` y `PasswordResetController` (fuera de la lista: hacen falta para el Set-Cookie), `application.properties`. Tests existentes adaptados (AuthenticationServiceTest, PasswordChange/ResetServiceTest, RequestBodySizeLimitTomcatIntegrationTest). Falta: tests nuevos y suite completa.
- [security, bloque 2: tests] Nuevos `security/device/DeviceCookieServiceTest`, `security/device/DeviceCookieLoginIntegrationTest` (casos a–g; 4 fallan sin el contador de dispositivo), `security/ratelimit/LoginAttemptServiceDeviceTest`; ampliados `AuthCookieServiceTest` y `AuthenticationServiceTest`. Falta: suite completa y el informe.
- [security, bloque 3: verificación] `PasswordResetIntegrationTest` ajustado (la confirmación ya entrega `streambox_device`, sigue sin cookies de sesión). `./mvnw -B test` sin Docker: 3823 ejecutados, 0 fallos, 306 omitidos (PostgreSQL). Paso 2 hecho; pendiente: revisión qa (paso 3), docs (manual, ADR 0014, plan, CLAUDE.md, `docs/api/openapi.yaml`).
- [qa, paso 3: revisión] +56 tests en 4 clases nuevas (`DeviceCookieServiceQaTest`, `ratelimit/LoginAttemptServiceDeviceConcurrencyTest`, `device/DeviceCookieAbuseQaIntegrationTest`, `device/DeviceCookieTomcatQaIntegrationTest`); 5 `@Disabled` que reproducen 2 fallos para `security`: QA-DC-1 (MEDIA, un `%` inválido en `streambox_device` —y en `streambox_refresh`, ya existía— da 500 porque `@CookieValue` URL-decodifica) y QA-DC-2 (BAJA, con dos `streambox_device` solo cuenta la primera). Concurrencia, revocación, indistinguibilidad y parseo: sin fallos. Ojo: la suite está en el tope de 32 contextos de Spring; uno más rompe la H2 compartida (las clases nuevas reutilizan contextos existentes). `./mvnw -B test`: 3879 ejecutados, 0 fallos, 311 omitidos, BUILD SUCCESS.
- Entorno: Playwright 1.64.1 pide Chromium 1248 y el contenedor trae 1243: enlace simbólico en `/opt/pw-browsers/chromium_headless_shell-1248/...` (repetirlo si el contenedor se recrea). Sin Docker: los tests de PostgreSQL se omiten.
