# Estado de las tareas en curso

Archivo de reanudación: si la sesión se corta (sin tokens o sin conexión), léelo primero. Se actualiza al terminar cada paso. Rama de trabajo: `claude/stoic-wright-ac5w1k`.

## Tarea actual: «Revisión de diseño con las skills» (plan de acción, Pendiente → Revisión visual humana)

**Interpretación (2026-10-07):** el plan ya recoge una primera revisión (2026-10-03), pero desde entonces se añadieron series, panel de administración y `/perfil`, que no se revisaron. Se repite la revisión sobre el frontend actual y se aplican las propuestas pendientes que **no** requieren decisión del autor. Queda fuera Geist Sans (recurso nuevo, decisión suya).

**Skills disponibles en esta sesión:** `impeccable`, `design-taste-frontend`, `emil-design-eng` (el plan menciona `redesign-skill` y `taste-skill`, que aquí no existen).

**Reglas a respetar:** CLAUDE.md (textos de la interfaz verdaderos para USER y ADMIN, contrastes WCAG AA recalculados si cambia un token, cero `style={{}}`, tests con rol/etiqueta). Verificación: `npm run build`, `npm run lint` (código 0), `npm run test`, `npm run test:e2e`.

### Pasos

| # | Paso | Estado |
|---|---|---|
| 1 | Crear este archivo de estado | ✅ |
| 2 | Preparar entorno del frontend (`npm ci`, Chromium de Playwright) y medir la línea base (build, lint, Vitest, E2E) | ✅ build ✅, lint exit 0, Vitest 772/772, E2E 134 pasan + 24 omitidos (capturas) + **2 fallan** (ver notas) |
| 3 | Auditoría con las skills de las páginas nuevas (series, detalle de serie, admin, perfil) y de las modificadas | 🔄 delegada al agente `frontend` (junto con el paso 4) |
| 4 | Aplicar mejoras (delegado al agente `frontend`; incluye el fallo de línea base de WCAG 2.4.11): movimiento reducido, contorno del buscador (3:1), constante de rejilla compartida (`CatalogSkeleton`/banner), «Estreno reciente» en 375 px, hallazgos de la auditoría | ⬜ |
| 5 | Verificación completa (build, lint, Vitest, E2E) y recuento de tests | ⬜ |
| 6 | Actualizar docs (plan, manual cap. 20, CLAUDE.md si cambian cifras, READMEs) y borrar lo obsoleto | ⬜ |
| 7 | Informe final | ⬜ |

### Notas de la sesión
- Entorno: `npm ci` hecho, Maven Central accesible (`./mvnw compile` OK), Chromium en `/opt/pw-browsers`. Los E2E se lanzan con `npx playwright test` desde `frontend/`.
- No ejecutar dos Playwright/Maven a la vez (puertos 8099/5199 y `target/`).
- Esta sesión es un contenedor efímero: los avances solo sobreviven si se suben a la rama. Se hacen commits de checkpoint en `claude/stoic-wright-ac5w1k` (nunca en `main`).
- **Entorno (no es del repo):** Playwright 1.63 pide `chromium_headless_shell-1243` y el contenedor trae el `1194`. Se creó un enlace simbólico `/opt/pw-browsers/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell` → `headless_shell` del 1194. Si el contenedor se recrea hay que repetirlo (`mkdir -p` + `ln -sf`). Sin él fallan 136 E2E con «Executable doesn't exist».
- **Línea base E2E con ese enlace (antes de tocar nada):** 2 fallos, ambos `accessibility.spec.ts:189` («formulario de película: el primer campo con error y el control anterior (Shift+Tab) quedan a la vista»), a 375 y 1280 px: «Volver al listado» queda en y=8, tapado por la barra (WCAG 2.4.11). Hay que averiguar si es un fallo real de la UI o de la versión del navegador (1194 vs 1243); no se debilita el test.
