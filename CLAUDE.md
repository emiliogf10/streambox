# StreamBox — instrucciones para Claude

Plataforma de streaming tipo Netflix. Es un **proyecto de portfolio**: el objetivo no es solo que funcione, sino que el código sea defendible en una entrevista técnica y que sirva para aprender. El autor es un **desarrollador junior**: cuando detectes algo mejorable explica *qué problema hay, por qué lo es y cómo se soluciona*, no solo "esto está mal".

## Estado y plan

- **`docs/PLAN_DE_ACCION.md` es la fuente de verdad** de lo hecho y lo pendiente. Al terminar una tarea del plan, actualízalo (y el README si cambia algo visible: endpoints, variables, comandos).
- **`docs/MANUAL_PROGRAMADOR.md` explica cómo funciona todo por dentro.** Tras cualquier cambio que altere cómo funciona algo (flujos, seguridad, configuración, endpoints, esquema, frontend, tests, comandos), actualiza el capítulo correspondiente para que el manual siga describiendo el código real. Si el cambio no afecta a nada de lo que explica, no hace falta tocarlo.
- Antes de proponer o implementar algo, comprueba en el código que no existe ya o que sigue igual: el plan y este archivo pueden quedar por detrás del repositorio.

## Estructura del repositorio

| Carpeta | Contenido |
| :--- | :--- |
| `streambox/` | Backend: Java 21, Spring Boot 4.1, Maven (wrapper `mvnw`), PostgreSQL, Flyway |
| `frontend/` | SPA: React 19, Vite, TypeScript, Tailwind v4 |
| `docs/` | Plan de acción y documentación |
| `.github/` | CI (`workflows/ci.yml`: backend, frontend, e2e y docker) y Dependabot |
| raíz | `docker-compose.yml` (db + backend + frontend con nginx), `.env.example` (plantilla; `.env` está ignorado) |
| `.claude/agents/` | Agentes especializados (ver abajo) |

Paquete base del backend: `com.emilio.streambox` en `streambox/src/main/java/...`.

## Comandos

Backend (desde `streambox/`; en PowerShell `.\mvnw.cmd`, en bash `./mvnw`):

```
.\mvnw.cmd test                         # toda la suite (H2 en memoria, no necesita PostgreSQL ni JWT_SECRET; si la variable existe en tu shell debe tener ≥32 caracteres)
.\mvnw.cmd test -Dtest=NombreDeTest     # un test concreto
.\mvnw.cmd -q compile                   # solo compilar
.\mvnw.cmd spring-boot:run              # perfil dev: necesita JWT_SECRET + application-local.properties con la conexión (Supabase o PostgreSQL local)
```

Frontend (desde `frontend/`): `npm run dev` (puerto 5173, proxy de `/api` a `localhost:8080`), `npm run build` (`tsc -b && vite build`), `npm run lint` (oxlint), `npm run test` (Vitest, ~15 s) y `npm run test:e2e` (Playwright, ~60 s; levanta su propio backend en el 8099 y Vite en el 5199, aislados de la BD y los puertos del usuario).

Docker (desde la raíz; ver cap. 4.6 del manual): `docker compose up -d --build` levanta todo en `http://localhost:8088` (necesita `.env`, copia de `.env.example` con `POSTGRES_PASSWORD` y `JWT_SECRET`); `docker compose down` para (con `-v` borra la base). Para probar sin tocar el `.env` del usuario: `docker compose --env-file <archivo temporal fuera del repo> ...`. **Ojo: las variables de la terminal ganan al `.env`** (la del usuario tiene `JWT_SECRET`; usa `env -u JWT_SECRET`). Validar el CI en local: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12`.

**Maven y Docker:** los tests del paquete `postgres` usan Testcontainers (`postgres:16`) y **se omiten solos si Docker no está en marcha**. Solo ellos: `.\mvnw.cmd test "-Dtest=Postgres*"`; sin ellos: `"-Dtest=!Postgres*"`. Dos procesos de Maven a la vez en `streambox/` se pisan (`target/`): un solo agente con Maven cada vez.

**El editor también escribe en `target/`:** la extensión Java del IDE del autor (Antigravity, basado en VS Code) compila en `target/classes` y `target/test-classes`. Si su espacio de trabajo Java se corrompe, deja clases con «Unresolved compilation problem» y `mvnw test` falla en el descubrimiento (`ClassNotFoundException` de una clase que sí existe) aunque el código esté bien. Solución en Maven: `.\mvnw.cmd clean test`. Solución en el editor (los archivos «en rojo»): comando «Java: Clean Java Language Server Workspace».

## Reglas de trabajo

- **No hagas commit ni push** salvo que se te pida. Si se pide, mensajes en español con prefijo (`feat:`, `fix:`...).
- **Todo código nuevo lleva Javadoc en español** (clases, métodos públicos y la razón de las decisiones no obvias). Los comentarios explican el *porqué*, no repiten el código.
- **Idioma:** respuestas, documentación, mensajes de error de la API y OpenAPI en español.
- No añadas dependencias al `pom.xml` o `package.json` sin justificarlo y avisar.
- **Nunca toques la base de datos de desarrollo del usuario: es Supabase** (su `application-local.properties`, ignorado por git, la define; puede tener la app corriendo en el puerto 8080). **Arrancar la app sin más (`spring-boot:run`) se conecta a ella**, así que para cualquier prueba arranca con otro puerto (`--server.port=8099`) **y sobrescribe la conexión con variables de entorno** (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, que ganan a ese archivo) apuntando a una base temporal: H2 en memoria como hace el E2E (`frontend/playwright.config.ts`) o una base `streambox_check` en un PostgreSQL local, que se borra al terminar. Los tests no la necesitan: usan H2 y Testcontainers, nunca ese archivo (comprobado con una conexión falsa).
- **Nunca leas, busques ni imprimas `streambox/src/main/resources/application-local.properties`** (contiene las credenciales de Supabase). Nada de `grep`/`cat` con comodines sobre `src/main/resources/*.properties`: nombra los archivos uno a uno. Ya ocurrió una vez y obligó al usuario a cambiar la contraseña. Ese archivo tampoco debe entrar nunca en una imagen Docker ni en un JAR (lo impiden `streambox/.dockerignore` y `pom.xml`; si tocas cualquiera de los dos, compruébalo).
- Entorno Windows: el tool Bash rechaza scripts largos con muchas comillas/heredocs; para ficheros usa las herramientas Write/Edit.
- No inventes datos ni "arregles" tests debilitándolos: si un test falla, entiende la causa.

## Backend: arquitectura y convenciones

Capas: `controller` → `service` → `repository` → `entity`, más `dto`, `mapper`, `specification`, `security`, `exception`, `config`.

- **Controladores:** solo reciben y devuelven **DTOs**, sin lógica. Devuelven el DTO directamente con `@ResponseStatus` (no `ResponseEntity`). Todo endpoint con `@Operation`/`@ApiResponses` en español (códigos reales: 401 sin token, 403 sin permisos, 429 en login/registro).
- **Servicios:** clases concretas (sin interfaz). Devuelven **DTOs, nunca entidades**; el mapeo ocurre **dentro de la transacción** (`@Transactional(readOnly = true)` en lecturas) porque las colecciones son `LAZY` y `open-in-view=false`.
- **Solo JSON, sin multipart ni `FormContentFilter`:** `config/JsonOnlyMessageConvertersConfig` quita el conversor YAML que trae springdoc (YAML → 415); `spring.servlet.multipart.enabled=false` y `spring.mvc.formcontent.filter.enabled=false` (ese filtro leía sin límite y antes de la autenticación los formularios de `PUT`/`PATCH`/`DELETE`). Una subida de archivos futura exige reactivar multipart solo para su ruta y con límites bajos (cap. 13 del manual). Los errores de `GlobalExceptionHandler` salen **siempre en JSON** (fijan `Content-Type`), pida lo que pida el `Accept`: lo que acaba en `sendError` se reenvía a `/error` y llega sin autenticar (401 falso). MockMvc no hace ese reenvío: se prueba con Tomcat real.
- **DTOs:** `record`s (los de petición antiguos `CreateUserRequest` y `LoginRequest` aún son clases Lombok: convertirlos es opcional). Validación con Bean Validation.
- **Mappers:** clases `final` con métodos estáticos. No MapStruct.
- **Entidades:** `equals/hashCode` por id, fechas `Instant` + `@CreationTimestamp`. Relaciones `LAZY`; los listados paginados **no** hacen fetch de colecciones (`default_batch_fetch_size=50`), solo `findById` usa `@EntityGraph`.
- **Errores:** excepciones de dominio (`ResourceNotFoundException` es la base de los 404) tratadas en `GlobalExceptionHandler` (extiende `ResponseEntityExceptionHandler`), formato `ErrorResponse` con `ErrorCode`. Nunca devuelvas mensajes de excepciones del framework al cliente.
- **Favoritos** (`FavoriteService`): `INSERT`/`DELETE` directos sobre `user_favorite_movies`; no cargues la colección completa.
- **Paginación/orden:** el `sort` va por lista blanca y se desempata por `id`.

## Base de datos: Flyway manda

- El esquema lo gestiona **Flyway** (`src/main/resources/db/migration`, hoy `V1` a `V4`; mira la carpeta para la última). Un *callback* de Flyway solo para PostgreSQL (`db/callback/postgresql/afterMigrate__close_public_api.sql`, activado con `spring.flyway.locations=…{vendor}`) cierra cada arranque las tablas de `public` a la API pública de Supabase (RLS sin políticas, sin permisos para `anon`/`authenticated`, solo si esos roles existen): **las tablas nuevas ya no hay que añadirlas a mano** a `docs/supabase-seguridad.sql` (queda como respaldo manual con el mismo bloque). Hibernate solo **valida** (`ddl-auto=validate` en dev, prod y test).
- Cambiar una entidad = **entidad + migración nueva** (`V<N+1>__descripcion.sql`). **Nunca edites una migración ya aplicada.**
- SQL portable: debe funcionar en PostgreSQL y en H2 (modo PostgreSQL), porque los tests usan H2. Si algo es específico de PostgreSQL, hay que decirlo y la tarea de Testcontainers (plan nº 22) pasa a ser necesaria.
- Los índices y restricciones se prueban en `FlywaySchemaIntegrationTest`.

## Seguridad

- JWT stateless (`JwtService`, `JwtProperties` con secreto ≥32 caracteres validado al arrancar, `issuer=streambox`, **HS256 fijo**). El filtro lee el usuario de la BD en cada petición, así que un cambio de rol es inmediato.
- **Sesión = token de acceso de 15 min + *refresh token*** (ADR 0011, cap. 10.4 bis del manual): `streambox_token` (JWT, `Path=/api`) y `streambox_refresh` (opaco, `Path=/api/auth`; en `refresh_tokens` solo su SHA-256). `POST /api/auth/refresh` rota el token; reutilizar uno ya rotado revoca toda la sesión (gracia de 10 s entre pestañas); fallo = 401 `SESSION_EXPIRED`; exige `X-Requested-With` y tiene su límite por IP (solo cuentan las que traen la cabecera). El logout revoca la sesión en el servidor (500 sin borrar cookies si falla la BD). `jwt.expiration-hours`/`JWT_EXPIRATION_HOURS` ya no existe: es `jwt.access-token-ttl`/`JWT_ACCESS_TOKEN_TTL`.
- Roles `USER` y `ADMIN`. Los endpoints personales cuelgan de `/api/users/me/...` y usan el id **del token** (`@AuthenticationPrincipal AuthenticatedUser`), nunca un id de la URL (evita IDOR).
- Rate limiting: `RateLimitingFilter` (por IP, login y registro; reconoce las rutas con `PathPatternRequestMatcher`, **nunca** comparando `getRequestURI()`, que llega sin decodificar; **no cuenta** las peticiones sin `Content-Type`, con uno mal formado o *CORS-safelisted* —`text/plain`, formulario, multipart—, que otra web podría enviar sin preflight para gastar el presupuesto de la víctima, y cuenta todo lo demás, no solo JSON: Spring también lee YAML; ver cap. 11 del manual) y `LoginAttemptService` (bloqueo de cuenta: 5 fallos/15 min; los fallos responden 401 con `remainingAttempts` y el que agota los intentos, 429 `ACCOUNT_LOCKED`; el límite por IP es 429 `RATE_LIMIT_EXCEEDED`). **«IP conocida»**: una IP desde la que ya se inició sesión con éxito con una cuenta (máx. 5 por cuenta, 30 días; `lockout.max-known-ips`, `lockout.known-ip-ttl`) tiene su propio contador (email+IP, 5 fallos/15 min) y no sufre el bloqueo de la cuenta: así nadie puede mantener bloqueado al titular (ni al admin) fallando su login a propósito; la IP sale de `getRemoteAddr()` y `AuthenticationService.login` la recibe (ver cap. 11.2 del manual). Los contadores están en memoria, con tope de claves (`rate-limit.max-keys`, expulsa la más antigua) y las IPv6 se agrupan por `/64` (`ClientAddress`). Los tests suben los límites en `application-test.properties`; los de rate limiting los bajan con `@TestPropertySource` y usan IPs/emails únicos por test (cuidado: tras un login correcto, esa IP pasa a ser «conocida» y sus fallos van a otro contador).
- Contraseñas de cuentas nuevas: `security/password/PasswordPolicy` (12–64 caracteres, ≤72 bytes por BCrypt, no común, sin usuario/email). El login no exige mínimo (cuentas antiguas), solo un máximo de 1024.
- Catálogo: lectura (`GET`/`HEAD`) para autenticados y cualquier otro método sobre `/api/movies/**`, `/api/genres/**` y `/api/series/**` solo `ADMIN` (regla de cierre en `SecurityConfig`). `/api/admin/**` (vistas de gestión) solo `ADMIN`. **Añade la regla antes que el endpoint**: si no, nace abierto a cualquier autenticado. **Las reglas de recursos privilegiados van por ruta, no por método** (`/api/users` es ADMIN salvo el `POST` de registro): una regla atada a `GET` deja pasar `HEAD`.
- Series sin episodios: invisibles para los usuarios y **indistinguibles de una inexistente** (mismo 404 y mismo cuerpo en todas las rutas públicas); ver cap. 15 bis del manual.
- Los administradores solo se crean con `AdminAccountInitializer` (`ADMIN_EMAIL`/`ADMIN_PASSWORD`; la política de contraseñas se aplica solo al crearlo; si ya existe una cuenta con ese email que no es ADMIN, **el arranque falla** con un mensaje claro y nunca se promueve); el registro público siempre crea `USER`.
- Actuator: solo `health` (público, sin detalles) e `info` (con token). Perfil `prod`: Swagger desactivado, logs JSON, sin SQL en logs.
- Nunca secretos en el repositorio. `application-local.properties` está ignorado.

## Tests

- Estilo: integración con `@SpringBootTest` + `@ActiveProfiles("test")` + MockMvc; unitarios con Mockito para lógica aislada. Perfil `test`: H2 + Flyway.
- Suites que modifican datos con commit real (favoritos, catálogo) **no** usan `@Transactional` y limpian la BD en `@BeforeEach`/`@AfterEach`; el resto usa `@Transactional` (rollback).
- Cada bug corregido deja un test que falla sin el arreglo.
- Lo que depende del motor (migraciones, SQL nativo, collation, `lower()`, concurrencia real) se prueba también contra PostgreSQL real en `src/test/.../postgres/` (extiende `PostgresIntegrationTestSupport`); H2 puede ocultar diferencias (ya ocultó un bug de búsqueda).
- Frontend: lógica y componentes con Vitest + Testing Library (`*.test.ts(x)` junto al código, utilidades en `src/test/`); flujos completos con Playwright en `frontend/e2e/`. Localiza por rol/etiqueta, no con `data-testid`.
- Antes de dar algo por terminado, ejecuta la suite completa y cuenta los tests; informa del resultado real (hoy: backend 1889 con Docker —1742 con H2 y 147 contra PostgreSQL real—; sin Docker se omiten los de PostgreSQL (cada parametrizado omitido cuenta como uno, así que el recuento de omitidos no coincide con el de métodos); 920 de Vitest y 154 E2E + 24 de capturas omitidas; en el contenedor de Claude (Chromium 141) fallan 2 E2E de `accessibility.spec.ts:189`).
- Vitest no espera tiempo real: los debounces se prueban con `src/test/fakeTimers.ts`. Los E2E que modifican el catálogo van en el proyecto `catalogo-mutable` de Playwright, que corre al final.

## Frontend: estado actual

SPA en `frontend/src/` organizada en `pages/`, `components/`, `context/`, `hooks/` y `lib/` (la Fase 3 del plan está hecha). Reglas que ya se cumplen y no deben romperse:

- **Todas las llamadas a la API pasan por `lib/api.ts` (`apiFetch`/`ApiError`)**: nada de `fetch` suelto. Maneja 401 (cierra sesión una vez; el 401 de login/registro no), 403 (no cierra sesión), 429 (`Retry-After`), 204 y red caída. Se decide por `status`/`code`, no por el texto.
- **El JWT ya no existe para JavaScript**: va en la cookie HttpOnly `streambox_token` (tarea 29). `AuthContext` descubre la sesión con `GET /api/users/me`; `apiFetch` manda `credentials: 'same-origin'` y `X-Requested-With: StreamBox` en las peticiones no seguras (defensa CSRF; sin ella 403 `CSRF_REJECTED`). Nunca guardes el token en `localStorage`. **Renovación:** ante un 401 de una ruta protegida, `apiFetch` hace **un único** `POST /auth/refresh` compartido (*single-flight*; login, logout y refresh van en fila con `navigator.locks` `streambox-session` entre pestañas, y un `BroadcastChannel` avisa de cada renovación) y repite la petición **una sola vez**; un 401 del refresh lleva al flujo de sesión caducada y **nunca** se reintenta; red/5xx/429 del refresh no cierran la sesión. El logout solo cierra la sesión en la interfaz si el servidor lo confirma (`lib/logout.ts`). Pendiente: HSTS (cuando haya HTTPS).
- Estilos con **clases de Tailwind y tokens `@theme`** de `index.css` (`canvas`, `surface`, `accent`, `muted`...), cero `style={{}}`. Foco visible con `focus-ring`. Contrastes WCAG AA ya calculados: si cambias un token, recalcula.
- **Datos del servidor con TanStack Query** (`lib/queryClient.ts`, `lib/queryKeys.ts`; cap. 19.3 del manual): las `queryFn` llaman a `apiFetch` con el `signal`; nada de cargas manuales con `useEffect`/epochs en hooks nuevos. Reintento como mucho 1 y solo en red/5xx (nunca 4xx: el 401 ya lo gestiona el refresh). **Tras cada escritura, invalida** lo afectado (`hooks/useInvalidateCatalog.ts`, `keysAffectedBy`). **La caché se vacía en cada cambio de sesión** (`queryClient.clear()` en `AuthContext.applySession`): nunca deben verse datos del usuario anterior.
- Modales con `components/Modal` (`<dialog>` + `useModalDialog`); avisos con `useToast()`; favoritos con `FavoritesContext` (mutaciones optimistas de TanStack, 409/404 = estado ya correcto).
- Las imágenes salen **siempre del `imageUrl` del título** (película o serie, tipo común `CatalogItem`) vía `components/MoviePoster` (lazy, con respaldo). Portadas locales de ejemplo en `public/covers/*.webp` (+ script opcional `docs/portadas-locales.sql`).
- Las URLs que vienen de la API (`videoUrl`) se validan con `getSafeVideoUrl` (solo http/https, sin credenciales).
- El catálogo se pide ordenado por el servidor: `GET /api/movies?sort=createdAt&direction=desc` (`direction` = `asc`|`desc`, por defecto `asc`).
- El rol se pregunta al servidor (`GET /api/users/me` en `AuthContext`: `user`, `isAdmin`, `userStatus`); el JWT no lo lleva. Panel de administración en `pages/admin/` (`/admin`, tras `RequireAdmin`); su validación (`lib/movieValidation.ts`) replica exactamente las reglas y mensajes del backend.
- `/peliculas` y `/series` son las secciones de la barra (ya no hay secciones reservadas). Los filtros de `/peliculas` viven en la URL (`lib/movieFilters.ts`, `hooks/useMovieFilters.ts`) y todos los listados paginados pasan por `hooks/usePagedCatalog.ts`.
- **Los textos de la interfaz importan tanto como el código** (petición expresa del autor). Cada estado vacío, aviso o botón debe ser verdad para cada rol (USER/ADMIN; mientras carga el usuario, se trata como USER), con título, descripción y botón coherentes y sin llevar a otra página vacía (ver cap. 20.9 del manual). Al revisar un cambio, léelos todos, no te limites a los tests.
- La barra superior publica su altura en `--navbar-height` y el contenido usa `scroll-margin-top` para que el foco no quede tapado (WCAG 2.4.11).
- **Perfil** (`/perfil`, `pages/ProfilePage.tsx`, enlace «Mi perfil» en el menú de usuario): muestra datos reales (`useAuth` + `useFavorites`) y permite **editar el perfil** (`components/profile/*`): cambiar el nombre (`PATCH /api/users/me`), cambiar la contraseña (`PUT /api/users/me/password`: exige la actual, `PasswordPolicy`, 5 fallos/15 min; revoca **todas** las sesiones y abre una nueva para la actual, así que el usuario sigue dentro y los demás dispositivos quedan fuera —hasta 15 min por el JWT de acceso—) y «Cerrar sesión en todos los dispositivos» (`POST /api/auth/logout-all`, con confirmación; incluye la actual). **El email no se puede cambiar** (es el `subject` del JWT y la identidad del login; decisión del autor). No hay historial ni suscripción. Ver cap. 20.10 del manual.

Pendiente: la revisión visual humana. Al tocar el frontend verifica con `npm run build`, `npm run lint` (debe dar código 0), `npm run test` y, si afecta a flujos o a la maquetación, `npm run test:e2e`.

## Grafo de conocimiento (graphify)

`graphify-out/` (ignorado por git; solo existe en la máquina del autor) contiene un grafo del repositorio: código (AST) + conceptos y decisiones de la documentación (ADRs, manual, plan), con comunidades nombradas en español.

- **Úsalo antes de explorar a ciegas.** Para preguntas de arquitectura o «¿qué toca X?», lee primero `graphify-out/GRAPH_REPORT.md` o consulta con `graphify query "<pregunta>"`, `graphify path "A" "B"` o `graphify explain "X"` (el ejecutable está en `C:\Users\Emilio\AppData\Roaming\Python\Python314\Scripts`, fuera del `PATH`; o `python -m graphify ...`). Contrasta con el código antes de afirmar algo: el AST tiene aristas colgantes hacia librerías externas.
- **Código:** se actualiza solo tras cada commit (hook `post-commit` de graphify, sin modelo de lenguaje). A mano: `PYTHONHASHSEED=0 graphify update .`. **Hay que fijar `PYTHONHASHSEED=0`**: sin él, la agrupación cambia en cada ejecución y se pierden los nombres de las comunidades.
- **Documentación** (`docs/`, `README.md`, `CLAUDE.md`, ADRs, agentes, CI): el hook no la procesa. Tras cambios importantes en ella, ejecuta `/graphify --update`, que hace la extracción semántica.
- **`.graphifyignore`** excluye `application-local.properties` y los `.env`: no lo relajes. Tras reconstruir, comprueba que `graph.json` no contiene credenciales.

## Navegador (`agent-browser`)

**Preferencia del autor: usa `agent-browser` siempre que haga falta un navegador** (comprobar un cambio de interfaz, capturas, revisar textos y estados por rol, flujos de punta a punta, accesibilidad con el árbol de accesibilidad, depurar un fallo de E2E). Es una CLI instalada globalmente (`agent-browser --version`); antes del primer uso en una sesión, lee su guía con `agent-browser skills get core` (y `skills get dogfood` para pruebas exploratorias o QA). No sustituye a los E2E de Playwright: son la red de regresión; el navegador sirve para mirar y explorar.

- **Sesión propia siempre** (el navegador por defecto es compartido entre agentes y conversaciones): `export AGENT_BROWSER_SESSION="$(agent-browser session id --scope worktree --prefix <agente>)"`, y `agent-browser close` al terminar.
- **Nunca contra la base de desarrollo del autor:** ni el backend del 8080 ni `npm run dev` a secas (su proxy va al 8080, que usa Supabase). Levanta una pila aislada con H2, como el E2E (`frontend/playwright.config.ts`): backend con `spring-boot:run -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.profiles=e2e` desde `streambox/`, con `SERVER_PORT=8097`, `SPRING_DATASOURCE_URL=jdbc:h2:mem:streambox_browser;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`, `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver`, `SPRING_DATASOURCE_USERNAME=sa`, `SPRING_DATASOURCE_PASSWORD=`, un `JWT_SECRET` de prueba (≥32 caracteres), `STREAMBOX_AUTH_COOKIE_SECURE=false` y `ADMIN_EMAIL`/`ADMIN_USERNAME`/`ADMIN_PASSWORD` de prueba; y Vite con `VITE_API_PROXY_TARGET=http://localhost:8097 npx vite --port 5197 --strictPort` desde `frontend/`. Abre `http://localhost:5197`. Al terminar, para **solo esos procesos** (por puerto o PID; nunca `java` en bloque).
- **Maven:** ese backend usa `streambox/target/`: no lo arranques mientras otro agente ejecuta `mvnw`, ni a la vez que `npm run test:e2e` (que levanta el suyo en 8099/5199).
- Capturas y archivos temporales fuera del repositorio (p. ej. el *scratchpad* de la sesión o `Pruebas/streambox-capturas/`), salvo que se pidan como entregable. Nada de credenciales reales en el navegador.

## Agentes (`.claude/agents/`)

Equipo: `orchestrator` (principal) y los especialistas `backend`, `database`, `security`, `qa` y `frontend`.

- **`.claude/settings.json` (`"agent": "orchestrator"`) hace que la sesión principal sea el orquestador.** Es la única forma de que pueda delegar: un subagente no puede lanzar a otros. Sus reglas completas están en `.claude/agents/orchestrator.md`.
- El orquestador divide la petición en subtareas y delega en el especialista de cada área; una tarea de una sola área (p. ej. solo frontend) va directa a su agente sin tocar el resto. Las tareas triviales (docs, erratas) las hace él mismo.
- Los especialistas no se hablan entre sí: cuando necesitan algo de otra área lo piden en su apartado «Peticiones para otros agentes» y el orquestador lo reencamina.
- Si el usuario dice «hazlo tú directamente» o «sin agentes», se trabaja sin delegar. Para desactivar el orquestador de forma permanente, quitar `agent` de `.claude/settings.json`.
- Al delegar: contexto completo (objetivo y porqué, decisiones ya tomadas, archivos, restricciones, qué debe devolver) y verificación posterior con la suite real, no solo con el informe del agente.
