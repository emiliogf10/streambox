# StreamBox — instrucciones para Claude

Plataforma de streaming tipo Netflix. Es un **proyecto de portfolio**: el objetivo no es solo que funcione, sino que el código sea defendible en una entrevista técnica y que sirva para aprender. El autor es un **desarrollador junior**: cuando detectes algo mejorable explica *qué problema hay, por qué lo es y cómo se soluciona*, no solo "esto está mal".

## Estado y plan

- **`docs/PLAN_DE_ACCION.md` es la fuente de verdad** de lo hecho y lo pendiente. Al terminar una tarea del plan, actualízalo (y el README si cambia algo visible: endpoints, variables, comandos).
- Antes de proponer o implementar algo, comprueba en el código que no existe ya o que sigue igual: el plan y este archivo pueden quedar por detrás del repositorio.

## Estructura del repositorio

| Carpeta | Contenido |
| :--- | :--- |
| `streambox/` | Backend: Java 21, Spring Boot 4.1, Maven (wrapper `mvnw`), PostgreSQL, Flyway |
| `frontend/` | SPA: React 19, Vite, TypeScript, Tailwind v4 |
| `docs/` | Plan de acción y documentación |
| `.claude/agents/` | Agentes especializados (ver abajo) |

Paquete base del backend: `com.emilio.streambox` en `streambox/src/main/java/...`.

## Comandos

Backend (desde `streambox/`; en PowerShell `.\mvnw.cmd`, en bash `./mvnw`):

```
.\mvnw.cmd test                         # toda la suite (H2 en memoria, no necesita PostgreSQL ni JWT_SECRET)
.\mvnw.cmd test -Dtest=NombreDeTest     # un test concreto
.\mvnw.cmd -q compile                   # solo compilar
.\mvnw.cmd spring-boot:run              # perfil dev: necesita PostgreSQL local + JWT_SECRET + application-local.properties
```

Frontend (desde `frontend/`): `npm run dev` (puerto 5173, proxy de `/api` a `localhost:8080`), `npm run build` (`tsc -b && vite build`), `npm run lint` (oxlint).

## Reglas de trabajo

- **No hagas commit ni push** salvo que se te pida. Si se pide, mensajes en español con prefijo (`feat:`, `fix:`...).
- **Todo código nuevo lleva Javadoc en español** (clases, métodos públicos y la razón de las decisiones no obvias). Los comentarios explican el *porqué*, no repiten el código.
- **Idioma:** respuestas, documentación, mensajes de error de la API y OpenAPI en español.
- No añadas dependencias al `pom.xml` o `package.json` sin justificarlo y avisar.
- **Nunca toques la base de datos de desarrollo del usuario** (`streambox` en su PostgreSQL local; puede tener la app corriendo en el puerto 8080). Para probar contra PostgreSQL crea una base temporal (`streambox_check`), usa otro puerto (`--server.port=8099`) y bórrala al terminar. Los tests no la necesitan.
- Entorno Windows: el tool Bash rechaza scripts largos con muchas comillas/heredocs; para ficheros usa las herramientas Write/Edit.
- No inventes datos ni "arregles" tests debilitándolos: si un test falla, entiende la causa.

## Backend: arquitectura y convenciones

Capas: `controller` → `service` → `repository` → `entity`, más `dto`, `mapper`, `specification`, `security`, `exception`, `config`.

- **Controladores:** solo reciben y devuelven **DTOs**, sin lógica. Devuelven el DTO directamente con `@ResponseStatus` (no `ResponseEntity`). Todo endpoint con `@Operation`/`@ApiResponses` en español (códigos reales: 401 sin token, 403 sin permisos, 429 en login/registro).
- **Servicios:** clases concretas (sin interfaz). Devuelven **DTOs, nunca entidades**; el mapeo ocurre **dentro de la transacción** (`@Transactional(readOnly = true)` en lecturas) porque las colecciones son `LAZY` y `open-in-view=false`.
- **DTOs:** `record`s (los de petición antiguos `CreateUserRequest`, `CreateGenreRequest`, `LoginRequest` aún son clases Lombok: convertirlos es opcional). Validación con Bean Validation.
- **Mappers:** clases `final` con métodos estáticos. No MapStruct.
- **Entidades:** `equals/hashCode` por id, fechas `Instant` + `@CreationTimestamp`. Relaciones `LAZY`; los listados paginados **no** hacen fetch de colecciones (`default_batch_fetch_size=50`), solo `findById` usa `@EntityGraph`.
- **Errores:** excepciones de dominio (`ResourceNotFoundException` es la base de los 404) tratadas en `GlobalExceptionHandler` (extiende `ResponseEntityExceptionHandler`), formato `ErrorResponse` con `ErrorCode`. Nunca devuelvas mensajes de excepciones del framework al cliente.
- **Favoritos** (`FavoriteService`): `INSERT`/`DELETE` directos sobre `user_favorite_movies`; no cargues la colección completa.
- **Paginación/orden:** el `sort` va por lista blanca y se desempata por `id`.

## Base de datos: Flyway manda

- El esquema lo gestiona **Flyway** (`src/main/resources/db/migration`, hoy `V1`, `V2`; mira la carpeta para la última). Hibernate solo **valida** (`ddl-auto=validate` en dev, prod y test).
- Cambiar una entidad = **entidad + migración nueva** (`V<N+1>__descripcion.sql`). **Nunca edites una migración ya aplicada.**
- SQL portable: debe funcionar en PostgreSQL y en H2 (modo PostgreSQL), porque los tests usan H2. Si algo es específico de PostgreSQL, hay que decirlo y la tarea de Testcontainers (plan nº 22) pasa a ser necesaria.
- Los índices y restricciones se prueban en `FlywaySchemaIntegrationTest`.

## Seguridad

- JWT stateless (`JwtService`, `JwtProperties` con secreto ≥32 caracteres validado al arrancar, `issuer=streambox`). El filtro lee el usuario de la BD en cada petición, así que un cambio de rol es inmediato.
- Roles `USER` y `ADMIN`. Los endpoints personales cuelgan de `/api/users/me/...` y usan el id **del token** (`@AuthenticationPrincipal AuthenticatedUser`), nunca un id de la URL (evita IDOR).
- Rate limiting: `RateLimitingFilter` (por IP, login y registro) y `LoginAttemptService` (bloqueo de cuenta). Contadores en memoria. Los tests suben los límites en `application-test.properties`; los de rate limiting los bajan con `@TestPropertySource` y usan IPs/emails únicos por test.
- Los administradores solo se crean con `AdminAccountInitializer` (`ADMIN_EMAIL`/`ADMIN_PASSWORD`); el registro público siempre crea `USER`.
- Actuator: solo `health` (público, sin detalles) e `info` (con token). Perfil `prod`: Swagger desactivado, logs JSON, sin SQL en logs.
- Nunca secretos en el repositorio. `application-local.properties` está ignorado.

## Tests

- Estilo: integración con `@SpringBootTest` + `@ActiveProfiles("test")` + MockMvc; unitarios con Mockito para lógica aislada. Perfil `test`: H2 + Flyway.
- Suites que modifican datos con commit real (favoritos, catálogo) **no** usan `@Transactional` y limpian la BD en `@BeforeEach`/`@AfterEach`; el resto usa `@Transactional` (rollback).
- Cada bug corregido deja un test que falla sin el arreglo.
- Antes de dar algo por terminado, ejecuta la suite completa y cuenta los tests; informa del resultado real.

## Frontend: estado actual

SPA en `frontend/src/` (sin carpetas por ahora). El token JWT está en `localStorage`; las llamadas usan `fetch` con `authHeader()` de `api.ts`. Problemas conocidos y planificados (tareas 15–21 del plan): sin `apiFetch` central ni manejo de 401/403/429, sin página de registro, solo carga la primera página del catálogo, estilos en línea en casi todo, sin responsive ni accesibilidad en modales, imágenes locales en `src/assets` por título. Al tocar el frontend verifica con `npm run build` y `npm run lint`.

## Agentes (`.claude/agents/`)

Equipo: `orchestrator` (principal) y los especialistas `backend`, `database`, `security`, `qa` y `frontend`.

- **`.claude/settings.json` (`"agent": "orchestrator"`) hace que la sesión principal sea el orquestador.** Es la única forma de que pueda delegar: un subagente no puede lanzar a otros. Sus reglas completas están en `.claude/agents/orchestrator.md`.
- El orquestador divide la petición en subtareas y delega en el especialista de cada área; una tarea de una sola área (p. ej. solo frontend) va directa a su agente sin tocar el resto. Las tareas triviales (docs, erratas) las hace él mismo.
- Los especialistas no se hablan entre sí: cuando necesitan algo de otra área lo piden en su apartado «Peticiones para otros agentes» y el orquestador lo reencamina.
- Si el usuario dice «hazlo tú directamente» o «sin agentes», se trabaja sin delegar. Para desactivar el orquestador de forma permanente, quitar `agent` de `.claude/settings.json`.
- Al delegar: contexto completo (objetivo y porqué, decisiones ya tomadas, archivos, restricciones, qué debe devolver) y verificación posterior con la suite real, no solo con el informe del agente.
