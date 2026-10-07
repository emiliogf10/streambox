# 0005. Recursos personales en `/api/users/me/...` con el id del token

**Estado:** Aceptada

## Contexto
Los favoritos y los datos de cuenta pertenecen a un usuario. Si la ruta llevara su id (`/api/users/{userId}/favorites`), bastaría cambiar el número para leer o modificar los de otra persona: es el fallo **IDOR / BOLA**, el más frecuente en APIs.

## Decisión
- Todo endpoint personal cuelga de `/api/users/me/...` y obtiene el usuario de `@AuthenticationPrincipal AuthenticatedUser` (el del token), **nunca** de un id en la URL o el cuerpo.
- Las reglas de acceso están centralizadas en `SecurityConfig`: lectura (`GET`/`HEAD`) del catálogo para autenticados; cualquier otro método sobre `/api/movies/**`, `/api/genres/**` y `/api/series/**`, y todo `/api/admin/**`, solo `ADMIN`. **Se añade la regla antes que el endpoint**, para que nunca nazca abierto a cualquier autenticado.
- Los casos de acceso entre usuarios se prueban (`*ObjectLevelAuthorizationIntegrationTest`).

## Alternativas descartadas
- **Id en la ruta con comprobación de propiedad en cada servicio:** funciona, pero basta olvidarse una vez para abrir un agujero; con `/me` no hay parámetro que manipular.

## Consecuencias
- (+) El IDOR es imposible por diseño en los recursos personales.
- (−) Un administrador no puede consultar la lista de otro usuario con esta API (no es un requisito). Si lo fuera, iría en `/api/admin/...` con su propia regla.
