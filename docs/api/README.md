# API de StreamBox: especificación OpenAPI y colección de Postman

| Archivo | Para qué sirve |
| :--- | :--- |
| [`openapi.yaml`](openapi.yaml) | Especificación OpenAPI 3.1 de todos los endpoints (34 operaciones en 20 rutas), con sus DTOs, códigos de error y seguridad. Se puede importar en Postman, Insomnia, Swagger Editor, etc. |
| [`postman/streambox.postman_collection.json`](postman/streambox.postman_collection.json) | Colección de Postman (formato v2.1) con 52 peticiones y 90 aserciones, organizadas en 8 carpetas. |
| [`postman/streambox-local.postman_environment.json`](postman/streambox-local.postman_environment.json) | Entorno `StreamBox - Local` (`baseUrl = http://localhost:8080`). |

## Estado de la autenticación (importante)

Ambos archivos describen la autenticación **tal como está hoy en `main`**: el login devuelve el JWT en el cuerpo (`{"token": "..."}`) y el cliente lo manda en la cabecera `Authorization: Bearer <token>` (esquema `bearerAuth`). La tarea 29 lo cambiará a una cookie HttpOnly: cuando se haga, hay que regenerar `openapi.yaml` y actualizar en la colección el login (ya no guardará el token) y la autorización (cookie en vez de `Bearer`).

## Cómo usar la colección

1. Arranca el backend en local (ver README principal) o apúntalo a otro entorno cambiando `baseUrl`.
2. En Postman: **Import** de la colección y del entorno, y selecciona el entorno `StreamBox - Local`.
3. Rellena `adminEmail` y `adminPassword` con los valores de `ADMIN_EMAIL` / `ADMIN_PASSWORD` con los que arrancó el backend (las cuentas ADMIN solo se crean así). Son opcionales solo si no vas a ejecutar las peticiones de administrador. `userPassword` es la contraseña de una cuenta de prueba que la propia colección registra: no es una credencial real.
4. Lanza la colección entera con el **Runner**, en orden. Las peticiones se encadenan solas (los ids y los tokens se guardan en variables de colección) y la carpeta 8 borra lo que se creó.

Carpetas: 1 Autenticación · 2 Seguridad (401/403) · 3 Usuarios · 4 Géneros · 5 Películas · 6 Series y episodios · 7 Favoritos · 8 Limpieza.

Desde la línea de comandos (por ejemplo contra el backend efímero de la suite E2E):

```bash
npx newman run docs/api/postman/streambox.postman_collection.json \
  -e docs/api/postman/streambox-local.postman_environment.json \
  --env-var baseUrl=http://localhost:8099 \
  --env-var adminEmail=<ADMIN_EMAIL> --env-var adminPassword=<ADMIN_PASSWORD>
```

### Límites de velocidad

El registro permite 5 altas por hora y IP y el login 10 por minuto. Cada ejecución completa gasta 2 registros y 4 logins, así que a la **tercera ejecución en una hora** el registro responde 429 (`RATE_LIMIT_EXCEEDED`) y fallan esos tests: no es un fallo de la colección. Para iterar, arranca el backend local con `STREAMBOX_SECURITY_RATE_LIMIT_REGISTER_MAX_REQUESTS=1000` (y análogo para login). El test de «credenciales incorrectas» usa un email inexistente para no bloquear ninguna cuenta real (5 fallos = bloqueo de 15 min).

## Cómo se generó y cómo regenerarla

- **`openapi.yaml`**: se arrancó el backend con la base H2 en memoria y el perfil `e2e` (como hace Playwright, en el puerto 8099, sin tocar la base de desarrollo), se descargó `GET /v3/api-docs` y se convirtió a YAML. Solo se cambió `servers` (apunta a `localhost:8080`). Es la salida de `springdoc`, así que refleja exactamente lo que anotan los controladores. Si cambia un endpoint o un DTO, hay que volver a generarla.
- **Colección**: escrita a mano a partir de la especificación y comprobada con Newman contra el backend real (52/52 peticiones y 90/90 aserciones, también en una segunda ejecución sobre la misma base, que ejercita el caso «el usuario ya existe» = 409).

## Qué comprueban los tests de la colección

Códigos de estado reales (201, 204, 400, 401, 403, 404, 409), forma de las respuestas (páginas con `content/page/size/totalElements/...`), el formato `ErrorResponse` con su `code` (`VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `INVALID_CREDENTIALS`...), la autorización por rol y reglas de negocio no obvias: una serie sin episodios es invisible para un usuario (404 idéntico al de una inexistente) pero visible para un ADMIN en `/api/admin/series`; un episodio repetido da 409; no se puede añadir dos veces un favorito.

## Nota sobre Postman en la nube

La colección, la especificación y el entorno viven en el repositorio y se importan a mano. En la sesión que los generó el conector de Postman no llegó a conectar, así que **no se crearon en un workspace de Postman**: basta importar los archivos, o pedirlo de nuevo cuando el conector funcione.
