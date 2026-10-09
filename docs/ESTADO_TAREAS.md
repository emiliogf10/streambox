# Estado de las tareas (sesiones en la nube)

Archivo de reanudación de los hilos de Claude en la nube: si la sesión se corta (sin tokens o sin conexión), léelo primero y actualízalo al terminar cada paso de una tarea. Cada hilo trabaja en su rama `claude/project-thread-*` (el contenedor es efímero: lo que no se sube a la rama se pierde) y entrega los cambios en una PR.

**Lo hecho y lo pendiente no se lleva aquí**, sino en [`PLAN_DE_ACCION.md`](PLAN_DE_ACCION.md) (fuente de verdad, con un historial por fecha). Este archivo solo guarda la tarea que esté a medias en la nube y las notas del entorno.

## Tarea en curso

Ninguna (2026-10-09). La última, la tarea 29 completa (token de acceso de 15 min + *refresh token*), se hizo en local: ver el historial del plan.

## Notas de entorno (contenedor de la nube)

- Playwright 1.63 pide `chromium_headless_shell-1243` y el contenedor trae el `1194` (Chromium 141). Hay que crear un enlace simbólico `/opt/pw-browsers/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell` → `headless_shell` del 1194 (`mkdir -p` + `ln -sf`); sin él fallan todos los E2E con «Executable doesn't exist».
- Con ese Chromium 141 falla `accessibility.spec.ts:189` (no aplica `scroll-margin-top` a `focus()` si el elemento ya cabe en la ventana; en la máquina del autor pasa). Al fallar, el proyecto `catalogo-mutable` sale «did not run»: ejecútalo con `npx playwright test --project=catalogo-mutable --no-deps`.
- Maven Central es accesible (`./mvnw` funciona). En un contenedor nuevo hace falta `npm ci` en `frontend/`.
- No ejecutes dos Playwright/Maven a la vez (puertos 8099/5199 y `streambox/target/`).
