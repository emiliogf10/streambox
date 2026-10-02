# Plan de acción — StreamBox

Derivado de la auditoría técnica del proyecto. Última actualización: 2026-10-02 (tras la Fase 4).

Leyenda: ✅ hecho · ⬜ pendiente · Dificultad: BAJA / MEDIA / ALTA.
Los códigos (C1, I5, S4, U1...) remiten a los hallazgos de la auditoría.

**Estado: 26 de 29 tareas completadas (Fases 1 a 4 y la limpieza 25 terminadas). Tests: 469 de backend (80 de ellos contra PostgreSQL real con Testcontainers, que se omiten solos sin Docker), 236 del frontend (Vitest) y 48 E2E (Playwright), todos en verde. `npm run build` y `npm run lint` (completo) limpios.**
Lo que queda es **producción (Fase 5: tareas 27, 28 y 29)**, una **revisión humana del frontend** (ya se ha mirado en capturas, ver Fase 4) y los pendientes menores.

---

## ✅ Completado

### Fase 1 — Seguridad y bugs

| # | Tarea | Resultado |
|---|---|---|
| 1 | Favoritos duplicados/ausentes devolvían 500 (C1) | 409 `MOVIE_ALREADY_IN_FAVORITES` y 404 `MOVIE_NOT_IN_FAVORITES` |
| 2 | Errores del cliente tratados como 500 (C2, C3) | `GlobalExceptionHandler` extiende `ResponseEntityExceptionHandler`; jerarquía `ResourceNotFoundException` |
| 3 | `sort` libre del cliente (S7) | Lista blanca en `MovieController` |
| 4 | Tests dependientes de `JWT_SECRET` (I1) | Perfil `test` (`application-test.properties`) |
| 5 | Secreto y claims JWT (I3, S3) | `JwtProperties` validado (≥32 caracteres), clave construida una vez, `issuer` |
| 6 | Configuración insegura por defecto (C4, S2) | Perfiles `dev` / `prod`, Swagger desactivable, sin SQL en logs en prod |
| 7 | Tests de favoritos y auth/JWT (I10) | Favoritos, login, filtro JWT, errores de API |
| 14 | Rate limiting (I2, S1) | `RateLimitingFilter` (login y registro por IP) + `LoginAttemptService` (bloqueo de cuenta), 429 con `Retry-After` |

### Fase 2 — Arquitectura y datos

| # | Tarea | Resultado |
|---|---|---|
| 8 | Flyway, esquema, índices y admin inicial (I8, S14) | `V1__create_schema.sql` (FKs, cascadas, CHECKs, 3 índices), `ddl-auto=validate` en todos los perfiles, `AdminAccountInitializer` |
| 9 | Servicios devuelven DTOs (I6) | `MovieService`, `GenreService`, `UserService`, `FavoriteService` y `AuthenticationService.login` devuelven DTOs; el mapeo ocurre dentro de la transacción. Los controladores ya no tocan entidades. Los DTOs de respuesta son `record` |
| 10 | Favoritos rehechos (I7) | `FavoriteService` + `FavoriteController` separados de `UserService`; operaciones como `INSERT`/`DELETE` directos sobre la tabla de unión (ya no se carga la lista completa con géneros para tocar un elemento); condición de carrera de altas simultáneas controlada; lista ordenada por título; `equals/hashCode` por id en las entidades; `@AuthenticationPrincipal` en lugar de castear `Authentication` |
| 11 | Paginación sin fetch de colección (I5) | Sin `@EntityGraph` en las páginas, `default_batch_fetch_size=50` (una consulta extra por página para todos los géneros), filtro por género con `EXISTS`, orden determinista (`id` como desempate), comodines de `LIKE` escapados (S10) |
| 12 | Fechas con `Instant` (I9) | `createdAt` pasa a `Instant` + `@CreationTimestamp`; `V2__created_at_with_time_zone.sql` (`TIMESTAMP WITH TIME ZONE`); `ErrorResponse` también usa `Instant` |
| 13 | Limpieza de código (mejoras 2–8, 22) | Eliminados `exception/ErrorResponse`, `saveUser(User)`, `UserMapper.toEntity`, `searchMoviesByTitle`, `findByName`, `findAll()` con `@EntityGraph` y `CustomUserDetailsService` (se excluye el usuario por defecto de Spring); `CreateMovieRequest`/`UpdateMovieRequest` unificados en `MovieRequest`; bucle de géneros con N+1 sustituido por una consulta (`findAllById`); anotaciones con nombre completo sustituidas por imports; Swagger corregido (401 en lugar de 403, 409 y 429 documentados) |

### Fase 3 — UX/UI y accesibilidad (frontend)

Hecha con el equipo de agentes (`orchestrator` → `frontend`, `backend`, `qa`, `security`). Código en `frontend/src/{pages,components,context,hooks,lib}`.

| # | Tarea | Resultado |
|---|---|---|
| 15 | `apiFetch` + `AuthContext` + errores uniformes (I11, U7) | `lib/api.ts` (`apiFetch`, `ApiError`): comprueba siempre `res.ok`, 204 sin cuerpo, red caída (`NETWORK_ERROR`), **401** cierra sesión una sola vez (ignora respuestas tardías de tokens antiguos; el 401 de login/registro no cierra nada), **403** no cierra sesión, **429** con cuenta atrás desde `Retry-After`. `AuthContext` es el único sitio que toca `localStorage`. No queda ningún `fetch` fuera de `api.ts` ni `any` |
| 16 | Página de registro (U1) | `RegisterPage` (`/registro`): validación en cliente con los límites reales del backend, `validationErrors` junto a cada campo, 409 y 429 tratados, redirige a login con toast |
| 17 | Paginación y hero real (U2) | `useCatalog`: hero = película **más reciente**, "Novedades" y una fila por género (≥3 películas), botón "Cargar más" con `hasNext`, sin duplicados ni saltos de scroll, fallo de "cargar más" sin perder lo cargado |
| 18 | Confirmaciones, toasts y "Ver ahora" (U3–U5) | `ToastContext` propio (aria-live), `ConfirmDialog` para vaciar la lista, favoritos con actualización optimista (`FavoritesContext`; 409/404 = estado ya correcto), "Ver ahora" abre `videoUrl` validada (solo http/https) en pestaña nueva |
| 19 | Accesibilidad (A1–A8) | `lang="es"`, enlace "Saltar al contenido", landmarks y un `h1` por página, `Modal` sobre `<dialog>` (foco, Escape, devolución de foco, bloqueo de scroll), buscador como combobox con teclado y anuncios, foco visible, `prefers-reduced-motion`, contrastes WCAG AA calculados (el placeholder de formularios sí fallaba: 3,54 → 6,02) |
| 20 | Responsive y Tailwind (U12–U14) | 0 estilos en línea; tokens `@theme`; navbar con buscador en segunda fila en móvil; hero fluido; filas con `snap` y flechas desde `md`; objetivos táctiles ≥44 px |
| 21 | Imágenes (U14) | La UI usa siempre `imageUrl` (`MoviePoster`: lazy, sin saltos de layout, `referrerPolicy="no-referrer"`, respaldo con título). Eliminados `imageMap` y 14 JPG (≈12,9 MB); 10 portadas en WebP (≈0,8 MB) en `public/covers/`. **Ver pendiente: script SQL opcional** |
| — | `genres` en `types.ts` y filas por género | Hecho junto con la 17 |
| + | Backend: `direction=asc\|desc` en `GET /api/movies` y `/search` | Faltaba para "lo más reciente primero" (solo había orden ascendente). Opcional, por defecto `asc` (compatible); el desempate por id sigue la misma dirección. 9 tests nuevos (192 → 201) |
| + | Test `JwtPropertiesValidationTest` aislado del entorno | Con `JWT_SECRET` definida en tu shell fallaba (el `ApplicationContextRunner` leía la variable del sistema). Ahora la suite da 201 en verde con y sin la variable |
| + | Auditoría de seguridad del frontend | 0 hallazgos críticos/altos/medios y `npm audit` con 0 vulnerabilidades. Corregidos los menores del cliente: credenciales embebidas en `getSafeVideoUrl`, `Referer` de las imágenes, `localStorage.clear()` entre pestañas y `Object.hasOwn` |

### Fase 4 — Testing

Hecha con el equipo de agentes (`qa`, `database`, `backend`, `frontend`). Las pruebas encontraron **cuatro fallos reales** que ya están corregidos (ver al final).

| # | Tarea | Resultado |
|---|---|---|
| 22 | Testcontainers + PostgreSQL | Paquete `com.emilio.streambox.postgres` (80 tests; imagen `postgres:16` Debian, un contenedor compartido; `@Testcontainers(disabledWithoutDocker = true)`: **sin Docker se omiten** y la suite sigue en `BUILD SUCCESS`, comprobado dentro de un contenedor Linux sin Docker). Cubre: Flyway V1+V2 desde cero y `ddl-auto=validate`, tipos reales (`timestamptz`), índices, PK/FK/CHECK con su SQLSTATE real; **adopción de una base heredada** (baseline) con datos; favoritos con `INSERT`/`DELETE` nativos y **concurrencia real** (8 hilos × 10 rondas: exactamente 1 éxito y el resto 409); diferencias de motor en el catálogo (collation, `lower()`, `ñ`, comodines, `EXISTS`, N+1). Dependencias de test: `testcontainers-junit-jupiter` y `testcontainers-postgresql` (versiones del BOM de Spring Boot; `spring-boot-testcontainers` se añadió y se retiró en la limpieza por no usarse: el contenedor se conecta con `@DynamicPropertySource`). Mutaciones comprobadas: quitar un `ON DELETE CASCADE` o los `ALTER` de V2 los hace fallar |
| 23 | Paginación y búsqueda con casos límite | `CatalogEdgeCasesIntegrationTest` (169 ejecuciones, casi todas parametrizadas): `page`/`size` fuera de rango y mal formados, `title` vacío/enorme/Unicode/inyección, `genreId` y `releaseYear` extremos, 5 columnas × asc/desc, empates, película sin géneros o con varios, 401. Ningún caso de cliente acaba en 500 |
| 24 | Tests del frontend | **Vitest + Testing Library: 236 tests** (cliente de API, `getSafeVideoUrl`, validación, catálogo, `useCatalog`, `AuthContext`, favoritos, login/registro, buscador, modales, guardas de ruta...); 12 roturas deliberadas del código, las 12 detectadas. **Playwright: 48 tests E2E** en Chromium con backend y Vite propios y aislados (8099/5199, H2 en memoria; no tocan tu base de datos ni tus puertos): registro → login → catálogo → "Cargar más" → favoritos → vaciar con confirmación, buscador, sesión caducada (una sola redirección), 429 real, teclado/foco y responsive (375/768/1280 sin scroll horizontal). 3 ejecuciones seguidas sin flakiness; 5 roturas deliberadas, las 5 detectadas. Dependencias: `vitest`, `jsdom`, `@testing-library/*`, `@playwright/test` (solo `devDependencies`) |

**Fallos reales que encontraron los tests (todos corregidos):**
- `page` muy grande (`page × size > Integer.MAX_VALUE`) daba **500** y `hasNext` erróneo → ahora 400 `VALIDATION_ERROR` en `validationErrors.page`.
- La búsqueda por título no encontraba títulos con `İ` ni con sigma final (`ΟΔΥΣΣΕΥΣ`) en PostgreSQL, porque Java y PostgreSQL minusculizan distinto (H2 lo ocultaba) → `lower()` se aplica ahora en la base de datos a ambos lados.
- `FavoriteService` convertía **cualquier** violación de integridad en 409 "ya está en favoritos"; si la película se borraba justo antes del `INSERT` (violación de FK) el cliente recibía un 409 falso → unicidad = 409, clave foránea = 404.
- **Visual (primera revisión real de la interfaz, con capturas a 375/768/1280 px):** la portada del banner y del modal no cubría su caja (quedaba una miniatura de 600 px y el resto vacío) por un conflicto de clases (`relative` ganaba a `absolute inset-0`) → corregido en `MoviePoster.tsx` con una comprobación E2E que lo vigila.

### Fase 5 — Producción (parcial)

| # | Tarea | Resultado |
|---|---|---|
| 25 | Limpieza del repositorio (2026-10-02) | **Frontend:** borrados con `git rm` los 8 scripts de scaffolding (`fix.js`, `fix.cjs`, `fix_app.cjs`, `fix_react.cjs`, `gen.js`, `gen.cjs`, `g.py`, `setup.js`; reescribían archivos con contenido antiguo y roto, uno de ellos con directivas de Tailwind v3), `tailwind.config.js` (resto de la v3; Tailwind v4 se configura en CSS y no lo leía), `public/icons.svg` (sin uso) y la dependencia `autoprefixer` (Tailwind v4 ya prefija; el CSS generado es idéntico salvo prefijos antiguos de Firefox/Opera). Código muerto: tipo `LoginRequest`, prop `cancelLabel` de `ConfirmDialog` y `export` de `API_URL`. `postcss.config.js` se queda mínimo (Vite lo necesita para cargar `@tailwindcss/postcss`). `frontend/README.md` reescrito (era de la plantilla, con la estructura antigua y la codificación rota). `npm run lint` pasa por fin **con código 0 en todo el proyecto**. **Backend:** auditoría completa (clases, DTO, `ErrorCode`, repositorios, servicios, imports, propiedades): 0 código muerto; se retiraron las dependencias de test `spring-boot-starter-data-jpa-test` y `spring-boot-testcontainers` (sin uso) y se corrigieron 3 Javadoc desactualizados. **Raíz:** eliminados los restos de una actualización de Java hecha con otra herramienta (`.github/modernize/` en la raíz y en `streambox/`, solo logs y planes, nunca versionados) y `streambox/HELP.md` (plantilla de Spring). **`docs/` se versiona:** estaba en el `.gitignore` («Documentación y planes de acción»), así que este plan y `portadas-locales.sql` no estaban en el repositorio aunque el README y `CLAUDE.md` los citan; se quitó esa línea. Suite tras la limpieza: 469 + 236 + 48 tests en verde |
| 26 | Actuator, endpoints de prueba y logs (S11) | Añadido Actuator con solo `health` e `info` expuestos; `/actuator/health` (+ `liveness` y `readiness`) público y **sin detalles**, incluye el estado de la base de datos; eliminados `TestController` y `ProtectedTestController`; perfil `prod` con logs en JSON (formato ECS). Probado arrancando el perfil `prod` de verdad: logs JSON, health `UP`, Swagger y `/actuator/env` inaccesibles, administrador creado por `ADMIN_EMAIL`/`ADMIN_PASSWORD` |

**Extra: entorno de trabajo con Claude Code.** Añadido `CLAUDE.md` (contexto y reglas del proyecto) y los agentes `backend`, `database`, `security`, `qa` y `frontend` en `.claude/agents/` (formato de Claude Code, con el contenido actualizado al código real). Después se añadió el agente `orchestrator` como **sesión principal** (`.claude/settings.json` → `"agent": "orchestrator"`): en Claude Code un subagente no puede lanzar a otros, así que solo puede delegar si es él quien dirige la sesión. Divide las peticiones, delega en el especialista de cada área (las de una sola área van directas a su agente), verifica con la suite real y actualiza el plan. Se aclararon las fronteras entre agentes (`database` es dueño de entidades, migraciones y `@Query`) y todos devuelven un apartado «Peticiones para otros agentes».

**Extras resueltos por el camino**
- Bug: el login no normalizaba el email (registrarse con `Foo@x.com` impedía entrar escribiéndolo igual).
- Login con usuario inexistente compara contra un hash falso (reduce la diferencia de tiempo que delata qué emails existen; S6 parcial).
- `SecurityErrorResponseWriter` elimina el código duplicado de los handlers de seguridad.
- Un test cuenta las sentencias SQL por página (Hibernate Statistics) y falla si reaparece el problema N+1: lo comprobé desactivando el batch fetch (7 consultas frente a ≤3).

**Verificado a mano contra PostgreSQL real** (y con el perfil `prod`, ver tarea 26): adopción de una base ya existente (baseline), V1 desde cero, V2 sobre datos reales y la API de extremo a extremo (registro → login → catálogo → favoritos → `sort` inválido).

---

## ⬜ Pendiente

### Revisión visual humana del frontend

Un agente ya revisó capturas a 375/768/1280 px (a 768 px el navbar con "Películas" y "Series" cabe: el elemento más a la derecha termina en ~729 de 768 px), pero **un ojo humano sigue siendo imprescindible**. Recorre la app (`npm run dev` con el backend en el 8080) y mira sobre todo:
- Que el texto del banner y del modal se lee sobre cada portada real (el contraste sobre imágenes no se puede calcular).
- La sensación general: espaciados, tipografía, animaciones, y el **teclado y un lector de pantalla** (los E2E comprueban la estructura, no la experiencia).

### Fase 5 — Producción y repositorio (resto)

| # | Tarea | Prior. | Dif. | Archivos | Depende de |
|---|---|---|---|---|---|
| 27 | Dockerfile multi-etapa + `docker-compose` (app + PostgreSQL) + CI (GitHub Actions) + Dependabot. El `HEALTHCHECK` del contenedor puede usar `/actuator/health/readiness` (ya existe). **Datos para el CI (de la Fase 4):** los runners `ubuntu-latest` traen Docker, así que los 80 tests de PostgreSQL corren sin configuración extra (se puede cachear la imagen `postgres:16`; o un job rápido con `-Dtest=!Postgres*` y otro con `-Dtest=Postgres*`); el job de frontend ejecuta `npm run build`, `npx oxlint src` y `npm run test`; el E2E necesita Java 21 + Node, `npx playwright install --with-deps chromium` (cachear `~/.cache/ms-playwright`), los puertos 8099 y 5199 libres y subir `frontend/playwright-report/` y `frontend/test-results/` como artefacto si falla (`CI=1` activa `forbidOnly` y 2 workers) | Alta | MEDIA | raíz, `.github/` | 22 ✅ |
| 28 | Documentación: arquitectura, tabla de errores, ADRs; revisar README (codificación rota, árbol de carpetas) (`frontend/README.md` ya se reescribió en la tarea 25) | Media | BAJA | `README.md`, `frontend/README.md`, `docs/` | resto |
| 29 | Token en el cliente: cookie HttpOnly o CSP + vida corta + refresh (I4, S4). El frontend ya concentra el token en `AuthContext` y `lib/api.ts`, así que el cambio queda localizado. **CSP y cabeceras** (no existen; el frontend no se sirve aún desde ningún servidor): `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`, como cabecera HTTP (no `<meta>`; no aplicar en dev por el preámbulo inline de Vite) + `Referrer-Policy`, `X-Content-Type-Options`, HSTS en el servidor que sirva `dist/` | Media | MEDIA | `SecurityConfig`, `api.ts`, `AuthContext`, servidor del `dist/` | 15 ✅ |

---

## ⬜ Pendientes menores y hallazgos nuevos (sin número)

| Tema | Detalle | Prior. | Dif. |
|---|---|---|---|
| **Reiniciar tu app local** | Hay una instancia corriendo en el puerto 8080 con el código anterior. Al reiniciarla se aplicará `V2` a tu base de datos (`created_at` pasa a `timestamptz`). **Aviso:** PostgreSQL interpreta los valores antiguos en la zona horaria de la sesión; en la prueba, `22:00` (Madrid) quedó como `20:00Z`, que es correcto como instante. Solo afecta a `created_at`, que la aplicación no usa para decidir nada | — | — |
| **Portadas locales: ejecutar el SQL (decisión tuya)** | Tras la tarea 21 la UI usa solo `imageUrl`, así que **las películas cuyo `image_url` no apunte a una imagen alcanzable muestran un hueco con el título** hasta que ejecutes a mano `docs/portadas-locales.sql` (actualiza las 10 portadas de ejemplo a `/covers/*.webp`; es opcional e idempotente; no se ha ejecutado). Ojo: esas rutas relativas no pasan el `@URL` del backend, así que reenviarlas por `PUT /api/movies/{id}` daría 400. Alternativas en la cabecera del script (URL absoluta del host del frontend o CDN) | Media | BAJA |
| `@URL` solo https + longitud (S9, auditoría del frontend) | `@URL` de `MovieRequest.imageUrl`/`videoUrl` no restringe el esquema (acepta `file:`, `ftp:`, `jar:`, `http:`; el frontend ya lo neutraliza, otros clientes no) y falta `@Size(max = 500)` (una URL más larga acaba en `DataIntegrityViolation` y el cliente recibe un 409 engañoso). **Decidir junto con el punto anterior** la política (`https` + ¿rutas relativas propias?/`http://localhost` en dev). Cubrir con test que rechace `file:`, `ftp:` y `javascript:` | Baja | BAJA |
| Test del `JWT_SECRET` (residual) | Las clases `@SpringBootTest` heredan variables de entorno: un `JWT_SECRET` definido con **menos de 32 caracteres** rompería toda la suite (8/8 errores comprobados). Con 32 o más no hay problema. Para blindarlo: fijar `jwt.secret` con mayor precedencia que el entorno en una base común de tests | Baja | BAJA |
| Navbar: "Películas" y "Series" reservados | Se mantienen visibles (decisión del autor: se usarán más adelante) como texto atenuado **sin enlace**, no enfocable y con "(próximamente)" solo para lectores de pantalla. Están en la constante `PLANNED_SECTIONS` de `Navbar.tsx`: cuando exista la ruta, se mueve esa sección a un `NavLink` y se le crea su página. A 375 px el navbar ocupa tres filas (logo + usuario, navegación, buscador); a 768 px es el punto más justo (≈691 de 720 px), revisar que no haya scroll horizontal | Opcional | BAJA |
| Responsive: orden de tabulación en móvil | El buscador baja a una segunda fila pero el orden de `Tab` sigue el de escritorio (buscador antes que el menú de usuario). Aceptado | Opcional | BAJA |
| Detalles del frontend (limpieza y auditoría) | `public/favicon.svg` es el logo por defecto de Vite (decisión de diseño); la prop `alt` de `MoviePoster` no se usa en producción (siempre `alt=""`) pero está documentada y probada (4 tests); `oxlint -W suspicious` avisa de `exhaustive-effect-dependencies` en `FavoritesContext`, `useCatalog`, `MovieRow` y `SearchBar` (revisar las dependencias de esos efectos); los pósters son de 600 px y pueden verse suaves en el hero a 1280 px. Desde las capturas: **(a)** las portadas son verticales (2:3) y el banner y la cabecera del modal son horizontales, así que se recortan (a 1280 px solo se ve la franja central del póster; en el modal el texto del póster se mezcla con la etiqueta y el título). Es legible gracias a los degradados, pero poco limpio: la mejora real sería una imagen horizontal (`backdropUrl`) en la API o mostrar el póster completo a un lado; **(b)** en móvil las filas muestran 2 tarjetas y no asoma una tercera, así que se nota menos que son carruseles (reducir el ancho de la tarjeta); **(c)** en el registro la ayuda «Entre 3 y 50 caracteres» repite el error | Opcional | BAJA |
| Ruido en `pom.xml` y propiedades (limpieza) | Placeholders vacíos de Initializr (`<name />`, `<description />`, `<url />`, `<licenses>`, `<developers>`, `<scm>`): no se quitaron porque, al desaparecer, el proyecto heredaría la licencia, los desarrolladores y el scm de Spring Boot (rellenarlos con datos propios o ponerlos vacíos a propósito). `spring.datasource.driver-class-name`, `spring.jpa.show-sql=false` y `logging.level.root=INFO` coinciden con lo que Spring deduce, pero se dejan como documentación explícita | Opcional | BAJA |
| `genreIds` obligatorio | `MovieRequest.genreIds` es `@NotEmpty`: no se pueden crear películas sin género (la UI sí las soporta). Confirmar si es deliberado | Opcional | BAJA |
| Datos de ejemplo | Las portadas de `public/covers` no corresponden a los títulos de las películas de ejemplo de los E2E (p. ej. «Interstellar» con el póster de «Event Horizon»). Es solo de los datos de prueba, no de la UI. Ver también la propuesta de datos de ejemplo para `dev` | Opcional | BAJA |
| Orden de `sort=title` según el motor | En PostgreSQL (collation `en_US.utf8`) ignora mayúsculas y acentos como primer criterio y la `ñ` se ordena como una `n` acentuada; en H2 ordena por código de carácter. La paginación es estable en ambos, pero el orden visible depende del servidor (imagen/locale). Fijado por tests en `PostgresCatalogIntegrationTest` | Info | — |
| Búsqueda sensible a acentos | `manana` no encuentra `Mañana` (sí ignora mayúsculas). Hacerla insensible exigiría `unaccent`, específico de PostgreSQL (migración + Testcontainers, ya disponible) | Opcional | MEDIA |
| V2 y la zona horaria | V2 interpreta las fechas antiguas en la zona horaria de la sesión de quien ejecute Flyway (`22:00` → `20:00Z` en Madrid, `22:00Z` en UTC) y, salvo en UTC, reescribe la tabla con bloqueo `ACCESS EXCLUSIVE`. Irrelevante con catálogos pequeños; medido en `PostgresBaselineAdoptionIntegrationTest` | Info | — |
| E2E: huecos | Solo Chromium; sin auditoría automática de contraste/ARIA (propuesta: `@axe-core/playwright`, ≈1 MB, `devDependency`); no se prueba que "Ver ahora" abra el vídeo (solo `href`/`target`) ni la caducidad real del JWT; el `role="alert"` vacío permanente de `ToastViewport` obliga a localizar alertas por texto | Opcional | BAJA |
| Código de error del 401 (mejora 19) | El token ausente usa `INVALID_CREDENTIALS`; crear `UNAUTHENTICATED` | Baja | BAJA |
| Política de contraseñas (S8) | Más allá de la longitud: rechazar contraseñas comunes | Baja | BAJA |
| CORS y cabeceras (S12) | CORS explícito por perfil y CSP, para cuando el frontend esté en otro origen | Media | BAJA |
| DTOs de petición con Lombok | `CreateUserRequest`, `CreateGenreRequest` y `LoginRequest` siguen siendo clases con `@Getter/@Setter`; convertirlas a `record` por coherencia con `MovieRequest` | Opcional | BAJA |
| Favoritos: sin fecha de alta | La tabla de unión no guarda cuándo se añadió cada película, así que no se puede ordenar "añadidas recientemente". Requeriría una entidad `Favorite` con `added_at` y una migración `V3` | Opcional | MEDIA |
| Listados sin paginar | `GET /api/users` (admin) y `GET /api/users/me/favorites` devuelven todo | Opcional | BAJA |
| Rate limiting en memoria | Con varias réplicas el límite se multiplica y un reinicio lo borra. Si se escala: Redis/Bucket4j distribuido | Baja (hasta escalar) | MEDIA |
| Bloqueo de cuenta como arma | Alguien puede bloquear 15 min la cuenta de otro fallando su login a propósito. Aceptado y documentado; alternativa: bloqueo por par (IP + email) o CAPTCHA | Baja | MEDIA |
| Bases antiguas: sin cascada ni CHECKs | Las bases creadas antes de Flyway conservan sus FKs sin `ON DELETE CASCADE` (por eso `deleteMovie` sigue limpiando favoritos a mano con `deleteFromAllFavorites`) y **tampoco reciben los CHECK de año y duración** (aceptan `release_year=1700`). Lo comprobaron los tests de adopción en PostgreSQL. **La cabecera de `V1` dice lo contrario** («índices, claves foráneas...»): solo reciben los índices; no se puede corregir editando `V1` (Flyway valida el checksum, también de los comentarios), así que queda documentado aquí. Unificar requiere una `V3` que recree FKs y añada CHECKs, con riesgo sobre datos antiguos que no los cumplan | Baja | MEDIA |
| Índice por título | `lower(title) LIKE '%x%'` no usa índice normal; en PostgreSQL requiere `pg_trgm` + índice GIN (migración específica de PostgreSQL) | Opcional hasta tener catálogo grande | MEDIA |
| Unicidad de película | Valorar `UNIQUE (title, release_year)` (evitaría `AmbiguousTitleException`); requiere revisar los datos existentes antes | Opcional | BAJA |
| Endpoints `by-title` (mejora 10) | Duplican los de ID; valorar retirarlos | Opcional | BAJA |
| Favoritos idempotentes (mejora 11) | `PUT`/`DELETE` idempotentes en lugar de 409 en duplicado | Opcional | BAJA |
| `Location` en 201 (mejora 12) | Añadir cabecera en alta de películas, géneros y usuarios | Opcional | BAJA |
| Opcionales (🟢) | Versionado `/api/v1`, MapStruct, auditoría (`updatedAt`, `createdBy`) y borrado lógico, ETag en géneros, reproductor real, "Series" | Opcional | — |

**Quitado de esta lista por estar hecho:** comodines de `LIKE` (S10), Swagger con 403 en vez de 401, y la limpieza de anotaciones con nombre completo.

---

## Orden sugerido para continuar

1. **Revisión visual humana** y decidir lo de las portadas (`docs/portadas-locales.sql` + política de `@URL` + posibles datos de ejemplo para `dev`).
2. **27** (Docker/CI): ya están todos los comandos y requisitos (ver la fila de la tarea) y `npm run lint` pasa sin excepciones, así que el pipeline puede ejecutarlo tal cual.
3. Seguridad del token y cabeceras (29) y documentación (28).
