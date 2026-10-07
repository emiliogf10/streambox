# Estado de las tareas

Archivo de reanudación: si la sesión se corta (sin tokens o sin conexión), léelo primero. Se actualiza al terminar cada paso de una tarea. Rama de trabajo de las sesiones en la nube: `claude/stoic-wright-ac5w1k` (los avances se suben ahí porque el contenedor es efímero).

## Tarea en curso

Ninguna. Esperando la siguiente petición.

## Colección de Postman y OpenAPI versionada (2026-10-07)

Hecho: `docs/api/openapi.yaml` (generada con springdoc desde el backend real en H2/8099), `docs/api/postman/` (colección v2.1 con 52 peticiones y 90 aserciones + entorno `StreamBox - Local`) y `docs/api/README.md`. Verificado con Newman contra el backend real: 52/52 y 90/90, también en una segunda ejecución (caso 409). Auth modelada como en `main` (Bearer en cabecera); **al hacer la tarea 29 (cookie HttpOnly) hay que regenerar la spec y actualizar login y autorización de la colección.** Pendiente: el conector de Postman no conectó en la sesión, así que no se creó nada en un workspace de Postman (hay que importar los archivos o repetirlo con el conector). Sin cambios de código de producción; no se ejecutó la suite de Maven.

## Tarea 28 terminada: ADRs y revisión del README (2026-10-07)

Solo documentación (sin cambios de código; no se ejecutaron tests). Hecho: `docs/adr/` con un índice y 10 ADR (0001 backend, 0002 frontend, 0003 Flyway, 0004 JWT, 0005 `/me`, 0006 rate limiting, 0007 favoritos, 0008 series, 0009 tests, 0010 Docker/nginx); README con título, árbol de carpetas corregido, stack actualizado, pasos para arrancar backend (`JWT_SECRET`) y frontend (Node 24, proxy) y enlace a los ADR. Plan actualizado (fila 28 y orden de trabajo). Pendiente del autor: leer los ADR y corregir lo que no refleje sus motivos reales (los ADR se redactaron a partir del código y del manual).

## Tarea anterior: «Revisión de diseño con las skills» (2026-10-07)

Resultado completo en `docs/PLAN_DE_ACCION.md`, apartado «Segunda revisión de diseño (2026-10-07)». Resumen:

| Paso | Estado |
|---|---|
| Entorno y línea base | ✅ build OK, lint 0, Vitest 772, E2E 134 pasan + 2 fallan |
| Auditoría (series, `/peliculas`, `/perfil`, admin) | ✅ manual (la skill `impeccable` no trajo detector) |
| Mejoras aplicadas | ✅ movimiento reducido, contorno del buscador, etiqueta del banner, rejilla compartida |
| Verificación final | ✅ build OK, lint 0, Vitest 779/779, E2E 141 pasan + 24 omitidos + 2 fallan (navegador del contenedor) |
| Docs actualizados | ✅ plan, manual (cap. 20 y tabla de tests), CLAUDE.md (cifra de Vitest) |

**Pendiente de la tarea (decisión o comprobación del autor):** ejecutar `accessibility.spec.ts:189` en su máquina con el Chromium de Playwright 1.63 para confirmar que el fallo es del navegador del contenedor; y decidir las «Propuestas para el autor» del plan (Geist Sans, borde de botones `outline`, «Volver a series», unificar cuadros de error, flake del aviso bajo el cursor).

## Notas de entorno (sesiones en la nube)

- Playwright 1.63 pide `chromium_headless_shell-1243` y el contenedor trae el `1194` (Chromium 141). Hay un enlace simbólico `/opt/pw-browsers/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell` → `headless_shell` del 1194. Si el contenedor se recrea, repetirlo (`mkdir -p` + `ln -sf`); sin él fallan todos los E2E con «Executable doesn't exist».
- Maven Central es accesible (`./mvnw` funciona). `npm ci` hace falta en `frontend/` en un contenedor nuevo.
- No ejecutar dos Playwright/Maven a la vez (puertos 8099/5199 y `target/`).
- Con `accessibility.spec.ts:189` fallando, el proyecto `catalogo-mutable` sale «did not run»; se ejecuta con `npx playwright test --project=catalogo-mutable --no-deps`.
