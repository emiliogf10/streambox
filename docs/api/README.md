# API de StreamBox: especificación OpenAPI y colección de Postman

| Archivo | Para qué sirve |
| :--- | :--- |
| [`openapi.yaml`](openapi.yaml) | Especificación OpenAPI 3.1 de todos los endpoints (37 operaciones en 22 rutas), con sus DTOs, códigos de error y seguridad. Se puede importar en Postman, Insomnia, Swagger Editor, etc. |
| [`postman/streambox.postman_collection.json`](postman/streambox.postman_collection.json) | Colección de Postman (formato v2.1) con 62 peticiones y 117 aserciones, organizadas en 9 carpetas. |
| [`postman/streambox-local.postman_environment.json`](postman/streambox-local.postman_environment.json) | Entorno `StreamBox - Local` (`baseUrl = http://localhost:8080`). |

## Cómo funciona la autenticación

El login **no devuelve el token en el cuerpo**: la sesión viaja en dos cookies HttpOnly que JavaScript no puede leer (así un XSS no puede robarla).

1. **`POST /api/auth/login`** con `{"email": ..., "password": ...}` responde **204 sin cuerpo** y dos `Set-Cookie` (HttpOnly; SameSite=Strict; `Secure` en producción):
   - `streambox_token`: JWT de acceso, vida de 15 minutos, `Path=/api` (viaja a toda la API).
   - `streambox_refresh`: refresh token opaco, vida de 7 días, `Path=/api/auth` (solo viaja a login, refresh y logout).

   Errores: 401 `INVALID_CREDENTIALS` (con `remainingAttempts`), 429 `ACCOUNT_LOCKED` o `RATE_LIMIT_EXCEEDED` (con `Retry-After`).
2. **Peticiones normales**: el cliente reenvía la cookie sola. Si la petición es **no segura** (POST/PUT/PATCH/DELETE) y se autentica por cookie, debe llevar además **`X-Requested-With: StreamBox`**; si falta, 403 `CSRF_REJECTED`. Es la defensa CSRF: otra web puede hacer que el navegador envíe la cookie, pero no puede añadir esa cabecera.
3. **`POST /api/auth/refresh`** (sin cuerpo, con la cookie `streambox_refresh` y **siempre** con `X-Requested-With: StreamBox`) responde 204 con un JWT nuevo y un refresh token nuevo: el anterior queda revocado (rotación). Errores: 401 `SESSION_EXPIRED` (hay que volver a iniciar sesión; la respuesta borra las cookies), 403 `CSRF_REJECTED`, 429.
4. **`POST /api/auth/logout`** (**siempre** con `X-Requested-With: StreamBox`) revoca la sesión en el servidor y borra las dos cookies (`Max-Age=0`); responde 204 haya o no sesión. Error: 403 `CSRF_REJECTED`. Si falla la base de datos responde 500 **sin** borrar las cookies, para que el cliente pueda reintentar. El JWT de acceso es stateless: una copia seguiría valiendo hasta caducar (15 minutos como mucho), pero ya no se podría renovar.

**Clientes de API sin navegador** pueden mandar el JWT en `Authorization: Bearer <JWT>` (el valor de la cookie `streambox_token`). Con Bearer no se exige `X-Requested-With` (no hay riesgo de CSRF), salvo en refresh y logout, que solo leen la cookie `streambox_refresh` y exigen la cabecera siempre. Si una petición trae Bearer, manda el Bearer y no se mira la cookie.

En `openapi.yaml` las dos formas son esquemas de seguridad alternativos de cada operación autenticada: `cookieAuth` (`apiKey` en la cookie `streambox_token`, la principal) y `bearerAuth`. Las operaciones no seguras autenticadas documentan el 403 `CSRF_REJECTED` (lo añade `CookieAuthOperationCustomizer`); login y registro no lo tienen, y refresh y logout documentan el suyo.

**Formato:** la API solo acepta JSON en los cuerpos (otro tipo, como YAML: 415 `UNSUPPORTED_MEDIA_TYPE`) y todos los errores salen en JSON con el formato `ErrorResponse` y su `code`, incluidos el 406 `NOT_ACCEPTABLE` (un `Accept` que no admite JSON) y los de `/error`.

## Cómo usar la colección

1. Arranca el backend en local (ver README principal) o apúntalo a otro entorno cambiando `baseUrl`.
2. En Postman: **Import** de la colección y del entorno, y selecciona el entorno `StreamBox - Local`.
3. Rellena `adminEmail` y `adminPassword` con los valores de `ADMIN_EMAIL` / `ADMIN_PASSWORD` con los que arrancó el backend (las cuentas ADMIN solo se crean así). Son opcionales solo si no vas a ejecutar las peticiones de administrador. `userPassword` es la contraseña de una cuenta de prueba que la propia colección registra: no es una credencial real.
4. Lanza la colección entera con el **Runner**, en orden. Las peticiones se encadenan solas (los ids se guardan en variables de colección), la carpeta 8 borra lo que se creó y la 9 cierra las sesiones.

Carpetas: 1 Autenticación · 2 Seguridad (401/403) · 3 Usuarios · 4 Géneros · 5 Películas · 6 Series y episodios · 7 Favoritos · 8 Limpieza · 9 Sesión (refresh y logout).

### Cómo modela la sesión

La colección usa las dos formas de autenticarse, para que se vean las dos:

- **USER, por cookies, como el navegador.** Postman (y Newman) guardan las cookies de cada respuesta en su *cookie jar*, por dominio, y las reenvían solas a las rutas de su `Path`; en Postman se ven en el botón **Cookies** (bajo *Send*). El login del USER no necesita ningún script y sus peticiones no llevan autorización. En el Postman web, las peticiones a `localhost` necesitan el *Postman Agent* de escritorio.
- **ADMIN, por `Authorization: Bearer`, como un cliente de API.** El jar guarda una sola cookie `streambox_token` por dominio: si el login del ADMIN la guardara, sustituiría la sesión del USER. Por eso sus peticiones desactivan el jar (pestaña *Settings* → *Disable cookie jar*; en el JSON, `protocolProfileBehavior.disableCookies`), su login lee el JWT del `Set-Cookie` y lo guarda en la variable `adminToken`, y sus peticiones lo mandan como Bearer.
- **`X-Requested-With: StreamBox`** la añade un *pre-request* a nivel de colección en las peticiones no seguras, salvo en las de Bearer (para demostrar que no se exige), y siempre en refresh y logout. Las peticiones que comprueban el 403 `CSRF_REJECTED` la quitan en su propio *pre-request*, que se ejecuta después.
- **Carpeta 9:** refresh sin cabecera (403) y con ella (204; comprueba que el refresh token rota), `GET /api/users/me` con la sesión renovada, logout sin cabecera (403, sin borrar cookies) y con ella (204, borra las cookies), `GET /api/users/me` tras el logout (401) y un refresh con el token que estaba vigente, enviado a mano en la cabecera `Cookie` (401 `SESSION_EXPIRED`: la revocación es en el servidor, no solo en la cookie). Por último cierra también la sesión del ADMIN.

Si cortas la ejecución a medias o lanzas una petición suelta, ten en cuenta que el jar de Postman conserva las cookies entre ejecuciones (bórralas desde **Cookies** si quieres empezar sin sesión); Newman empieza cada ejecución con el jar vacío.

### Desde la línea de comandos

Por ejemplo contra un backend efímero en el puerto 8099 (ver «Cómo se generó» más abajo):

```bash
npx --yes newman@6 run docs/api/postman/streambox.postman_collection.json \
  -e docs/api/postman/streambox-local.postman_environment.json \
  --env-var baseUrl=http://localhost:8099 \
  --env-var adminEmail=<ADMIN_EMAIL> --env-var adminPassword=<ADMIN_PASSWORD>
```

Newman es una herramienta de un solo uso: no está en ningún `package.json`.

### Límites de velocidad

El registro permite 5 altas por hora y IP, el login 10 por minuto y el refresh 30 por minuto. Cada ejecución completa gasta 2 registros, 3 logins y 3 renovaciones, así que a la **tercera ejecución en una hora** el registro responde 429 (`RATE_LIMIT_EXCEEDED`) y fallan esos tests: no es un fallo de la colección. Para iterar, arranca el backend local con `STREAMBOX_SECURITY_RATE_LIMIT_REGISTER_MAX_REQUESTS=1000` (y análogo para login y refresh). El test de «credenciales incorrectas» usa un email inexistente para no bloquear ninguna cuenta real; aun así ese email también cuenta fallos (5 en 15 minutos = bloqueo), de modo que a la quinta ejecución en 15 minutos responde 429 `ACCOUNT_LOCKED`.

## Cómo se generó y cómo regenerarla

- **`openapi.yaml`**: se arrancó el backend con la base H2 en memoria y el perfil `e2e` (como hace Playwright: `spring-boot:run -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.profiles=e2e` desde `streambox/`, con `SERVER_PORT=8099`, `SPRING_DATASOURCE_*` apuntando a H2, un `JWT_SECRET` de prueba, `STREAMBOX_AUTH_COOKIE_SECURE=false` y un administrador de prueba en `ADMIN_*`; ver `frontend/playwright.config.ts`), sin tocar la base de desarrollo. Se descargó `GET /v3/api-docs` y se convirtió a YAML (PyYAML, `sort_keys=False`, `width=120`). Solo se cambió `servers` (apunta a `localhost:8080`). Es la salida de `springdoc`, así que refleja exactamente lo que anotan los controladores. Si cambia un endpoint o un DTO, hay que volver a generarla.
- **Colección**: escrita a partir de la especificación y comprobada con Newman 6 contra ese backend efímero: 62/62 peticiones y 117/117 aserciones en la primera ejecución, y 62/62 y 116/116 en una segunda sobre la misma base (el registro da 409 «el usuario ya existe» y se omite la aserción que solo aplica al 201).

## Qué comprueban los tests de la colección

Códigos de estado reales (201, 204, 400, 401, 403, 404, 409), forma de las respuestas (páginas con `content/page/size/totalElements/...`), el formato `ErrorResponse` con su `code` (`VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `INVALID_CREDENTIALS`, `CSRF_REJECTED`, `ACCESS_DENIED`, `SESSION_EXPIRED`...), las cookies de sesión (atributos `HttpOnly`, `SameSite=Strict` y `Path`, rotación del refresh token y borrado en el logout), la defensa CSRF, que un Bearer inválido no se «rescata» con la cookie, la autorización por rol y reglas de negocio no obvias: una serie sin episodios es invisible para un usuario (404 idéntico al de una inexistente) pero visible para un ADMIN en `/api/admin/series`; un episodio repetido da 409; no se puede añadir dos veces un favorito.

## Nota sobre Postman en la nube

La colección, la especificación y el entorno viven en el repositorio y se importan a mano. **No se han creado en un workspace de Postman**: basta importar los archivos.
