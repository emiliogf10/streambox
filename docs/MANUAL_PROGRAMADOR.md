# Manual del programador — StreamBox

Este manual explica **cómo funciona por dentro** cada parte de StreamBox: qué ocurre cuando arranca la aplicación, qué recorrido hace una petición desde que el navegador la envía hasta que la base de datos responde, y por qué el código está escrito como está.

No sustituye al código ni a su Javadoc: los complementa. El Javadoc explica cada clase por separado; este manual explica **cómo encajan todas**.

> **Cómo leerlo.** Los capítulos 1 a 3 dan la visión general: léelos primero. Los demás se pueden consultar sueltos. Al final hay recetas («cómo añado un endpoint», «cómo añado una columna») y un glosario.
>
> Las rutas de archivo son relativas a la raíz del repositorio. El paquete Java base es `com.emilio.streambox` y está en `streambox/src/main/java/com/emilio/streambox/`; en el texto se abrevia como `…/`.

---

## Índice

1. [Visión general](#1-visión-general)
2. [Recorrido completo de una petición](#2-recorrido-completo-de-una-petición)
3. [Qué pasa al arrancar el backend](#3-qué-pasa-al-arrancar-el-backend)
4. [Configuración, perfiles y variables de entorno](#4-configuración-perfiles-y-variables-de-entorno) (incluye 4.6 Docker y CI)
5. [Base de datos y Flyway](#5-base-de-datos-y-flyway)
6. [Entidades JPA](#6-entidades-jpa)
7. [Repositorios: cómo se generan las consultas](#7-repositorios-cómo-se-generan-las-consultas)
8. [Transacciones, carga diferida y el problema N+1](#8-transacciones-carga-diferida-y-el-problema-n1)
9. [Seguridad (I): la cadena de filtros](#9-seguridad-i-la-cadena-de-filtros)
10. [Seguridad (II): JWT, login y contraseñas](#10-seguridad-ii-jwt-login-y-contraseñas)
11. [Seguridad (III): rate limiting y bloqueo de cuentas](#11-seguridad-iii-rate-limiting-y-bloqueo-de-cuentas)
12. [Seguridad (IV): roles, administrador y «mis» recursos](#12-seguridad-iv-roles-administrador-y-mis-recursos)
13. [Controladores, validación y DTOs](#13-controladores-validación-y-dtos)
14. [Catálogo: paginación, orden y búsqueda](#14-catálogo-paginación-orden-y-búsqueda)
15. [Favoritos («Mi lista»)](#15-favoritos-mi-lista)
    - 15 bis. [Series: temporadas y episodios](#15-bis-series-temporadas-y-episodios)
16. [Gestión de errores](#16-gestión-de-errores)
17. [Actuator, Swagger y logs](#17-actuator-swagger-y-logs)
18. [Frontend: arquitectura](#18-frontend-arquitectura)
19. [Frontend: cliente de API y sesión](#19-frontend-cliente-de-api-y-sesión)
20. [Frontend: pantallas y estado](#20-frontend-pantallas-y-estado)
21. [Frontend: accesibilidad, estilos e imágenes](#21-frontend-accesibilidad-estilos-e-imágenes)
22. [Tests](#22-tests)
23. [Recetas](#23-recetas)
24. [Glosario](#24-glosario)

---

## 1. Visión general

StreamBox es una aplicación tipo Netflix con dos programas independientes que se hablan por HTTP:

| Pieza | Tecnología | Carpeta | Puerto en desarrollo |
| :--- | :--- | :--- | :--- |
| **Backend** (API REST) | Java 21, Spring Boot 4.1, Spring Security, Spring Data JPA (Hibernate), Flyway | `streambox/` | 8080 |
| **Base de datos** | PostgreSQL (H2 en memoria en los tests) | — | 5432 |
| **Frontend** (SPA) | React 19, TypeScript, Vite, Tailwind CSS v4, React Router | `frontend/` | 5173 |

```
 Navegador ──► Vite (5173) ──/api/*──► Spring Boot (8080) ──JDBC──► PostgreSQL (5432)
   React          proxy                  API REST + JWT               tablas + Flyway
```

El navegador **nunca habla directamente** con el puerto 8080. Vite actúa de proxy: todo lo que empieza por `/api` lo reenvía al backend (`frontend/vite.config.ts`). Así, para el navegador frontend y API están en el mismo origen y no hace falta configurar CORS.

### 1.1 Las capas del backend

El backend sigue una arquitectura en capas. Cada capa solo habla con la de debajo:

```
controller   → recibe HTTP, valida, devuelve DTOs              (…/controller)
   │
service      → lógica de negocio y transacciones                (…/service)
   │
repository   → acceso a datos (Spring Data JPA)                 (…/repository)
   │
entity       → clases que representan tablas (JPA)              (…/entity)
```

Y alrededor, piezas de apoyo:

| Paquete | Para qué sirve |
| :--- | :--- |
| `dto` | Objetos que entran y salen por la API (nunca se exponen las entidades) |
| `mapper` | Convierten entidad ⇄ DTO |
| `specification` | Filtros dinámicos para las búsquedas |
| `security` | Autenticación JWT, autorización, rate limiting, administrador inicial |
| `exception` | Excepciones de dominio y el manejador global de errores |
| `config` | Configuración de OpenAPI (Swagger) |

**¿Por qué tantas capas?** Porque cada una cambia por motivos distintos. Si mañana la API devuelve un campo más, solo cambian el DTO y el mapper; si cambia la base de datos, solo cambian entidad y migración. El controlador no sabe nada de SQL y el repositorio no sabe nada de HTTP.

---

## 2. Recorrido completo de una petición

Sigamos una petición real de principio a fin: el frontend pide la primera página del catálogo, de la película más reciente a la más antigua.

```
GET /api/movies?page=0&size=20&sort=createdAt&direction=desc
Cookie: streambox_token=eyJhbGciOiJIUzI1NiJ9...
```

(El navegador añade la cookie solo; un cliente que no sea el navegador, como Swagger o un script, puede enviar en su lugar `Authorization: Bearer <token>`.)

**1. El navegador.** `useCatalog` (frontend) llama a `apiFetch('/movies', { params: {...} })`. `apiFetch` hace el `fetch` con `credentials: 'same-origin'`: el navegador adjunta la cookie `streambox_token` por su cuenta, y JavaScript nunca ve el token.

**2. Vite.** Ve que la ruta empieza por `/api` y la reenvía a `http://localhost:8080`.

**3. Tomcat** (el servidor web que Spring Boot lleva dentro) recibe la petición y la pasa por la **cadena de filtros**.

**4. `RateLimitingFilter`.** Solo actúa en `POST /api/auth/login` y `POST /api/users` (los reconoce con el mismo tipo de comparador de rutas que la autorización; capítulo 11). Esta petición es un `GET`, así que la deja pasar.

**5. `JwtAuthenticationFilter`.** Lee la cabecera `Authorization`, valida el token (firma, caducidad, emisor), saca el email, busca el usuario en la base de datos y lo deja «apuntado» en el `SecurityContext` como usuario autenticado con su rol.

**6. Autorización.** Spring Security consulta las reglas de `SecurityConfig`: `GET /api/movies/**` exige estar autenticado. Lo está, así que pasa.

**7. `DispatcherServlet`** (el corazón de Spring MVC) busca qué método atiende `GET /api/movies`: es `MovieController.getMovies`. Convierte los parámetros de la URL en `int page`, `int size`, `String sort`, `String direction` y valida `@Min`/`@Max`.

**8. `MovieController.getMovies`** llama a `buildPageable`, que comprueba que `sort` está en la lista blanca, interpreta `direction`, y construye un `PageRequest` ordenado por `createdAt DESC, id DESC`.

**9. `MovieService.getMovies`** abre una **transacción de solo lectura** y llama a `movieRepository.findAll(pageable)`.

**10. Hibernate** genera y ejecuta el SQL. Para una página hacen falta, como mucho, **3 consultas**:

```sql
-- 1) la página
select ... from movies m order by m.created_at desc, m.id desc offset 0 rows fetch first 20 rows only;
-- 2) el total, para saber cuántas páginas hay
select count(m.id) from movies m;
-- 3) los géneros de las 20 películas de golpe (carga por lotes, capítulo 8)
select ... from movie_genres mg join genres g ... where mg.movie_id in (?, ?, ..., ?);
```

**11. El mapeo.** Todavía dentro de la transacción, cada `Movie` se convierte en un `MovieResponse` (`MovieMapper.toResponse`). Es aquí donde se leen los géneros, y por eso tiene que ser dentro de la transacción.

**12. La respuesta.** El controlador envuelve la página en `MoviePageResponse` y Spring la serializa a JSON con Jackson:

```json
{
  "content": [
    { "id": 25, "title": "Dune: Parte Dos", "description": "...", "duration": 166,
      "releaseYear": 2024, "imageUrl": "...", "videoUrl": "...",
      "createdAt": "2026-10-01T18:22:10.123Z",
      "genres": [ { "id": 2, "name": "Aventura" }, { "id": 1, "name": "Ciencia ficción" } ] }
  ],
  "page": 0, "size": 20, "totalElements": 25, "totalPages": 2,
  "hasNext": true, "hasPrevious": false
}
```

**13. De vuelta en el navegador**, `apiFetch` comprueba `res.ok`, interpreta el JSON y se lo da a `useCatalog`, que lo guarda en el estado de React. La portada se vuelve a pintar.

Si algo falla en cualquier punto (token caducado, parámetro inválido, error de base de datos), la petición no sigue: se corta y se devuelve un error JSON con un formato común (capítulo 16).

---

## 3. Qué pasa al arrancar el backend

Cuando ejecutas `.\mvnw.cmd spring-boot:run` desde `streambox/`, ocurre esto, en orden:

1. **`StreamboxApplication.main`** arranca Spring Boot. La clase lleva dos anotaciones importantes:
   - `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`: Spring Security, por defecto, crea un usuario `user` con una contraseña aleatoria que imprime en la consola. No lo queremos (los usuarios están en nuestra base de datos), así que se excluye.
   - `@ConfigurationPropertiesScan`: busca los `record` anotados con `@ConfigurationProperties` (`JwtProperties`, `AdminProperties`, `RateLimitProperties`) y los rellena con los valores de los `.properties`.

2. **Se carga la configuración.** Primero `application.properties` (común), luego el del perfil activo (`application-dev.properties` por defecto) y, si existe, `application-local.properties` con tus credenciales (capítulo 4).

3. **Se valida la configuración.** Si `JWT_SECRET` falta, está vacío, es un marcador sin resolver (`${JWT_SECRET}`) o tiene menos de 32 caracteres, **la aplicación no arranca**, y el error explica en español qué falta y cómo generarlo (`openssl rand -base64 48`). Es mejor fallar al arrancar que descubrir el problema al primer login.

   La comprobación está en el constructor del `record` `JwtProperties` y **no** con Bean Validation (`@Size`). Con `@Size`, Spring Boot imprimía el valor rechazado en el log (`Value: "..."`), y ese valor podía ser un secreto real mal copiado; los logs suelen acabar en sistemas con más acceso que el propio secreto. El mensaje nunca incluye el valor ni su longitud. Por la misma razón, `JwtProperties` y `AdminProperties` ocultan los secretos en `toString()`.

4. **Se conecta a la base de datos** y **Flyway** aplica las migraciones pendientes (`V1`, `V2`…). Ver capítulo 5.

5. **Hibernate valida el esquema** (`ddl-auto=validate`): comprueba que cada entidad Java tiene su tabla y sus columnas con los tipos correctos. Si no coinciden, **la aplicación no arranca**. Hibernate no crea ni modifica tablas: eso es cosa de Flyway.

6. **Se crean los beans** (los objetos que gestiona Spring): servicios, repositorios, controladores, filtros… Spring los conecta entre sí por **inyección por constructor**: si `MovieService` necesita un `MovieRepository`, lo pide en su constructor y Spring se lo pasa.

7. **Se construye la cadena de seguridad** (`SecurityConfig.securityFilterChain`).

8. **`AdminAccountInitializer`** se ejecuta (implementa `ApplicationRunner`, que Spring llama justo después de arrancar). Si `ADMIN_EMAIL` y `ADMIN_PASSWORD` están definidas, crea el administrador si no existe (capítulo 12).

9. **Tomcat empieza a escuchar** en el puerto 8080.

> **Los repositorios no tienen implementación.** `MovieRepository` es una `interface` y nadie escribe una clase que la implemente. Al arrancar, Spring Data genera una implementación en memoria a partir de los nombres de los métodos y las anotaciones (capítulo 7).

---

## 4. Configuración, perfiles y variables de entorno

### 4.1 Los archivos

Todos en `streambox/src/main/resources/`:

| Archivo | Cuándo se usa | Contenido |
| :--- | :--- | :--- |
| `application.properties` | Siempre | Lo común: URL de PostgreSQL, Flyway, JPA, JWT, límites, Actuator |
| `application-dev.properties` | Perfil `dev` (por defecto) | SQL visible en la consola, Swagger activado |
| `application-prod.properties` | Perfil `prod` | Credenciales por variables de entorno, sin Swagger, sin SQL en logs, logs en JSON |
| `application-local.properties` | Si existe (está en `.gitignore`) | **Tu** conexión a la base de datos (Supabase o PostgreSQL local). Plantilla: `application-local.properties.example` |
| `src/test/resources/application-test.properties` | Perfil `test` (los tests) | H2 en memoria, secreto JWT fijo, límites muy altos |

`spring.profiles.default=dev` hace que, si no indicas perfil, se use `dev`. Para producción: `SPRING_PROFILES_ACTIVE=prod`.

`spring.config.import=optional:classpath:application-local.properties` es lo que carga tus credenciales. El `optional:` significa que, si el archivo no existe, no pasa nada.

### 4.2 Cómo funcionan los `${...}`

```properties
jwt.secret=${JWT_SECRET}
jwt.expiration-hours=${JWT_EXPIRATION_HOURS:24}
```

`${JWT_SECRET}` toma el valor de la variable de entorno `JWT_SECRET`. Lo que va detrás de los dos puntos es el **valor por defecto**: si `JWT_EXPIRATION_HOURS` no existe, vale 24.

Además, Spring Boot tiene *relaxed binding*: una variable de entorno `JWT_SECRET` también rellena la propiedad `jwt.secret` aunque no hubiera `${...}`. Las variables de entorno **tienen más prioridad** que los `.properties`. (Esto explica por qué un test que comprueba «sin secreto la app no arranca» fallaba en tu máquina: tenías `JWT_SECRET` definida. Está resuelto en `JwtPropertiesValidationTest`.)

### 4.3 Propiedades que leen nuestras clases

En vez de leer valores sueltos con `@Value`, el proyecto agrupa la configuración en `record`s tipados:

| Prefijo | Clase | Qué controla |
| :--- | :--- | :--- |
| `jwt.*` | `security/JwtProperties` | Secreto (≥32 caracteres) y horas de validez del token |
| `streambox.admin.*` | `security/AdminProperties` | Email, usuario y contraseña del administrador inicial |
| `streambox.security.rate-limit.*` | `security/ratelimit/RateLimitProperties` | Límites de login, registro y bloqueo de cuentas |

Las duraciones se escriben como `1m`, `1h`, `15m` y Spring las convierte en `java.time.Duration` automáticamente.

### 4.4 Variables de entorno

| Variable | Obligatoria | Uso |
| :--- | :--- | :--- |
| `JWT_SECRET` | Sí | Secreto de firma de los tokens (≥32 caracteres). Genera uno: `openssl rand -base64 48` |
| `JWT_EXPIRATION_HOURS` | No (24) | Validez del token |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | No | Crean el primer administrador. Al crearlo, la contraseña debe cumplir la política del registro (capítulo 10.5); si ya existe, no se valida |
| `ADMIN_USERNAME` | No (`admin`) | Nombre del administrador |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Solo en `prod` | Conexión a la base de datos |
| `SPRING_PROFILES_ACTIVE` | No | `prod` en producción |

### 4.5 Base de datos de desarrollo: Supabase (o PostgreSQL local)

Supabase es PostgreSQL gestionado, así que el backend funciona sin cambios de código: solo cambia a qué base de datos se conecta. La conexión se define en **`streambox/src/main/resources/application-local.properties`**, el archivo que Spring importa siempre (`spring.config.import` en `application.properties`) y que git ignora. No hace falta activar ningún perfil.

1. Copia `application-local.properties.example` como `application-local.properties` (misma carpeta).
2. Deja la **opción A** (PostgreSQL local, la URL por defecto es `localhost:5432/streambox`) o sustitúyela por la **opción B** (Supabase) con los valores del botón **Connect** del panel.
3. Arranca como siempre: `.\mvnw.cmd spring-boot:run`.

Funciona porque lo importado desde `application.properties` sobrescribe sus valores por defecto (sección 4.1). Las variables de entorno `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` ganan a todo (sección 4.2): es lo que usan los tests E2E para apuntar a su propia base y lo que debe usarse para cualquier prueba, porque **arrancar la app sin más se conecta a la base real**.

**Los tests nunca tocan esa base.** Algunos borran tablas enteras al empezar y al terminar, así que se comprobó: con una conexión falsa e inalcanzable en `application-local.properties`, los 387 tests con H2 pasan. Las propiedades de `application-test.properties` ganan a las del archivo local.

Puntos que conviene entender:

- **Pooler.** Usa la cadena *Session pooler* (puerto **5432**). El *Transaction pooler* (puerto 6543) no admite las sentencias preparadas que usa Hibernate. La conexión directa suele ser solo IPv6 y puede no funcionar en tu red.
- **SSL.** `sslmode=require` es obligatorio: Supabase no acepta conexiones sin cifrar.
- **Usuario.** Con el pooler es `postgres.<id-del-proyecto>`, no solo `postgres`.
- **El esquema lo crea Flyway** al arrancar contra la base vacía (aplica `V1` y `V2`), igual que en local.
- **Seguridad: la API pública de Supabase.** Supabase publica automáticamente una API REST sobre el esquema `public`, accesible con una clave pública. Como las tablas viven ahí, **hay que cerrarla** ejecutando [`docs/supabase-seguridad.sql`](supabase-seguridad.sql) una vez creadas las tablas (activa RLS sin políticas y retira permisos a `anon` y `authenticated`). La aplicación no se ve afectada porque conecta con el rol `postgres`, que se salta RLS. No es una migración de Flyway porque `ENABLE ROW LEVEL SECURITY` es específico de PostgreSQL y H2 (los tests) no lo entiende. Si una migración futura crea tablas nuevas, hay que añadirlas a ese script.
- **Mover los datos** de una base a otra: ver `docs/PLAN_DE_ACCION.md` (migración a Supabase). Los `id` se conservan, y por eso hay que comprobar que las secuencias de identidad quedan por encima del mayor `id` (si no, el siguiente `INSERT` chocaría con una fila existente).

### 4.6 Docker y CI

Con Docker, `docker compose up -d --build` levanta la aplicación completa en `http://localhost:8088`. Uso básico en el README; aquí, cómo está montado y por qué.

#### Las imágenes

- **Backend** (`streambox/Dockerfile`), en dos etapas:
  1. **Compilación** con Maven + JDK 21. Primero se copia solo `pom.xml` y se descargan las dependencias en una capa propia (`dependency:go-offline`); así, un cambio de código no vuelve a descargarlas. Se compila **sin tests** (`-Dmaven.test.skip=true`), porque los tests van en el CI.
  2. **Ejecución** con un JRE 21 Alpine. El JAR se extrae en capas (`-Djarmode=tools extract`): `lib/`, 65 MB que casi nunca cambian, y `app.jar`, 185 kB. Un cambio de código solo cambia una capa pequeña.

  La aplicación corre con un **usuario sin privilegios** (UID 10001), perfil `prod`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` y un `HEALTHCHECK` contra `/actuator/health/readiness`. Imagen: unos 270 MB.
- **Frontend** (`frontend/Dockerfile`):
  1. Compila con Node 24. Usa `npm ci --ignore-scripts`: los `postinstall` son la vía típica de los ataques a la cadena de suministro y aquí no hacen falta. Después, `npm run build`.
  2. Copia `dist/`, con las portadas, a `nginxinc/nginx-unprivileged`: sin root (UID 101) y en el puerto 8080. La imagen oficial de nginx arranca como root y no funcionaría con `cap_drop: ALL`. Imagen: unos 80 MB.
- **Listas blancas en `.dockerignore`.** Solo entra lo imprescindible (`pom.xml` y `src/main`; las fuentes del frontend). Sobre todo, **`application-local.properties` queda fuera**, porque tiene las credenciales de Supabase. Si entrara en la imagen, el contenedor llevaría tus credenciales y se conectaría a tu base de desarrollo. Se comprobó listando el JAR y el sistema de archivos de la imagen. Por la misma razón, `pom.xml` lo excluye también del JAR que genera un `mvnw package` en local (`maven-jar-plugin`), aunque sigue en `target/classes` para `spring-boot:run`, el IDE y los tests.

#### `docker-compose.yml`

- **Servicios:**
  - `db`: `postgres:16.15-trixie`, la misma versión mayor que los tests con Testcontainers. Datos en el volumen `streambox_db-data`, que `docker compose down -v` borra.
  - `backend`: espera a que `db` esté sano.
  - `frontend`: nginx; espera a que el backend esté sano.
- **Redes** (mínimo privilegio):
  - `db-network` (interna): solo `db` y `backend`.
  - `app-network` (interna): `backend` y nginx. Al ser interna, **el backend no tiene salida a Internet**, que no necesita.
  - `edge`: solo nginx, la única no interna, porque Docker solo publica puertos en ese tipo de red.
- **Puertos.** El único publicado es el de nginx: `STREAMBOX_PORT` (8088 por defecto), solo en `127.0.0.1` salvo que cambies `STREAMBOX_BIND_ADDRESS`. No se usan el 8080 ni el 5173, así que no chocan con tu entorno de desarrollo. Para depurar la base, un `docker-compose.override.yml` (ignorado por git) puede publicarla en `127.0.0.1:15432`; hay un ejemplo comentado en el compose.
- **Endurecimiento** de los tres servicios: sin root (`db` con `user: postgres`), `read_only` con `tmpfs` donde hace falta escribir, `cap_drop: ALL`, `no-new-privileges` y límite de memoria.
- **Variables.** Salen de `.env` (ignorado por git; plantilla `.env.example`). Las obligatorias, `POSTGRES_PASSWORD` y `JWT_SECRET`, van **vacías a propósito** en la plantilla: si se copia sin rellenar, compose se niega a arrancar (`${VAR:?mensaje}`) en lugar de arrancar con un secreto público. `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` se construyen a partir de `POSTGRES_*`. **Las variables de la terminal ganan al `.env`.** Los secretos se ven en `docker inspect`; la mejora sería usar *Docker secrets*.

#### nginx (`frontend/nginx/default.conf`)

Es la única puerta de entrada.

- **SPA:** fallback a `index.html`, para que recargar `/series/7` funcione.
- **Proxy de `/api/`** a `backend:8080`. Es el mismo origen, así que no hace falta CORS, igual que con el proxy de Vite en desarrollo. Usa `resolve`, para seguir funcionando si el backend se reinicia y cambia de IP, y `proxy_connect_timeout 5s`, para que un backend caído dé error enseguida y no a los 60 s.
- **`X-Forwarded-For` se fija con la IP real** (`$remote_addr`). El backend, en el perfil `prod` con `forward-headers-strategy=native`, limita los logins por esa IP. Si se usara `$proxy_add_x_forwarded_for`, un atacante podría inventarse una IP en cada petición. Se comprobó:
  - directo al backend, con una IP falsa distinta en cada petición, 13 de 13 intentos se saltan el límite;
  - a través de nginx, el 10.º intento ya da 429.
- **Caché:** `index.html` con `no-cache`; `/assets/` (nombres con hash) un año e `immutable`; `/covers/` un día. Un recurso que no existe da 404, no `index.html`.
- **No se publica:** `/actuator` no es accesible desde fuera, y las rutas con `;` dan 400. El `;` es un truco clásico para saltarse reglas de seguridad por ruta en Java.
- **Cabeceras de seguridad**, en todas las respuestas (`always`), declaradas una sola vez en el `server`. Si un `location` tuviera su propio `add_header`, dejaría de heredarlas.
  - Una **CSP** estricta: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. Es la segunda línea de defensa contra XSS: aunque se colara HTML, el navegador no ejecutaría scripts en línea, y un script colado no podría mandar el token de `localStorage` a otro servidor.
  - `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, una `Permissions-Policy` restrictiva y `Cross-Origin-Opener/Resource-Policy: same-origin`.
  
  Se comprobó recorriendo toda la app en Chromium: **0 violaciones**, y un script en línea de control quedó bloqueado. La CSP no se aplica en `npm run dev`, porque Vite inyecta código en línea. HSTS está comentado porque por HTTP los navegadores lo ignoran; se activará cuando haya HTTPS.

#### CI (`.github/workflows/ci.yml`)

Se ejecuta en cada `push` a `main`, en cada pull request y a mano. Lanza cuatro jobs en paralelo en `ubuntu-latest`:

| Job | Qué hace |
| :--- | :--- |
| `backend` | `./mvnw -B -ntp verify` con Temurin 21. Los runners traen Docker, así que **los tests de PostgreSQL se ejecutan siempre**. Si falla, sube los informes de surefire |
| `frontend` | `npm ci --ignore-scripts`, `build`, `lint` y `test` con Node 24 |
| `e2e` | Playwright con su propio backend H2 (8099) y Vite (5199), y caché de los navegadores. Si falla, sube el informe |
| `docker` | Construye las dos imágenes (caché `type=gha`, sin publicarlas en ningún registro), levanta el stack con un `.env` de secretos aleatorios (`openssl rand`, enmascarados en el log) y comprueba con `curl` la CSP, el 401 de la API y el login del administrador. Siempre termina con `down -v` |

Ningún job usa secretos del repositorio y el token solo tiene `contents: read`. Las *actions* van fijadas **por SHA** y no por etiqueta, porque una etiqueta se puede mover a código malicioso (ocurrió con `tj-actions` en 2025); Dependabot actualiza el SHA y su comentario. La sintaxis se validó con `actionlint`.

#### Dependabot (`.github/dependabot.yml`)

Cada lunes revisa Maven, npm, las *actions*, las imágenes de los Dockerfile y la de PostgreSQL del compose.

- **Agrupa** las versiones menores y los parches para no llenar el repositorio de pull requests. Las mayores llegan en su propio pull request, y el CI dice cuánto rompen.
- **Ignora las mayores** que el CI no puede validar o que exigen cambios coordinados:
  - **PostgreSQL**: el CI arranca con una base vacía y daría verde mientras tu volumen real no arrancaría, porque pasar de una mayor a otra exige `pg_upgrade`.
  - **Java, Node, Maven y `@types/node`**: su versión está repetida en varios archivos.
- Espera **7 días** antes de proponer una versión nueva de npm, Maven o una *action*, por si la versión resulta secuestrada.

---

## 5. Base de datos y Flyway

### 5.1 Las tablas

```mermaid
erDiagram
    users ||--o{ user_favorite_movies : "tiene en su lista"
    movies ||--o{ user_favorite_movies : "está en listas"
    movies ||--o{ movie_genres : "tiene"
    genres ||--o{ movie_genres : "clasifica"

    users { bigint id PK
            varchar username UK
            varchar email UK
            varchar password
            varchar role
            timestamptz created_at }
    movies { bigint id PK
             varchar title
             varchar description
             int duration
             int release_year
             varchar image_url
             varchar video_url
             timestamptz created_at }
    genres { bigint id PK
             varchar name UK }
    movie_genres { bigint movie_id PK
                   bigint genre_id PK }
    user_favorite_movies { bigint user_id PK
                           bigint movie_id PK }
```

Hay dos relaciones **muchos a muchos**, y cada una necesita una **tabla de unión**:

- `movie_genres`: una película tiene varios géneros y un género tiene varias películas.
- `user_favorite_movies`: la «Mi lista» de cada usuario.

En las tablas de unión, la **clave primaria es la pareja** `(movie_id, genre_id)` o `(user_id, movie_id)`. Eso hace imposible, a nivel de base de datos, que una película esté dos veces en la lista del mismo usuario.

Las **series** (migración `V3`) tienen tablas propias con la misma estructura: `series`, `series_genres`, `episodes` y `user_favorite_series`. Se explican en el capítulo 15 bis.

### 5.2 Las restricciones (y por qué están en la base de datos)

Están en `V1__create_schema.sql`:

| Restricción | Qué impide |
| :--- | :--- |
| `UNIQUE (username)`, `UNIQUE (email)`, `UNIQUE (name)` en géneros | Duplicados |
| `CHECK (role IN ('USER','ADMIN'))` | Roles inventados |
| `CHECK (duration > 0)`, `CHECK (release_year BETWEEN 1888 AND 2100)` | Datos absurdos |
| `NOT NULL` en todas las columnas | Campos vacíos |
| `FOREIGN KEY ... ON DELETE CASCADE` | Al borrar una película o un usuario, desaparecen sus filas en las tablas de unión |
| `FOREIGN KEY` sin cascada en `movie_genres.genre_id` | No se puede borrar un género que alguna película usa |

**¿Por qué en la base de datos si Java ya valida?** Porque Java solo valida lo que entra por la API. Un script SQL, otra aplicación o un bug saltarían esas validaciones; la base de datos es la última defensa y siempre se cumple. Además, la base de datos resuelve bien las **condiciones de carrera** (dos peticiones a la vez), cosa que una comprobación en Java no puede (capítulo 15).

### 5.3 Los índices

Una clave primaria compuesta `(user_id, movie_id)` sirve para buscar por `user_id`, pero **no** por `movie_id` solo. Por eso V1 crea índices para las búsquedas por la segunda columna:

| Índice | Para qué consulta |
| :--- | :--- |
| `idx_movie_genres_genre_id` | Filtro por género en `/api/movies/search?genreId=` |
| `idx_favorites_movie_id` | Borrar una película (encontrar sus favoritos) |
| `idx_movies_release_year` | Filtro por año |

### 5.4 Flyway: cómo se crea y evoluciona el esquema

Flyway es una herramienta que ejecuta scripts SQL **en orden y una sola vez**. Los scripts están en `streambox/src/main/resources/db/migration/` y se llaman `V<número>__<descripción>.sql`.

Al arrancar, Flyway:

1. Mira la tabla `flyway_schema_history` de la base de datos, donde apunta qué migraciones ya aplicó.
2. Ejecuta, en orden, las que falten.
3. Guarda en el historial cada migración aplicada junto a un **checksum** (una huella de su contenido).

**Regla de oro: nunca edites una migración ya aplicada**, ni siquiera un comentario. Flyway recalcula el checksum, ve que no coincide con el guardado y se niega a arrancar. Para cambiar algo, crea una migración nueva (`V3__...`).

#### Adopción de bases antiguas (baseline)

Antes de Flyway, Hibernate creaba las tablas solo (`ddl-auto=update`). Para que esas bases antiguas sigan funcionando:

```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
```

Si Flyway encuentra una base **con tablas pero sin historial**, la marca como «versión 0» (baseline) y aplica V1 y V2 encima. Por eso V1 usa `CREATE TABLE IF NOT EXISTS` y `CREATE INDEX IF NOT EXISTS`: sobre una base antigua no falla, solo añade lo que falte.

> **Matiz importante.** En una base antigua, V1 solo añade los **índices**. Las tablas ya existían, así que `IF NOT EXISTS` no las toca y conservan sus claves foráneas **sin `ON DELETE CASCADE`** y **sin los `CHECK`**. Por eso `MovieService.deleteMovie` sigue borrando a mano los favoritos antes de borrar la película (`deleteFromAllFavorites`). Los tests de `PostgresBaselineAdoptionIntegrationTest` lo comprueban.

#### V2: fechas con zona horaria

V1 creó `created_at` como `TIMESTAMP` (sin zona). V2 lo cambia a `TIMESTAMP WITH TIME ZONE`, que guarda un **instante absoluto**. En Java se usa `Instant` (capítulo 6). Al convertir datos antiguos, PostgreSQL interpreta las horas existentes en la zona horaria de la sesión: `22:00` en Madrid queda como `20:00Z`.

### 5.5 SQL portable

Los tests usan **H2** (una base de datos en memoria) en «modo PostgreSQL», así que las migraciones tienen que funcionar en los dos motores. Por eso se usa SQL estándar (`GENERATED BY DEFAULT AS IDENTITY`, `ALTER COLUMN ... SET DATA TYPE`). Si algún día hace falta algo exclusivo de PostgreSQL (índices `pg_trgm`, `unaccent`), solo se podrá probar con los tests de Testcontainers (capítulo 22).

---

## 6. Entidades JPA

Las entidades (`…/entity`) son clases Java que representan filas de una tabla. JPA (la especificación) e Hibernate (la implementación) se encargan de convertir entre objetos y filas.

### 6.1 Anotaciones principales (con `Movie` como ejemplo)

```java
@Entity                       // es una entidad: Hibernate la gestiona
@Table(name = "movies")       // su tabla
@Getter @Setter               // Lombok genera getters y setters al compilar
public class Movie {

    @Id                                                    // clave primaria
    @GeneratedValue(strategy = GenerationType.IDENTITY)    // la genera la base de datos
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @CreationTimestamp                         // Hibernate la rellena al insertar
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany                                // relación muchos a muchos
    @JoinTable(name = "movie_genres",          // a través de esta tabla de unión
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new HashSet<>();
}
```

- **`releaseYear` → `release_year`.** Hibernate convierte los nombres *camelCase* de Java en *snake_case* de SQL automáticamente.
- **`@Enumerated(EnumType.STRING)`** en `User.role` guarda el rol como texto (`'ADMIN'`), no como número. Si se guardara como número (0, 1) y alguien reordenara el `enum`, todos los roles cambiarían sin avisar.
- **`@ManyToMany` es `LAZY` por defecto**: los géneros no se cargan hasta que alguien los lee (capítulo 8).

### 6.2 `Instant` y `@CreationTimestamp`

`Instant` es un momento absoluto en UTC («el 1 de octubre de 2026 a las 18:22:10 UTC»), sin ambigüedad de zona horaria. `LocalDateTime` (lo que se usaba antes) no lleva zona: «las 20:22» no significa nada sin saber dónde. Además, `hibernate.jdbc.time_zone=UTC` obliga a que Hibernate hable con la base de datos siempre en UTC.

`@CreationTimestamp` hace que Hibernate rellene la fecha justo antes del `INSERT`. Con `updatable = false`, nunca se sobrescribe.

### 6.3 `equals` y `hashCode` por id

Las tres entidades implementan la igualdad así:

```java
public boolean equals(Object other) {
    if (this == other) return true;
    return other instanceof Movie that && id != null && id.equals(that.getId());
}
public int hashCode() { return Movie.class.hashCode(); }   // constante
```

**Por qué así:**

- Dos objetos `Movie` que representan la **misma fila** deben ser iguales aunque se hayan cargado en momentos distintos. El `id` identifica la fila.
- Una entidad **aún no guardada** no tiene `id` (`null`) y solo es igual a sí misma.
- Se usa `that.getId()` (el getter) y no `that.id`, porque Hibernate a veces da un **proxy** (un objeto «falso» que carga los datos cuando se tocan) y el campo directo del proxy está vacío.
- **El `hashCode` es constante** porque el `id` cambia de `null` a un número al guardar. Si `hashCode` dependiera del `id`, una película metida en un `Set` antes de guardarla quedaría «perdida» dentro del `Set` después. Un `hashCode` constante es correcto (aunque menos eficiente para colecciones enormes, que aquí no hay).

### 6.4 Lombok

Lombok genera código al compilar a partir de anotaciones: `@Getter`/`@Setter` crean los getters y setters. Lo usan las entidades y dos DTOs antiguos (`CreateUserRequest`, `LoginRequest`). El resto de DTOs son `record`, que ya traen todo esto en el propio lenguaje.

---

## 7. Repositorios: cómo se generan las consultas

Los repositorios (`…/repository`) son **interfaces** que extienden `JpaRepository<Entidad, TipoDelId>`. Solo con eso ya tienes `findAll`, `findById`, `save`, `deleteById`, `existsById`, `count`… sin escribir nada.

Hay **cuatro formas** de añadir consultas, y el proyecto usa todas:

### 7.1 Consultas derivadas del nombre

```java
Optional<User> findByEmail(String email);
boolean existsByUsername(String username);
List<Movie> findAllByTitleIgnoreCase(String title);
```

Spring Data **lee el nombre del método** y genera la consulta: `findByEmail` → `select u from User u where u.email = ?`. `IgnoreCase` añade `upper(...)` a ambos lados. Si escribes un nombre que no corresponde a ningún campo, la aplicación no arranca.

### 7.2 JPQL con `@Query`

```java
@Query("select m from User u join u.favoriteMovies m where u.id = :userId order by m.title, m.id")
List<Movie> findFavoriteMovies(@Param("userId") Long userId);
```

JPQL se parece a SQL pero trabaja con **entidades y atributos Java** (`User`, `favoriteMovies`), no con tablas. Hibernate lo traduce a SQL.

### 7.3 SQL nativo con `@Query(nativeQuery = true)`

```java
@Modifying(flushAutomatically = true)
@Query(value = "INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (:userId, :movieId)",
       nativeQuery = true)
void addFavorite(@Param("userId") Long userId, @Param("movieId") Long movieId);
```

SQL puro, tal cual se envía a la base de datos. Se usa para los favoritos porque es mucho más eficiente que la alternativa JPA (capítulo 15).

- **`@Modifying`** indica que la consulta modifica datos (no es un `select`).
- **`flushAutomatically = true`** hace que Hibernate envíe antes a la base de datos cualquier cambio pendiente en memoria, para que el SQL nativo vea el estado real.

### 7.4 `@EntityGraph`: cargar relaciones de golpe

```java
@Override
@EntityGraph(attributePaths = { "genres" })
Optional<Movie> findById(Long id);
```

Sobrescribe `findById` para que, al cargar **una** película, traiga sus géneros en la misma consulta (`JOIN`). Solo se hace aquí: en los listados paginados **no** se puede (capítulo 8).

### 7.5 `Specification`: filtros dinámicos

`MovieRepository` también extiende `JpaSpecificationExecutor<Movie>`, que añade `findAll(Specification, Pageable)`. Una `Specification` es un trozo de condición `WHERE` que se puede combinar con otros. Se usa para la búsqueda con filtros opcionales (capítulo 14).

---

## 8. Transacciones, carga diferida y el problema N+1

Este capítulo explica tres ideas que están muy relacionadas y que causan la mayoría de problemas de rendimiento y de errores raros con Hibernate.

### 8.1 Transacciones con `@Transactional`

Una transacción agrupa varias operaciones de base de datos para que se apliquen **todas o ninguna**. En Spring se pone `@Transactional` en el método del servicio:

```java
@Transactional                       // escritura: si algo falla, se deshace todo
public MovieResponse createMovie(MovieRequest request) { ... }

@Transactional(readOnly = true)      // lectura: más eficiente, Hibernate no vigila cambios
public Page<MovieResponse> getMovies(Pageable pageable) { ... }
```

Mientras dura la transacción, Hibernate mantiene abierta una **sesión** (el *contexto de persistencia*). Dentro de ella:

- Las entidades están «gestionadas»: si cambias un campo, Hibernate lo detecta y hace el `UPDATE` al confirmar. Por eso `MovieService.updateMovie` **no llama a `save()`**: basta con modificar la entidad.
- Si se lanza una excepción no controlada, la transacción se deshace (*rollback*).

### 8.2 Carga diferida (LAZY) y `open-in-view=false`

Los géneros de una película son `LAZY`: al cargar una `Movie`, Hibernate **no** carga sus géneros. Pone en su lugar una colección «vacía» que, la primera vez que la recorres, lanza la consulta.

Eso solo funciona **mientras la sesión está abierta**. Si intentas leer `movie.getGenres()` después de que termine la transacción, obtienes la temida `LazyInitializationException`.

Spring Boot, por defecto, tiene una opción (`open-in-view`) que mantiene la sesión abierta durante toda la petición HTTP, incluido el controlador y la serialización a JSON. Es cómodo pero peligroso: oculta consultas que se lanzan «sin querer» desde el controlador. En este proyecto está **desactivada**:

```properties
spring.jpa.open-in-view=false
```

**Consecuencia y regla del proyecto:** los servicios devuelven **DTOs, nunca entidades**, y el mapeo a DTO se hace **dentro** del método `@Transactional`. Así todo lo que hay que leer se lee mientras la sesión está abierta, y el controlador recibe un objeto «inerte» que no puede lanzar consultas.

### 8.3 El problema N+1 y la carga por lotes

Imagina una página de 20 películas. Al convertirlas a DTO se leen los géneros de cada una. Sin más configuración, Hibernate haría:

```
1 consulta  → las 20 películas
20 consultas → los géneros de la película 1, de la 2, ..., de la 20
```

Son **N+1** consultas (N = número de películas). Con 100 películas, 101 consultas. Es el problema de rendimiento más típico de los ORM.

**La solución obvia no sirve aquí.** Lo natural sería traer los géneros con un `JOIN FETCH`. Pero en una consulta **paginada** eso no funciona: el `JOIN` multiplica filas (una por cada género) y la base de datos no puede aplicar `LIMIT 20` sobre películas. Hibernate lo resuelve trayendo **todo el catálogo a memoria** y paginando en Java (y avisa con un *warning*). Con un catálogo grande, sería desastroso.

**La solución del proyecto: carga por lotes.**

```properties
spring.jpa.properties.hibernate.default_batch_fetch_size=50
```

Cuando Hibernate necesita los géneros de una película, aprovecha y carga los de **hasta 50 películas** de la sesión con una sola consulta `... where movie_id in (?, ?, ...)`. Resultado: **1 consulta para la página + 1 para el total + 1 para todos los géneros = 3**, da igual que la página tenga 5 o 100 películas.

`CatalogIntegrationTest` cuenta las consultas con las estadísticas de Hibernate y falla si el número crece con el número de películas. Así, si alguien rompe esto sin darse cuenta, un test lo detecta.

---

## 9. Seguridad (I): la cadena de filtros

Spring Security funciona con una **cadena de filtros**: cada petición pasa por una serie de objetos, uno tras otro, y cualquiera puede dejarla pasar, modificarla o cortarla y responder él mismo.

Toda la configuración está en `…/security/SecurityConfig.java`. Los filtros relevantes, en el orden en que actúan:

```
Petición ─► RateLimitingFilter ─► JwtAuthenticationFilter ─► (filtros de Spring) ─► Autorización ─► Controlador
              │ 429 si se pasa       │ identifica al usuario                          │ 401 / 403
```

### 9.1 Lo que configura `securityFilterChain`

| Configuración | Qué hace y por qué |
| :--- | :--- |
| `csrf.disable()` | CSRF es un ataque que aprovecha las **cookies** que el navegador envía solo. Esta API usa un token en la cabecera `Authorization` que el navegador no envía automáticamente, así que CSRF no aplica |
| `SessionCreationPolicy.STATELESS` | No se crean sesiones HTTP (ni cookie `JSESSIONID`). Cada petición se autentica por sí misma con su token. El servidor no recuerda nada entre peticiones |
| `exceptionHandling(...)` | Sustituye las páginas de error HTML de Spring por respuestas JSON (capítulo 16) |
| `authorizeHttpRequests(...)` | Las reglas de acceso por ruta y método (ver abajo) |
| `addFilterBefore(...)` | Inserta nuestros dos filtros en la cadena, en el orden correcto |

### 9.2 Reglas de acceso

Se evalúan **de arriba abajo** y gana la primera que coincide:

| Método y ruta | Quién puede |
| :--- | :--- |
| `POST /api/users`, `POST /api/auth/login` | Cualquiera (registro y login) |
| `/api/admin/**` (cualquier método) | Solo `ADMIN` (vistas de gestión, p. ej. series sin episodios) |
| `GET /api/users` | Solo `ADMIN` |
| `GET`, `HEAD /api/movies/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/movies/**` | Solo `ADMIN` |
| `GET`, `HEAD /api/genres/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/genres/**` | Solo `ADMIN` |
| `GET`, `HEAD /api/series/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/series/**` (incluye los episodios) | Solo `ADMIN` |
| **Cualquier otro método** sobre `/api/movies/**`, `/api/genres/**` y `/api/series/**` (regla de cierre del catálogo) | Solo `ADMIN` |
| `/actuator/health`, `/actuator/health/**` | Cualquiera (comprobaciones de salud) |
| `/v3/api-docs/**`, `/swagger-ui/**` | Cualquiera (en `prod` están desactivados) |
| **Todo lo demás** (`anyRequest()`) | Cualquier usuario autenticado |

La última regla es una red de seguridad: un endpoint nuevo que se olvide de añadir aquí queda **protegido por defecto**, no abierto. Por ejemplo, `/api/users/me` y `/api/users/me/favorites` caen en ella.

**Pero «autenticado» no basta para escribir.** Hasta octubre de 2026 solo había reglas para `GET` y `POST` de géneros, así que `PUT` y `DELETE` habrían caído en `anyRequest().authenticated()`: en cuanto se crearon esos endpoints, **cualquier usuario normal** habría podido editar o borrar géneros. Por eso se añadieron antes que los endpoints, y además una **regla de cierre del catálogo**: cualquier método que no sea lectura (`GET`/`HEAD`) sobre películas o géneros es solo para `ADMIN`. Así, un endpoint de escritura que se añada en el futuro nace protegido aunque se olvide su regla. `HEAD` va con `GET` porque es la misma lectura sin cuerpo (Spring MVC lo atiende con los `@GetMapping`). Lo vigilan `CatalogWriteAuthorizationIntegrationTest` (401/403 en cada método de escritura) y `CatalogClosureRuleRegressionIntegrationTest` (la regla de cierre no bloquea lecturas, endpoints personales, login/registro, Actuator ni Swagger; y un cambio de rol se aplica con el mismo token).

`hasRole("ADMIN")` comprueba que el usuario tiene la autoridad `ROLE_ADMIN`. El prefijo `ROLE_` lo añade el filtro JWT (sección 9.3).

### 9.3 `JwtAuthenticationFilter`: quién eres

Para cada petición:

1. Busca el token: primero la cabecera `Authorization: Bearer` (si existe, **manda ella**; un Bearer inválido da 401 aunque haya cookie válida) y, si no hay, la cookie `streambox_token`. Si no hay ninguna, **no hace nada** y deja pasar la petición (sin autenticar). Login, registro y logout ignoran la cookie para que una vieja no los bloquee.
2. Extrae el token y pide a `JwtService.extractEmail(token)` que lo valide y devuelva el email. Si el token está manipulado, caducado o lo emitió otro sistema, salta una excepción: se registra un `WARN` en el log y la petición sigue **sin autenticar**.
3. **Busca el usuario en la base de datos** por email. Si no existe (por ejemplo, se borró la cuenta), la petición sigue sin autenticar.
4. Crea un `AuthenticatedUser(id, email, role)` y lo guarda en el `SecurityContextHolder` con la autoridad `ROLE_<rol>`.
5. **Defensa CSRF.** Si la autenticación vino de la **cookie** y la petición no es segura (POST/PUT/PATCH/DELETE), exige la cabecera `X-Requested-With: StreamBox`; sin ella responde 403 `CSRF_REJECTED`. Una web ajena no puede poner esa cabecera sin un preflight CORS, y aquí no hay CORS abierto. Con Bearer no hace falta (no es una credencial que el navegador envíe solo).

**¿Por qué consulta la base de datos en cada petición, si el token ya lleva el email?** Porque así los cambios son **inmediatos**: si a un usuario le quitan el rol de administrador o le borran la cuenta, su token sigue siendo válido criptográficamente, pero a la siguiente petición el filtro lee el rol actualizado. Cuesta una consulta por petición (muy barata: busca por un campo `UNIQUE`, que tiene índice).

**¿Por qué `AuthenticatedUser` y no la entidad `User`?** La entidad lleva la contraseña cifrada y colecciones `LAZY`. Guardarla en el contexto de seguridad la expondría por todas partes y podría causar `LazyInitializationException`. `AuthenticatedUser` es un `record` pequeño con solo lo necesario.

**El filtro no rechaza nada.** Solo identifica. El rechazo (401/403) lo decide la etapa de autorización con las reglas de la sección 9.2.

### 9.4 401 frente a 403

| Código | Significa | Quién lo genera |
| :--- | :--- | :--- |
| **401** Unauthorized | «No sé quién eres»: sin token, token inválido o caducado, usuario borrado | `JwtAuthenticationEntryPoint` |
| **403** Forbidden | «Sé quién eres, pero no tienes permiso»: un `USER` intentando crear una película | `JwtAccessDeniedHandler` |

Ambos escriben el error con `SecurityErrorResponseWriter`. Hace falta una clase aparte porque estos componentes actúan **antes** de que la petición llegue a Spring MVC, así que `GlobalExceptionHandler` no puede capturarlos.

> **Detalle avanzado: `OncePerRequestFilter`.** Nuestros dos filtros se declaran como `@Bean` en `SecurityConfig`. Spring Boot registra automáticamente todo bean de tipo `Filter` también en el servidor web, así que en teoría podrían ejecutarse dos veces por petición. Heredar de `OncePerRequestFilter` lo impide: marca la petición la primera vez y se salta la segunda.

---

## 10. Seguridad (II): JWT, login y contraseñas

### 10.1 Qué es un JWT

Un JWT (*JSON Web Token*) es una cadena con tres partes separadas por puntos:

```
eyJhbGciOiJIUzI1NiJ9 . eyJpc3MiOiJzdHJlYW1ib3giLCJzdWIiOiJhbmFAbWFpbC5jb20iLC4uLn0 . Xk3...firma
      cabecera                               contenido (claims)                            firma
```

Las dos primeras son JSON codificado en Base64. **No están cifradas**: cualquiera puede leer el contenido. Por eso el token nunca lleva datos sensibles. El de StreamBox lleva:

```json
{ "iss": "streambox", "sub": "ana@mail.com", "iat": 1790000000, "exp": 1790086400 }
```

| Claim | Significado |
| :--- | :--- |
| `iss` (*issuer*) | Quién lo emitió: `streambox` |
| `sub` (*subject*) | De quién es: el email del usuario |
| `iat` (*issued at*) | Cuándo se emitió |
| `exp` (*expiration*) | Cuándo caduca (24 h después, por defecto) |

La tercera parte es la **firma**: un HMAC-SHA256 de las otras dos partes calculado con el secreto `JWT_SECRET`, que solo conoce el servidor. Si alguien cambia una letra del contenido (por ejemplo, el email), la firma deja de coincidir y el token se rechaza. Sin el secreto es imposible fabricar una firma válida.

### 10.2 `JwtService`

- **Constructor**: construye la clave de firma **una sola vez** a partir de `JwtProperties` (ya validada: ≥32 caracteres = 256 bits, lo mínimo para HS256).
- **`generateToken(user)`**: crea el token con los claims de arriba y lo firma.
- **`extractEmail(token)`**: verifica la firma, la caducidad y que el emisor sea `streambox` (`requireIssuer`). Si todo es correcto, devuelve el `sub`. Si no, la librería (jjwt) lanza una excepción.

### 10.3 Contraseñas con BCrypt

Las contraseñas **nunca se guardan en texto plano**. `SecurityConfig` declara un `BCryptPasswordEncoder`:

- **`encode("mi-contraseña")`** produce algo como `$2a$10$N9qo8uLOickgx2ZMRZoMye...`. El `10` es el **coste**: BCrypt repite su cálculo 2¹⁰ veces a propósito, para que probar contraseñas por fuerza bruta sea lento. Además incluye una **sal** aleatoria: la misma contraseña da un hash distinto cada vez, así que dos usuarios con la misma contraseña no tienen el mismo hash.
- **`matches(textoPlano, hash)`** comprueba si coinciden. No se puede «descifrar» el hash: solo se puede volver a calcular y comparar.

### 10.4 El login paso a paso

```mermaid
sequenceDiagram
    participant F as Frontend
    participant RL as RateLimitingFilter
    participant C as AuthController
    participant A as AuthenticationService
    participant L as LoginAttemptService
    participant DB as Base de datos

    F->>RL: POST /api/auth/login {email, password}
    RL->>RL: ¿esta IP superó 10 intentos/min?
    RL-->>F: 429 RATE_LIMIT_EXCEEDED + Retry-After (si se pasa)
    RL->>C: continúa
    C->>C: @Valid (email con formato, password no vacía y ≤1024)
    C->>A: login(email, password)
    A->>A: normaliza email (trim + minúsculas)
    A->>L: reserveAttempt(email)
    L-->>F: 429 ACCOUNT_LOCKED + Retry-After si la cuenta ya acumula 5 fallos en 15 min
    A->>DB: findByEmail(email)
    A->>A: BCrypt.matches(password, hash real o hash falso)
    alt incorrecto
        A->>L: recordFailure(intento) → intentos restantes
        A-->>F: 401 INVALID_CREDENTIALS + remainingAttempts (4, 3, 2, 1)
        L-->>F: o 429 ACCOUNT_LOCKED si este fallo era el 5.º
    else correcto
        A->>L: recordSuccess(intento) (borra los fallos)
        A->>A: JwtService.generateToken(user)
        A-->>F: 204 + Set-Cookie streambox_token (HttpOnly, SameSite=Strict, Path=/api)
    end
```

Detalles de seguridad que conviene saber defender:

1. **Normalizar el email.** El registro guarda el email en minúsculas y sin espacios. El login hace lo mismo; si no, registrarse con `Ana@Mail.com` impediría entrar escribiendo exactamente lo mismo.
2. **Mismo mensaje para todo.** Tanto si el email no existe como si la contraseña es incorrecta, la respuesta es la misma: «Email o contraseña incorrectos». Si fueran distintos, un atacante podría averiguar qué emails están registrados (*enumeración de usuarios*).
3. **El hash falso.** Si el email no existe, no hay hash con el que comparar, y la respuesta sería instantánea; si existe, BCrypt tarda ~100 ms. Esa diferencia de tiempo delataría qué emails existen. Por eso, cuando el usuario no existe, se compara contra un hash falso (`dummyPasswordHash`, calculado una vez en el constructor) para que el tiempo sea parecido.
4. **El bloqueo se comprueba antes que la contraseña.** Una cuenta bloqueada rechaza el login aunque la contraseña sea correcta; si no, el bloqueo no protegería nada.
5. **El intento se reserva antes de comprobar la contraseña** (`reserveAttempt`), de forma atómica. Si solo se contara al fallar, 20 peticiones simultáneas pasarían todas la comprobación de bloqueo mientras BCrypt trabaja y se probarían 20 contraseñas en vez de 5.
6. **La contraseña del login no tiene mínimo** (las cuentas antiguas de 8 caracteres siguen entrando: la política de 12 solo se aplica al registrarse), pero sí un **máximo de 1024 caracteres**, para no leer cuerpos enormes ni pasárselos a BCrypt. Ese 400 ocurre antes del servicio, así que no gasta intento de la cuenta.

### 10.5 El registro

`POST /api/users` → `UserController.createUser` → `UserService.registerUser`:

1. Normaliza email (minúsculas) y usuario (sin espacios exteriores).
2. Comprueba que no existen ya (`existsByUsername`, `existsByEmail`) → 409 `USER_ALREADY_EXISTS` con un mensaje que dice cuál está en uso.
3. Cifra la contraseña con BCrypt.
4. **Fija el rol a `USER`.** El rol nunca viene del cliente: `CreateUserRequest` ni siquiera tiene ese campo. Si lo tuviera, cualquiera podría registrarse como administrador (*mass assignment*).

El registro **sí** revela si un email está en uso (es inevitable: hay que decirle al usuario por qué no puede registrarse). Se mitiga con el límite de 5 registros por hora por IP.

#### Política de contraseñas (`security/password/PasswordPolicy`)

Solo se aplica a las **cuentas nuevas** (registro y creación del administrador inicial). Sigue la guía NIST SP 800-63B: **longitud y lista de bloqueo**, en lugar de reglas de composición del tipo «una mayúscula, un número y un símbolo», que producen contraseñas como `Password1!`, fáciles de adivinar y difíciles de recordar.

| Regla | Mensaje (en `validationErrors.password`) |
| :--- | :--- |
| Obligatoria | «La contraseña es obligatoria» |
| Entre **12 y 64 caracteres** | «La contraseña debe tener entre 12 y 64 caracteres» |
| Como máximo **72 bytes en UTF-8** | Mensaje propio (pide usar menos caracteres acentuados o especiales) |
| No es **común ni trivial**: una lista de ~200 contraseñas en el código (sin ficheros ni dependencias), comparada sin mayúsculas, y un mínimo de 5 caracteres distintos (`aaaaaaaaaaaa` no vale) | «La contraseña es demasiado común. Elige otra más difícil de adivinar.» |
| No contiene el **nombre de usuario** ni la **parte local del email** (si tienen 4 caracteres o más) | «La contraseña no puede contener tu nombre de usuario ni tu email.» |

- **¿Por qué 72 bytes?** BCrypt solo usa los primeros 72 bytes. Con la versión de Spring Security del proyecto, `BCryptPasswordEncoder` lanza una excepción si se pasan; antes se aceptaban hasta 100 caracteres, así que una contraseña de 64 caracteres con tildes (2 bytes cada una) acababa en un **500**. Era un bug real y ahora lo cubre un test.
- **Un solo mensaje por campo.** Hay prioridad: longitud > bytes > común > datos personales. Cada comprobación ignora lo que ya rechaza la anterior, porque el manejador de errores guarda un mensaje por campo y Bean Validation no garantiza el orden.
- **¿Por qué 4 caracteres en la regla de datos personales?** Con 3 había falsos positivos: la usuaria `ana` no podía usar «mañana iremos al cine».
- **Normalización para comparar.** Se quitan los caracteres invisibles de los extremos (separadores Unicode, controles y caracteres de formato: la misma expresión que `GenreService`) y se pasa a minúsculas. Con `trim()`, `password1234` rodeada de espacios de no separación pasaba la lista. La contraseña que se cifra es siempre la original.
- Se implementa con una anotación de clase (`@ValidPassword` sobre `CreateUserRequest`, que implementa `NewPasswordRequest`), porque necesita ver a la vez la contraseña, el usuario y el email.
- El frontend solo valida la longitud (12–64); el resto lo decide el servidor y el formulario pinta su mensaje. Así la lista de contraseñas comunes no se duplica.

---

## 11. Seguridad (III): rate limiting y bloqueo de cuentas

Hay dos protecciones contra la fuerza bruta, complementarias:

| Protección | Clase | Clave | Límite por defecto | Protege contra |
| :--- | :--- | :--- | :--- | :--- |
| **Por IP** | `RateLimitingFilter` | IP del cliente | Login: 10/min. Registro: 5/h | Un atacante que prueba muchas cuentas desde una IP |
| **Por cuenta** | `LoginAttemptService` | Email | 5 fallos en 15 min | Un atacante que prueba muchas contraseñas contra una cuenta desde muchas IPs |

Ambas responden **429 Too Many Requests** con la cabecera **`Retry-After`** (segundos que hay que esperar), que el frontend usa para la cuenta atrás. Se distinguen por el `code`: **`RATE_LIMIT_EXCEEDED`** (límite por IP) o **`ACCOUNT_LOCKED`** (cuenta bloqueada).

**Contrato del bloqueo por cuenta** (5 fallos en 15 minutos, configurable en `streambox.security.rate-limit.lockout.*`):

| Intento | Respuesta |
| :--- | :--- |
| Fallos 1.º a 4.º | 401 `INVALID_CREDENTIALS` «Email o contraseña incorrectos» con **`remainingAttempts`** = 4, 3, 2, 1 |
| 5.º fallo | 429 `ACCOUNT_LOCKED` + `Retry-After: 900` «Has superado el número máximo de intentos. La cuenta queda bloqueada durante 15 minutos.» |
| Cualquier intento durante el bloqueo (también con la contraseña correcta) | 429 `ACCOUNT_LOCKED` con el tiempo que falta («…Inténtalo de nuevo en N minutos.») |
| Login correcto | 200; borra los fallos acumulados |

- `remainingAttempts` son los intentos que le quedan a la cuenta **con este fallo ya descontado**. Por eso nunca vale 0: el fallo que agota los intentos ya responde 429. En el resto de errores de la API el campo **no aparece** (ni siquiera como `null`).
- **No revela qué emails existen:** los números, los mensajes, las cabeceras y el bloqueo son idénticos para un email registrado y para uno inexistente (lo comprueba `LoginLockoutContractIntegrationTest`, que también mide que los tiempos de respuesta sean parecidos).
- El frontend muestra «Te quedan N intentos antes de que la cuenta se bloquee 15 minutos» y, con `ACCOUNT_LOCKED`, un aviso de cuenta bloqueada con cuenta atrás en minutos y segundos (capítulo 20).

**Cómo reconoce el filtro las rutas.** `RateLimitingFilter` usa los mismos `PathPatternRequestMatcher` que las reglas de autorización, y las rutas (`LOGIN_PATH`, `REGISTER_PATH`) son constantes que `SecurityConfig` también usa en su `permitAll()`: lo público y lo limitado son siempre lo mismo. **Antes había un fallo de seguridad (encontrado por `qa` en octubre de 2026):** el filtro comparaba `request.getRequestURI()` con `equals`, pero ese método devuelve la ruta **sin decodificar**, mientras que Spring MVC decodifica cada segmento. `POST /api/auth/%6cogin` (la `l` codificada) llegaba al login sin pasar por el contador, y `POST /api/%75sers` creaba cuentas sin límite. El cortafuegos de Spring Security no lo impide, porque una letra codificada es legal. Lección: **para decidir sobre una ruta, compárala igual que la compara quien la enruta.**

### 11.1 `SlidingWindowCounter`: el algoritmo

Las dos protecciones usan el mismo contador de **ventana deslizante**. Para cada clave (una IP o un email) guarda una cola con los instantes de sus eventos recientes:

```
Ventana: 1 minuto. Máximo: 10.
IP 1.2.3.4 → [12:00:05, 12:00:07, 12:00:30, ...]
```

- **`tryAcquire(clave, max)`**: primero tira de la cola los eventos más antiguos que la ventana; si quedan menos de `max`, apunta el nuevo y devuelve `true`; si no, devuelve `false` **sin apuntarlo**. Así, un cliente que espera recupera el acceso (las peticiones rechazadas no alargan el bloqueo).
- **`retryAfter(clave)`**: cuánto falta para que el evento más antiguo salga de la ventana, es decir, para que se libere un hueco.
- **`reserve` / `confirm` / `reset`**: los usa el bloqueo por cuenta. `reserve` aparta el intento antes de comprobar la contraseña (devuelve una `Reservation` con id), `confirm` lo deja apuntado como fallo y devuelve su **posición real** en la ventana, y `reset` borra los fallos tras un login correcto.
- **¿Por qué la posición real y no el número de la reserva?** Durante los ~100 ms de BCrypt pueden pasar cosas: un login correcto simultáneo borra la cola entera, o caduca un fallo antiguo. Si se decidiera con el número obtenido al reservar, de cinco intentos simultáneos en los que el primero acierta, los otros cuatro responderían 3, 2, 1 y un 429 con la cuenta sin bloquear. `confirm` vuelve a apuntar la reserva si un acierto la borró (con el mismo id, para no contarla dos veces).
- **Limpieza periódica**: cada 500 operaciones borra las claves caducadas, para que un atacante no pueda llenar la memoria con millones de IPs distintas.
- **`synchronized`**: todos los métodos están sincronizados porque Tomcat atiende muchas peticiones a la vez en hilos distintos.

**¿Por qué «deslizante»?** Con una ventana fija (por ejemplo, «10 por minuto natural»), un atacante podría hacer 10 intentos a las 12:00:59 y otros 10 a las 12:01:00. La ventana deslizante mira siempre «los últimos 60 segundos» desde ahora.

### 11.2 Limitaciones conocidas

- **Vive en memoria.** Si la aplicación se ejecuta en varias copias (réplicas), cada una lleva su cuenta y el límite real se multiplica; un reinicio lo pone a cero. Para escalar habría que moverlo a un almacén compartido como Redis.
- **La IP.** Se usa `request.getRemoteAddr()`. Detrás de un proxy inverso (nginx, un balanceador) esa IP sería la del proxy; por eso el perfil `prod` activa `server.forward-headers-strategy=native`, que hace que Tomcat lea la IP real de la cabecera `X-Forwarded-For`. **Nunca se lee esa cabecera a mano**: un cliente podría falsificarla para evadir el límite.
- **Bloqueo como arma.** Alguien puede bloquear 15 minutos la cuenta de otra persona fallando su login a propósito. Se acepta porque es temporal.

Los fallos se cuentan **también para emails que no existen**: si solo se contaran los existentes, el bloqueo delataría qué emails están registrados.

---

## 12. Seguridad (IV): roles, administrador y «mis» recursos

### 12.1 Roles

Hay dos: `USER` y `ADMIN` (`entity/Role`). El `USER` consulta el catálogo y gestiona su lista; el `ADMIN` además crea, modifica y borra películas y géneros, y lista usuarios.

### 12.2 Cómo se crea un administrador: `AdminAccountInitializer`

El registro público siempre crea `USER`. Los administradores solo se crean así:

1. Defines `ADMIN_EMAIL` y `ADMIN_PASSWORD` (y opcionalmente `ADMIN_USERNAME`) antes de arrancar.
2. Al arrancar, `AdminAccountInitializer.run` valida los datos (email con `@`, usuario de 3 a 50 caracteres).
3. Si ya existe un usuario con ese email, **no lo toca** (nunca sobrescribe una contraseña) y **no valida la contraseña configurada**: validarla solo serviría para que una instalación que funcionaba dejara de arrancar al endurecerse la política. Si esa contraseña no cumple la política, escribe un aviso en el log **sin la contraseña ni la regla que falla** (sería una pista sobre la contraseña de una cuenta activa). Si el nombre de usuario lo tiene otra cuenta, falla con un mensaje claro.
4. Si no existe, comprueba que `ADMIN_PASSWORD` cumple **la misma política que el registro** (capítulo 10.5). Si no, **la aplicación no arranca** y el error dice qué regla falla, nunca la contraseña. Después lo crea con rol `ADMIN` y la contraseña cifrada.

Sin esas variables, no hace nada. Por eso, en el día a día, no tienes que preocuparte de él.

### 12.3 `/api/users/me/...` y por qué no hay ids de usuario en la URL

Los endpoints personales son `/api/users/me` y `/api/users/me/favorites/...`. El usuario se obtiene **del token**, nunca de la URL:

```java
@GetMapping
public List<MovieResponse> getFavorites(@AuthenticationPrincipal AuthenticatedUser principal) {
    return favoriteService.getFavorites(principal.id());
}
```

`@AuthenticationPrincipal` le pide a Spring el objeto que `JwtAuthenticationFilter` guardó en el contexto de seguridad.

**¿Por qué?** Si la ruta fuera `/api/users/{userId}/favorites`, bastaría con cambiar el número para ver o modificar la lista de otra persona. Es la vulnerabilidad más común en APIs y se llama **IDOR** o **BOLA** (*Insecure Direct Object Reference* / *Broken Object Level Authorization*). Con `/me` y el id del token, es imposible por diseño: no hay ningún parámetro que manipular.

---

## 13. Controladores, validación y DTOs

### 13.1 Endpoints

| Método | Ruta | Rol | Controlador → servicio |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/login` | Público | `AuthController.login` → `AuthenticationService.login` |
| `POST` | `/api/users` | Público | `UserController.createUser` → `UserService.registerUser` |
| `GET` | `/api/users/me` | Autenticado | `UserController.getCurrentUser` |
| `GET` | `/api/users` | ADMIN | `UserController.getUsers` |
| `GET` | `/api/movies` | Autenticado | `MovieController.getMovies` (paginado) |
| `GET` | `/api/movies/{id}` | Autenticado | `MovieController.getMovieById` |
| `GET` | `/api/movies/search` | Autenticado | `MovieController.searchMovies` (paginado, filtros) |
| `POST` | `/api/movies` | ADMIN | `MovieController.createMovie` |
| `PUT` | `/api/movies/{id}` | ADMIN | `MovieController.updateMovie` |
| `DELETE` | `/api/movies/{id}` | ADMIN | `MovieController.deleteMovie` |
| `GET` | `/api/genres` | Autenticado | `GenreController.getGenres` |
| `POST` | `/api/genres` | ADMIN | `GenreController.createGenre` |
| `PUT` | `/api/genres/{id}` | ADMIN | `GenreController.updateGenre` |
| `DELETE` | `/api/genres/{id}` | ADMIN | `GenreController.deleteGenre` |
| `GET` | `/api/users/me/favorites` | Autenticado | `FavoriteController.getFavorites` |
| `POST` / `DELETE` | `/api/users/me/favorites/{movieId}` | Autenticado | Añadir / quitar por id |
| `POST` / `DELETE` | `/api/users/me/favorites/by-title?title=` | Autenticado | Añadir / quitar por título exacto |
| `DELETE` | `/api/users/me/favorites` | Autenticado | Vaciar la lista |
| `GET` | `/api/series`, `/api/series/search`, `/api/series/{id}` | Autenticado | `SeriesController` (solo series con episodios; capítulo 15 bis) |
| `POST` / `PUT` / `DELETE` | `/api/series[/{id}]` | ADMIN | `SeriesController` → `SeriesService` |
| `POST` / `PUT` / `DELETE` | `/api/series/{id}/episodes[/{episodeId}]` | ADMIN | `EpisodeController` → `EpisodeService` |
| `GET` | `/api/admin/series[/{id}]` | ADMIN | `AdminSeriesController` (incluye series sin episodios) |
| `GET` / `DELETE` | `/api/users/me/favorites/series` | Autenticado | `SeriesFavoriteController`: listar / vaciar las series de la lista |
| `POST` / `DELETE` | `/api/users/me/favorites/series/{seriesId}` | Autenticado | Añadir / quitar una serie |

Películas y series comparten la construcción de la paginación (`controller/PageableFactory`: lista blanca de `sort`, `direction`, desempate por `id` y control de desbordamiento de `page × size`).

### 13.2 Cómo es un controlador

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)                     // 201 si todo va bien
@Operation(summary = "Crea una película", ...)         // documentación Swagger
public MovieResponse createMovie(@Valid @RequestBody MovieRequest request) {
    return movieService.createMovie(request);
}
```

- **`@RestController`** en la clase: cada método devuelve datos (convertidos a JSON), no páginas HTML.
- **`@RequestBody`**: Jackson convierte el JSON del cuerpo en un `MovieRequest`.
- **`@Valid`**: antes de entrar al método, se comprueban las anotaciones de validación del DTO. Si alguna falla, el método **no se ejecuta** y se responde 400.
- **`@ResponseStatus`**: el código HTTP de éxito (201 en altas, 204 en borrados). El proyecto devuelve el DTO directamente en lugar de `ResponseEntity`, que solo haría falta para respuestas con cabeceras o estados variables.
- **Sin lógica**: el controlador solo traduce HTTP ⇄ llamadas al servicio.

### 13.3 Validación (Bean Validation)

Las reglas viven en los DTOs de petición. Por ejemplo, `MovieRequest`:

```java
public record MovieRequest(
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(max = 1000) String description,
    @NotNull @Min(1) Integer duration,
    @NotNull @Min(1888) @Max(2100) Integer releaseYear,
    @NotBlank @Size(max = 500) @HttpsUrl(allowLocalCovers = true, maxLength = 500) String imageUrl,
    @NotBlank @Size(max = 500) @HttpsUrl(maxLength = 500) String videoUrl,
    @NotEmpty Set<Long> genreIds) { }
```

**URLs de portada y vídeo (`validation/HttpsUrl` + `HttpsUrlValidator`).** Antes se usaba `@URL` de Hibernate Validator, que acepta cualquier esquema (`http:`, `file:`, `ftp:`, `jar:`…), y no había límite de longitud: una URL de más de 500 caracteres llegaba a la columna `VARCHAR(500)` y el cliente recibía un **409 engañoso**. Ahora:

- Una URL vale si solo tiene **caracteres ASCII visibles** (sin espacios; lo demás va codificado con `%XX`), empieza exactamente por **`https://`** (en minúsculas: `HTTPS://` se rechaza en lugar de normalizarse, porque un validador no debe cambiar el valor), **tiene host** y **no lleva credenciales** (`https://usuario:clave@host`). Se analiza con `java.net.URI`, no con una expresión regular.
- Solo en `imageUrl` (`allowLocalCovers = true`) vale también una **portada propia** `/covers/<archivo>`, con el archivo de la forma `[A-Za-z0-9][A-Za-z0-9._-]*` (sin subcarpetas, sin `..`, sin `?` ni `%`). Es como se sirven las de `frontend/public/covers`. Antes no pasaban `@URL`, así que `docs/portadas-locales.sql` no se podía reproducir por la API; ahora sí.
- **Un solo mensaje por campo:** `@HttpsUrl` no evalúa lo que ya rechazan `@NotBlank` (vacío) ni `@Size` (si es demasiado larga, manda el mensaje de longitud). Los mensajes y el máximo son constantes de `MovieRequest`, y el frontend usa exactamente los mismos.
- ¿Por qué validar en el servidor si el frontend ya filtra `videoUrl` con `getSafeVideoUrl`? Porque la API la pueden usar otros clientes y no debe depender de que cada uno se proteja. Además, `https` encaja con la CSP prevista (`img-src 'self' data: https:`).
- Consecuencia práctica: una película antigua con una URL `http://` no se puede guardar desde el panel hasta corregir la URL (el formulario muestra el error junto al campo). El servidor nunca descarga estas URLs, así que no hay riesgo de peticiones internas (SSRF).

**Géneros por título:** `genreIds` admite entre 1 y 20 géneros, en películas y en series (`MovieRequest.MAX_GENRES`). Sin tope, la lista acaba en un `IN (...)` sin límite dentro de `findAllById`; PostgreSQL admite como mucho 32 767 parámetros.

**Decimales en campos enteros:** un número con decimales en un campo entero del JSON (`"duration": 100.5`, `"seasonNumber": 1.5`, incluso `100.0`) responde 400 `MALFORMED_REQUEST` en lugar de truncarse en silencio. Jackson trae esa conversión activada por defecto y se desactiva con `spring.jackson.deserialization.accept-float-as-int=false` en `application.properties`. Se aplica al `JsonMapper` de Jackson 3, con el que Spring Boot 4 lee los cuerpos; Jackson 2 solo lo usa `SecurityErrorResponseWriter` para escribir los 401/403. Lo más grave era `"genreIds": [5.5]`, que asignaba **otro género** (el 5). Los parámetros de consulta (`?page=1`) no se ven afectados, porque los convierte Spring. Tests: `JsonIntegerCoercionIntegrationTest`.

Los parámetros de la URL también se validan (`@Min`/`@Max` en `page` y `size`). Para eso el controlador lleva `@Validated` en la clase. Si un parámetro no tiene el tipo correcto (`page=abc`), Spring lanza una excepción de conversión que el manejador de errores convierte en 400.

Los límites coinciden con los de la base de datos (`VARCHAR(150)`, `CHECK release_year ...`), así que el usuario recibe un mensaje claro en vez de un error de base de datos.

### 13.4 DTOs y mappers

**¿Por qué no devolver directamente las entidades?**

1. **Seguridad**: `User` lleva la contraseña cifrada. `UserResponse` no.
2. **Estabilidad**: el contrato de la API no cambia cada vez que cambia una tabla.
3. **Rendimiento y errores**: serializar una entidad con relaciones `LAZY` provocaría consultas inesperadas o `LazyInitializationException`.

Los DTOs de salida son `record` (inmutables): `MovieResponse`, `GenreResponse`, `UserResponse`, `MoviePageResponse`.

Los **mappers** (`…/mapper`) son clases `final` con métodos estáticos que copian campos:

- `MovieMapper.toResponse(movie)` convierte la entidad en DTO y **ordena los géneros por nombre**, para que el JSON sea siempre igual.
- `MovieMapper.toEntity` / `updateEntity` copian los campos de un `MovieRequest` a la entidad. Los géneros no: los resuelve el servicio, porque requieren consultar la base de datos.

Se escriben a mano en vez de usar MapStruct (una librería que los genera): son pocos y así se ve exactamente qué se copia.

`MoviePageResponse.from(page)` convierte el `Page` de Spring en un JSON propio, estable y sencillo (`content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`). El `Page` de Spring tiene un formato interno que cambia entre versiones.

---

## 14. Catálogo: paginación, orden y búsqueda

### 14.1 Paginación

`GET /api/movies?page=0&size=20&sort=createdAt&direction=desc`

| Parámetro | Por defecto | Reglas |
| :--- | :--- | :--- |
| `page` | 0 | ≥ 0 (la primera página es la 0) |
| `size` | 10 | Entre 1 y 100 |
| `sort` | `title` | Solo `id`, `title`, `releaseYear`, `duration`, `createdAt` |
| `direction` | `asc` | `asc` o `desc`, sin distinguir mayúsculas |

`MovieController.buildPageable` construye el `Pageable` y hace tres comprobaciones:

1. **Lista blanca de `sort`.** Si el cliente pudiera ordenar por cualquier campo, podría ordenar por `genres` (una relación, que rompe la consulta) o tantear la estructura interna de la entidad. Cualquier otro valor da 400.
2. **Dirección.** `parseDirection` se escribió a mano porque el método de Spring (`Sort.Direction.fromString`) lanzaría un mensaje en inglés del framework, que no debe llegar al cliente.
3. **Desplazamiento máximo.** Spring Data calcula el desplazamiento (`page × size`) como `int`. Si no cabe (por ejemplo `page=21474837&size=100`), lanzaría un error interno: un 500 provocado por el cliente. `requireOffsetWithinIntRange` lo detecta antes y devuelve 400.

### 14.2 El desempate por `id`

```java
Sort ordering = Sort.by(order, sort);
if (!"id".equals(sort)) ordering = ordering.and(Sort.by(order, "id"));
```

Si se ordena por `releaseYear` y diez películas son de 2024, la base de datos puede devolverlas **en cualquier orden** entre ellas, y ese orden puede cambiar entre la consulta de la página 0 y la de la página 1. Resultado: una película sale en dos páginas y otra en ninguna. Añadir `id` como segundo criterio hace el orden **determinista**.

El desempate sigue la **misma dirección** que el campo principal: con `direction=desc`, las películas con la misma fecha también van de la más nueva (id mayor) a la más antigua.

### 14.3 Búsqueda con filtros opcionales

`GET /api/movies/search?title=dune&genreId=2&releaseYear=2024` (todos opcionales, más los de paginación).

`MovieService.searchMovies` construye una lista de condiciones **solo con los filtros indicados** y las combina con `AND`:

```java
List<Specification<Movie>> filters = new ArrayList<>();
if (title != null && !title.isBlank()) filters.add(MovieSpecification.hasTitle(title));
if (genreId != null)                    filters.add(MovieSpecification.hasGenre(genreId));
if (releaseYear != null)                filters.add(MovieSpecification.hasReleaseYear(releaseYear));
return movieRepository.findAll(Specification.allOf(filters), pageable).map(MovieMapper::toResponse);
```

Sin ningún filtro, `allOf` de una lista vacía no filtra nada y devuelve todo el catálogo paginado.

Las tres condiciones están en `…/specification/MovieSpecification.java`:

**`hasTitle`: «contiene», sin distinguir mayúsculas**

```sql
lower(title) like lower('%dune%') escape '\'
```

- **Comodines escapados.** En `LIKE`, `%` significa «cualquier texto» y `_` «cualquier carácter». Si el usuario busca `100%`, sin escapar encontraría cualquier título que empiece por «100». `escapeLike` antepone `\` a esos caracteres para tratarlos como texto literal.
- **`lower()` en los dos lados, en la base de datos.** Antes, el texto buscado se pasaba a minúsculas en Java. Java y PostgreSQL no convierten igual algunas letras (`İ` turca, la sigma griega final), así que un título con esas letras no se encontraba. Los tests contra PostgreSQL lo descubrieron (en H2 no pasaba, porque H2 usa la conversión de Java). Ahora las dos minúsculas las hace el mismo motor.
- La búsqueda **sí distingue acentos**: `manana` no encuentra `Mañana`.
- Se usan parámetros, nunca concatenación de texto en el SQL, así que **no hay inyección SQL** posible.

**`hasGenre`: con `EXISTS`, no con `JOIN`**

```sql
where exists (select m2.id from movies m2 join movie_genres ... where m2.id = m.id and g.id = ?)
```

Un `JOIN` directo multiplicaría las filas de una película que tenga varios géneros y obligaría a usar `DISTINCT`, que complica la paginación. La subconsulta `EXISTS` solo pregunta «¿tiene este género?» sin alterar las filas de la consulta principal.

**`hasReleaseYear`**: igualdad simple (`release_year = ?`), apoyada en su índice.

### 14.4 Alta, edición y borrado de películas (admin)

- **Alta y edición** resuelven los géneros con `resolveGenres`: carga todos los ids pedidos con **una sola consulta** (`findAllById`). Si falta alguno, responde 404 indicando el de menor id. (Antes se hacía una consulta por género: otro caso de N+1.)
- **Edición** no llama a `save()`: la entidad está gestionada dentro de la transacción y Hibernate guarda los cambios al confirmar (capítulo 8).
- **Borrado** primero quita la película de todas las listas de favoritos (`deleteFromAllFavorites`, necesario en bases antiguas sin cascada) y después la borra. Las filas de `movie_genres` desaparecen por el `ON DELETE CASCADE`.

### 14.5 Géneros

Los administradores pueden crear (`POST`), renombrar (`PUT /api/genres/{id}`) y borrar (`DELETE /api/genres/{id}`) géneros. Alta y edición reciben el mismo DTO, `record GenreRequest(String name)`.

**Normalización del nombre** (`GenreService.normalizeAndValidateName`, un único método que comparten alta y edición):

1. Se recortan los extremos, incluidos los separadores y caracteres invisibles de Unicode (`\p{Z}`, `\p{Cc}`, `\p{Cf}`). `String.trim()` y `strip()` no bastan: ninguno quita el espacio duro (U+00A0), y con él se podía crear un género de nombre invisible.
2. Los espacios interiores repetidos se reducen a uno, para que `Ciencia  ficción` no sea un género distinto de `Ciencia ficción`.
3. Primera letra en mayúscula y el resto en minúscula (`Locale.ROOT`), para que `ACCION` y `accion` no sean dos géneros.
4. **Se vuelve a comprobar la longitud (2 a 50) sobre el resultado.** Bean Validation (`@NotBlank`, `@Size` en `GenreRequest`) valida el texto *tal como llega*, antes de normalizarlo. Sin esta segunda comprobación, `" a"` pasaba la validación y se guardaba como `"A"` (1 carácter), y un nombre que *crece* al pasarlo a minúsculas (`"İ"` se convierte en dos caracteres) no cabía en `VARCHAR(50)` y acababa en un 409 engañoso. Si no cumple, se lanza `InvalidParameterException` → 400 `VALIDATION_ERROR`, con el mismo mensaje que `@Size` (constantes compartidas en `GenreRequest`). Ocurre antes de tocar la base de datos.

**Duplicados.** El servicio comprueba antes de guardar con `existsByNameIgnoreCase` (o `...AndIdNot` al renombrar, para que renombrar un género a su propio nombre sea un 200). Ignora mayúsculas porque la restricción `UNIQUE` sí las distingue y podría haber filas antiguas como «Ciencia Ficción». La restricción queda como respaldo ante dos altas simultáneas: si salta (SQLSTATE `23505`), se traduce al mismo 409 `GENRE_ALREADY_EXISTS`. Se usa `saveAndFlush` para que el error salte dentro del servicio y se pueda traducir.

**Borrado.** Si alguna película usa el género, se responde 409 `GENRE_IN_USE` con el recuento («lo usan 3 películas»; `MovieRepository.countByGenres_Id`, un `COUNT` que no carga películas). ¿Por qué no borrar y ya? La clave foránea `movie_genres.genre_id` **no** tiene `ON DELETE CASCADE` a propósito, y toda película debe tener al menos un género: borrar en cascada podría dejar películas sin ninguno. La base de datos respalda la regla: si alguien asigna el género justo entre la comprobación y el borrado, la FK salta en el `flush` (SQLSTATE `23503`) y se traduce también a `GENRE_IN_USE`, esta vez sin cifra (tras un `flush` fallido la transacción ya no admite más consultas). `SqlStates` es el ayudante que saca el `SQLSTATE` de la cadena de causas; lo comparten `GenreService` y `FavoriteService`.

---

## 15. Favoritos («Mi lista»)

Código: `FavoriteController` → `FavoriteService` → consultas de `UserRepository`.

### 15.1 Por qué SQL nativo y no la colección `favoriteMovies`

La forma «JPA de libro» de añadir un favorito sería:

```java
User user = userRepository.findById(userId).get();   // carga el usuario
user.getFavoriteMovies().add(movie);                  // carga TODA su lista (y sus géneros)
```

Para añadir **una** película, cargaría la lista entera. El proyecto lo hace con una sola sentencia sobre la tabla de unión:

```sql
INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?);
DELETE FROM user_favorite_movies WHERE user_id = ? AND movie_id = ?;
```

### 15.2 Añadir: el flujo y la condición de carrera

`FavoriteService.addFavorite(userId, movieId)`:

1. ¿Existe la película? Si no → **404**.
2. ¿Existe el usuario? Si no → **404**.
3. ¿Ya está en la lista? (`isFavorite`) Si sí → **409** `MOVIE_ALREADY_IN_FAVORITES`.
4. `INSERT`.

**El problema:** si el usuario hace doble clic, llegan dos peticiones a la vez. Ambas pasan la comprobación 3 («todavía no está») y ambas intentan el `INSERT`. Una comprobación en Java no puede evitarlo.

**La solución:** la **clave primaria** `(user_id, movie_id)` de la base de datos. El segundo `INSERT` falla con una violación de unicidad, y el servicio la captura y la traduce a 409. Los tests contra PostgreSQL lo comprueban con 8 hilos a la vez: siempre 1 éxito y 7 respuestas 409, nunca un 500.

**Traducir bien el error.** `translateInsertViolation` mira el **código SQLSTATE** del error (un código estándar de 5 caracteres), no el texto del mensaje, que cambia según el motor y el idioma:

| SQLSTATE | Significa | Respuesta |
| :--- | :--- | :--- |
| `23505` | Clave duplicada | 409 «ya está en tu lista» |
| `23503` (PostgreSQL), `23506` (H2) | Clave foránea: la película se borró justo entre la comprobación y el `INSERT` | 404 «película no encontrada» |
| Cualquier otro | Un fallo de programación | Se relanza y acaba en 500 (queda registrado en el log) |

`findSqlState` recorre la cadena de causas porque el error del driver JDBC viene envuelto dos veces (Hibernate lo envuelve y Spring lo vuelve a envolver).

### 15.3 Quitar, vaciar y listar

- **Quitar**: el `DELETE` devuelve cuántas filas borró. Si es 0, la película no estaba → **404** `MOVIE_NOT_IN_FAVORITES`. No hace falta consultar antes.
- **Vaciar**: un solo `DELETE ... WHERE user_id = ?`. Si ya estaba vacía, no es error (204).
- **Listar**: una consulta JPQL ordenada por título; los géneros se cargan por lotes.

### 15.4 Por título

`/by-title?title=...` busca con `findAllByTitleIgnoreCase`. Si no hay ninguna película, 404; si hay **más de una** con ese título, 409 `AMBIGUOUS_TITLE` («usa el id»). El título va como parámetro de consulta (`?title=`) y no en la ruta para que los espacios y caracteres especiales no den problemas.

---

## 15 bis. Series: temporadas y episodios

Las series se añadieron en octubre de 2026. Este capítulo reúne cómo funcionan de punta a punta; los capítulos generales (seguridad, errores, frontend) remiten aquí.

### 15 bis.1 Decisiones de diseño (y por qué)

| Decisión | Alternativa descartada | Por qué |
| :--- | :--- | :--- |
| **Tablas propias**, sin tocar `movies` | Un supertipo común «contenido» con herencia JPA | No arriesga nada de lo que ya funcionaba (películas, favoritos, los datos reales de Supabase). La migración solo crea tablas. A cambio se repiten algunas columnas |
| **La temporada es un número dentro del episodio** (`season_number`) | Una tabla `seasons` | Basta para el selector de temporadas y simplifica la API. Si algún día una temporada necesita título o año propio, se añade la tabla con una migración nueva |
| **Página propia** `/series/:id` | Modal, como las películas | Las temporadas y la lista de episodios no caben bien en un modal, y así la serie tiene una URL que se puede compartir |
| **Las series sin episodios no las ve el usuario** | Mostrarlas como «próximamente» | Evita páginas vacías; el administrador las prepara en el panel y aparecen solas con el primer episodio |
| **Episodios sin imagen propia** (usan la de la serie) | Columna `image_url` en `episodes` | No hacía falta en la primera versión; está anotado en el plan |

### 15 bis.2 Modelo de datos (`V3__create_series.sql`)

- `series`: título, sinopsis, `release_year`, `end_year` (**nulo = en emisión**; `CHECK` de años entre 1888 y 2100 y fin ≥ estreno), `image_url`, `created_at`.
- `series_genres`: el mismo catálogo de géneros que las películas. Borrar una serie limpia sus filas (cascada); borrar un género usado por una serie falla por clave foránea, y el servicio lo traduce a 409.
- `episodes`: `season_number`, `episode_number`, título, sinopsis opcional, duración y `video_url`, con `CHECK` (temporada, número y duración positivos) y **`UNIQUE (series_id, season_number, episode_number)`**: no puede haber dos «T1:E3» en la misma serie. Ese índice único empieza por `series_id`, así que sirve también para listar los episodios de una serie ya ordenados y para el `EXISTS` de visibilidad.
- `user_favorite_series`: «Mi lista» de series.
- **Cascadas en la base de datos:** al borrar una serie se borran sus episodios, sus géneros y sus favoritos; al borrar un usuario, sus favoritos de series.
- V3 **solo crea** tablas e índices (es segura sobre una base con datos), todas las restricciones tienen nombre (`pk_`, `fk_`, `ck_`, `uk_`, `idx_`) y **no usa `IF NOT EXISTS`**: si existiera ya una tabla `series` distinta, es mejor que falle de forma visible a que la adopte en silencio.
- **Supabase:** las tablas nuevas nacen sin RLS. Tras arrancar la app con V3, hay que volver a ejecutar `docs/supabase-seguridad.sql`, que ya incluye las cuatro (capítulo 4.5).

En Java (`entity/Series`, `entity/Episode`): `Episode.series` es `LAZY` con `@OnDelete(CASCADE)`. No se usa `CascadeType.REMOVE`, que cargaría y borraría los episodios uno a uno cuando la base de datos ya lo hace. **No hay colección `Series.episodes`**, porque los episodios se piden siempre con una consulta ordenada y una colección invitaría al N+1. **Tampoco hay colección de series favoritas en `User`**, porque el filtro JWT carga `User` en cada petición. Los favoritos de series son `INSERT`/`DELETE` nativos en `SeriesRepository`, como los de películas.

### 15 bis.3 La regla de visibilidad

Una serie **sin episodios** existe para el administrador pero no para el usuario:

- El listado y la búsqueda públicos usan `SeriesSpecification.hasEpisodes()`, un `EXISTS` sobre `episodes`; tampoco cuentan en `totalElements`.
- El detalle público usa `SeriesRepository.findVisibleById`: una serie vacía da **404 idéntico** (mismo estado y mismo cuerpo) al de una que no existe.
- Solo se puede añadir a favoritos una serie con episodios. Si una serie de la lista se queda sin episodios, **la fila se conserva pero no se muestra**, y reaparece sola cuando vuelve a tenerlos.
- El panel usa las **vistas de gestión** `GET /api/admin/series[/{id}]` (solo ADMIN), que sí incluyen las vacías.

**¿Por qué «idéntico»?** Los ids son secuenciales. Si una serie oculta respondiera distinto que una inexistente, un usuario podría recorrer los ids y descubrir qué está preparando el administrador. Por eso también quitar de favoritos una serie oculta que el usuario **no** tenía responde igual que una inexistente (`RESOURCE_NOT_FOUND`). La única excepción deliberada es quitar una que **sí** estaba en su lista: da 204, porque no le revela nada que no supiera. Lo comprueba `SeriesObjectLevelAuthorizationIntegrationTest` (fue un hallazgo de la revisión de `security`).

### 15 bis.4 API

Endpoints en el capítulo 13.1. Detalles:

- **DTOs:** `SeriesResponse` (listados, con `seasonCount` y `episodeCount`), `SeriesDetailResponse` (más `seasons`), `SeasonResponse { seasonNumber, episodes }` y `EpisodeResponse`. Las temporadas se construyen agrupando los episodios por `seasonNumber` (`TreeMap` en `SeriesMapper`). `endYear` y la sinopsis del episodio van como `null` explícito.
- **Validación:**
  - `SeriesRequest`: título ≤150, sinopsis ≤1000, `releaseYear` 1888–2100, `endYear` opcional en ese rango y no anterior al estreno (restricción de clase `@ValidSeriesYears`, que cuelga el error del campo `endYear`), portada con `@HttpsUrl(allowLocalCovers = true)` y entre 1 y 20 géneros.
  - `EpisodeRequest`: temporada 1–100, número 1–1000, título ≤150, sinopsis opcional ≤1000 (en blanco se guarda `null`), duración 1–600, `videoUrl` solo `https`.
- **Coste en consultas** (lo vigilan tests con Hibernate Statistics):
  - Una página cuesta ≤4 consultas, tenga 5 o 25 series: la página, el total, los géneros por lotes y **una** consulta agrupada con los recuentos de temporadas y episodios (`EpisodeRepository.countBySeriesIds`, a través de `SeriesEpisodeCounts`).
  - El detalle cuesta 2: la serie con sus géneros y sus episodios ordenados.
  - «Mi lista» cuesta ≤3.
- **Episodios:** el duplicado de temporada + número se comprueba **antes** de copiar los datos a la entidad. Si fuera después, Hibernate volcaría el cambio antes de la consulta. El `UNIQUE` queda como respaldo ante dos altas simultáneas: `saveAndFlush` y traducción por SQLSTATE (23505 → 409 `EPISODE_ALREADY_EXISTS`, clave foránea → 404). Las rutas de episodio buscan con `findByIdAndSeriesId`: cambiar el `seriesId` de la URL no permite tocar el episodio de otra serie (404).
- **Favoritos** (`/api/users/me/favorites/series`): mismo patrón que películas (capítulo 15). La ruta literal `/series` tiene prioridad sobre `/{movieId}`, y hay un test que lo comprueba con una película y una serie del mismo id.
- **Géneros:** borrar un género cuenta películas y series («lo usan 3 películas y 2 series», en singular o plural y omitiendo la parte que vale 0).
- **Código compartido con películas:** `controller/PageableFactory` (paginación y orden) y `specification/LikePatterns` (búsqueda por título con comodines escapados y `lower()` en la base de datos). Se extrajeron de películas sin cambiar su comportamiento, para que las dos no puedan divergir.

### 15 bis.5 Frontend del usuario

- **Componentes genéricos.** Las piezas visuales de películas se generalizaron para servir a las dos: `PosterCard`, `PosterRow<T>`, `FeaturedBanner` y `MetaTags`, sobre un tipo común `CatalogItem` (`lib/types.ts`). `MovieCard`, `MovieRow`, `HeroBanner`… quedan como envoltorios finos con la misma API, y por eso sus tests no cambiaron. `useCatalog` se apoya en `usePagedCatalog`, que también usan las series, y `buildCatalogRows<T>` es genérica (`CatalogRow.items`).
- **`/series` (`SeriesPage`):** la misma estructura que la portada. Banner con la serie más reciente (marca «Novedad»; la acción principal «Ver episodios» enlaza a su página, porque una serie no tiene un único vídeo), «Novedades», filas por género (≥3 títulos **sin contar el del banner**), «Cargar más series» y los estados de carga, vacío y error.
- **`/series/:id` (`SeriesDetailPage`):**
  - Cabecera con `FeaturedBanner` (h1, sinopsis completa, «Empezar a ver» el primer episodio y «Mi lista»).
  - **Selector de temporada** (`SeasonPicker`): son enlaces `?temporada=N` con `aria-current` y navegación con `replace`, no un `<select>` ni el patrón `tablist`. La temporada vive en la URL, así que se puede compartir y abrir en otra pestaña; con un `<select>`, en Windows cada flecha cambiaría la URL. Solo aparece con dos o más temporadas. Una temporada inválida o ausente muestra la primera (`resolveSeasonNumber`). «Temporada 2 · 8 episodios» está en una región `role="status"`, que anuncia el cambio sin mover el foco.
  - `EpisodeList`: una `<ol>` con un h3 «N. Título», la duración, la sinopsis si la hay y «Ver», que abre el vídeo validado con `getSafeVideoUrl` (nombre accesible «Ver T1:E3 Título»). Sin miniatura por episodio, porque aún no tienen imagen propia.
  - Un 404 o un id mal formado muestran «Serie no encontrada» con un enlace a `/series`.
- **Portada:** la fila «Series» (`LatestSeriesRow`) va tras «Novedades» y pide las 12 más recientes en paralelo al catálogo. Mientras carga reserva su hueco con un esqueleto; si no hay series no aparece; si falla, lo dice con «Reintentar» sin tapar el resto.
- **Barra:** «Series» es un `NavLink` (también queda marcado en `/series/7`), igual que «Películas» (capítulo 20.8).
- **Buscador:**
  - Busca películas y series en paralelo (10 de cada) con **un solo `AbortController`**, y espera a las dos con `Promise.allSettled` para que la lista no crezca bajo el puntero.
  - Muestra dos grupos ARIA («listbox con opciones agrupadas»); las flechas recorren todo en el orden visual. Intro abre el modal (película) o navega a la página (serie).
  - Si una de las dos peticiones falla, se muestra la otra con una nota que lo explica.
- **«Mi lista»:**
  - `FavoritesContext` gestiona dos listas que se cargan juntas (`Promise.all`): si falla una, toda la lista muestra el error, porque enseñar media lista haría creer que se ha perdido la otra mitad.
  - `toggleSeries` sigue el mismo patrón optimista (409/404 = el servidor ya tiene el estado deseado). Los pendientes se identifican por `tipo:id`, para que la película 3 y la serie 3 no se confundan.
  - «Vaciar lista» envía siempre los dos `DELETE`, vacía en pantalla solo lo que el servidor confirmó y, si falla uno, dice qué se quitó y qué no.
  - La página tiene dos secciones con su propio estado vacío.

### 15 bis.6 Panel de administración

- **Pestaña «Series»** (`AdminSeriesPage`, `SeriesFormPage`). Funciona como la de películas y comparte con ella:
  - `hooks/useAdminSearchList`: búsqueda con debounce, cancelación y `?q=&page=` en la URL.
  - `hooks/useCatalogDelete`: borrado con confirmación, no optimista; un 404 se trata como «ya borrado».
  - En `pages/admin/`: `AdminRowActions`, `GenreCheckboxes`, `CoverPreview` y `formErrors`.
- **Listado:** las series vacías llevan la marca «Sin episodios · oculta para los usuarios», con icono y texto, no solo color. El diálogo de borrado dice cuántos episodios se borran con la serie.
- **Formulario:**
  - Valida con `lib/seriesValidation.ts`, que copia literalmente los mensajes del servidor, y no deja enviar hasta que cargan los géneros.
  - **Al crear, lleva a la edición** de la serie nueva («Serie creada. Añade episodios para que sea visible»).
  - Al guardar cambios se queda en la página, porque debajo está la gestión de episodios.
- **Episodios** (`SeriesEpisodesSection` + `EpisodeFormDialog`):
  - Están agrupados por temporada. Cada uno tiene las acciones «Editar episodio T1:E3 Título» y «Borrar episodio T1:E3 Título»; el código hace único el nombre accesible.
  - **Añadir y editar usan un único diálogo.** Al añadir, propone la última temporada y el número siguiente al más alto, y lo recalcula si cambias la temporada, salvo que ya hayas escrito el número a mano.
  - El 409 `EPISODE_ALREADY_EXISTS` aparece junto a «Temporada» y «Número», con el foco ahí.
  - **Tras cada cambio se vuelve a pedir la serie al servidor** (`onSeriesChange`): temporadas, recuentos y la marca de visibilidad son siempre los del servidor, nunca un cálculo local.
  - Los avisos dicen cuándo cambia la visibilidad: con el primer episodio, «ya es visible para los usuarios»; al borrar el último, «vuelve a estar oculta».

### 15 bis.7 Tests

| Qué | Dónde |
| :--- | :--- |
| Esquema (H2) | `FlywaySchemaIntegrationTest`, `SeriesRepositoryIntegrationTest` |
| Esquema y motor (PostgreSQL) | `PostgresSeriesSchemaIntegrationTest`, `PostgresSeriesCatalogIntegrationTest`, `PostgresBaselineAdoptionIntegrationTest` (V3 desde cero, adopción de una base antigua, SQLSTATE reales, `lower()` con İ/Σ, carreras reales entre transacciones) |
| API | `SeriesCatalogIntegrationTest`, `SeriesAdminIntegrationTest`, `SeriesFavoritesIntegrationTest`, `SeriesQaEdgeCasesIntegrationTest` (límites, visibilidad completa, concurrencia real en H2: 4 hilos × 5 rondas → una alta y tres 409) |
| Seguridad | `SeriesAuthorizationIntegrationTest` (reglas), `SeriesObjectLevelAuthorizationIntegrationTest` (IDOR y oculta = inexistente) |
| Frontend | Vitest de páginas, componentes, validación y panel; E2E `series.spec.ts` y `admin-series.spec.ts` (este en el proyecto `catalogo-mutable`). La siembra E2E crea 5 series, una sin episodios creada la última: si se colara en algún listado sería el banner, así que su ausencia se nota |

La prueba de mutación de `qa` confirmó que estos tests detectan si el detalle público deja de filtrar las series vacías o si un episodio se busca sin su serie.

---

## 16. Gestión de errores

### 16.1 El formato común

Todos los errores de la API, vengan de donde vengan, tienen la misma forma (`dto/ErrorResponse`):

```json
{
  "timestamp": "2026-10-02T10:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Los datos proporcionados no son válidos",
  "path": "/api/movies",
  "validationErrors": { "title": "no debe estar vacío", "releaseYear": "debe ser menor que o igual a 2100" }
}
```

`validationErrors` solo aparece en errores de validación, y **`remainingAttempts`** solo en el 401 de un login fallido (capítulo 11). `@JsonInclude(NON_NULL)` omite los campos nulos, así que en el resto de errores no aparecen.

**El campo importante para los clientes es `code`**, no `message`: el mensaje es para mostrar al usuario y puede cambiar; el código es un contrato estable.

### 16.2 Códigos de error (`dto/ErrorCode`)

| `code` | HTTP | Cuándo |
| :--- | :--- | :--- |
| `VALIDATION_ERROR` | 400 | `@Valid` falla, parámetro mal formado, `sort`/`direction`/`page` no permitidos |
| `MALFORMED_REQUEST` | 400 | JSON mal escrito o con tipos imposibles |
| `INVALID_CREDENTIALS` | 401 | Login incorrecto (con `remainingAttempts`), y también 401 por falta de token |
| `ACCESS_DENIED` | 403 | Autenticado sin el rol necesario |
| `RESOURCE_NOT_FOUND` | 404 | Película, género o usuario inexistente; ruta inexistente |
| `MOVIE_NOT_IN_FAVORITES` | 404 | Quitar de la lista algo que no estaba |
| `SERIES_NOT_IN_FAVORITES` | 404 | Quitar de la lista una serie (visible) que no estaba |
| `SERIES_ALREADY_IN_FAVORITES` | 409 | Añadir a la lista una serie que ya estaba |
| `EPISODE_ALREADY_EXISTS` | 409 | Crear o mover un episodio a una temporada y número ya ocupados («Ya existe el episodio N de la temporada T») |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP no soportado en esa ruta |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Cuerpo que no es JSON |
| `USER_ALREADY_EXISTS` | 409 | Registro con usuario o email en uso |
| `MOVIE_ALREADY_IN_FAVORITES` | 409 | Añadir a la lista algo que ya estaba |
| `AMBIGUOUS_TITLE` | 409 | Varias películas con el mismo título en `/by-title` |
| `GENRE_ALREADY_EXISTS` | 409 | Crear o renombrar un género con un nombre que ya tiene otro (sin distinguir mayúsculas) |
| `GENRE_IN_USE` | 409 | Borrar un género que tiene asignado alguna película o serie (el mensaje dice cuántas: «lo usan 3 películas y 2 series») |
| `DATA_INTEGRITY_VIOLATION` | 409 | Choque no previsto con una restricción de la base de datos (respaldo genérico) |
| `RATE_LIMIT_EXCEEDED` | 429 | Límite por IP de login o registro (con `Retry-After`) |
| `ACCOUNT_LOCKED` | 429 | Cuenta bloqueada por demasiados logins fallidos: el fallo que agota los intentos y cualquier intento durante el bloqueo (con `Retry-After`) |
| `INTERNAL_ERROR` | 500 | Cualquier error no previsto |

### 16.3 Cómo funciona `GlobalExceptionHandler`

`…/exception/GlobalExceptionHandler.java` está anotado con `@RestControllerAdvice`: Spring lo consulta cada vez que un controlador (o algo que este llama) lanza una excepción. Cada método `@ExceptionHandler(X.class)` atiende un tipo de excepción y construye la respuesta.

Tiene tres bloques:

1. **Excepciones de dominio** (las nuestras). Los servicios lanzan excepciones con significado (`MovieNotFoundException`, `UserAlreadyExistsException`…) y el manejador decide su código HTTP. Así los servicios no saben nada de HTTP.
   - **Jerarquía**: `MovieNotFoundException`, `GenreNotFoundException`, `UserNotFoundException` y `MovieNotInFavoritesException` heredan de `ResourceNotFoundException`. Un solo manejador atiende todos los 404, y una excepción nueva «no encontrado» funciona sin tocar el manejador. (`MovieNotInFavoritesException` tiene su propio manejador para darle un código más específico; Spring siempre elige el manejador más concreto.)
2. **Excepciones estándar de Spring MVC.** La clase **hereda de `ResponseEntityExceptionHandler`**, que ya sabe convertir cada error del framework en su código correcto (404 ruta inexistente, 405, 415, JSON ilegible…). Se sobrescriben sus métodos solo para cambiar el **formato** a `ErrorResponse`. Sin esta herencia, el manejador genérico de `Exception` capturaría todo eso y lo convertiría en 500.
3. **El último recurso** (`@ExceptionHandler(Exception.class)`): cualquier error no previsto se registra en el log **con su traza completa** y el cliente recibe un mensaje genérico. **Nunca se devuelve el mensaje de una excepción del framework**, porque puede revelar nombres de clases, tablas o rutas internas.

**Los 401 y 403 no pasan por aquí** (capítulo 9.4): los genera Spring Security antes de llegar a los controladores.

---

## 17. Actuator, Swagger y logs

### 17.1 Actuator (comprobaciones de salud)

Spring Boot Actuator expone endpoints de operación. Solo se exponen dos:

| Endpoint | Acceso | Para qué |
| :--- | :--- | :--- |
| `/actuator/health` | Público | `{"status":"UP"}` o `DOWN`. Incluye la conexión a la base de datos. **Sin detalles** (`show-details=never`), para no revelar qué tecnologías se usan |
| `/actuator/health/liveness` | Público | «El proceso está vivo» (para que Docker/Kubernetes lo reinicien si no) |
| `/actuator/health/readiness` | Público | «Puede recibir tráfico» (base de datos lista) |
| `/actuator/info` | Con token | Información de la aplicación |

En Docker, el `HEALTHCHECK` del contenedor del backend usa `/actuator/health/readiness`. nginx **no publica `/actuator`**: desde fuera solo se llega a `/api/` (capítulo 4.6).

El resto (`env`, `beans`, `heapdump`…) **no se exponen**: algunos mostrarían variables de entorno y secretos.

### 17.2 Swagger / OpenAPI

`springdoc` genera la documentación interactiva a partir de las anotaciones `@Operation` y `@ApiResponses` de los controladores. Está en `http://localhost:8080/swagger-ui.html` (solo en `dev`).

`config/OpenApiConfig` define el título y el esquema de seguridad `bearerAuth`: en Swagger, pulsas «Authorize», pegas el token y todas las peticiones lo llevan. Los controladores con `@SecurityRequirement(name = "bearerAuth")` muestran el candado.

**Respuestas de error en el OpenAPI.** springdoc rellena cada `@ApiResponse` sin `content` con el tipo de retorno del método. Por eso, hasta octubre de 2026, el 401 del login aparecía documentado como un `LoginResponse` (el token) y el 404 de películas como una `MovieResponse`: un cliente generado a partir del OpenAPI habría leído un error como si fuera un token. Ahora `config/ErrorResponseOpenApiCustomizer` (un `GlobalOpenApiCustomizer` registrado como `@Bean` en `OpenApiConfig`) recorre el documento ya generado y hace dos cosas:

- A toda respuesta cuyo código empieza por 4 o 5 le pone como cuerpo `application/json` con `$ref` a `ErrorResponse`.
- A los 429 les añade la cabecera `Retry-After` (entero ≥ 1).

Las 2xx conservan su tipo y los 204 siguen sin cuerpo. En los controladores basta con escribir el código y la descripción de cada error: la regla se aplica sola también a los endpoints nuevos. El esquema de `ErrorResponse` se describe con `@Schema` en el propio DTO (solo documenta, no cambia el JSON). Los seis campos fijos son `required`; `validationErrors` y `remainingAttempts` son opcionales. Si se añade un `ErrorCode` nuevo hay que explicarlo también en la descripción de `code`, o `OpenApiErrorResponseDocumentationIntegrationTest` fallará. Ese test recorre todas las rutas y falla si alguna 4xx/5xx documenta otro esquema.

Al generar `/v3/api-docs` aparece 4 veces un WARN de `SpringDocUtils` («Json Processing Exception…»). Es un fallo inofensivo de springdoc al clonar el esquema de `page`/`size` en modo OpenAPI 3.1; esos parámetros conservan sus `minimum`/`maximum`.

### 17.3 Logs

- En **`dev`**, `show-sql=true` y `format_sql=true` muestran cada consulta SQL formateada en la consola. Muy útil para ver el N+1 con tus propios ojos.
- En **`prod`**, sin SQL (puede contener datos personales) y en **formato JSON** (ECS): un objeto por línea, que herramientas como Loki, ELK o CloudWatch leen sin interpretar texto libre.
- `prod` también activa `server.shutdown=graceful`: al apagar, termina las peticiones en curso en vez de cortarlas.

---

## 18. Frontend: arquitectura

### 18.1 Carpetas

```
frontend/src/
├── main.tsx            punto de entrada: monta <App/> en el HTML
├── App.tsx             proveedores globales + rutas
├── index.css           Tailwind + tokens de diseño (@theme) + utilidades propias
├── pages/              una por pantalla: HomePage, LoginPage, RegisterPage, MyListPage, MoviesPage, SeriesPage, SeriesDetailPage
│   └── admin/          panel de administración: AdminLayout, AdminMoviesPage, MovieFormPage, AdminGenresPage
├── components/         piezas reutilizables (Navbar, Modal, MoviePoster, Pagination, Button...) y las bases
│                       genéricas del catálogo (PosterCard, PosterRow, FeaturedBanner, MetaTags), comunes a películas y series
├── context/            estado compartido: AuthContext, ToastContext, FavoritesContext
├── hooks/              lógica reutilizable: useCatalog/usePagedCatalog, useMovieFilters, useSeriesCatalog, useSeriesDetail, useModalDialog,
│                       useCountdown, useGenres, useDebouncedValue, useAdminSearchList, useCatalogDelete...
├── lib/                sin React: api.ts, types.ts, utils.ts, catalog.ts, series.ts, movieFilters.ts, validation.ts, movieValidation.ts,
│                       seriesValidation.ts, episodeValidation.ts
└── test/               utilidades de los tests (setup, helpers, fakeTimers)
```

### 18.2 Proveedores y rutas (`App.tsx`)

```tsx
<BrowserRouter>                    // enrutador (URLs sin recargar la página)
  <ToastProvider>                  // avisos emergentes
    <AuthProvider>                 // sesión (necesita los avisos: "sesión caducada")
      <SkipLink />
      <Routes>
        /login, /registro          → envueltas en RedirectIfAuthenticated
        (layout) RequireAuth + AppShell   // AppShell monta FavoritesProvider + Navbar + <main>
            /            → HomePage
            /favorites   → MyListPage
            /my-list     → redirige a /favorites
            /peliculas   → MoviesPage (filtros en ?genero=&anio=&orden=)
            /series      → SeriesPage
            /series/:id  → SeriesDetailPage (temporada en ?temporada=N)
            /admin       → RequireAdmin + AdminLayout (h1 «Administración» y pestañas)
                (índice)              → redirige a /admin/peliculas
                peliculas             → AdminMoviesPage
                peliculas/nueva       → MovieFormPage (alta)
                peliculas/:id/editar  → MovieFormPage (edición)
                series                → AdminSeriesPage
                series/nueva          → SeriesFormPage (alta)
                series/:id/editar     → SeriesFormPage (edición) + SeriesEpisodesSection
                generos               → AdminGenresPage
        *                → redirige a /
```

Un **contexto** de React es una forma de compartir un valor con todos los componentes de debajo sin pasarlo de padre a hijo. Los proveedores van en este orden porque cada uno usa al de fuera.

**Guardas de ruta** (`components/RouteGuards.tsx`):

- `RequireAuth`: sin sesión, redirige a `/login`.
- `RedirectIfAuthenticated`: con sesión, `/login` y `/registro` redirigen a `/`.
- `RequireAdmin` (envuelve todo `/admin`): mientras se carga el usuario muestra «Comprobando permisos...» (así, recargar en `/admin` no expulsa a un administrador real antes de saber su rol); si la carga falla, no enseña el contenido y ofrece «Reintentar»; si el usuario es `USER`, redirige a `/`.

`RequireAuth` y `RedirectIfAuthenticated` esperan al primer chequeo de sesión (`isCheckingSession`, ver 19.2) y muestran «Comprobando tu sesión...» en lugar de redirigir o enseñar el formulario, así que no hay «parpadeo». Recuerda que esto es solo experiencia de usuario: **la seguridad real la pone el backend**, que rechaza cualquier petición sin token válido.

**`FavoritesProvider` vive dentro de `AppShell`**: solo existe en la zona autenticada. Al cerrar sesión se desmonta y la lista de un usuario no puede verla el siguiente.

### 18.3 El proxy de Vite

```ts
proxy: { '/api': { target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true } }
```

En desarrollo, el navegador pide `http://localhost:5173/api/movies` y Vite lo reenvía a `http://localhost:8080/api/movies`. La variable `VITE_API_PROXY_TARGET` solo la usan los tests E2E, que levantan su propio backend en el 8099.

---

## 19. Frontend: cliente de API y sesión

### 19.1 `apiFetch` (`lib/api.ts`)

**Todas** las llamadas a la API pasan por esta función. Así, el manejo de la sesión y de los errores vive en un solo sitio y las pantallas solo tienen que capturar un `ApiError` con un mensaje ya preparado.

```ts
const page = await apiFetch<PageResponse<Movie>>('/movies', {
  params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc' },
  signal: controller.signal,
});
```

Qué hace, paso a paso:

1. Envía `credentials: 'same-origin'` (la cookie de sesión viaja sola) y, en todo método que no sea GET/HEAD/OPTIONS, la cabecera `X-Requested-With: StreamBox` (defensa CSRF). Ya no hay cabecera `Authorization`.
2. Construye la query con `URLSearchParams` (que codifica caracteres especiales) y omite los parámetros vacíos.
3. Convierte `body` a JSON.
4. Hace el `fetch`. Si falla la red (backend apagado, sin conexión) → `ApiError` con `status: 0` y código `NETWORK_ERROR`.
5. Lee la respuesta como texto. Si está vacía (204) devuelve `undefined`; si no es JSON válido → `INVALID_RESPONSE`.
6. **Comprueba siempre `res.ok`.** Si la respuesta es de error, construye un `ApiError`:

| Respuesta | Mensaje para el usuario | Efecto |
| :--- | :--- | :--- |
| 401 en endpoint privado | «Tu sesión ha caducado…» | Avisa a `AuthProvider` para cerrar sesión (`sessionExpired: true`) |
| 401 en endpoint público | El del servidor («Email o contraseña incorrectos») | **No** cierra sesión: aquí significa credenciales incorrectas |
| 403 | «No tienes permisos…» | **No** cierra sesión: el usuario sí está identificado |
| 429 | «Demasiados intentos. Inténtalo de nuevo en N s.» | `retryAfterSeconds` sale de la cabecera `Retry-After`; el `code` distingue `ACCOUNT_LOCKED` de `RATE_LIMIT_EXCEEDED` |
| 5xx | «El servidor ha tenido un problema…» | — |
| Otros 4xx | El `message` del servidor (ya en español y específico) | `validationErrors` se conserva para pintarlos junto a cada campo |

7. Las **cancelaciones** (`AbortController`) se relanzan tal cual. `isAbortError` las reconoce para ignorarlas: no son errores, sino peticiones que ya no interesan (el usuario salió de la pantalla o escribió otra letra en el buscador).

### 19.2 `AuthContext`: la sesión

`context/AuthContext.tsx` gestiona la sesión, pero **ya no hay token en el cliente**: vive en la cookie HttpOnly `streambox_token`, invisible para JavaScript (un XSS ya no puede robarlo). La sesión se descubre al arrancar con `GET /api/users/me` (200 = sesión; 401 = sin sesión, en silencio; red/5xx = error con «Reintentar»), con tres estados (`unknown`, `active`, `none`) y un contador `epoch` que descarta respuestas tardías. Expone `isAuthenticated`, `isCheckingSession`, `login(email, password)` (llama a `/auth/login` y luego a `/users/me`), `logout()` (limpia el estado y pide `POST /auth/logout` para borrar la cookie), `user`, `isAdmin`, `userStatus` y `refreshUser`. Se borra al arrancar la clave `token` heredada de `localStorage`.

**El puente con `apiFetch`.** `api.ts` necesita el token y avisar de los 401, pero no puede importar `AuthContext` (sería una dependencia circular: el contexto ya importa `api.ts`). La solución: `api.ts` ofrece `configureAuth(puente)` y el `AuthProvider` se registra al montarse, pasando dos funciones: `getToken` y `onUnauthorized`.

**El 401 sin bucles.** Si el token caduca y la portada lanza tres peticiones a la vez, llegan tres 401. Sin cuidado, habría tres cierres de sesión, tres avisos y tres redirecciones. El diseño lo evita:

1. `apiFetch` **no navega nunca**; solo llama a `onUnauthorized(tokenUsado)`.
2. `onUnauthorized` ignora el aviso si ya no hay sesión o si el token usado **no es el actual** (una respuesta tardía de una sesión anterior).
3. El primer aviso cierra la sesión y muestra un único toast. Los siguientes ya encuentran la sesión cerrada y no hacen nada.
4. Al quedar `isAuthenticated = false`, `RequireAuth` redirige a `/login` **una vez**.

**Detalles que explican el código:**

- `useLayoutEffect` registra el puente **antes** que cualquier `useEffect`. Los componentes hijos lanzan peticiones en sus `useEffect` al montarse, y esos efectos se ejecutan antes que los del padre; los *layout effects* se ejecutan antes que todos ellos.
- Sin sincronización inmediata entre pestañas (ya no hay `localStorage` ni evento `storage`): la otra pestaña se entera en su siguiente petición (401 con aviso).

**El usuario actual y su rol.** Además del token, `AuthProvider` carga el usuario con `GET /api/users/me` cada vez que cambia la sesión: tras `login()`, al arrancar con un token guardado y cuando otra pestaña cambia el token. `useAuth()` expone `user`, `isAdmin`, `userStatus` (`idle` sin sesión, `loading`, `ready` o `error`) y `refreshUser()` para reintentar.

- **Por qué se pregunta al servidor y no se lee del JWT.** El token no lleva el rol (solo el email como `sub` y el emisor). Y aunque lo llevara, quedaría desfasado hasta que caducase (24 h), mientras que el backend lee el usuario de la base de datos en cada petición y un cambio de rol es inmediato. Preguntar a `/users/me` mantiene esa misma coherencia en el cliente.
- `login()` sigue siendo síncrono: la carga del usuario va detrás y la navegación no espera por ella.
- **Respuestas tardías.** El resultado se guarda junto al token (y al número de intento) que lo pidió, y `user`/`userStatus` se calculan comparándolo con la sesión actual: el usuario de una sesión anterior nunca se asigna a la nueva. Además, cada cambio cancela la petición en curso con `AbortController`. Son dos defensas independientes.
- **Errores.** Un 401 lo gestiona `apiFetch` como cualquier otro (cierra sesión y avisa una vez). Si falla por red o un 5xx, la sesión se mantiene con `userStatus = 'error'` e `isAdmin = false`: **falla cerrado** (ante la duda, no se muestra nada de administrador).
- **El rol del cliente solo decide qué se pinta**: la etiqueta «Administrador» del menú de usuario (`Navbar`, que muestra también «Sesión iniciada como» y el nombre), y la guarda `RequireAdmin`. La seguridad real es el 403 del backend.
- En los tests, `routeFetch` (`src/test/helpers.tsx`) responde por defecto a `/users/me` con un usuario `USER` (`makeUser`), y cada test puede sobrescribirlo.

**El login** (`pages/LoginPage.tsx`) llama a `apiFetch('/auth/login', { method: 'POST', body, public: true })`, guarda el token con `login(token)` y ya está: `RedirectIfAuthenticated` ve la sesión y lleva a `/`.

> **Hecho en la tarea 29.** El JWT va en cookie HttpOnly (`SameSite=Strict`, `Path=/api`, `Secure` según `streambox.auth.cookie.secure`/`STREAMBOX_AUTH_COOKIE_SECURE`, `true` por defecto; el compose HTTP en localhost lo pone a `false`). Riesgos residuales: sin revocación (el logout solo borra la cookie; el JWT dura 24 h), login CSRF mitigado con SameSite, y siguen pendientes vida corta + refresh, y HSTS con HTTPS.

---

## 20. Frontend: pantallas y estado

### 20.1 Portada (`pages/HomePage.tsx` + `hooks/useCatalog.ts`)

`useCatalog` carga el catálogo **de la película más reciente a la más antigua**:

- Carga inicial: `GET /movies?page=0&size=20&sort=createdAt&direction=desc`.
- «Cargar más películas»: pide la página siguiente mientras la respuesta diga `hasNext: true`, y la **añade al final** sin duplicados (`mergeById`). El banner no cambia y el scroll no salta.
- Estados separados para la carga inicial (`status`: cargando, listo, error) y para «cargar más» (`loadingMore`, `loadMoreFailed`): si falla «cargar más», no se pierde lo ya cargado; aparece un aviso y el botón pasa a «Reintentar».
- Cancela las peticiones en vuelo al salir de la pantalla (`AbortController`).

`HomePage` reparte las películas así:

- **Banner (hero)** (`components/HeroBanner.tsx`): la primera, es decir, la más reciente. Lleva póster, título, metadatos, una sinopsis recortada a 3 líneas y tres botones con jerarquía: «Ver ahora» (principal, variante `light`), «Mi lista» (secundaria, `outline`) y «Más información» (terciaria, `ghost`, sin borde), que abre el modal con la sinopsis completa. En los metadatos, «Estreno reciente» es una etiqueta de acento (el primer `<li>` de la misma lista de datos: como `<span>` aparte, en 375 px la lista entera no cabía a su lado y la etiqueta quedaba sola en una línea), el año y la duración van en texto plano con cifras tabulares, y solo los géneros llevan etiqueta (`MovieMetaTags`): si todo es etiqueta, nada destaca. Va a sangre (todo el ancho) bajo la barra superior. Ver en 21.3 por qué el póster se muestra dos veces y por qué va junto al título.
- **Mientras carga**, la portada muestra `CatalogSkeleton`: siluetas con la forma real del banner y de una fila (`role="status"` con texto para lectores de pantalla; las siluetas están ocultas a la accesibilidad). Con un spinner, toda la pantalla cambiaba de golpe al llegar los datos; con el esqueleto, el contenido aparece donde ya se esperaba.
- **Filas** (`lib/catalog.ts` → `buildCatalogRows`, con el resto de películas): «Novedades» con las 12 primeras, y **una fila por género** que tenga al menos 3 películas (una fila con 1 o 2 queda casi vacía). Las filas de género se ordenan de más a menos películas.

### 20.2 «Mi lista» y favoritos (`context/FavoritesContext.tsx`)

Antes cada pantalla guardaba su propia copia de la lista y se desincronizaban. Ahora hay **una sola copia compartida**: la portada, el banner, el modal, el buscador y «Mi lista» ven siempre lo mismo.

**Actualización optimista.** Al pulsar «Mi lista», la interfaz cambia **al instante** y la petición va detrás:

1. Se marca la película como «pendiente» (el botón se bloquea; evita el doble clic).
2. Se añade o quita de la lista local inmediatamente.
3. Se envía `POST` o `DELETE` al servidor.
4. Si va bien → aviso de éxito.
5. Si falla → se **deshace solo ese cambio** (no se restaura una copia vieja de toda la lista, que podría pisar otros cambios en curso) y se avisa.
6. **Excepción**: un 409 al añadir («ya estaba») o un 404 al quitar («ya no estaba») significan que el servidor **ya tiene el estado deseado** (por ejemplo, lo cambiaste en otra pestaña). No se deshace nada; solo se informa.

**Vaciar la lista no es optimista**: es destructivo, así que primero se confirma con un diálogo y se espera a la respuesta del servidor.

**La lista vacía enseña qué va a pasar.** En lugar de un icono genérico, `EmptyState` recibe en la prop `visual` una ilustración decorativa (tres huecos de póster en abanico con un «+») y el texto nombra el botón que hay que pulsar. Un estado vacío es la primera vez que el usuario ve esa pantalla: debe decirle cómo llenarla.

### 20.3 Registro (`pages/RegisterPage.tsx` + `lib/validation.ts`)

- Valida en el navegador con **los mismos límites que el backend** (usuario 3–50, contraseña 12–64, email), para dar feedback inmediato. **La validación que manda es la del servidor**: la del cliente se puede saltar. El resto de la política de contraseñas (comunes, datos personales, 72 bytes; capítulo 10.5) solo la comprueba el servidor, y su mensaje se pinta junto al campo: así la lista de contraseñas comunes no se duplica en el cliente.
- Si el servidor devuelve `validationErrors`, cada mensaje se pinta junto a su campo y el foco va al primer error.
- 409 (usuario o email en uso) se muestra como aviso del formulario.
- 429: el botón se bloquea con una cuenta atrás (`hooks/useCountdown.ts`) usando `retryAfterSeconds`. La cuenta atrás es solo comodidad: el límite real lo impone el servidor.
- Éxito: lleva a `/login` con un aviso. No inicia sesión automáticamente.
- **Aspecto** (común a login y registro, `components/AuthLayout.tsx`): formulario sin tarjeta, en una columna `max-w-sm`, con una luz radial ámbar muy tenue desde arriba (solo CSS, retoma el punto del logo). La tarjeta centrada era el patrón de plantilla más reconocible y, en móvil, estrechaba los campos. Los campos (`FormField`) usan el token `field-border` (ver 21.1).

### 20.4 Avisos (`context/ToastContext.tsx`)

`useToast()` ofrece `success`, `error`, `info` y `errorFrom(error, mensajePorDefecto)`. Máximo 4 a la vez; desaparecen solos (5 s, o 9 s los errores, que necesitan más tiempo de lectura) y se pausan al pasar el ratón o el foco por encima. `errorFrom` ignora los errores de sesión caducada, que ya tienen su propio aviso.

### 20.5 Buscador (`components/SearchBar.tsx`)

- **Debounce de 300 ms**: no busca con cada tecla, sino cuando dejas de escribir 300 ms.
- **Cancelación**: si escribes otra letra mientras una búsqueda está en vuelo, la anterior se cancela. Sin esto, una respuesta lenta antigua podría llegar después y sustituir a la nueva.
- Pide `/movies/search?title=...&size=10&sort=title`.
- Teclado: ↑/↓ recorren los resultados, Intro abre la película, Escape cierra y vacía.
- La opción resaltada lleva un contorno de acento alrededor de toda la fila (3,5:1 sobre el fondo), no una barra lateral de color: esa barra es uno de los patrones que más delatan una interfaz generada y, además, solo marca un borde.

### 20.5 bis Login: intentos restantes y bloqueo (`pages/LoginPage.tsx`)

`ApiError` expone `remainingAttempts` (tipado y opcional), copiado del cuerpo del error. La pantalla decide por `status`, `code` y ese campo, nunca por el texto:

- **401 con `remainingAttempts`**: el error genérico («Email o contraseña incorrectos») y, debajo, «Te quedan N intentos antes de que la cuenta se bloquee 15 minutos.» (en singular con 1, y más visible).
- **429 `ACCOUNT_LOCKED`**: «Tu cuenta está bloqueada temporalmente por demasiados intentos fallidos» con una cuenta atrás legible en minutos y segundos, y el botón bloqueado (`useCountdown` + `retryAfterSeconds`).
- **429 `RATE_LIMIT_EXCEEDED`** (límite por IP): el aviso de «demasiados intentos», distinto del anterior.

Al terminar la espera, el aviso desaparece y el botón se reactiva. Todos los avisos van en regiones accesibles (`role="alert"`).

### 20.6 Modales (`components/Modal.tsx` + `hooks/useModalDialog.ts`)

Usan el elemento nativo `<dialog>` con `showModal()`, que ya da: capa por encima de todo, fondo inerte y foco atrapado dentro. El hook añade lo que el navegador no hace:

- Recuerda qué elemento lo abrió y **le devuelve el foco** al cerrar.
- **Bloquea el scroll** de la página de fondo (con un contador, por si hubiera modales anidados).
- Pone el foco inicial donde toca (el botón cerrar en el detalle; «Cancelar» en la confirmación, que es la opción segura).
- Escape y clic en el fondo cierran.

Un detalle: un `<dialog>` modal vuelve inerte todo lo de fuera, incluidos los avisos. Por eso, mientras hay un modal abierto, `ToastContext` mueve los avisos **dentro** del diálogo (con un *portal*, `registerHost`). **Solo los avisos nacidos con el modal abierto**: los anteriores se quedan en la página, detrás del fondo. Antes se trasladaban todos, y el aviso de la acción anterior («Episodio añadido») se colaba en el siguiente diálogo y le tapaba los botones durante 5 s. Era un fallo real que destapó el E2E de episodios; lo cubre un test de `ConfirmDialog.test.tsx`.

**Entrada con movimiento.** El `<dialog>` aparece con un fundido y una escala de 96 % a 100 % en 200 ms. Los avisos suben 8 px en 250 ms. Las dos animaciones son **transiciones** y no `@keyframes`: una transición se puede interrumpir a mitad (si cierras el modal mientras entra, vuelve desde donde está, sin saltos). El estado inicial se define con `@starting-style` (en `index.css` para el diálogo y con la variante `starting:` de Tailwind en `Toast.tsx`). La curva es `ease-out-strong` (`cubic-bezier(0.23, 1, 0.32, 1)`, token en `@theme`): arranca rápido y frena suave, de modo que la respuesta se percibe inmediata. Con `prefers-reduced-motion` se **conservan los fundidos de opacidad** (no marean y avisan de que algo apareció) y se quita el movimiento: el diálogo no crece (`@starting-style` con `scale: 1` dentro del `@media` de `index.css`), el aviso no sube (`motion-reduce:starting:translate-y-0`), y los hundidos al pulsar, el zoom de `PosterCard` y los giros/pulsos se anulan con la variante `motion-reduce:`. La regla global solo deja a ~0 ms las animaciones sueltas (`@keyframes`) y el scroll suave. `src/index.css.test.ts` comprueba que esa regla no vuelva a anular las transiciones.

### 20.7 Panel de administración (`pages/admin/`)

Solo para `ADMIN`. La barra muestra el enlace **«Administrar»** únicamente si `isAdmin` (y no mientras se carga el usuario). En 375 px se reduce al icono, con su nombre accesible. Todo `/admin` va dentro de `RequireAdmin`, pero recuerda que **esto es solo interfaz**: quien protege los datos es el backend (403 para un `USER`, regla de cierre del catálogo; capítulo 9.2).

- **`AdminLayout`**: un único `h1` «Administración» y dos pestañas, Películas y Géneros. Como cada pestaña es una **ruta**, son enlaces (`NavLink` con `aria-current="page"`) dentro de un `<nav aria-label="Secciones de administración">`, no el patrón ARIA `tablist`. Ese patrón es para paneles que se muestran sin cambiar de URL, y con enlaces funcionan el botón «Atrás», abrir en otra pestaña y compartir la dirección.
- **`AdminMoviesPage`** (listado):
  - Tabla con portada pequeña (`MoviePoster`), título, año, duración, géneros y acciones «Editar X» y «Borrar X» (el nombre accesible incluye el título).
  - En móvil la tabla se compacta (año, duración y géneros bajo el título, y acciones solo con icono) para no provocar scroll horizontal.
  - Buscador por título con debounce de 300 ms (`useDebouncedValue`) y cancelación (`AbortController`). Sin texto pide `/movies?sort=createdAt&direction=desc`; con texto, `/movies/search?title=…&sort=title`.
  - Paginación con «Anterior»/«Siguiente», «Página X de Y» y el total (`components/Pagination`). La búsqueda y la página viven en la URL (`?q=&page=`), así que recargar o volver atrás conserva el estado.
  - Estados de carga, vacío y error con reintento.
- **Borrar película**: `ConfirmDialog` («¿Borrar "Título"? También se quitará de las listas de todos los usuarios…»). Espera al servidor (no es optimista, porque es destructivo). Después avisa y recarga la página actual (retrocede una si queda vacía). Un 404 significa que ya estaba borrada: se informa y se recarga.
- **`MovieFormPage`** (alta y edición, el mismo componente):
  - Campos: título, sinopsis (con contador de caracteres), duración, año, URL de portada, URL de vídeo y géneros (casillas en un `fieldset` con `legend`, cargadas con `useGenres`; al menos uno).
  - La validación en el cliente (`lib/movieValidation.ts`) replica **exactamente** las reglas y los mensajes del servidor, incluidas las de URL (capítulo 13.3). Los errores del servidor (`validationErrors`) se pintan junto a cada campo y el foco va al primero.
  - **Vista previa de la portada**: se actualiza al escribir la URL, con un debounce de 400 ms, usando `MoviePoster` con un objeto película «borrador». Así se respeta la regla de que las imágenes salen siempre de un objeto película. Si la URL no es válida no se intenta cargar y se ve el respaldo.
  - En edición se carga `GET /api/movies/{id}` (un 404 muestra un mensaje con enlace al listado). Si no hay géneros, el formulario lo dice y enlaza a la pestaña Géneros.
- **`AdminGenresPage`**:
  - Alta arriba y lista con **renombrar en línea** (el campo sustituye al nombre; Escape cancela y el foco vuelve al botón) y «Borrar X» con confirmación.
  - Un 409 `GENRE_ALREADY_EXISTS` se muestra junto al campo. Un 409 `GENRE_IN_USE` muestra el mensaje del servidor («lo usan 3 películas…») y el género sigue en la lista.
- **Foco bajo la barra fija.** La barra superior es `sticky` en todos los anchos. Cuando un formulario mueve el foco al primer error, el navegador podía dejar el campo justo debajo de la barra, tapado (incumple WCAG 2.2 · 2.4.11). `Navbar` publica su altura real en la variable CSS `--navbar-height` (`hooks/useHeightCssVariable`, con un `ResizeObserver`, porque la barra mide 57 px en escritorio, 165 px en móvil y 213 px a 320 px con «Administrar»). `index.css` aplica `scroll-margin-top: calc(var(--navbar-height) + 2rem)` al contenido de `<main>` (no a los `<dialog>`, que van por encima de todo). Se descartó `scroll-padding-top` en `html` porque también desplazaba la página al enfocar los controles de la propia barra.

### 20.8 Página Películas (`pages/MoviesPage.tsx`)

`/peliculas` es el destino de «Películas» en la barra. Tiene **dos vistas**:

- **Sin filtros:** la misma estructura que `/series` (capítulo 15 bis.5). Lleva un h1 «Películas», el banner con la más reciente (`HeroBanner`), «Novedades» y filas por género (`buildCatalogRows`, con al menos 3 títulos sin contar el del banner). Debajo, «Mostrando N de M películas» con «Cargar más películas», y los estados de carga (esqueleto), vacío y error.
- **Con algún filtro:** una **cuadrícula de resultados** de `GET /api/movies/search` con `genreId`, `releaseYear`, `sort` y `direction`. No filtra por título, porque eso ya lo hace el buscador de la barra. Tiene:
  - el recuento en una región `role="status"`;
  - «Cargar más» sin duplicados;
  - el vacío «No hay películas con estos filtros», con «Quitar filtros»;
  - el error, con «Reintentar».

Las tarjetas abren el modal de detalle de siempre (`MovieDetailsModal`). Una página propia por película (`/peliculas/:id`, como las series) queda como posible mejora en el plan.

**La barra de filtros** (`components/MovieFilterBar.tsx`) tiene tres controles con su etiqueta: **Género** (`<select>` con «Todos los géneros»), **Año** y **Ordenar por**. «Quitar filtros» solo aparece si hay algún filtro y va **unido al desplegable de orden** (prop `trailing` de `SelectField`). Antes llevaba un margen fijo para alinearse con los controles: cuando bajaba solo de línea a 768 px, dejaba un hueco de 38 px. Ahora baja junto con el desplegable, y lo vigila un E2E.

- **¿Por qué el año es un campo de texto** (`inputmode="numeric"`) y no `type="number"` ni una lista?
  - Con `type="number"`, la rueda del ratón cambia el valor al desplazar la página, y además admite `e` y decimales.
  - Una lista tendría 213 opciones (1888–2100), y no hay endpoint de «años con películas».
  - Se valida en el cliente con las mismas constantes que el backend. El error aparece al llegar a 4 cifras o al salir del campo, no mientras escribes «20…», y un año inválido no se aplica.

**Los filtros viven en la URL** (`?genero=<id>&anio=<año>&orden=<clave>`; `lib/movieFilters.ts`). Así se pueden compartir, «Atrás» vuelve al filtro anterior, y los valores inválidos de la URL se ignoran sin romper la página. Las claves de orden son:

| Clave | Orden |
| :--- | :--- |
| `recientes` (por defecto, no se escribe en la URL) | `createdAt desc` |
| `titulo-asc` / `titulo-desc` | Título A–Z / Z–A |
| `anio-desc` / `anio-asc` | Año: más nuevas / más antiguas |
| `duracion-asc` / `duracion-desc` | Duración: más cortas / más largas |

**`useMovieFilters`** guarda un borrador de los controles y lo pasa a la URL tras 300 ms, o al momento con Intro. El retraso se aplica **también a los desplegables**: en Windows, las flechas sobre un `<select>` cerrado disparan un `change` cada una, y sin espera recorrer los órdenes con el teclado dejaba una entrada de historial y una petición por flecha.

Un detalle que costó un bug: `setSearchParams` de React Router cambia de identidad con cada URL. Por eso, tras aplicar con Intro, se volvía a aplicar el borrador anterior y se deshacía el filtro. Lo evita la marca `handledDraft`, y hay un test que lo reproduce.

**Datos:** las dos vistas usan una sola llamada a `usePagedCatalog`, que ahora acepta una `CatalogQuery` (`sort`, `direction`, `genreId`, `releaseYear`). Al cambiar de consulta, reinicia la lista durante el render y cancela con `AbortController` la carga y el «cargar más» anteriores, así que una respuesta lenta del filtro anterior nunca pisa la del nuevo. El pie «Mostrando N de M / Cargar más» es `LoadMoreFooter`, compartido con la portada y `/series`.

### 20.9 Estados vacíos según el rol

Un estado vacío tiene que **decir la verdad a quien lo lee**. El caso que lo motivó: un administrador creó 15 series sin episodios (ocultas por diseño) y `/series` le decía «Todavía no hay series… echa un vistazo a las películas». Para él era falso y no explicaba nada. Desde entonces:

- **`/series` y `/peliculas` vacías:**
  - Al **usuario** le dicen «Todavía no hay series/películas. Cuando se publiquen aparecerán aquí.», con «Ir al inicio».
  - Al **administrador** le explican el motivo («las series solo aparecen cuando tienen al menos un episodio») y le ofrecen «Gestionar series/películas», que lleva al panel.
- **Fila «Series» de la portada:** para un usuario, si no hay series visibles, no se pinta. Un administrador ve en su lugar una sola línea que explica por qué los usuarios no la ven, con «Gestionar series».
- **Portada sin películas:** delega en la fila «Series» si las hay. Solo si no hay nada se muestra «El catálogo está vacío» (con su versión para administradores).
- **`/series/:id` con 404:** el administrador lee además que una serie sin episodios solo se ve en el panel. El texto no confirma que la serie exista.
- **«Mi lista»:** cada sección vacía solo ofrece «Explorar películas/series» si de verdad hay algo visible allí. `hooks/useCatalogPresence` lo pregunta pidiendo una página de tamaño 1 al mismo endpoint que usa la página de destino. Así ningún botón lleva a otra página vacía.

**Reglas que se comprobaron en todos los estados vacíos:**

1. Que el texto sea verdad para cada rol.
2. Que título, descripción y botón hablen de lo mismo.
3. Que el botón lleve a un sitio útil, nunca a otra página vacía.
4. Que explique qué hacer o por qué.

Mientras se carga el usuario, se trata como usuario normal (falla cerrado). En los tests, `src/test/UserStatusProbe` espera a que el rol esté confirmado antes de comprobar el texto.

**Enlaces:** el estado vacío de la sección «Películas» de «Mi lista» lleva a `/peliculas`. El vacío general de «Mi lista» lleva a la portada, que mezcla películas y series.

### 20.10 Perfil (`pages/ProfilePage.tsx` + `lib/profile.ts`)

`/perfil` responde a «¿quién soy y qué he guardado?». Se llega desde **«Mi perfil»** en el menú de usuario de la barra (no está en la fila de navegación principal, que ya va justa de ancho). Es una ruta privada dentro de `AppShell`, como «Mi lista».

**De dónde sale cada dato.** La página no hace ninguna petición propia: el usuario (nombre, correo, rol y fecha de alta) viene de `useAuth()` (`GET /api/users/me`) y las películas y series de `useFavorites()`, que `AppShell` ya cargó. Por eso no hay «títulos vistos», «horas», suscripción ni idioma: el modelo de datos no tiene historial de reproducción ni planes, y **no se inventan datos** para que la pantalla se parezca a un diseño.

**Qué enseña:**

- *Cabecera:* avatar con la inicial, el rol (en mayúsculas con CSS `uppercase`, para que el lector de pantalla lea «Administrador» y no deletree), el nombre (el `<h1>`), «Miembro desde octubre de 2026» (formateado en UTC: la API da un instante UTC y, sin fijar la zona, un alta a las 00:30 del día 1 podría salir en el mes anterior), «Panel de administración» (solo `isAdmin`) y «Cerrar sesión». No hay «Editar perfil»: exigiría endpoints nuevos y un botón que no hace nada es peor que no tenerlo (queda en el plan como pendiente).
- *Estadísticas:* películas en la lista, series en la lista y géneros distintos. Son una lista de descripción (`dl`): pares etiqueta → valor. Solo se pintan con la lista ya cargada; con la lista cargando, un «0» sería falso.
- *Cuenta:* nombre, correo y contraseña. Los puntos son fijos (no se conoce la contraseña) y los lectores leen «Oculta».
- *Tus géneros:* los 6 más frecuentes de la lista con su recuento. `countGenres` cuenta **por id de género** (no por nombre) y cada título cuenta una vez por género; orden: recuento descendente, nombre e id.
- *De tu lista:* hasta 5 títulos, primero películas (`FavoritesContext` las da de más reciente a más antigua) y después series, con el enlace «Ver toda mi lista». Reutiliza `MovieCard` y `SeriesCard`.

**Dos fuentes, cada una con sus tres estados.** El usuario (cargando, error con «Reintentar» → `refreshUser`) y la lista (cargando, error con «Reintentar» → `reload`). Si la lista falla, la cuenta sigue visible y el error aparece solo dentro de la tarjeta «Tus géneros» (`ErrorState` con la prop `compact`, que no reserva media pantalla).

**Estados vacíos (ver 20.9).** Con la lista vacía hay un único estado vacío, en «Tus géneros», y «De tu lista» no se pinta para no repetirlo. «Explorar películas» usa `ExploreLink` (extraído de «Mi lista» para compartirlo): solo aparece si `useCatalogPresence` confirma que hay películas visibles. Los textos son verdad para `USER` y `ADMIN`.

**Accesibilidad.** Un solo `<h1>` (mientras carga o si falla, «Mi perfil»; con el usuario cargado, su nombre), secciones con `<h2>`, `<dl>` para los pares etiqueta-valor y el avatar `aria-hidden`. En el menú de usuario, «Mi perfil» es un `NavLink` (marca `aria-current` cuando ya estás en `/perfil`) y va **antes** de «Cerrar sesión»: con el teclado, el primer Tab desde el botón del menú llega a «Mi perfil» y el segundo a «Cerrar sesión» (lo comprueba el E2E de accesibilidad).

**Tests:** `lib/profile.test.ts` (lógica pura), `pages/ProfilePage.test.tsx` (usuario y administrador, estados, lista vacía y llena), casos nuevos en `Navbar.test.tsx` y `App.test.tsx` (la ruta exige sesión) y `e2e/profile.spec.ts` (flujo completo y 320/375 px sin scroll horizontal).
---

## 21. Frontend: accesibilidad, estilos e imágenes

### 21.1 Accesibilidad

- `lang="es"` y un `<title>` por página (`useDocumentTitle`).
- **Enlace «Saltar al contenido»** (`SkipLink`): el primer `Tab` lo muestra y lleva directamente al contenido, sin recorrer la barra.
- Estructura semántica: `<header>`, `<nav>`, `<main>`, un solo `<h1>` por página.
- Foco siempre visible (utilidad `focus-ring`); botones de solo icono con `aria-label`.
- Buscador con el patrón *combobox* (`role="combobox"`, `listbox`, `aria-activedescendant`) y una región `aria-live` que anuncia el número de resultados.
- Avisos en regiones `aria-live` que **existen desde el principio** (los lectores de pantalla solo anuncian cambios en regiones que ya estaban).
- `prefers-reduced-motion`: si el sistema pide menos movimiento, se quita lo que mueve (desplazamientos, escalas, giros, pulsos) pero se conservan los fundidos de opacidad (ver «Entrada con movimiento» en este capítulo).
- Contrastes calculados para cumplir WCAG AA (4,5:1); las cifras están comentadas en `index.css`.
- **El foco nunca queda tapado por la barra fija** (WCAG 2.2 · 2.4.11): variable `--navbar-height` y `scroll-margin-top` en el contenido (detalle en 20.7).
- **El contorno de los controles también cuenta** (WCAG 1.4.11 pide 3:1 en los elementos de interfaz). El borde de los campos era `white/15` (1,47:1) y pasó al token `field-border` (`#687286`: 3,90:1 sobre `canvas` y 3,55:1 sobre `surface`). El contorno del buscador de la barra (`SearchBar`) también usa `field-border` (3,91:1 sobre `canvas` por fuera y 3,33:1 sobre `surface-raised` por dentro). Queda pendiente de decisión del autor el borde `white/30` de los botones `outline` y de las temporadas inactivas de `SeasonPicker` (≈2,7:1; llevan texto, así que el borde no es lo único que los identifica).
- La barra ya no tiene secciones reservadas: «Películas» (`/peliculas`) y «Series» (`/series`) son `NavLink` sin `end`, así que siguen marcadas en sus subrutas y con filtros en la URL. La constante `PLANNED_SECTIONS` (texto atenuado, no enfocable y con «(próximamente)») se retiró al crear la página de películas.

### 21.2 Estilos (Tailwind CSS v4)

Tailwind genera clases de utilidad (`flex`, `p-4`, `text-sm`…) y solo incluye en el CSS final las que se usan. En la versión 4 se configura **en CSS**, no en un `tailwind.config.js`:

```css
@theme {
  --color-canvas: #0e1117;   /* fondo */
  --color-accent: #e8a020;   /* color de marca */
  ...
}
```

Cada token genera sus clases (`bg-canvas`, `text-accent`…). Regla del proyecto: **cero `style={{...}}`**, todo con clases y tokens; si cambias un color, cambia en un solo sitio. `postcss.config.js` solo carga el plugin de Tailwind para Vite.

**Botones (`components/buttonStyles.ts`).** Hay cinco variantes con jerarquía: `primary` (acento), `light` (acción principal sobre imágenes), `outline` (secundaria), `ghost` (terciaria, sin borde: pesa menos y no compite con las otras) y `danger`. Todos se hunden un 3 % al pulsar (`active:scale-97`, 150 ms, curva `ease-out-strong`), salvo los desactivados y con movimiento reducido: es la confirmación física de que el clic se ha registrado. `WatchButton` y `FavoriteButton` aceptan `className` para colocarlos en una rejilla.

**Detalles globales en `index.css`:** el texto seleccionado y el cursor de los campos usan el acento (la selección da 9,33:1), y la barra de desplazamiento usa la paleta oscura en vez de la del sistema.

### 21.3 Imágenes (`components/MoviePoster.tsx`)

- La imagen sale **siempre de `movie.imageUrl`** (lo que diga la base de datos). La vista previa del formulario del panel también: usa un objeto película «borrador» con la URL que se está escribiendo.
- `loading="lazy"`: solo se descarga al acercarse a la pantalla. La del banner, en cambio, carga con prioridad alta, porque es lo primero que se ve.
- Ancho y alto reservados: la página no «salta» al cargar.
- Si no hay URL o la imagen falla, se muestra un hueco con icono y título, hecho con HTML (no es otra imagen, así que no puede fallar en bucle). El hueco lleva **un degradado elegido por el título** (`lib/posterFallback.ts`): un hash del título (FNV-1a) escoge uno de 6 degradados, así que la misma película tiene siempre el mismo color y varios huecos seguidos no parecen una página sin cargar. Todos los degradados dan un contraste de 9:1 o más con el texto blanco (cifras en el código).
- **Banner y modal: fondo desenfocado + póster entero.** Las portadas son verticales (2:3) y el banner y la cabecera del modal son horizontales. Con `object-cover`, una imagen vertical en una caja horizontal solo deja ver una franja central ampliada (antes se veía un «PARTE DOS» gigante y borroso). Por eso el banner usa la misma imagen dos veces: como **fondo** a sangre, muy desenfocado y bajo un velo `canvas/60` (con degradados hacia el color de fondo) para que el texto se lea, y como **póster nítido entero** pegado al título, en todos los anchos. El modal hace lo mismo. Ambas usan la misma URL, así que el navegador la descarga una sola vez. La versión de fondo se pinta con la prop `backdrop` de `MoviePoster` (sin icono ni título si falla, y oculta a los lectores de pantalla porque es decorativa). Los E2E vigilan tres cosas: que el fondo cubre su sección, que el póster se ve entero, en proporción 2:3 y sin recortar (`e2e/support/images.ts`), y que entre póster y título no hay más de 64 px (`e2e/responsive.spec.ts`).
- **Por qué el póster va junto al título (revisión de diseño del 2026-10-03).** Antes, a 1280 px, el póster estaba en el borde derecho, a ~430 px del texto: por proximidad se leían como dos cosas sin relación. En móvil iba detrás del texto y el degradado tapaba su mitad, así que parecía una imagen rota. Ahora en móvil es una rejilla de dos columnas (póster pequeño | título) con metadatos, sinopsis y botones a todo el ancho debajo. La columna de texto usa `display: contents` en móvil (sus hijos se colocan directamente en la rejilla) y desde `md` pasa a ser una columna flex junto al póster; así no hay que duplicar el marcado para cada tamaño.
- **El velo del 60 %** se eligió calculando el peor caso (fondo blanco puro detrás): título 7,5:1, sinopsis 6,0:1 y etiquetas de género 4,8:1. Con un 55 % los géneros bajaban a 4,3:1 y no llegaban a WCAG AA. Las cifras están en `HeroBanner.tsx`.
- `referrerPolicy="no-referrer"`: si la imagen está en un servidor ajeno, ese servidor no recibe la URL de la aplicación de cada visitante.
- Las portadas de ejemplo están en `public/covers/*.webp` (se sirven como `/covers/...`). El script opcional `docs/portadas-locales.sql` apunta las películas de ejemplo a ellas.

### 21.4 «Ver ahora»

`WatchButton` abre `videoUrl` en una pestaña nueva **solo si es una URL `http`/`https` válida y sin credenciales** (`lib/utils.ts` → `getSafeVideoUrl`). Así una URL maliciosa como `javascript:...` nunca se ejecuta. Lleva `rel="noopener noreferrer"` para que la página nueva no pueda controlar la nuestra.

---

## 22. Tests

### 22.1 Resumen

| Suite | Herramienta | Nº | Comando |
| :--- | :--- | :--- | :--- |
| Backend (H2) | JUnit 5, Spring Boot Test, MockMvc, Mockito | 1153 | `.\mvnw.cmd test` (desde `streambox/`) |
| Backend (PostgreSQL real) | Testcontainers | 119 | Se ejecutan con el anterior (1272 en total); se omiten si Docker no está en marcha. Sin Docker, Maven cuenta cada test parametrizado omitido como uno solo, así que la cifra de omitidos es menor (95) |
| Frontend (lógica y componentes) | Vitest, Testing Library | 779 | `npm run test` (desde `frontend/`) |
| Frontend (flujos completos) | Playwright (Chromium) | 143 (+24 de capturas, que se omiten) | `npm run test:e2e` |

### 22.2 Tests del backend

Están en `streambox/src/test/java/com/emilio/streambox/`. Hay dos estilos:

**Tests de integración** (la mayoría, `*IntegrationTest`):

```java
@SpringBootTest                 // arranca la aplicación completa
@ActiveProfiles("test")         // con application-test.properties (H2)
@AutoConfigureMockMvc           // MockMvc: simula peticiones HTTP sin red real
class FavoritesControllerIntegrationTest { ... }
```

Prueban de verdad todo el recorrido del capítulo 2: filtros, seguridad, controlador, servicio, base de datos y manejador de errores.

- **Perfil `test`**: H2 en memoria en modo PostgreSQL, Flyway aplica las migraciones reales, secreto JWT fijo (no necesitas `JWT_SECRET`) y límites de rate limiting altísimos para que no molesten.
- **Limpieza**: la mayoría usan `@Transactional`, que deshace todo al acabar cada test. Las que necesitan que los datos se confirmen de verdad (favoritos, catálogo, conteo de consultas) no lo usan y borran la base de datos en `@BeforeEach`/`@AfterEach`.
- **Rate limiting**: `RateLimitingIntegrationTest` baja los límites con `@TestPropertySource` y usa una IP y un email distintos en cada test, porque los contadores viven en memoria durante toda la ejecución.

**Tests unitarios** (con Mockito): prueban una clase aislada, simulando sus dependencias. Por ejemplo, `FavoriteServiceTest` simula que el repositorio lanza una violación de clave duplicada para comprobar la traducción a 409 sin necesitar dos hilos reales.

**Tests contra PostgreSQL real** (paquete `postgres`): H2 se parece a PostgreSQL pero no es igual, y puede ocultar errores (ya ocultó el de la búsqueda con `İ`). Estos tests extienden `PostgresIntegrationTestSupport`, que arranca un contenedor Docker `postgres:16` compartido por todos. Prueban migraciones, adopción de bases antiguas, concurrencia real de favoritos y diferencias de orden y búsqueda. Con `@Testcontainers(disabledWithoutDocker = true)`, si Docker no está en marcha **se omiten** en vez de fallar.

```
.\mvnw.cmd test "-Dtest=Postgres*"     # solo los de PostgreSQL
.\mvnw.cmd test "-Dtest=!Postgres*"    # todos menos esos
```

### 22.3 Tests del frontend

- **Vitest + Testing Library** (`*.test.ts(x)` junto al código): prueban la lógica (`apiFetch`, validación, `buildCatalogRows`…) y los componentes **como los usaría una persona**: buscan elementos por su rol y su texto (`getByRole('button', { name: 'Iniciar sesión' })`), no por clases CSS. Si un test no encuentra un elemento por su rol, suele ser un problema de accesibilidad. Se ejecutan en `jsdom` (un navegador simulado), con algunas simulaciones en `src/test/setup.ts` (por ejemplo, `<dialog>`, que jsdom no implementa).
- **Playwright** (`frontend/e2e/`): abre un Chromium real y recorre la aplicación de verdad (registro, login, catálogo, favoritos, buscador, teclado, responsive en 375/768/1280 px). Levanta **su propio backend** en el puerto 8099 con H2 en memoria y su propio Vite en el 5199, siembra 25 películas por la API con un administrador temporal y lo apaga todo al terminar. No toca tu base de datos ni tus puertos 8080 y 5173. Las portadas se siembran como rutas propias (`/covers/...`), que son las únicas no `https` que acepta la API.
- **Specs que modifican el catálogo** (`admin-peliculas.spec.ts`: crea, edita y borra una película) van en un proyecto aparte, `catalogo-mutable`, que Playwright solo empieza cuando el resto ha terminado. Mientras existe una película creada por un test, ella pasa a ser la más reciente, y los tests que comprueban el banner o «Mostrando 25 de 25» fallarían según el orden. Contrapartida: si falla algún test del proyecto principal, este se omite. Para ejecutarlo solo: `npx playwright test admin-peliculas --no-deps`.
- **El tiempo en los tests.** Ningún test de Vitest espera tiempo real. Los que dependen de un debounce (buscador de la barra, buscador del panel, vista previa de la portada) usan el reloj falso de `src/test/fakeTimers.ts`: `installManualTimers()` en `beforeEach` y `passTime(ms)` para dejar pasar el tiempo. Así se comprueba el retraso exacto (nada a los 299 ms, la petición a los 300) y el resultado no depende de lo cargada que esté la máquina. Con el reloj real y `waitFor` (1 s), algunos fallaban a veces con la suite en paralelo.
- **Esperar como una persona en E2E.** Antes de pulsar en el formulario de película se espera a que esté completo (`waitForMovieForm(page)` en `e2e/support/fixtures.ts`). Los géneros llegan aparte y, al aparecer, desplazan los botones 128 px en móvil. Playwright solo comprueba qué hay bajo el puntero en el primer evento del clic, así que el `mouseup` podía caer en otro elemento.

### 22.4 Regla del proyecto

Cada bug corregido deja un test que **falla sin el arreglo**. Se comprueba quitando el arreglo temporalmente: si el test sigue pasando, no protege nada.

---

## 23. Recetas

### 23.1 Añadir un endpoint nuevo

Ejemplo: `GET /api/movies/{id}/similar`.

1. **Servicio**: método en `MovieService` con `@Transactional(readOnly = true)` que devuelva DTOs (mapeo dentro de la transacción).
2. **Controlador**: método en `MovieController` con `@GetMapping("/{id}/similar")`, `@Operation` y `@ApiResponses` en español con los códigos reales (401, 404…).
3. **Seguridad**: comprueba qué regla de `SecurityConfig` le aplica. `GET /api/movies/**` ya exige autenticación; si fuera solo para administradores, habría que añadir una regla **antes** de esa.
4. **Errores**: si hay un caso nuevo, crea una excepción de dominio (heredando de `ResourceNotFoundException` si es un 404) y, si hace falta, su `ErrorCode` y su manejador.
5. **Tests**: de integración con MockMvc (éxito, 401 sin token, 404, parámetros inválidos) y, si es un listado, comprueba que no hay N+1.
6. **Documentación**: tabla de endpoints del README y el plan de acción.

### 23.2 Añadir una columna a una tabla

Ejemplo: `age_rating` en `movies`.

1. **Migración nueva**: `V3__add_movie_age_rating.sql`, en SQL que funcione en PostgreSQL y en H2. Piensa qué pasa con las filas existentes (¿`NOT NULL` con valor por defecto?).
2. **Entidad**: añade el campo a `Movie` con su `@Column`.
3. **DTOs y mapper**: `MovieRequest` (con validación), `MovieResponse` y `MovieMapper`.
4. **Arranca la aplicación**: Hibernate valida que entidad y migración coinciden.
5. **Tests**: `FlywaySchemaIntegrationTest` y `PostgresSchemaIntegrationTest` para la columna y sus restricciones.
6. **Nunca** edites V1 ni V2.

### 23.3 Añadir una pantalla al frontend

1. Componente en `pages/`, con `useDocumentTitle` y un único `<h1>`.
2. Ruta en `App.tsx` dentro del bloque de `RequireAuth` si es privada.
3. Datos con `apiFetch` (nunca `fetch` directo) y los tres estados: cargando (`LoadingState`), vacío (`EmptyState`) y error con reintento (`ErrorState`).
4. Estilos con clases de Tailwind y tokens; nada de `style={{}}`.
5. Tests con Vitest (y Playwright si es un flujo importante).
6. Comprueba: `npm run build`, `npm run lint`, `npm run test`.

### 23.4 Añadir una sección a la barra superior

Crea la página y su ruta (23.3) y añade un `NavLink` en `components/Navbar.tsx`, como los de «Películas» y «Series». Sin `end`, para que quede marcado también en sus subrutas. Comprueba que la barra sigue cabiendo a 375 y 768 px, también con «Administrar»: lo vigila `e2e/responsive.spec.ts`. Actualiza el orden de tabulación que comprueba `e2e/accessibility.spec.ts`.

---

## 24. Glosario

| Término | Significado |
| :--- | :--- |
| **Bean** | Objeto creado y gestionado por Spring (servicios, repositorios, controladores…) |
| **BCrypt** | Algoritmo de hash de contraseñas, lento a propósito y con sal |
| **BOLA / IDOR** | Vulnerabilidad: acceder a datos de otro usuario cambiando un id en la petición |
| **CSRF** | Ataque que aprovecha las cookies que el navegador envía solo; no aplica a APIs con token en cabecera |
| **Debounce** | Esperar a que el usuario deje de escribir antes de actuar |
| **DTO** | *Data Transfer Object*: objeto que entra o sale por la API, separado de la entidad |
| **Entidad** | Clase Java que representa una fila de una tabla (JPA) |
| **Flyway** | Herramienta que aplica migraciones SQL en orden y una sola vez |
| **H2** | Base de datos en memoria usada en los tests |
| **Hibernate** | Implementación de JPA: traduce entre objetos Java y SQL |
| **Idempotente** | Que se puede ejecutar varias veces con el mismo resultado |
| **Inyección de dependencias** | Spring pasa a cada clase los objetos que necesita por su constructor |
| **JPA** | Especificación Java para guardar objetos en bases de datos relacionales |
| **JPQL** | Lenguaje de consultas de JPA, sobre entidades en lugar de tablas |
| **JWT** | Token firmado que identifica al usuario en cada petición |
| **LAZY** | Carga diferida: una relación no se lee de la base de datos hasta que se usa |
| **Migración** | Script SQL versionado que cambia el esquema |
| **N+1** | Problema de rendimiento: 1 consulta para N elementos + 1 consulta extra por cada uno |
| **Optimista (actualización)** | Cambiar la interfaz antes de que responda el servidor y deshacer si falla |
| **Perfil** | Conjunto de configuración por entorno (`dev`, `prod`, `test`) |
| **Proxy (Vite)** | Reenvío de `/api` del puerto 5173 al 8080 |
| **Rate limiting** | Limitar cuántas peticiones se aceptan en un tiempo |
| **SPA** | *Single Page Application*: la página no se recarga al navegar |
| **SQLSTATE** | Código estándar de 5 caracteres que identifica el tipo de error SQL |
| **Stateless** | El servidor no guarda estado de sesión entre peticiones |
| **Testcontainers** | Librería que arranca contenedores Docker (aquí PostgreSQL) para los tests |
| **Transacción** | Grupo de operaciones que se aplican todas o ninguna |
