# 0011. Token de acceso corto y *refresh token* rotatorio y revocable

**Estado:** Aceptada (2026-10-08). Sustituye en parte al [0004](0004-jwt-stateless.md): la vida del token y la alternativa descartada de los *refresh tokens*. El resto del 0004 sigue vigente.

## Contexto
Con el 0004, el JWT valía 24 horas y no se podía invalidar: un token robado servía un día entero y «cerrar sesión» solo borraba la cookie del navegador. La tarea 29 ya había quitado el token del alcance de JavaScript (cookie HttpOnly); quedaba acortar su vida sin obligar a iniciar sesión cada pocos minutos, y poder revocar sesiones.

## Decisión
- **Token de acceso:** el mismo JWT del 0004 (rol leído de la base en cada petición), pero de **15 minutos** (`jwt.access-token-ttl`, máximo 1 h) y con el algoritmo **fijado a HS256** al firmar y al verificar. Cookie `streambox_token`, `Path=/api`.
- ***Refresh token*:** 32 bytes aleatorios, opacos, en la cookie `streambox_refresh` (HttpOnly, SameSite=Strict, **`Path=/api/auth`**, para que solo viaje a login, refresh y logout). En la base de datos (`refresh_tokens`, migración V4) se guarda **solo su SHA-256**. Vida de 7 días, con un tope de 30 días para toda la sesión.
- **Rotación en cada uso** (`POST /api/auth/refresh`) y **detección de reutilización**: presentar un token ya rotado revoca toda la sesión («familia»), porque indica que alguien tiene una copia. Hay una **gracia de 10 segundos** para las pestañas que refrescan a la vez.
- Bloqueo pesimista al leer el token (dos refresh simultáneos no rotan dos veces), y revocación de la familia **en dos pasadas**: en READ COMMITTED, un `UPDATE` que espera a una rotación en curso no ve el sucesor que esa rotación acaba de insertar (encontrado en la revisión de seguridad y probado contra PostgreSQL real).
- **Logout** revoca la familia en el servidor. Limpieza periódica de lo caducado.
- El refresh exige `X-Requested-With` (CSRF) y tiene su propio límite por IP. Respuesta de fallo única: 401 `SESSION_EXPIRED`.
- El frontend no ve ningún token: ante un 401 hace un único refresh compartido y repite la petición una vez.

## Alternativas descartadas
- **Token de 24 h con lista de revocación (`jti` o `tokens_valid_after` en `users`):** permite revocar, pero obliga a consultar esa lista en cada petición y no reduce la ventana de un token robado si no se revoca a tiempo.
- **Sesión de servidor (`JSESSIONID`):** resuelve la revocación, pero vuelve a un estado en memoria por réplica (ver el 0004).
- ***Refresh token* como JWT:** al ser autocontenido no se podría revocar sin guardarlo igualmente; un valor opaco con su hash en la base es más simple y no expone datos.
- **Guardar el *refresh token* en claro:** una copia de la base de datos daría acceso a todas las sesiones.
- **Detección de reutilización sin gracia:** dos pestañas que refrescan a la vez cerrarían la sesión del usuario legítimo.

## Consecuencias
- (+) Un token de acceso robado vale como mucho 15 minutos; un *refresh token* robado y usado se detecta en cuanto el legítimo (o el ladrón) reutiliza el viejo.
- (+) El logout invalida la sesión de verdad (el *refresh token*); la tabla permitiría un futuro «cerrar sesión en todos los dispositivos» (`revokeAllByUserId`).
- (−) Más piezas: una tabla, un endpoint, una tarea de limpieza y lógica de reintento en el frontend.
- (−) Tras el logout, el token de acceso copiado sigue valiendo hasta 15 minutos (es *stateless*). Dentro de la gracia de 10 s, un token robado puede conseguir un token de acceso sin ser detectado.
- (−) El `sub` sigue siendo el email (mitigado por la vida corta); HSTS queda para cuando haya HTTPS.
