# Plan de acción — StreamBox

> **Fuente de verdad** de lo hecho y lo pendiente. Nació de la auditoría técnica inicial del proyecto (los códigos como C1, I5, S4 o U1 remiten a sus hallazgos).
> Última actualización: **2026-10-08** (auditoría 2 y lo que destapó su arreglo).

**Contenido**

1. [Estado actual](#1-estado-actual)
2. [Pendiente](#2-pendiente)
3. [Completado: tareas numeradas](#3-completado-tareas-numeradas)
4. [Historial de trabajos](#4-historial-de-trabajos)

**Leyenda**

| Marca | Significado |
| :--- | :--- |
| ✅ / ⬜ | Hecho / pendiente |
| *Prioridad · DIFICULTAD* | Prioridad: Alta, Media, Baja, Opcional o Info (solo informativo). Dificultad: BAJA, MEDIA o ALTA |
| (agente) | Agente que debería hacerlo: `backend`, `database`, `security`, `frontend` o `qa` |

---

## 1. Estado actual

### Tareas

- **29 de 29 tareas completadas:** Fases 1 a 4, la limpieza (25), Actuator y logs (26), Docker + CI (27), la documentación (28) y la seguridad del token (29: CSP y cabeceras en nginx, cookie HttpOnly, token de acceso de 15 min + *refresh token* rotatorio). De la 29 solo queda **HSTS, que depende de tener HTTPS** (hoy la app solo se publica en `127.0.0.1` por HTTP).
- **Hitos recientes:**
  - panel de administración, política de contraseñas, intentos de login y validación de URLs (2026-10-04);
  - series con temporadas y episodios, incluidos sus tests contra PostgreSQL real (2026-10-06);
  - página de perfil `/perfil` (2026-10-07);
  - auditoría de seguridad completa y corrección de sus 5 pistas (2026-10-08);
  - auditoría 2 (tarea 29), corrección de NV-A y de todo lo que destapó (2026-10-08).
- `npm run build` y `npm run lint` (completo) limpios.

### Tests

| Suite | Tests | Notas |
| :--- | ---: | :--- |
| Backend (JUnit) | **1889** | 1742 con H2 y 147 contra PostgreSQL real con Testcontainers. Sin Docker se omiten los de PostgreSQL |
| Frontend (Vitest) | **920** | |
| E2E (Playwright) | **154** | Más 24 de capturas que solo corren a petición. En el contenedor Linux de Claude (Chromium 141) fallan 2 por el navegador: ver [2.10](#210-tests-herramientas-y-notas-informativas) |

Todos en verde en la máquina del autor.

### Orden sugerido para continuar

1. **Revisión visual humana** del frontend ([2.3](#23-revisión-visual-humana-del-frontend)).
2. **Prioridad media:** bloqueo de cuenta residual, en [2.5](#25-seguridad).

---

## 2. Pendiente

### 2.1 Lo más importante

| Tema | Prioridad | Dónde |
| :--- | :--- | :--- |
| Revisión visual humana | Media | [2.3](#23-revisión-visual-humana-del-frontend) |
| Portadas locales y URLs antiguas en tu base | Media | [2.4](#24-acciones-tuyas-datos-y-decisiones) |
| Bloqueo de cuenta como arma (residual) | Media | [2.5](#25-seguridad) |
| CORS y cabeceras por perfil (S12) | Media | [2.5](#25-seguridad) |
| Reproductor integrado y siguientes pasos de series | Media | [2.9](#29-funcionalidades-nuevas) |

### 2.2 Tarea 29: lo que queda (solo HSTS)

**Token en el cliente y cabeceras (I4, S4)** — *Media · MEDIA* — depende de la 15 ✅

- ✅ **Hecho (2026-10-06):** CSP y cabeceras de seguridad en nginx (dentro de la tarea 27).
- ✅ **Hecho (2026-10-07):** token en cookie HttpOnly `streambox_token` (SameSite=Strict, `Secure` configurable, logout, defensa CSRF con `X-Requested-With`). El frontend ya no guarda el token en `localStorage`. Ver capítulos 9.3 y 19.2 del manual. En su momento: backend 1166 tests con H2 (13 nuevos), Vitest 774, E2E sin regresiones.
- ✅ **Hecho (2026-10-09):** vida corta del token (15 min) + *refresh token* rotatorio y revocable (backend, cap. 10.4 bis del manual) y su renovación transparente en el frontend (`lib/api.ts`: un solo refresh compartido, reintento único, Web Lock entre pestañas; cap. 19.1). Ver el [historial](#4-historial-de-trabajos).
- ⬜ **Queda:** HSTS cuando haya HTTPS.
- Archivos: `SecurityConfig`, `api.ts`, `AuthContext`, servidor del `dist/`.
- CSP planificada (ya aplicada en nginx, como cabecera HTTP y no en `<meta>`; no se aplica en dev por el preámbulo inline de Vite): `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`, más `Referrer-Policy`, `X-Content-Type-Options` y HSTS en el servidor que sirva `dist/`.

### 2.3 Revisión visual humana del frontend

Un agente ya revisó capturas a 375, 768 y 1280 px (a 768 px la barra con «Películas» y «Series» cabe: el elemento más a la derecha termina en ~729 px). También se hicieron dos revisiones de diseño (ver el [historial](#4-historial-de-trabajos)). Pero **un ojo humano sigue siendo imprescindible**. Recorre la app (`npm run dev` con el backend en el 8080) y mira sobre todo:

- [ ] Que el texto del banner y del modal se lee sobre cada portada real (el contraste sobre imágenes no se puede calcular).
- [ ] La sensación general: espaciados, tipografía y animaciones.
- [ ] El **teclado y un lector de pantalla**: los E2E comprueban la estructura, no la experiencia.
- [ ] Las pantallas más nuevas: panel de administración, series, películas, perfil y «Comprobando tu sesión...».

### 2.4 Acciones tuyas (datos y decisiones)

- ✅ ~~**Auditoría: comprobar Supabase**~~ — **hecho por el autor el 2026-10-08**: todas las tablas con RLS, ningún permiso para `anon`/`authenticated`, privilegios por defecto cerrados para el rol de la app y **Data API desactivada** (ver el [historial](#4-historial-de-trabajos)).
- **Si algún día se publica la app** — *Alta (antes de publicar) · BAJA*: `STREAMBOX_BIND_ADDRESS=0.0.0.0` solo con TLS delante, y `set_real_ip_from`/`real_ip_header` en nginx. Hoy no aplica (solo se publica en `127.0.0.1`).
- **Portadas locales: ejecutar el SQL** — *Media · BAJA*
  - Desde la tarea 21 la UI usa solo `imageUrl`: las películas cuyo `image_url` no apunte a una imagen alcanzable muestran un hueco con el título.
  - Se arregla ejecutando a mano `docs/portadas-locales.sql` (pone las 10 portadas de ejemplo en `/covers/*.webp`; opcional e idempotente; no se ha ejecutado).
  - Desde el 2026-10-04 esas rutas pasan la validación de la API (`@HttpsUrl` admite `/covers/<archivo>`), así que también puedes ponerlas desde el panel, película a película.
- **URLs antiguas en tu base de datos** — *Media · BAJA*
  - El panel no deja guardar una película con `imageUrl`/`videoUrl` `http://` o con caracteres no ASCII (el `PUT` reenvía todos los campos).
  - Para localizarlas (consulta de solo lectura, lánzala tú en Supabase):
    `SELECT id, title, image_url, video_url FROM movies WHERE (image_url NOT LIKE 'https://%' AND image_url NOT LIKE '/covers/%') OR video_url NOT LIKE 'https://%';`
- **`genreIds` obligatorio** — *Opcional · BAJA*. `MovieRequest.genreIds` es `@NotEmpty`: no se pueden crear películas sin género, aunque la UI sí las soporta. Confirmar si es deliberado.
- **Géneros: filas antiguas con espacios dobles** — *Baja · BAJA*. La normalización colapsa ahora los espacios interiores; una fila antigua con dos espacios o un espacio duro no se detectaría como duplicado. No debería haber ninguna (compruébalo en Supabase si te interesa; la limpieza sería una migración).

### 2.5 Seguridad

- ✅ ~~**Logout que falla abierto (auditoría 2)**~~ — **hecho el 2026-10-08** (`frontend`): la interfaz solo pasa a «sin sesión» cuando el servidor confirma el cierre; si falla por red o 5xx hay un reintento y, si sigue fallando, la sesión se mantiene con un aviso honesto. Ver el [historial](#4-historial-de-trabajos).
- **Otras notas de la auditoría 2** — *Baja · BAJA*. Lista completa en `run-2/REPORT.md` §6.1.
  - `STREAMBOX_AUTH_COOKIE_SECURE=false` por defecto en compose: falla abierto si se pone TLS delante.
  - La prueba de humo del CI debería comprobar también `SameSite=Strict` y `Path=/api`.
  - Fijar el algoritmo del JWT (hoy depende de la longitud del secreto).
  - `sub` = email: reutilizable si algún día se borran cuentas.
  - ✅ ~~`index.html` con `no-store` (bfcache muestra páginas ya cargadas tras el logout)~~ (hecho el 2026-10-09 en `frontend/nginx/default.conf`, por el `map` y sin perder las cabeceras de seguridad; comprobado con `curl -I`).
  - Tests de la cookie con `POST`/`PUT`/`PATCH`/`HEAD`, cookie + Bearer y cookie duplicada.
  - ✅ ~~WARN sin límite con `e.getMessage()` de jjwt~~ (hecho el 2026-10-08: DEBUG y solo la clase de la excepción).
- **Notas de endurecimiento de la auditoría 1** — *Baja · BAJA*. Unas 40 mejoras de defensa en profundidad (`run-1/REPORT.md`, apartado 6.1). Entre ellas:
  - fijar el `Dockerfile` (hoy `maven:3.9-eclipse-temurin-26` flotante, frente a su comentario Maven 3.9.16/JDK 21) y `distributionSha256Sum` del wrapper;
  - un rol no superusuario para la app en PostgreSQL;
  - `server.address=127.0.0.1` en desarrollo y en el E2E;
  - `@Size` en `title` y en el email del registro; `genreIds` con `@NotNull` por elemento;
  - `@PreAuthorize` como segunda barrera; `aud` en el JWT;
  - `limit_req` en nginx y rotación de logs en compose;
  - `getSafeVideoUrl` solo `https:` y `CHECK` en `image_url`/`video_url`;
  - un test con el perfil `prod`;
  - ✅ ~~no convertir fallos de BD en 401 en `JwtAuthenticationFilter`~~ (hecho el 2026-10-08).
- **Auditoría 1: pistas sin verificar** — *Baja · BAJA*. Dos pistas de resultados descartados por mal formados, que los cazadores nuevos no repitieron. No son hallazgos: comprobar si interesan.
  - ✅ ~~Una pestaña restaurada desde la caché atrás/adelante (bfcache) podría seguir usando una sesión cerrada en otra pestaña~~: **resuelta por la tarea 29**. El token ya no está en la memoria de la pestaña sino en una cookie que el logout borra para todas, y una pestaña restaurada recibe 401 en su siguiente petición.
  - ⬜ Tras el NAT de Docker Desktop o un proxy TLS, el límite por IP podría volverse global.
- **Bloqueo de cuenta como arma (residual)** — *Media · MEDIA*
  - Resuelto para el titular que entra desde una **IP conocida** (NV-2 de la auditoría 1).
  - Sigue siendo posible bloquear un primer login desde una IP **nueva** mientras dure el ataque, y la «IP conocida» se pierde al reiniciar (estado en memoria).
  - Con una IP compartida con la víctima (NAT móvil, empresa, VPN, `/64`) se puede distinguir si una cuenta tiene un login reciente desde esa IP (limitación documentada).
  - **Alternativa estructural pendiente de decidir:** cookie de dispositivo firmada (HMAC) y ligada al email, emitida en el login correcto; no depende de la IP ni de los reinicios. Otras: CAPTCHA o persistir las IPs conocidas.
- **CORS y cabeceras (S12)** — *Media · BAJA*. CORS explícito por perfil y CSP, para cuando el frontend esté en otro origen.
- **Rate limiting en memoria** — *Baja (hasta escalar) · MEDIA*. Con varias réplicas el límite se multiplica y un reinicio lo borra. Si se escala: Redis/Bucket4j distribuido. También lo propone el informe de Gemini; sigue siendo condicional (ADR 0006: hoy hay una sola instancia).
- **Contraseñas: lo que la política no cubre** — *Baja · MEDIA*
  - Hecho el 2026-10-04: 12–64 caracteres, 72 bytes, lista de ~200 comunes, 5 caracteres distintos y sin usuario ni email.
  - Quedan: secuencias (`qwertyuiopasd`, `234567890123`); los espacios cuentan como caracteres distintos; invisibles **en medio** de la contraseña (`pass`+U+200B+`word1234` pasa la lista); y comparar con listas de contraseñas filtradas (HIBP, *k-anonymity*).
- **Código de error del 401 (mejora 19)** — *Baja · BAJA*. El token ausente usa `INVALID_CREDENTIALS`; crear `UNAUTHENTICATED`.
- **Editar perfil: incidencias bajas de la revisión de `qa`** (2026-10-09) — *Baja · BAJA*
  1. (`security`) Las «IP conocidas» de la cuenta no se olvidan al cambiar la contraseña ni con `logout-all`: quien entró con la contraseña robada conserva su IP como conocida 30 días (con su propio contador, sin sufrir el bloqueo de la cuenta). Olvidarlas en `PasswordChangeService.replaceCredentials` (y quizá en `logoutAll`).
  2. (`frontend`) Autocompletado: `PasswordForm` sin campo de usuario oculto (el gestor de contraseñas no actualiza la credencial guardada) y `UsernameForm` con `autoComplete="username"` (el navegador puede proponer el correo). Propuesta: `<input type="email" autoComplete="username" value={email} readOnly hidden>` y `autoComplete="nickname"`.
  3. (`backend`) Normalización del nombre: los caracteres de formato (`\p{Cf}`) interiores no se quitan (`bo\u200Bb` sigue siendo gemelo de `bob`) y `AdminAccountInitializer` solo hace `trim()`.
  4. (`frontend`) Si la respuesta de `PUT /password` se pierde tras confirmarse, el usuario reintenta con la vieja y lee «La contraseña actual no es correcta»: añadir una pista en el error de red.
  - Huecos de test (`qa`): dos cambios de contraseña simultáneos contra PostgreSQL real; N intentos en paralelo con la actual incorrecta (como mucho 5 BCrypt).
- **Login con una sesión anterior en el navegador** — *Baja · BAJA* (`security`). El login no revoca la familia del `streambox_refresh` que pudiera traer el navegador (solo pasa tras un logout fallido y otro login); esa sesión antigua vive hasta caducar (7 días). Arreglo: si el login trae esa cookie, `revokeFamilyOf`. (Revisión de `qa`, 2026-10-09.)
- **Filtros registrados dos veces** — *Baja · BAJA* (`security`). `RateLimitingFilter` y `JwtAuthenticationFilter` son `@Bean` de tipo `Filter`, así que Spring Boot también los registra como filtros globales del servidor. Hoy no hace daño (`OncePerRequestFilter` evita la segunda ejecución), pero lo limpio es un `FilterRegistrationBean#setEnabled(false)` para cada uno.

### 2.6 Backend y API

- **Coerción de escalares de Jackson** — *Baja · BAJA*. `"password": 123456789012.5` (un número) crea la cuenta con ese texto; `true` da 400 de longitud. Desactivar `ALLOW_COERCION_OF_SCALARS` para que un campo de texto solo acepte texto (cambio global: revisar que el frontend envía los números como números).
- **Validador de URLs: puertos y `_`** — *Baja · BAJA*. `java.net.URI` acepta puertos imposibles (`:99999`, `:0`) y rechaza hosts con guion bajo (`cdn_1.example.com`) que los navegadores sí cargan. Sin riesgo (el servidor nunca abre estas URLs); decidir el comportamiento.
- **`@NotBlank` sin mensaje en `MovieRequest`** — *Baja · BAJA*. Los campos obligatorios de película usan el mensaje por defecto, que depende del idioma de la JVM. El frontend ya los valida antes con sus propios textos; poner mensajes en español cambia el contrato (coordinar con `lib/movieValidation.ts`).
- **Normalización del email duplicada** — *Baja · BAJA*. `UserService`, `AuthenticationService`, `LoginAttemptService` y `AdminAccountInitializer` normalizan cada uno el email: centralizarlo evita que diverjan. `UserService` recorta el usuario con `trim()` (un espacio de no separación final crearía un «gemelo» visual): usar la expresión de `GenreService`.
- **OpenAPI: 500 sin documentar** — *Opcional · BAJA*. Ningún endpoint documenta el 500; se podría añadir desde `ErrorResponseOpenApiCustomizer`. Algunas descripciones de 409 no nombran su `code` (`USER_ALREADY_EXISTS`, `MOVIE_ALREADY_IN_FAVORITES`).
- **DTOs de petición con Lombok** — *Opcional · BAJA*. `CreateUserRequest` y `LoginRequest` siguen siendo clases con `@Getter/@Setter`: convertirlas a `record` por coherencia con `MovieRequest` y `GenreRequest`.
- **Listados sin paginar** — *Opcional · BAJA*. `GET /api/users` (admin) y `GET /api/users/me/favorites` devuelven todo.
- **Endpoints `by-title` (mejora 10)** — *Opcional · BAJA*. Duplican los de id: valorar retirarlos.
- **Favoritos idempotentes (mejora 11)** — *Opcional · BAJA*. `PUT`/`DELETE` idempotentes en lugar de 409 en duplicado.
- **`Location` en 201 (mejora 12)** — *Opcional · BAJA*. Añadir la cabecera en el alta de películas, géneros y usuarios.
- **Opcionales** — *Opcional*. Versionado `/api/v1`, MapStruct (descartado por ahora en el ADR 0001), auditoría (`updatedAt`, `createdBy`) y borrado lógico, ETag en géneros.

### 2.7 Base de datos

- **Índice por título y búsqueda tolerante a erratas** — *Opcional hasta tener un catálogo grande · MEDIA* (`database`)
  - `lower(title) LIKE '%x%'` no usa un índice normal; en PostgreSQL requiere `pg_trgm` + índice GIN (migración específica de PostgreSQL).
  - El mismo `pg_trgm` daría **búsqueda tolerante a erratas** (`similarity()`, operador `%`), que es lo que el informe de Gemini (2026-10-08) pedía a Elasticsearch/Meilisearch. Con `pg_trgm` se consigue sin otro servidor que sincronizar y en la misma transacción.
  - Exige tests contra PostgreSQL real (H2 no lo tiene).
- **Búsqueda sensible a acentos** — *Opcional · MEDIA*. `manana` no encuentra `Mañana` (sí ignora mayúsculas). Hacerla insensible exige `unaccent`, específico de PostgreSQL (migración + Testcontainers, ya disponible).
- **Unicidad de película** — *Opcional · BAJA*. Valorar `UNIQUE (title, release_year)` (evitaría `AmbiguousTitleException`); antes hay que revisar los datos existentes.
- **Géneros: unicidad sin mayúsculas en la BD** — *Opcional · MEDIA*. Solo la garantiza el servicio (`existsByNameIgnoreCase`); la `UNIQUE` distingue mayúsculas. Un índice único sobre `lower(name)` lo impondría en la BD, pero es específico de PostgreSQL (H2 no admite índices sobre expresiones).
- **Favoritos: sin fecha de alta** — *Opcional · MEDIA*. La tabla de unión no guarda cuándo se añadió cada película, así que no se puede ordenar por «añadidas recientemente». Requeriría una entidad `Favorite` con `added_at` y una migración nueva.
- **Bases antiguas: sin cascada ni CHECKs** — *Baja · MEDIA*
  - Las bases creadas antes de Flyway conservan sus FKs sin `ON DELETE CASCADE` (por eso `deleteMovie` sigue limpiando los favoritos a mano con `deleteFromAllFavorites`) y **tampoco reciben los CHECK de año y duración** (aceptan `release_year=1700`). Lo comprobaron los tests de adopción en PostgreSQL.
  - **La cabecera de `V1` dice lo contrario** («índices, claves foráneas...»): solo reciben los índices. No se puede corregir editando `V1` (Flyway valida el checksum, también de los comentarios), así que queda documentado aquí.
  - Unificar exige una migración nueva que recree las FKs y añada los CHECKs, con riesgo sobre datos antiguos que no los cumplan.

### 2.8 Frontend y diseño

- ✅ ~~**Caché de datos del servidor (TanStack Query)**~~ — **hecho el 2026-10-09** (ver el [historial](#4-historial-de-trabajos) y el cap. 19.3 del manual).
- **Diseño: propuestas de las revisiones (decisión tuya)** — *Opcional · BAJA*
  - **Fuente:** Geist Sans (licencia OFL, `woff2` alojado en `public/fonts`, compatible con la CSP). Hoy se usa la del sistema (Segoe UI en Windows), lo más genérico que queda; no se instaló porque es un recurso nuevo.
  - Subir el borde `white/30` de los botones `outline` y de `SeasonPicker` (≈2,7:1) a `field-border` (cambia todos los botones secundarios).
  - Un enlace «Volver a series» en el detalle de una serie.
  - Unificar los tres cuadros de error (`ErrorState`, `LatestSeriesRow`, `GenreCheckboxes`) en `ErrorState compact`.
  - La fila «Contraseña» de `/perfil`, con puntos, podría sugerir que se puede editar.
  - Opcional: `impeccable init` para generar `PRODUCT.md`/`DESIGN.md` con el contexto de diseño del proyecto.
- **Estados vacíos: decisiones de gusto** — *Opcional · BAJA*
  1. El buscador no da ninguna pista a un administrador que busca una serie oculta (ve «Sin resultados»).
  2. Con series pero sin películas, la portada muestra solo la fila «Series», sin banner: se podría usar la serie más reciente.
  3. En la portada de un admin, el aviso de la fila «Series» puede aparecer un instante después y empujar las filas si `/users/me` llega tarde.
  4. Estilo: «no la ven en el catálogo, el buscador ni su lista» → «ni en el catálogo, ni en el buscador ni en su lista».
  5. «Explorar catálogo» (Mi lista) e «Ir al inicio» (`/series`, `/peliculas`) llevan al mismo sitio con nombres distintos.
- **Películas: detalles abiertos** — *Opcional · BAJA*
  - **Página propia por película** (`/peliculas/:id`, como las series; hoy el detalle es un modal sin URL).
  - Con `?anio=99` en la URL, el año inválido se ignora sin mostrar error en el campo (solo aparece al teclearlo); avisar también ahí es un cambio en `useMovieFilters`.
  - Los mismos filtros (género, año, orden) podrían llevarse a `/series`.
- **Panel de administración: detalles de diseño** — *Opcional · BAJA*
  - Al cargar los géneros, el formulario de película salta ~128 px en móvil (reservar el hueco con esqueletos).
  - Durante una búsqueda, el título accesible de la tabla ya describe la búsqueda nueva mientras se ven las filas antiguas (atenuadas, `aria-busy`).
  - En 375 px «Administrar» es solo un icono (con nombre accesible).
  - Con zoom del 400 % o el móvil en horizontal, la barra fija (165–213 px) ocupa mucho: valorar no fijarla en pantallas bajas.
- ✅ ~~**Portadas pesadas en `public/covers/`**~~ — **hecho el 2026-10-09**: las 21 JPG recomprimidas **con el mismo nombre** (máx. 800 px de ancho, calidad 82, progresivas, sin metadatos): de 17,9 MB a 3,5 MB (la carpeta, de 19 MB a 4,4 MB). Ninguna URL cambia, así que tu base de datos no se toca. E2E 150/150.
- **E2E de dos pestañas** — *Baja · BAJA* (`frontend`). La propagación de la sesión entre pestañas está probada con Vitest (dos canales) y a mano con `agent-browser`, pero no con un E2E (`context.newPage()` en `e2e/auth.spec.ts`).
- **Detalles del frontend (limpieza y auditoría)** — *Opcional · BAJA*
  - `public/favicon.svg` es el logo por defecto de Vite (decisión de diseño).
  - La prop `alt` de `MoviePoster` no se usa en producción (siempre `alt=""`), pero está documentada y probada (4 tests).
  - `oxlint -W suspicious` avisa de `exhaustive-effect-dependencies` en `FavoritesContext`, `useCatalog`, `MovieRow` y `SearchBar` (revisar las dependencias de esos efectos).
  - Los pósters son de 600 px y pueden verse suaves en el banner a 1280 px. Una imagen horizontal (`backdropUrl`) en la API permitiría un banner con fotograma real.
- **Responsive: orden de tabulación en móvil** — *Opcional · BAJA*. El buscador baja a una segunda fila, pero el orden de `Tab` sigue el de escritorio (buscador antes que el menú de usuario). Aceptado.

### 2.9 Funcionalidades nuevas

- **Reproductor integrado** — *Media · MEDIA*
  - «Ver ahora» abre el vídeo en otra pestaña. Un reproductor en la app solo sirve para vídeos directos (`.mp4`, `.webm`, HLS): revisar antes qué `videoUrl` hay en la base de datos.
  - Para HLS (`.m3u8`, que propone el informe de Gemini) bastaría `hls.js` con URLs HLS ya publicadas. Transcodificar con FFmpeg, guardar en S3/MinIO y servir por CDN es otro proyecto (subidas, colas de trabajo, almacenamiento) y hoy no hay vídeos propios. Una subida de archivos exigiría reactivar multipart solo para su ruta (capítulo 13 del manual).
- **Series: siguientes pasos** (fuera de la primera versión, 2026-10-05) — *Media · MEDIA–ALTA*
  - **Imagen propia por episodio** (hoy usa la de la serie): columna nueva en `episodes` con su migración y `@HttpsUrl`.
  - **Progreso de visionado / «Seguir viendo»**: tabla de progreso por usuario y episodio o película; depende del reproductor.
  - **Episodios favoritos** sueltos.
  - **Tráileres**, y una imagen horizontal `backdropUrl` para el banner.
  - **Valoraciones**: me gusta o estrellas, y nota media.
- ✅ ~~**Editar perfil**~~ — **hecho el 2026-10-09** (ver el [historial](#4-historial-de-trabajos)): cambiar nombre, cambiar contraseña y cerrar sesión en todos los dispositivos. El email no se puede cambiar (decisión del autor).
- **Perfil: historial y suscripción** (2026-10-07) — *Opcional · ALTA*. La captura de referencia mostraba títulos vistos, horas, vistos recientemente y suscripción. No hay nada de eso en el modelo de datos: solo tendría sentido si se añade historial de reproducción (tabla nueva + endpoint).

### 2.10 Tests, herramientas y notas informativas

- **E2E en el contenedor de Claude (Chromium 141)** — *Info*
  - `accessibility.spec.ts:189` (formulario de película: «Volver al listado» tapado por la barra, a 375 y 1280 px) falla con ese navegador. En una página de prueba sin la app, ese Chromium no aplica `scroll-margin-top` a `focus()` si el elemento ya cabe entero en la ventana; el enlace tiene `scroll-margin-top` de 89 px. La interfaz es correcta y en la máquina del autor (Chromium de Playwright 1.63) pasa.
  - Al fallar, los 7 tests del proyecto `catalogo-mutable` salen «did not run»; con `--project=catalogo-mutable --no-deps` pasan 7/7.
- **E2E inestables con la máquina cargada** — *Baja · BAJA*
  - Un aviso que aparece bajo el cursor queda pausado por el *hover* y puede tapar un botón de la fila (p. ej. «Borrar» en Géneros, `admin.spec.ts:122`). Pausar solo con movimiento real del puntero, o colocar los avisos sin tapar acciones, cambia el comportamiento: queda como propuesta.
  - El 2026-10-08, con la batería tardando casi 4 minutos en lugar de ~1, fallaron entre 2 y 4 E2E distintos por tiempo agotado; el último commit, sin los cambios del día, falló igual. Con la máquina menos cargada, 144/144.
  - Tests E2E de entre 15 y 19 s (`admin-series.spec.ts:61`, géneros de `admin.spec.ts`, `admin-peliculas.spec.ts:31`), por debajo del límite de 30 s.
  - Vitest: un fallo suelto no reproducible (1 de ~20 ejecuciones) y, en otra ocasión, 5 fallos en una de cuatro ejecuciones con la máquina cargada (80 s en vez de 33 s; no se guardó la salida). Si reaparece, mirar primero los tiempos de espera.
- **E2E: huecos** — *Opcional · BAJA*. Solo Chromium; sin auditoría automática de contraste y ARIA (propuesta: `@axe-core/playwright`, ≈1 MB, `devDependency`); no se prueba que «Ver ahora» abra el vídeo (solo `href`/`target`) ni la caducidad real del JWT; el `role="alert"` vacío permanente de `ToastViewport` obliga a localizar las alertas por texto.
- **Test del `JWT_SECRET` (residual)** — *Baja · BAJA*. Las clases `@SpringBootTest` heredan las variables de entorno: un `JWT_SECRET` con **menos de 32 caracteres** rompería toda la suite (8/8 errores comprobados). Con 32 o más no hay problema. Para blindarlo: fijar `jwt.secret` con más precedencia que el entorno en una base común de tests.
- **Aviso de springdoc** — *Baja · BAJA*. Al generar `/v3/api-docs` sale 4 veces un WARN de `SpringDocUtils` («Json Processing Exception ... JsonSchema["type"]»); la documentación es correcta. Es una incompatibilidad de la librería, anterior a estos cambios.
- **Ruido en `pom.xml` y propiedades** — *Opcional · BAJA*. Placeholders vacíos de Initializr (`<name />`, `<description />`, `<url />`, `<licenses>`, `<developers>`, `<scm>`): no se quitaron porque, al desaparecer, el proyecto heredaría la licencia, los desarrolladores y el scm de Spring Boot (rellenarlos con datos propios o dejarlos vacíos a propósito). `spring.datasource.driver-class-name`, `spring.jpa.show-sql=false` y `logging.level.root=INFO` coinciden con lo que Spring deduce, pero se dejan como documentación explícita.
- **Datos de ejemplo** — *Opcional · BAJA*. Las portadas de `public/covers` no corresponden a los títulos de las películas de ejemplo de los E2E (p. ej. «Interstellar» con el póster de «Event Horizon»). Es de los datos de prueba, no de la UI.
- **Orden de `sort=title` según el motor** — *Info*. En PostgreSQL (collation `en_US.utf8`) ignora mayúsculas y acentos como primer criterio y la `ñ` se ordena como una `n` acentuada; en H2 ordena por código de carácter. La paginación es estable en ambos, pero el orden visible depende del servidor. Fijado por tests en `PostgresCatalogIntegrationTest`.
- **V2 y la zona horaria** — *Info*. V2 interpreta las fechas antiguas en la zona horaria de la sesión de quien ejecute Flyway (`22:00` → `20:00Z` en Madrid, `22:00Z` en UTC) y, salvo en UTC, reescribe la tabla con bloqueo `ACCESS EXCLUSIVE`. Irrelevante con catálogos pequeños; medido en `PostgresBaselineAdoptionIntegrationTest`.
- **Series: orden de «Mi lista»** — *Baja · BAJA*. El servidor ordena por título, pero en la interfaz las añadidas en la sesión van primero (igual que con películas) y tras recargar se reordenan. Los avisos `WARN HHH000247 … 23505` del log cuando hay carreras son esperados.

**Quitado de esta lista por estar hecho:** comodines de `LIKE` (S10), Swagger con 403 en vez de 401, la limpieza de anotaciones con nombre completo y la API que aceptaba YAML, multipart y formularios (2026-10-08).

---

## 3. Completado: tareas numeradas

### Fase 1 — Seguridad y bugs ✅

| # | Tarea | Resultado |
| :--- | :--- | :--- |
| 1 | Favoritos duplicados o ausentes devolvían 500 (C1) | 409 `MOVIE_ALREADY_IN_FAVORITES` y 404 `MOVIE_NOT_IN_FAVORITES` |
| 2 | Errores del cliente tratados como 500 (C2, C3) | `GlobalExceptionHandler` extiende `ResponseEntityExceptionHandler`; jerarquía `ResourceNotFoundException` |
| 3 | `sort` libre del cliente (S7) | Lista blanca en `MovieController` |
| 4 | Tests dependientes de `JWT_SECRET` (I1) | Perfil `test` (`application-test.properties`) |
| 5 | Secreto y claims JWT (I3, S3) | `JwtProperties` validado (≥32 caracteres), clave construida una vez, `issuer` |
| 6 | Configuración insegura por defecto (C4, S2) | Perfiles `dev` / `prod`, Swagger desactivable, sin SQL en los logs de prod |
| 7 | Tests de favoritos y auth/JWT (I10) | Favoritos, login, filtro JWT, errores de API |
| 14 | Rate limiting (I2, S1) | `RateLimitingFilter` (login y registro por IP) + `LoginAttemptService` (bloqueo de cuenta), 429 con `Retry-After` |

### Fase 2 — Arquitectura y datos ✅

| # | Tarea | Resultado |
| :--- | :--- | :--- |
| 8 | Flyway, esquema, índices y admin inicial (I8, S14) | `V1__create_schema.sql` (FKs, cascadas, CHECKs, 3 índices), `ddl-auto=validate` en todos los perfiles, `AdminAccountInitializer` |
| 9 | Los servicios devuelven DTOs (I6) | `MovieService`, `GenreService`, `UserService`, `FavoriteService` y `AuthenticationService.login` devuelven DTOs; el mapeo ocurre dentro de la transacción. Los controladores ya no tocan entidades. Los DTOs de respuesta son `record` |
| 10 | Favoritos rehechos (I7) | `FavoriteService` + `FavoriteController` separados de `UserService`; `INSERT`/`DELETE` directos sobre la tabla de unión (ya no se carga la lista completa para tocar un elemento); carrera de altas simultáneas controlada; lista ordenada por título; `equals/hashCode` por id; `@AuthenticationPrincipal` en lugar de castear `Authentication` |
| 11 | Paginación sin *fetch* de colección (I5) | Sin `@EntityGraph` en las páginas, `default_batch_fetch_size=50` (una consulta extra por página para todos los géneros), filtro por género con `EXISTS`, orden determinista (`id` como desempate), comodines de `LIKE` escapados (S10) |
| 12 | Fechas con `Instant` (I9) | `createdAt` pasa a `Instant` + `@CreationTimestamp`; `V2__created_at_with_time_zone.sql` (`TIMESTAMP WITH TIME ZONE`); `ErrorResponse` también usa `Instant` |
| 13 | Limpieza de código (mejoras 2–8, 22) | Eliminados `exception/ErrorResponse`, `saveUser(User)`, `UserMapper.toEntity`, `searchMoviesByTitle`, `findByName`, `findAll()` con `@EntityGraph` y `CustomUserDetailsService`; `CreateMovieRequest`/`UpdateMovieRequest` unificados en `MovieRequest`; N+1 del bucle de géneros sustituido por `findAllById`; anotaciones con nombre completo sustituidas por imports; Swagger corregido (401 en lugar de 403, 409 y 429 documentados) |

### Fase 3 — UX/UI y accesibilidad (frontend) ✅

Hecha con el equipo de agentes (`orchestrator` → `frontend`, `backend`, `qa`, `security`). Código en `frontend/src/{pages,components,context,hooks,lib}`.

| # | Tarea | Resultado |
| :--- | :--- | :--- |
| 15 | `apiFetch` + `AuthContext` + errores uniformes (I11, U7) | `lib/api.ts` (`apiFetch`, `ApiError`): comprueba siempre `res.ok`, 204 sin cuerpo, red caída (`NETWORK_ERROR`), **401** cierra sesión una sola vez (ignora respuestas tardías; el de login/registro no cierra nada), **403** no cierra sesión, **429** con cuenta atrás desde `Retry-After`. No queda ningún `fetch` fuera de `api.ts` ni ningún `any` |
| 16 | Página de registro (U1) | `RegisterPage` (`/registro`): validación en cliente con los límites reales del backend, `validationErrors` junto a cada campo, 409 y 429 tratados, redirige al login con un aviso |
| 17 | Paginación y banner real (U2) | `useCatalog`: banner = película **más reciente**, «Novedades» y una fila por género (≥3 películas), «Cargar más» con `hasNext`, sin duplicados ni saltos de scroll; un fallo de «cargar más» no pierde lo cargado |
| 18 | Confirmaciones, avisos y «Ver ahora» (U3–U5) | `ToastContext` propio (`aria-live`), `ConfirmDialog` para vaciar la lista, favoritos optimistas (`FavoritesContext`; 409/404 = estado ya correcto), «Ver ahora» abre la `videoUrl` validada (solo http/https) en otra pestaña |
| 19 | Accesibilidad (A1–A8) | `lang="es"`, «Saltar al contenido», *landmarks* y un `h1` por página, `Modal` sobre `<dialog>` (foco, Escape, devolución de foco, bloqueo de scroll), buscador como *combobox* con teclado y anuncios, foco visible, `prefers-reduced-motion`, contrastes WCAG AA calculados (el *placeholder* fallaba: 3,54 → 6,02) |
| 20 | Responsive y Tailwind (U12–U14) | 0 estilos en línea; tokens `@theme`; buscador en segunda fila en móvil; banner fluido; filas con `snap` y flechas desde `md`; objetivos táctiles ≥44 px |
| 21 | Imágenes (U14) | La UI usa siempre `imageUrl` (`MoviePoster`: *lazy*, sin saltos de diseño, `referrerPolicy="no-referrer"`, respaldo con título). Eliminados `imageMap` y 14 JPG (≈12,9 MB); 10 portadas WebP (≈0,8 MB) en `public/covers/`. Queda el [SQL opcional](#24-acciones-tuyas-datos-y-decisiones) |
| — | `genres` en `types.ts` y filas por género | Hecho junto con la 17 |
| + | Backend: `direction=asc\|desc` en `GET /api/movies` y `/search` | Faltaba para «lo más reciente primero». Opcional, por defecto `asc` (compatible); el desempate por id sigue la misma dirección. 9 tests nuevos (192 → 201) |
| + | `JwtPropertiesValidationTest` aislado del entorno | Con `JWT_SECRET` definida en tu shell fallaba (el `ApplicationContextRunner` leía la variable del sistema). Ahora pasa con y sin la variable |
| + | Auditoría de seguridad del frontend | 0 hallazgos críticos, altos o medios y `npm audit` con 0 vulnerabilidades. Corregidos los menores: credenciales embebidas en `getSafeVideoUrl`, `Referer` de las imágenes, `localStorage.clear()` entre pestañas y `Object.hasOwn` |

### Fase 4 — Testing ✅

Hecha con el equipo de agentes (`qa`, `database`, `backend`, `frontend`).

**22 · Testcontainers + PostgreSQL**
- Paquete `com.emilio.streambox.postgres` (80 tests en su momento), imagen `postgres:16`, un contenedor compartido. Con `@Testcontainers(disabledWithoutDocker = true)`, **sin Docker se omiten** y la suite sigue en `BUILD SUCCESS` (comprobado en un contenedor Linux sin Docker).
- Cubre: Flyway V1+V2 desde cero y `ddl-auto=validate`; tipos reales (`timestamptz`); índices; PK/FK/CHECK con su SQLSTATE real; **adopción de una base heredada** (baseline) con datos; favoritos con `INSERT`/`DELETE` nativos y **concurrencia real** (8 hilos × 10 rondas: exactamente 1 éxito y el resto 409); diferencias de motor en el catálogo (collation, `lower()`, `ñ`, comodines, `EXISTS`, N+1).
- Dependencias de test: `testcontainers-junit-jupiter` y `testcontainers-postgresql` (versiones del BOM de Spring Boot). `spring-boot-testcontainers` se añadió y se retiró en la limpieza por no usarse: el contenedor se conecta con `@DynamicPropertySource`.
- Mutaciones comprobadas: quitar un `ON DELETE CASCADE` o los `ALTER` de V2 los hace fallar.

**23 · Paginación y búsqueda con casos límite**
- `CatalogEdgeCasesIntegrationTest` (169 ejecuciones, casi todas parametrizadas): `page`/`size` fuera de rango y mal formados; `title` vacío, enorme, Unicode o con inyección; `genreId` y `releaseYear` extremos; 5 columnas × asc/desc; empates; película sin géneros o con varios; 401. Ningún caso de cliente acaba en 500.

**24 · Tests del frontend**
- **Vitest + Testing Library: 236 tests** en su momento (cliente de API, `getSafeVideoUrl`, validación, catálogo, `useCatalog`, `AuthContext`, favoritos, login/registro, buscador, modales, guardas de ruta...). 12 roturas deliberadas del código, las 12 detectadas.
- **Playwright: 48 E2E** en Chromium con backend y Vite propios y aislados (8099/5199, H2 en memoria; no tocan tu base de datos ni tus puertos): registro → login → catálogo → «Cargar más» → favoritos → vaciar con confirmación, buscador, sesión caducada (una sola redirección), 429 real, teclado y foco, y responsive (375/768/1280 sin scroll horizontal). 3 ejecuciones seguidas sin inestabilidad; 5 roturas deliberadas, las 5 detectadas.
- Dependencias: `vitest`, `jsdom`, `@testing-library/*`, `@playwright/test` (solo `devDependencies`).

**Fallos reales que encontraron los tests (todos corregidos):**
- `page` muy grande (`page × size > Integer.MAX_VALUE`) daba **500** y un `hasNext` erróneo → ahora 400 `VALIDATION_ERROR` en `validationErrors.page`.
- La búsqueda por título no encontraba títulos con `İ` ni con sigma final (`ΟΔΥΣΣΕΥΣ`) en PostgreSQL, porque Java y PostgreSQL pasan a minúsculas de forma distinta (H2 lo ocultaba) → `lower()` se aplica ahora en la base de datos a ambos lados.
- `FavoriteService` convertía **cualquier** violación de integridad en 409 «ya está en favoritos»; si la película se borraba justo antes del `INSERT` (violación de FK), el cliente recibía un 409 falso → unicidad = 409, clave foránea = 404.
- **Visual** (primera revisión real de la interfaz, con capturas a 375/768/1280 px): la portada del banner y del modal no cubría su caja por un conflicto de clases (`relative` ganaba a `absolute inset-0`) → corregido en `MoviePoster.tsx`, con una comprobación E2E que lo vigila.

**Verificado a mano contra PostgreSQL real** (y con el perfil `prod`, ver la tarea 26): adopción de una base ya existente (baseline), V1 desde cero, V2 sobre datos reales y la API de extremo a extremo (registro → login → catálogo → favoritos → `sort` inválido).

### Fase 5 — Producción y repositorio (28 hecha; 29 en [2.2](#22-tarea-29-lo-que-queda-solo-hsts))

**25 · Limpieza del repositorio (2026-10-02)**
- **Frontend:**
  - borrados con `git rm` los 8 scripts de *scaffolding* (`fix.js`, `fix.cjs`, `fix_app.cjs`, `fix_react.cjs`, `gen.js`, `gen.cjs`, `g.py`, `setup.js`), que reescribían archivos con contenido antiguo y roto (uno con directivas de Tailwind v3);
  - borrados `tailwind.config.js` (resto de la v3; Tailwind v4 se configura en CSS y no lo leía), `public/icons.svg` (sin uso) y la dependencia `autoprefixer` (Tailwind v4 ya prefija);
  - código muerto: tipo `LoginRequest`, prop `cancelLabel` de `ConfirmDialog` y el `export` de `API_URL`. `postcss.config.js` se queda mínimo (Vite lo necesita para cargar `@tailwindcss/postcss`);
  - `frontend/README.md` reescrito (era el de la plantilla, con la estructura antigua y la codificación rota);
  - `npm run lint` pasa por fin **con código 0 en todo el proyecto**.
- **Backend:** auditoría completa (clases, DTO, `ErrorCode`, repositorios, servicios, imports, propiedades): 0 código muerto. Se retiraron las dependencias de test `spring-boot-starter-data-jpa-test` y `spring-boot-testcontainers` (sin uso) y se corrigieron 3 Javadoc desactualizados.
- **Raíz:** eliminados los restos de una actualización de Java hecha con otra herramienta (`.github/modernize/` en la raíz y en `streambox/`, solo logs y planes, nunca versionados) y `streambox/HELP.md` (plantilla de Spring).
- **`docs/` se versiona:** estaba en el `.gitignore`, así que este plan y `portadas-locales.sql` no estaban en el repositorio aunque el README y `CLAUDE.md` los citaban; se quitó esa línea.
- Suite tras la limpieza: 469 + 236 + 48 tests en verde.

**26 · Actuator, endpoints de prueba y logs (S11)**
- Actuator con solo `health` e `info` expuestos; `/actuator/health` (+ `liveness` y `readiness`) público y **sin detalles**, incluido el estado de la base de datos.
- Eliminados `TestController` y `ProtectedTestController`; perfil `prod` con logs en JSON (formato ECS).
- Probado arrancando el perfil `prod` de verdad: logs JSON, health `UP`, Swagger y `/actuator/env` inaccesibles, administrador creado con `ADMIN_EMAIL`/`ADMIN_PASSWORD`.

**27 · Docker + CI + Dependabot (2026-10-06)** — funcionamiento en el capítulo 4.6 del manual
- **Docker:**
  - `streambox/Dockerfile`: Maven + JDK → JRE 21 Alpine, JAR en capas, UID 10001, HEALTHCHECK `readiness`. Tras un *bump* de Dependabot la etapa de compilación usa `maven:3.9-eclipse-temurin-26` (el bytecode sigue siendo de Java 21).
  - `frontend/Dockerfile`: Node 24 → `nginx-unprivileged`, UID 101.
  - `docker-compose.yml`: `db` (`postgres:16.15-trixie`), `backend` y `frontend` en **http://localhost:8088** (solo `127.0.0.1`); tres redes (dos internas: el backend no sale a Internet); los tres contenedores sin root, de solo lectura y con `cap_drop: ALL`.
  - `.env` ignorado, con la plantilla `.env.example` (las obligatorias vacías, para que compose no arranque sin rellenarlas).
- **Riesgo atajado:** las imágenes nunca incluyen `application-local.properties` (credenciales de Supabase): `.dockerignore` en lista blanca, comprobado en el JAR y en el sistema de archivos; además `pom.xml` lo excluye del JAR local.
- **nginx:** SPA, proxy de `/api/`, `X-Forwarded-For` fijado (probado: sin nginx, 13/13 intentos se saltaban el límite por IP), `/actuator` no publicado, `;` → 400, CSP y cabeceras (0 violaciones en un navegador real; adelanta la 29).
- **CI** (`.github/workflows/ci.yml`): jobs `backend` (con los tests de PostgreSQL), `frontend`, `e2e` y `docker` (construye y hace una prueba de humo del stack); acciones fijadas por SHA; sin secretos; validado con `actionlint`.
- **Dependabot** semanal y agrupado (ignora las versiones mayores de PostgreSQL, Java y Node).
- **Por el camino:** un `JWT_SECRET` corto se imprimía en el log al arrancar → ya no (validación en `JwtProperties`, `toString()` enmascarado). `streambox/mvnw` estaba en git sin permiso de ejecución (ya está como ejecutable).

**28 · Documentación**
- `docs/MANUAL_PROGRAMADOR.md` (2026-10-02): recorrido de una petición, arranque, configuración, Flyway, JPA, N+1, seguridad, errores, frontend, tests y recetas. Hay que mantenerlo al día al cambiar el código.
- **ADRs (2026-10-07):** 10 decisiones en `docs/adr/` (backend, frontend, Flyway, JWT, `/me`, rate limiting, favoritos, series, tests y Docker).
- **README revisado:** título, árbol de carpetas corregido (estaba descuadrado), stack, cómo arrancar backend y frontend, y enlace a los ADR (`frontend/README.md` ya se reescribió en la tarea 25). La codificación del README estaba bien (UTF-8).

---

## 4. Historial de trabajos

Trabajos fuera de las tareas numeradas, **del más reciente al más antiguo**.

### 2026-10-09 · Editar perfil: nombre, contraseña y cerrar sesión en todos los dispositivos

- **Decisiones del autor:** el email **no** se puede cambiar; cambiar la contraseña cierra las **demás** sesiones y mantiene la actual; «Cerrar sesión en todos los dispositivos» cierra **todas**, también la actual.
- **Backend** (`backend`): `PATCH /api/users/me` cambia solo el nombre (`email`/`role`/`password` → 400; 409 si está en uso, también ante la carrera con `UNIQUE`). El registro usa ya la misma normalización del nombre (antes solo `trim()`: permitía un «gemelo» con espacio duro).
- **Seguridad** (`security`): `PUT /api/users/me/password` (exige la actual: si no, 400 `CURRENT_PASSWORD_INCORRECT`; `PasswordPolicy`; 5 fallos en 15 min por cuenta → 429; BCrypt fuera de la transacción y comprobación de que el hash no cambió entre medias). Como la cookie del *refresh* (`Path=/api/auth`) no viaja a esa ruta, revoca **todas** las sesiones y abre una nueva para la actual en la misma transacción. `POST /api/auth/logout-all` revoca todas y borra las cookies. Los otros dispositivos pueden tardar hasta 15 min (token de acceso *stateless*). Manual cap. 10.4 bis.
- **Frontend** (`frontend`): tarjetas «Cuenta» (nombre y contraseña editables; el correo, explicado como no editable) y «Sesiones» (con confirmación) en `/perfil` (`components/profile/*`); cambio de contraseña y logout global dentro del Web Lock de sesión; comprobado con `agent-browser` a 375 y 1280 px, como USER y ADMIN.
- **Documentación de la API:** OpenAPI regenerado (40 operaciones) y colección de Postman ampliada: **Newman 79/79 peticiones y 147/147 aserciones**.
- **Revisión de `qa`:** apta (sin incidencias críticas ni altas; 2 mutaciones detectadas). Las 4 bajas quedan en la sección 2.
- **Tests:** backend de 1826 a **1889**; Vitest de 897 a **920**; E2E de 150 a **154**. Hubo dos cortes por el límite de sesión de la API, retomados desde `docs/TRABAJO_EN_CURSO.md` (ya borrado).

### 2026-10-09 · Los avisos de una pestaña oculta ya no se pierden (revisión visual del autor)

- **Problema** (lo vio el autor al probar dos pestañas): el aviso «Se ha cerrado la sesión en otra pestaña…» salía en la pestaña de fondo y su cuenta atrás de 5 s corría igual; al volver a ella ya había desaparecido. Pasaba con cualquier aviso nacido en una pestaña oculta.
- **Arreglo** (`frontend`, `context/ToastContext.tsx`, `components/Toast.tsx`, `hooks/usePageVisible.ts`): un aviso que nace con la pestaña oculta **no se pinta hasta que vuelves** a ella (así el lector de pantalla lo anuncia en ese momento y una sola vez) y dura entonces sus 5 s (9 s los errores); uno que ya estaba en pantalla **pausa** su cuenta atrás al ocultarse la pestaña y sigue con el tiempo que le quedaba.
- **Tests:** Vitest de 889 a **897** (`ToastContext.test.tsx`, nuevo, y un caso de `session-changed` con la pestaña oculta); fallan sin el arreglo. E2E 150/150. Comprobado con `agent-browser` en dos pestañas reales (la de fondo informa `hidden`; el aviso apareció al volver tras 12 y 22 s).
- **Recompresión de portadas** (el mismo día, orquestador): ver la sección 2.8.

### 2026-10-09 · Revisión de la migración, sesión entre pestañas, documentación de la API y análisis final

- **Revisión de `qa` de TanStack Query** (apta, con incidencias menores) y arreglos (`frontend`):
  - **Los cambios de sesión se propagan a las demás pestañas** (gravedad media, ya existía): antes, si en una pestaña se cerraba sesión y entraba otra persona, otra pestaña abierta seguía mostrando la identidad y la caché del usuario anterior mientras sus peticiones salían con la cookie nueva. Ahora se publica `{ type: 'session-changed' }` por el `BroadcastChannel` y las demás pestañas vacían su caché y vuelven a preguntar quién es el usuario, con avisos verdaderos («Se ha iniciado sesión en otra pestaña como «X».», «Se ha cerrado la sesión en otra pestaña…»). Comprobado por el orquestador con `agent-browser` en dos pestañas reales.
  - «Mi lista» ya no se desincroniza si un refresco empieza durante el POST/DELETE (se reafirma lo confirmado por el servidor); un aviso por racha de fallos (no uno por cada vuelta a la pestaña); ningún aviso de una petición que responde tras cerrar sesión; los 502/503/504 del proxy sin `code` se explican como «no se pudo conectar con el servidor»; más cobertura (caducidad con caché llena, «Reintentar» → esqueleto).
  - ADR nuevo: [0012](adr/0012-datos-del-servidor-con-tanstack-query.md).
- **Documentación de la API** (`backend`): `docs/api/openapi.yaml` regenerado desde el backend real (login 204 con cookies, refresh, logout, `SESSION_EXPIRED`, `NOT_ACCEPTABLE`, `CSRF_REJECTED`) y colección de Postman reescrita (USER por cookies como el navegador, ADMIN por Bearer, `X-Requested-With` automático, carpeta nueva de refresh y logout): **Newman 62/62 peticiones y 117/117 aserciones**. En el OpenAPI, esquema nuevo `cookieAuth` junto a `bearerAuth` y el 403 `CSRF_REJECTED` documentado en todas las operaciones no seguras (`CookieAuthOperationCustomizer`), más el 500 del logout.
- **Análisis final con el grafo** (graphify, 4203 nodos): ningún archivo de producción huérfano (los únicos sin referencias son puntos de entrada: controladores, configuraciones, `main.tsx`). `docs/ESTADO_TAREAS.md` reducido a su función (reanudación en la nube + notas de entorno; el historial ya está aquí). Lo pendiente que salió, en la sección 2.
- **Verificación final del orquestador:** backend **1826** tests (1680 con H2 y 146 contra PostgreSQL real), Vitest **889**, E2E **150/150** (+24 de capturas), build y lint limpios.

### 2026-10-09 · Caché de datos del servidor con TanStack Query

- **Problema:** cada hook de datos (`usePagedCatalog`, `useGenres`, `useSeriesDetail`, `useAdminSearchList`, `FavoritesContext`...) reimplementaba la carga, el error, la cancelación de respuestas viejas (épocas y `AbortController`) y «cargar más». Sin caché: volver a la portada repetía todas las peticiones y los géneros los pedían cuatro pantallas. Sin invalidación: tras editar una película en el panel, los listados seguían con el título viejo hasta recargar.
- **Arreglo** (`frontend`): `@tanstack/react-query` 5.104 (dependencia de producción nueva, ≈10 kB gzip de lo que se usa; sin DevTools). `apiFetch` sigue siendo el único cliente (las `queryFn` le pasan el `signal`) y su lógica de sesión no se ha tocado. `lib/queryClient.ts`: `staleTime` 1 min, `gcTime` 5 min, **un** reintento solo en red/5xx (nunca 4xx: un 401 ya pasó por el refresh), mutaciones sin reintento, `networkMode: 'always'`. `lib/queryKeys.ts`: claves tipadas por raíz (`movies`, `series`, `genres`, `favorites`), con los filtros de la URL dentro. Migrados: `usePagedCatalog` (`useInfiniteQuery`; con él la portada, `/peliculas`, `/series`, la fila «Series» y la presencia), `useSeriesDetail`, `useGenres` (una entrada para todas las pantallas), `useAdminSearchList` (`keepPreviousData`) y `FavoritesContext` (mutación optimista con `onMutate`/`onError`/`onSettled`; 409/404 siguen siendo «estado ya correcto»). El panel invalida lo afectado tras cada escritura (`useInvalidateCatalog`) y `AuthProvider` vacía la caché en cada cambio de sesión. Sin migrar, a propósito: `AuthContext`, `SearchBar` y la carga de los formularios de edición (ver cap. 19.3).
- **De paso (revisión de `qa`, gravedad baja):** el aviso del logout trata 502/503/504 como «no hay conexión con el servidor» (es lo que responde el proxy con el backend parado), y la pantalla «No se pudo comprobar tu sesión» tiene ahora `<main id="contenido">` (destino de «Saltar al contenido») y su título como `<h1>` dentro de la alerta.
- **Comprobado en el navegador** (`agent-browser`, pila aislada con H2): ida y vuelta Inicio ↔ Películas sin ninguna petición a `/api`; favoritos optimistas (un solo `POST`, sin recargar la lista) y vuelta atrás con el servidor cortado; editar una película en el panel y verla en `/peliculas` sin recargar; cerrar sesión y entrar con otra cuenta sin ver la lista anterior.
- **Tests:** Vitest de 823 a **859** (`serverCache.test.tsx`, `lib/queryClient.test.tsx`, `lib/queryKeys.test.ts` y casos nuevos en favoritos, `/peliculas`, guardas y logout); un `QueryClient` nuevo por test (`src/test/queryClient.tsx`). E2E 150/150 (`admin-peliculas.spec.ts` comprueba además la invalidación sin recargar).

### 2026-10-09 · Tarea 29 en el backend: token de acceso corto y *refresh token* (ADR 0011)

- **Problema:** el JWT valía 24 h y no se podía revocar; cerrar sesión solo borraba la cookie del navegador.
- **Modelo** (`database`): `V4__create_refresh_tokens.sql`, entidad `RefreshToken` y repositorio con **bloqueo pesimista** (dos refresh simultáneos del mismo token no rotan dos veces; probado contra PostgreSQL real). Solo se guarda el SHA-256 del token, y un `CHECK` impide guardarlo en claro por error.
- **Lógica** (`security`): token de acceso de **15 min** (`jwt.access-token-ttl`, sustituye a `JWT_EXPIRATION_HOURS`) y **HS256 fijo**; *refresh token* opaco en la cookie `streambox_refresh` (`Path=/api/auth`), 7 días y 30 como máximo por sesión; `POST /api/auth/refresh` lo **rota** en cada uso y, si llega uno ya rotado (señal de robo), **revoca toda la sesión**, con 10 s de gracia para pestañas simultáneas; el logout revoca la sesión en el servidor; limpieza programada; `WARN` en `prod` con `Secure=false`; prueba de humo del CI con los atributos de las dos cookies.
- **Revisión de seguridad independiente:** corrigió una **carrera real** (en READ COMMITTED, revocar una sesión mientras otra petición rotaba dejaba vivo el sucesor recién creado: revocación en dos pasadas, reproducido y probado contra PostgreSQL); hizo que un refresh **sin cookie** (cada visita anónima) no gaste el límite por IP; y el logout pasó a exigir `X-Requested-With` (otra web podía cerrarte la sesión haciendo que tu navegador recibiera los `Set-Cookie` de borrado).
- **Revisión de `qa`:** apto; 4 mutaciones detectadas; flujo completo comprobado con `agent-browser` (caducidad, dos pestañas, logout con el backend parado, volver solo con el refresh). Estabilizó `KnownIpLockoutIntegrationTest` (comparaba tiempos reales con ±2 s: ahora reloj fijo y exige la respuesta idéntica completa) y añadió el test de «logout/refresh con la BD caída: 500 sin borrar cookies».
- **Tests:** backend de 1683 a **1810**; E2E 150/150.

### 2026-10-09 · La sesión se renueva sola (frontend del *refresh token*)

- **Problema:** con el token de acceso de 15 minutos, todo usuario habría visto «Tu sesión ha caducado» a los 15 minutos de entrar.
- **Arreglo** (`frontend`, `lib/api.ts`): ante un 401 de una ruta autenticada, `apiFetch` pide `POST /api/auth/refresh` y repite la petición **una** vez. Un solo refresh aunque fallen varias peticiones a la vez (*single-flight*); una «generación» evita renovar otra vez por una petición que salió antes de la renovación; login, logout y refresh van en fila con un Web Lock común a las pestañas (un refresh no puede cruzarse con un logout y resucitar la sesión), y un `BroadcastChannel` avisa a las demás pestañas de cada renovación. Si el refresh da 401 → sesión caducada (aviso único); si falla por red, 5xx o 429 → la sesión **no** se cierra y la pantalla enseña el error con «Reintentar». En el arranque, un 401 de `/users/me` también se intenta renovar: quien vuelve al día siguiente entra sin login. Texto nuevo del chequeo fallido: «No hemos podido confirmar si tu sesión sigue abierta. Inténtalo de nuevo en unos instantes.» (antes culpaba siempre a la conexión).
- **nginx:** `index.html` con `Cache-Control: no-store` (nota de la auditoría 2).
- **Tests:** Vitest de 784 a 823 (`api.test.ts`, `AuthContext.test.tsx`, `RouteGuards.test.tsx`; Web Locks simulados en `src/test/webLocks.ts` y `BroadcastChannel` en memoria en `src/test/setup.ts`); E2E: 5 nuevos, 150 en total (`auth.spec.ts`: renovación sin aviso, «al día siguiente», sin cookies, logout tras renovar y dos pestañas a la vez). Los dos E2E de sesión caducada ponen ahora también un *refresh token* inválido, para seguir probando la caducidad.

### 2026-10-08 · El cierre de sesión ya no miente

- **Problema:** `AuthContext.logout` ponía la interfaz en «sin sesión» antes de `POST /api/auth/logout` y se tragaba el error. Con la sesión en una cookie HttpOnly, JavaScript no puede borrarla: si la petición fallaba, la pantalla decía «sesión cerrada» pero la cookie seguía valiendo y al recargar volvía la sesión (en un equipo compartido, la siguiente persona entraba en la cuenta).
- **Arreglo** (`frontend`, `lib/logout.ts`): primero el servidor. 2xx o 401 → sesión cerrada. Red caída o 5xx → un reintento al segundo y, si vuelve a fallar, la sesión se mantiene y un aviso lo dice («No se ha podido cerrar la sesión: no hay conexión con el servidor. Tu sesión sigue abierta; inténtalo de nuevo.»). Mientras tanto, «Cerrando sesión...» con `aria-disabled` (no `disabled`, para que el foco no salga del menú). Un doble clic no lanza dos peticiones.
- **Tests:** Vitest de 774 a 784; un E2E nuevo en `auth.spec.ts` que corta la red del logout con `page.route` y comprueba que la sesión sigue abierta de verdad, también tras recargar.

### 2026-10-08 · Comprobación de Supabase (hecha por el autor)

Pasos de solo lectura en el SQL Editor de Supabase, sin usar la clave `anon` ni la API REST, para cerrar lo que el código no podía ver (NV-4 de la auditoría 1):

- **RLS:** todas las tablas de `public` (incluida `flyway_schema_history`) con `rowsecurity = true`.
- **Permisos:** ninguno para `anon` ni `authenticated` (`information_schema.role_table_grants` sin filas).
- **Tablas futuras:** los privilegios por defecto del rol de la app no dan acceso a `anon`/`authenticated` (`pg_default_acl`), así que una migración nueva no nace abierta; además, el *callback* de Flyway la cerraría en el siguiente arranque.
- **Data API desactivada:** StreamBox se conecta directamente a PostgreSQL y no usa la API REST de Supabase, así que el riesgo desaparece de raíz.
- Security Advisor sin «RLS Disabled in Public» (los avisos «RLS Enabled No Policy» son esperados: las tablas están cerradas a propósito, sin políticas).

### 2026-10-08 · Lo que destapó el arreglo de NV-A: superficie de entrada y errores

Cada arreglo, revisado por `qa`, encontró el siguiente. Todos tienen un test que falla sin el cambio, la mayoría contra Tomcat real, porque MockMvc no hace el reenvío a `/error`.

- **Solo JSON** (`backend`): `config/JsonOnlyMessageConvertersConfig` quita el conversor YAML que Spring registraba solo por springdoc. Toda la API leía cuerpos YAML; ahora un YAML da 415 (y sigue contando en el límite por IP). `/v3/api-docs.yaml` sigue funcionando.
- **Sin multipart ni `FormContentFilter`** (`backend`): `spring.servlet.multipart.enabled=false` y `spring.mvc.formcontent.filter.enabled=false`. La API no sube archivos ni usa formularios, y un anónimo podía hacer que el servidor analizara 10 MB de multipart (en cualquier ruta pública) o leyera **sin límite y antes de la autenticación** un formulario de `PUT`/`PATCH`/`DELETE` (comprobado con 20 MB).
- **El «401 falso»** (`backend` + `security`): con un token válido, un error que acababa en `sendError` se reenviaba a `/error`, llegaba sin autenticar y el cliente recibía 401, con lo que el frontend cerraba la sesión. Pasaba con un `Accept` que no fuera JSON, con las URL que rechaza el cortafuegos (`;`, `//`, `%2e%2e`) y con cualquier excepción en un filtro. Arreglo:
  - errores de `GlobalExceptionHandler` siempre en JSON (`ErrorCode.NOT_ACCEPTABLE` nuevo, 406);
  - `JsonRequestRejectedHandler` (400 JSON del cortafuegos) y, para las cabeceras que el cortafuegos comprueba tarde (al leerlas ya dentro de Spring MVC), un manejador de `RequestRejectedException` en `GlobalExceptionHandler` (antes, 500 y traza a ERROR desde una ruta pública);
  - despacho `ERROR` permitido en `SecurityConfig` (`GET /error` directo sigue cerrado);
  - `ApiErrorController`, que sustituye al de Spring Boot y responde siempre `ErrorResponse` con la misma tabla de códigos (`GenericHttpError`) y con `nosniff`;
  - `spring.web.error.include-*=never`, porque devtools los pone a `always` en dev. En Spring Boot 4 el prefijo es `spring.web.error`: las `server.error.*` de `application-prod.properties` estaban muertas y se quitaron.
- **`JwtAuthenticationFilter`** (`security`): solo captura `JwtException | IllegalArgumentException`. Una caída de la BD con token válido da 500, no 401 (cierra una nota de las auditorías 1 y 2). El log de token inválido pasa a `DEBUG` con solo la clase de la excepción. `JwtServiceTest` comprueba con 43 tokens hostiles que jjwt no lanza otra cosa.
- **Tests:** backend de 1453 a **1683**, en verde (1552 con H2 y 131 contra PostgreSQL). E2E 144/144.

### 2026-10-08 · Auditoría 2, acotada a la tarea 29

- **Método:** misma skill (`security-audit`), perfil `standard`, sobre el commit `b3ae22f` (cookie HttpOnly + defensa CSRF tras la fusión). 14 agentes (8 cazadores, 4 críticos, 1 verificador y 1 revisor final) y, por primera vez, **ejecución real** en un contenedor Maven sin red, sobre una copia desechable del código (H2). Informe fuera del repositorio: `~/security-audit-skill/streambox/run-2/`.
- **Resultado:** **0 confirmados y 1 pista** (NV-A); validadores de la skill en verde.
- **Sin vías para usar la sesión de la víctima desde otra web ni para leer el token:** `HttpOnly` + `SameSite=Strict` + cabecera obligatoria sin CORS, comprobada después de validar el token. El login CSRF, además, lo impide que el login solo acepte JSON.
- **NV-A · el límite por IP de login y registro contaba peticiones que el servidor rechaza:**
  - **Problema:** una web ajena podía hacer que el navegador de la víctima enviara `POST` «simples» (`text/plain`, formulario) a `/api/auth/login`. El servidor respondía 415, pero `RateLimitingFilter` ya las había contado, y el login legítimo desde esa IP recibía 429 durante la ventana. Reproducido en el lado servidor (`[415 415 415]` → 429 en la misma IP, 204 en otra). Solo disponibilidad, acotada a la ventana.
  - **Arreglo** (`security`, revisado por `qa`): `RateLimitingFilter.countsTowardsLimit` **no cuenta** las peticiones sin `Content-Type`, con uno mal formado o *CORS-safelisted* (`text/plain`, formulario, multipart), y **cuenta todo lo demás**.
  - **Por qué no «contar solo JSON»:** al comprobarlo, el agente vio que Spring también leía el login y el registro en **YAML** (conversor que llegaba con springdoc). Con «solo JSON», un atacante directo habría probado contraseñas y creado cuentas sin límite por IP.
  - **Tests:** `RateLimitingContentTypeIntegrationTest` (39, incluida una «red» que falla si algún conversor aprende a leer esos DTO desde un tipo que no cuenta) y +3 en `RateLimitingFilterTest` (su `request()` lleva ahora `application/json`, como el cliente real). `qa` probó 5 mutaciones (todas detectadas), cabeceras `Content-Type` duplicadas contra Tomcat real y 19 valores raros que el navegador acepta. Backend de 1400 a 1453.
- **Mejora más útil para el usuario que quedó anotada:** el logout que falla abierto (hecho después, el mismo día).

### 2026-10-08 · Auditoría de seguridad completa y corrección de sus 5 pistas

- **Método:** skill `security-audit` (Cloudflare; sustituyó a las de Strix) con el equipo de agentes: 4 de reconocimiento, 20 cazadores, 3 críticos de cobertura, 5 verificadores independientes y 5 revisores finales (37 invocaciones; 5 cazadores murieron por el límite de sesión de la API y se repitieron con agentes nuevos). Informe, evidencia y pruebas de reproducción **fuera del repositorio**, en `~/security-audit-skill/streambox/run-1/`.
- **Resultado:** **0 hallazgos confirmados y 5 pistas** (no se podía ejecutar código con el aislamiento que exige la skill hasta descargar una imagen con JDK y Maven). NV-1 y NV-2 se reprodujeron después en un contenedor sin red. Las 5 se corrigieron:
  - **NV-1 · `HEAD /api/users` saltaba la regla ADMIN.** La regla era `GET /api/users` → ADMIN; `HEAD` caía en «autenticado», Spring MVC lo despachaba al `@GetMapping` y cualquier USER ejecutaba el listado y deducía cuántas cuentas hay por `Content-Length`. Ahora la regla es **por ruta** (cualquier método salvo el `POST` de registro). Lección: una regla atada a un método HTTP deja fuera a los demás.
  - **NV-2 · el bloqueo de cuenta se podía renovar sin fin** (~20 peticiones/h mantenían bloqueado al titular o al único ADMIN). Solución elegida por el autor: **«IP conocida»** (una IP con un login correcto previo tiene su propio contador y no sufre el bloqueo de la cuenta). Clases nuevas `KnownIpRegistry` y `ClientAddress`; `AuthenticationService.login` recibe la IP (`getRemoteAddr()`); propiedades `lockout.max-known-ips=5` y `lockout.known-ip-ttl=30d`.
  - **NV-3 · el registro público podía ocupar el email o el usuario del administrador** antes de crearlo (la app decía «ya existe» sin mirar el rol). Ahora el arranque falla con un mensaje claro si la cuenta no es ADMIN; nunca se promueve.
  - **NV-4 · las tablas de Flyway nacían abiertas a la API pública de Supabase** hasta repetir un script a mano. Ahora un *callback* de Flyway solo para PostgreSQL (`db/callback/postgresql/afterMigrate__close_public_api.sql`, `spring.flyway.locations=…{vendor}`) activa RLS y retira privilegios a `anon`/`authenticated` en cada arranque, solo si esos roles existen; `docs/supabase-seguridad.sql` queda como respaldo idéntico y tolerante a tablas ausentes. Probado contra PostgreSQL real (12 tests), sin tocar nunca Supabase.
  - **NV-5 · los contadores en memoria no tenían tope de claves.** Propiedad `rate-limit.max-keys=100000` por contador (expulsa la clave más antigua; se prefirió a rechazar las nuevas) e IPv6 agrupadas por `/64`.
- **Tests:** backend de 1272 a 1387 (+115, todos en verde, 131 contra PostgreSQL real). Cada protección tiene un test que falla sin ella (mutaciones comprobadas por el agente). Un test inestable (el `timestamp` del cuerpo del 403 mide 20, 24, 27 o 30 bytes según el instante) se encontró y se arregló sin debilitarlo; otro comparaba dos valores `-1` y no probaba nada.
- **Lo que queda:** lo que el código no puede resolver (estado real de Supabase, exposición del puerto, proxy delante) lo tiene que comprobar el propietario: ver [2.4](#24-acciones-tuyas-datos-y-decisiones) y [2.5](#25-seguridad).

### 2026-10-07 · Página de perfil

Solo frontend, con el agente `frontend`; el orquestador verificó después con las suites reales y capturas. Ruta privada nueva `/perfil`, desde «Mi perfil» en el menú de usuario. Diseño tomado de una captura de referencia, pero con los tokens, la tipografía y los componentes de la app.

- **Qué muestra (todo con datos reales):** cabecera (avatar con la inicial, rol, nombre, «Miembro desde mes año», «Panel de administración» solo para ADMIN y «Cerrar sesión»); estadísticas «Películas / Series en mi lista» y «Géneros distintos»; tarjeta «Cuenta» (nombre, correo, contraseña oculta); tarjeta «Tus géneros» (los 6 más frecuentes de la lista, con recuento); y «De tu lista» (hasta 5 títulos, primero películas y luego series, con «Ver toda mi lista»).
- **Qué de la captura NO se hizo y por qué:** «títulos vistos», «horas este mes» y «vistos recientemente» (no hay historial de reproducción), la tarjeta de suscripción y las preferencias de idioma y subtítulos (no existen esos conceptos). **«Editar perfil»** se hizo después, el 2026-10-09 (ver arriba en el historial).
- **Código:** `pages/ProfilePage.tsx`, `lib/profile.ts` (funciones puras: `countGenres`, `pickListPreview`, `formatMemberSince`, `avatarInitial`, `roleLabel`), `components/ExploreLink.tsx` (extraído de «Mi lista» para compartirlo) y prop `compact` en `ErrorState`. Sin peticiones nuevas: el usuario viene de `AuthContext` y las listas de `FavoritesContext`. Si la lista falla o carga, la cuenta sigue visible y solo la tarjeta de géneros lo cuenta.
- **Textos revisados por rol y estado:** con la lista vacía hay un único estado vacío (en «Tus géneros») y «Explorar películas» solo se pinta si hay películas visibles; «De tu lista» solo existe con la lista llena, así que «Ver toda mi lista» nunca lleva a una lista vacía.
- **Tests:** Vitest de 729 a 772; E2E de 135 a 143 (`e2e/profile.spec.ts`, 8, incluido 320/375 px sin scroll horizontal). Cambio legítimo en `accessibility.spec.ts`: el menú de usuario tiene ahora dos acciones, así que el primer Tab llega a «Mi perfil».

### 2026-10-07 · Segunda revisión de diseño

Repite la revisión sobre las pantallas añadidas después de la del 2026-10-03 (series, `/peliculas`, `/perfil`, panel de administración). Hecha por el agente `frontend`. La skill `impeccable` solo aportó su `SKILL.md` (sin detector automático ni `PRODUCT.md`/`DESIGN.md`), así que la auditoría fue manual (WCAG, jerarquía, consistencia, estados vacíos y textos por rol) y **sin revisión visual en navegador**. Sin hallazgos de gravedad alta; los estados vacíos y de error son verdad para USER y ADMIN.

- **Movimiento reducido:** la regla global ya no pone toda transición a ~0 ms. Conserva los fundidos de opacidad y quita lo que mueve (el diálogo ya no crece del 96 % al 100 %, el aviso no sube y el zoom de `PosterCard` no escala). Lo protege `src/index.css.test.ts`.
- **Buscador:** su contorno pasa de ≈1,2:1 a `field-border` (3,91:1 hacia el fondo de la barra y 3,33:1 hacia dentro; WCAG 1.4.11).
- **Etiqueta «Estreno reciente»/«Novedad»:** ahora es el primer `<li>` de la lista de datos (`MetaTags`, prop `badge`); a 375 px quedaba sola en su línea. Lo comprueba `e2e/responsive.spec.ts`.
- **Rejilla del banner:** `bannerGridStyles.ts` (`BANNER_GRID_CLASS`) la comparten `FeaturedBanner` y `CatalogSkeleton`, para evitar un salto de diseño si una cambia.
- **Tests:** Vitest de 772 a 779. Un test se cambió a propósito (`HeroBanner`: la etiqueta es el primer `<li>`).
- Las propuestas no aplicadas y el fallo con Chromium 141 están en [2.8](#28-frontend-y-diseño) y [2.10](#210-tests-herramientas-y-notas-informativas).

### 2026-10-06 · Página «Películas» y estados vacíos por rol

Solo frontend (el backend ya lo soportaba). Funcionamiento en el capítulo 20.8 del manual.

- «Películas» de la barra pasa a ser un enlace a `/peliculas` y se retira `PLANNED_SECTIONS`: ya no queda ninguna sección reservada.
- Misma estructura que `/series` (banner, «Novedades», filas por género, «Cargar más») **más filtros por género, año y orden**, guardados en la URL. Con un filtro, muestra una cuadrícula con recuento, «Cargar más», vacío con «Quitar filtros» y error con «Reintentar». El detalle sigue en el modal.
- Por el camino: un bug propio (el filtro aplicado con Intro se deshacía porque `setSearchParams` cambia de identidad) y un hueco de diseño a 768 px, los dos corregidos con test. Los enlaces «Explorar películas» de «Mi lista» y el vacío de `/series` llevan ahora a `/peliculas`. Se añadieron las capturas de `/series` y `/series/:id`.
- **Estados vacíos por rol** (capítulo 20.9): a raíz de que el autor creó 15 series sin episodios y `/series` le decía «Todavía no hay series…», se revisaron todos: textos distintos para usuario y administrador, ningún botón hacia otra página vacía (`useCatalogPresence`), aviso solo para administradores en la fila «Series» de la portada y textos inexactos corregidos.
- **Tests:** Vitest de 647 a 729; E2E de 112 a 135.

### 2026-10-05/06 · Series (temporadas y episodios)

Con el equipo completo (`database`, `security`, `backend`, `frontend`, `qa`), en 13 fases registradas en un archivo de estado (`ESTADO_SERIES.md`, ya retirado; incluidos tres cortes por el límite de sesión, retomados desde el estado guardado). Funcionamiento en el capítulo 15 bis del manual.

- **Decisiones del autor:** tablas separadas, temporada = número dentro del episodio, página propia por serie, «Mi lista» en dos secciones, fila «Series» en la portada, series sin episodios ocultas a los usuarios, episodios sin imagen propia por ahora.
- **Base de datos:** `V3__create_series.sql` (`series`, `series_genres`, `episodes` con `UNIQUE (series_id, season_number, episode_number)`, `user_favorite_series`; cascadas y `CHECK`); entidades `Series`/`Episode` sin colecciones peligrosas; favoritos nativos.
- **API:** catálogo público (solo series con episodios), detalle con temporadas, vistas de gestión `/api/admin/series` (solo ADMIN), alta, edición y borrado de series y episodios, favoritos de series. `PageableFactory` y `LikePatterns` se extrajeron y se comparten con películas; borrar un género cuenta también las series. Sin N+1 (página ≤4 consultas, detalle 2).
- **Seguridad:** `/api/admin/**` solo ADMIN (se añadió **antes** que los endpoints, para que no nacieran abiertos); escritura de series solo ADMIN y regla de cierre ampliada; revisión IDOR: una serie oculta responde **idéntico** a una inexistente en todas las rutas (hallazgo BAJA corregido en el DELETE de favoritos).
- **Frontend:** componentes del catálogo generalizados (`PosterCard`, `PosterRow`, `FeaturedBanner`, `MetaTags`); `/series` y `/series/:id` con selector de temporada en la URL, fila en la portada, buscador mixto, «Mi lista» en dos secciones; pestaña Series en el panel, con formulario y gestión de episodios en un diálogo.
- **Fallos reales encontrados:** los decimales en campos enteros del JSON se truncaban en silencio (`"genreIds": [5.5]` asignaba **otro género**) → 400 con `accept-float-as-int=false`; el aviso de la acción anterior se colaba en el siguiente diálogo y tapaba sus botones (toda la app) → `ToastContext`; máximo de 20 géneros por título.
- **Tests:** backend de 918 a 1242 sin Docker (1264 con Docker, incluidos los ~30 de series contra PostgreSQL real); Vitest de 444 a 647; E2E de 72 a 112. Mutación hecha por `qa`: los tests detectan si se quita el filtro de visibilidad o la comprobación de serie del episodio. Dos E2E largos (~25 s de 30) se dividieron.
- `docs/supabase-seguridad.sql` incluye ya las 4 tablas nuevas.

### 2026-10-04 · Panel de administración y endurecimiento de la entrada

Con el equipo de agentes (`frontend`, `backend`, `security`, `qa`). Dos agentes se cortaron por el límite de sesión antes de entregar su informe: el orquestador revisó lo que habían dejado y borró un test temporal de depuración.

- **Panel `/admin`** (`pages/admin/`): pestañas Películas | Géneros (enlaces, no `tablist`); tabla con portada, buscador con *debounce* y paginación en la URL; alta y edición en un formulario con vista previa de la portada; borrado con `ConfirmDialog`; géneros con alta, renombrado en línea y borrado (con los 409 `GENRE_ALREADY_EXISTS`/`GENRE_IN_USE`). Enlace «Administrar» solo para administradores; `RequireAdmin` en uso. Validación en cliente (`lib/movieValidation.ts`) idéntica a la del servidor.
- **URLs** (S9): `@HttpsUrl` + `@Size(max = 500)` en `imageUrl`/`videoUrl`; solo `https://` (ASCII, con host, sin credenciales) y, para portadas, `/covers/<archivo>`. Antes `@URL` aceptaba `file:`, `ftp:` y `http:`, y una URL larga daba un 409 engañoso.
- **Contraseñas** (S8): `PasswordPolicy` para cuentas nuevas (12–64 caracteres, ≤72 bytes, no común, ≥5 caracteres distintos, sin usuario ni email de ≥4 caracteres). **Bug real corregido:** una contraseña de 64 caracteres con tildes superaba los 72 bytes de BCrypt y daba **500**. El login no exige mínimo (cuentas antiguas), solo un máximo de 1024.
- **Intentos de login:** el bloqueo ya existía (5 fallos/15 min); ahora el 401 lleva `remainingAttempts` y el fallo que agota los intentos responde 429 `ACCOUNT_LOCKED` (distinto del `RATE_LIMIT_EXCEEDED` por IP), sin revelar qué emails existen. El frontend avisa «Te quedan N intentos…» y muestra la cuenta atrás.
- **QA encontró (y se corrigieron):**
  - **ALTA** (ya existía): el límite por IP de login y registro se saltaba codificando una letra de la ruta (`/api/auth/%6cogin`, `/api/%75sers`), porque el filtro comparaba `getRequestURI()` sin decodificar → ahora usa `PathPatternRequestMatcher`.
  - **MEDIA** (ya existía): el OpenAPI documentaba los errores con el esquema de la respuesta correcta → `ErrorResponseOpenApiCustomizer`.
  - **BAJAS:** falso bloqueo por una carrera entre un fallo y un login correcto (→ `SlidingWindowCounter.confirm`), falsos positivos de la regla de datos personales con 3 caracteres (→ 4), relleno con espacios Unicode que saltaba la lista de comunes y la incoherencia del administrador inicial.
- **Accesibilidad:** el foco al primer error quedaba tapado por la barra fija (WCAG 2.2 · 2.4.11) → `--navbar-height` + `scroll-margin-top`.
- **Tests inestables corregidos** (eran del test, no de la app): un *debounce* probado con tiempo real en Vitest (→ reloj falso, `src/test/fakeTimers.ts`) y un clic de Playwright que caía en otro elemento al desplazarse el formulario (→ `waitForMovieForm`).
- **Tests:** backend de 533 a 918 sin Docker; Vitest de 280 a 444; E2E de 50 a 72. Capturas del panel en `Pruebas/streambox-capturas/panel-admin`.

### 2026-10-04 · Base del panel de administración

Con el equipo de agentes (`frontend`, `security`, `backend`, `qa`).

- **Rol en el frontend:** `AuthContext` carga el usuario con `GET /api/users/me` tras el login, al recargar y al cambiar de sesión desde otra pestaña; expone `user`, `isAdmin`, `userStatus` y `refreshUser()`. Ignora respuestas tardías y falla cerrado (`isAdmin=false` si no se puede comprobar). El menú muestra el nombre y la etiqueta «Administrador». Guarda `RequireAdmin` lista para `/admin`.
- **Seguridad:** `PUT`, `PATCH` y `DELETE /api/genres/**` y `PATCH /api/movies/**` solo ADMIN, y una **regla de cierre del catálogo** (cualquier método que no sea `GET`/`HEAD` sobre películas o géneros, solo ADMIN). **Hallazgo:** sin esto, editar y borrar géneros habría quedado abierto a cualquier usuario autenticado.
- **Géneros:** `PUT /api/genres/{id}` y `DELETE /api/genres/{id}`; 409 `GENRE_ALREADY_EXISTS` (también en el alta, que antes daba un `DATA_INTEGRITY_VIOLATION` genérico) y 409 `GENRE_IN_USE` con el recuento de películas. `CreateGenreRequest` pasa a `record GenreRequest`.
- **QA** encontró 2 fallos de severidad baja, ya corregidos: un nombre que crecía al normalizar daba un 409 engañoso, y se aceptaban nombres de 1 carácter visible o invisibles (espacios duros). Ahora se valida también el nombre normalizado.
- **Tests:** backend de 448 a 533 sin Docker; Vitest de 259 a 280; E2E de 48 a 50.

### 2026-10-03 · Revisión de diseño con las skills

Hecha por el agente `frontend` con las skills `impeccable` (crítica, auditoría y su detector automático), `redesign-skill`, `taste-skill` y `emil-design-eng`. Para poder usarlas se añadió `Skill` al campo `tools` del orquestador y del agente `frontend`. Se conserva la identidad (oscuro + ámbar). Capturas antes/después a 375/768/1280 px en `Pruebas/streambox-capturas/{antes,despues}` (fuera del repositorio).

- **Banner:** el póster va junto al título en todos los anchos (a 1280 px quedaba a ~430 px del texto; en móvil el degradado tapaba medio póster). En móvil, «Ver ahora» y «Mi lista» van en una fila y «Más información» pasa a botón terciario. Velo del fondo al 60 % con el contraste calculado en el peor caso. «Novedades» sube de y≈573 a y≈500 a 1280 px, y en 375 px asoma la primera fila.
- **Metadatos:** fuera los antetítulos que no informaban («PELÍCULA»). «Estreno reciente» pasa a etiqueta; el año y la duración van en texto plano y solo los géneros llevan etiqueta. Sinopsis del modal sin caja.
- **Accesibilidad:** el borde de los campos pasa de 1,47:1 a 3,90:1 (token `field-border`, WCAG 1.4.11).
- **Login y registro** sin tarjeta, con luz ámbar en CSS. **«Mi lista» vacía** con ilustración de huecos de póster. **Esqueleto** de carga en la portada (`CatalogSkeleton`) en lugar del spinner. Opción activa del buscador con contorno en vez de barra lateral.
- **Acabado:** botones que se hunden al pulsar, entrada del modal (200 ms) y de los avisos (250 ms) con transiciones interrumpibles, y selección y cursor en ámbar.
- **Tests:** Vitest de 250 a 259. El E2E responsive exige el póster entero en los tres anchos y como mucho 64 px entre póster y título. Las capturas desactivan las animaciones para no salir a mitad de un fundido.
- **Descartado:** mosaico de pósters en el login (rompería la regla de que las imágenes salen de `imageUrl`), fila montada sobre el banner (riesgo) y cambiar de iconos o de fuente (dependencias nuevas).

### 2026-10-03 · Rediseño visual a partir de capturas reales

- El banner de escritorio parecía una imagen rota (póster vertical recortado en una caja horizontal): se rehízo con fondo desenfocado + póster nítido entero, a sangre y con sinopsis. Lo mismo en el modal de detalle.
- Se eliminaron los paneles bajo el banner (`HeroInfoPanels`: «Reparto: no disponible» parecía trabajo a medias y la «Ficha» repetía datos), así que las filas entran en la primera pantalla.
- Los huecos sin portada tienen un degradado determinista por título. En el registro el error sustituye a la ayuda en vez de duplicarla, y en móvil asoma la tercera tarjeta de cada fila.
- **Tests:** Vitest de 236 a 250; la guarda E2E de imágenes se reformuló (fondo que cubre + póster 2:3 sin recorte) y se comprobó que detecta ambos fallos.
- El autor probó la app sobre Supabase: login, registro, catálogo, búsqueda y «Mi lista» funcionan.

### 2026-10-03 · Migración de la base de datos de desarrollo a Supabase

- **Para qué:** solo desarrollo (la app no se despliega). **Supabase pasa a ser la base de datos de desarrollo** y se deja la local (decisión del autor).
- **Esquema:** se creó arrancando el backend con un perfil `supabase` (Flyway aplicó `V1` y `V2` y Hibernate validó).
- **API pública cerrada** con `docs/supabase-seguridad.sql`: RLS sin políticas y permisos retirados a `anon`/`authenticated` en las 6 tablas. Sin esto, la clave pública podía leer `users`. (Desde el 2026-10-08 lo hace sola un *callback* de Flyway en cada arranque.)
- **Datos importados** en una sola transacción: 4 usuarios (con su hash BCrypt), 3 géneros, 16 películas, 38 asignaciones de género y 3 favoritos, con las secuencias de identidad ajustadas. Verificado: recuentos idénticos, sin años fuera de rango, tildes y eñes intactas. La base local no se modificó.
- **Hallazgo:** tu `movies` local conservaba una columna antigua `release_date` (de antes de Flyway) que no existe en el esquema; se omitió al importar (`release_year` estaba rellena en las 16).
- **Configuración:** las credenciales van en `application-local.properties` (ignorado por git; la plantilla `application-local.properties.example` documenta las dos opciones; **nunca pongas credenciales en el `.example`, que sí se versiona**). `spring-boot:run` a secas ya usa Supabase.
- **Tests:** se comprobó que siguen usando solo H2 (387 en verde con una conexión falsa e inalcanzable en ese archivo).
- **Seguridad:** la contraseña llegó a pasar por la conversación, así que se recomendó **cambiarla** (Project Settings → Database → Reset database password); cuando se comprobó, la guardada ya no era válida, señal de que se rotó.

### Entorno de trabajo con Claude Code

- `CLAUDE.md` (contexto y reglas del proyecto) y los agentes `backend`, `database`, `security`, `qa` y `frontend` en `.claude/agents/`.
- Agente `orchestrator` como **sesión principal** (`.claude/settings.json` → `"agent": "orchestrator"`): en Claude Code un subagente no puede lanzar a otros, así que solo puede delegar si dirige la sesión. Divide las peticiones, delega en el especialista de cada área (las de una sola área van directas a su agente), verifica con la suite real y actualiza el plan.
- Fronteras entre agentes aclaradas (`database` es dueño de entidades, migraciones y `@Query`); todos devuelven un apartado «Peticiones para otros agentes».

### Otras correcciones por el camino

- Bug: el login no normalizaba el email (registrarse con `Foo@x.com` impedía entrar escribiéndolo igual).
- El login con un usuario inexistente compara contra un hash falso (reduce la diferencia de tiempo que delata qué emails existen; S6 parcial).
- `SecurityErrorResponseWriter` elimina el código duplicado de los manejadores de seguridad.
- Un test cuenta las sentencias SQL por página (Hibernate Statistics) y falla si reaparece el problema N+1 (comprobado desactivando el *batch fetch*: 7 consultas frente a ≤3).
