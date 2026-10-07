# 0004. Autenticación con JWT sin estado y rol consultado a la base en cada petición

**Estado:** Aceptada. El **almacenamiento del token en el navegador** se revisará en la tarea 29 del plan.

## Contexto
La API la consume una SPA y podría consumirla otro cliente. Se busca no depender de una sesión en memoria del servidor (para poder tener varias réplicas) y que revocar o cambiar un rol sea inmediato.

## Decisión
- **JWT firmado** (JJWT 0.12.6, HMAC) emitido en `POST /api/auth/login`, con `issuer=streambox` y caducidad configurable (`JWT_EXPIRATION_HOURS`, 24 por defecto). El secreto (`JWT_SECRET`) debe tener ≥ 32 caracteres y se valida al arrancar; nunca va al repositorio.
- Sesión **stateless**: Spring Security no crea `HttpSession`.
- El token **no lleva el rol**: `JwtAuthenticationFilter` lee el usuario de la base en cada petición, así que un cambio de rol o un borrado de usuario surte efecto al instante. El coste es una consulta por petición.
- Roles `USER` y `ADMIN`. El registro público siempre crea `USER`; los administradores solo nacen de `AdminAccountInitializer` (`ADMIN_EMAIL`/`ADMIN_PASSWORD`).
- Contraseñas con **BCrypt**; política para cuentas nuevas (12–64 caracteres, ≤ 72 bytes, no común, sin usuario ni email). El login no exige mínimo para no dejar fuera cuentas antiguas.
- Respuestas 401/403 en JSON estructurado (`ErrorResponse` con `ErrorCode`).
- El token viaja en una cookie `HttpOnly` (`streambox_token`, `SameSite=Strict`, `Path=/api`) que fija el login (tarea 29); el frontend ya no lo guarda ni lo ve. Defensa CSRF: cabecera `X-Requested-With: StreamBox` en las peticiones no seguras autenticadas por cookie. Los clientes que no son el navegador pueden seguir usando `Authorization: Bearer`.

## Alternativas descartadas
- **Sesión de servidor (cookie `JSESSIONID`):** simple, pero estado en memoria y más trabajo para escalar.
- **Rol dentro del JWT:** ahorra la consulta, pero un administrador degradado seguiría siéndolo hasta que caducase el token.
- **Refresh tokens:** más seguridad y complejidad; fuera del alcance actual.

## Consecuencias
- (+) Revocación de rol inmediata; el servidor no guarda sesiones.
- (−) Una lectura de base de datos por petición autenticada (aceptable a esta escala; cacheable si hiciera falta).
- (−) Con cookie hay que defenderse de CSRF (`SameSite=Strict` + cabecera personalizada; login CSRF residual) y, con HTTPS, activar `Secure` y HSTS. Un XSS ya no puede robar el token, aunque sí actuar con la sesión mientras dure.
- (−) Un JWT no se puede invalidar antes de que caduque (salvo borrando al usuario); no hay «cerrar sesión en todos los dispositivos».
