# Estado de las tareas

Archivo de reanudación: si la sesión se corta (sin tokens o sin conexión), léelo primero. Se actualiza al terminar cada paso de una tarea. Rama de trabajo de las sesiones en la nube: `claude/stoic-wright-ac5w1k` (los avances se suben ahí porque el contenedor es efímero).

## Tarea en curso

Tarea 29 (token en cookie HttpOnly): hecha en la rama `claude/project-thread-6wdzw2`, PR abierta. Backend 1166 (H2, sin Docker), Vitest 774, E2E 133 + 7 de `catalogo-mutable` pasan; fallan solo los 2 de `accessibility.spec.ts:189` (navegador del contenedor) y a veces el flake del aviso bajo el cursor (`admin.spec.ts:122`). Pendiente: tests `Postgres*` con Docker, revisión visual de «Comprobando tu sesión...», vida corta + refresh, HSTS.

## Última tarea terminada: «Revisión de diseño con las skills» (2026-10-07)

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
