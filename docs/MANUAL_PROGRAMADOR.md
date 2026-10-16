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
| **Frontend** (SPA) | React 19, TypeScript, Vite, Tailwind CSS v4, React Router, TanStack Query | `frontend/` | 5173 |

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

**1. El navegador.** `useCatalog` (frontend; delega en `usePagedCatalog`, que usa la caché de TanStack Query, ver 19.3) llama a `apiFetch('/movies', { params: {...} })`. `apiFetch` hace el `fetch` con `credentials: 'same-origin'`: el navegador adjunta la cookie `streambox_token` por su cuenta, y JavaScript nunca ve el token.

**2. Vite.** Ve que la ruta empieza por `/api` y la reenvía a `http://localhost:8080`.

**3. Tomcat** (el servidor web que Spring Boot lleva dentro) recibe la petición y la pasa por la **cadena de filtros**.

**4. `RateLimitingFilter`.** Solo actúa en `POST /api/auth/login` y `POST /api/users` (los reconoce con el mismo tipo de comparador de rutas que la autorización; capítulo 11), y en ellas solo cuenta las peticiones que otra web no podría enviar sin *preflight* (capítulo 11, «Qué peticiones gastan el límite»). Esta petición es un `GET`, así que la deja pasar.

**5. `JwtAuthenticationFilter`.** Toma el token de la cookie `streambox_token` (o, si viene, de `Authorization: Bearer`, que tiene preferencia: clientes de API), valida el token (firma, caducidad, emisor), saca el email, busca el usuario en la base de datos y lo deja «apuntado» en el `SecurityContext` como usuario autenticado con su rol. Si el token vino de la cookie y la petición no es segura (POST/PUT/PATCH/DELETE), exige además la cabecera `X-Requested-With: StreamBox` (defensa CSRF): sin ella, 403 `CSRF_REJECTED`.

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
      "releaseYear": 2024, "imageUrl": "...", "videoUrl": null,
      "trailerUrl": "https://www.youtube.com/watch?v=...",
      "createdAt": "2026-10-01T18:22:10.123Z",
      "genres": [ { "id": 2, "name": "Aventura" }, { "id": 1, "name": "Ciencia ficción" } ] }
  ],
  "page": 0, "size": 20, "totalElements": 25, "totalPages": 2,
  "hasNext": true, "hasPrevious": false
}
```

**13. De vuelta en el navegador**, `apiFetch` comprueba `res.ok`, interpreta el JSON y se lo da a `usePagedCatalog` (que lo usa `useCatalog`), que lo guarda en la caché de TanStack Query (19.3). La portada se vuelve a pintar, y si el usuario vuelve a ella en el siguiente minuto se pinta desde la caché, sin repetir la petición.

Si algo falla en cualquier punto (token caducado, parámetro inválido, error de base de datos), la petición no sigue: se corta y se devuelve un error JSON con un formato común (capítulo 16).

---

## 3. Qué pasa al arrancar el backend

Cuando ejecutas `.\mvnw.cmd spring-boot:run` desde `streambox/`, ocurre esto, en orden:

1. **`StreamboxApplication.main`** arranca Spring Boot. La clase lleva dos anotaciones importantes:
   - `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`: Spring Security, por defecto, crea un usuario `user` con una contraseña aleatoria que imprime en la consola. No lo queremos (los usuarios están en nuestra base de datos), así que se excluye.
   - `@ConfigurationPropertiesScan`: busca los `record` anotados con `@ConfigurationProperties` (`JwtProperties`, `AdminProperties`, `AuthCookieProperties`, `RateLimitProperties`) y los rellena con los valores de los `.properties`.

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
jwt.access-token-ttl=${JWT_ACCESS_TOKEN_TTL:15m}
```

`${JWT_SECRET}` toma el valor de la variable de entorno `JWT_SECRET`. Lo que va detrás de los dos puntos es el **valor por defecto**: si `JWT_ACCESS_TOKEN_TTL` no existe, vale 15 minutos. (Hasta el 2026-10-08 era `jwt.expiration-hours`/`JWT_EXPIRATION_HOURS`, 24 h; esa variable ya **no se lee**: si la tienes en tu terminal, en un `.env` o en `application-local.properties`, bórrala.)

Además, Spring Boot tiene *relaxed binding*: una variable de entorno `JWT_SECRET` también rellena la propiedad `jwt.secret` aunque no hubiera `${...}`. Las variables de entorno **tienen más prioridad** que los `.properties`. (Esto explica por qué un test que comprueba «sin secreto la app no arranca» fallaba en tu máquina: tenías `JWT_SECRET` definida. Está resuelto en `JwtPropertiesValidationTest`.)

### 4.3 Propiedades que leen nuestras clases

En vez de leer valores sueltos con `@Value`, el proyecto agrupa la configuración en `record`s tipados:

| Prefijo | Clase | Qué controla |
| :--- | :--- | :--- |
| `jwt.*` | `security/JwtProperties` | Secreto (≥32 caracteres) y vida del token de acceso (`access-token-ttl`, 15 min, máximo 1 h) |
| `streambox.auth.refresh.*` | `security/refresh/RefreshTokenProperties` | Vida del *refresh token* (`ttl`, 7 días), tope de la sesión (`family-ttl`, 30 días), gracia entre pestañas (`reuse-grace`, 10 s, máximo 1 min) y limpieza periódica (`cleanup.*`: activada, cada hora, conserva 3 días los rotados) |
| `streambox.admin.*` | `security/AdminProperties` | Email, usuario y contraseña del administrador inicial |
| `streambox.auth.cookie.*` | `security/AuthCookieProperties` | Atributo `Secure` de la cookie de sesión `streambox_token` (por defecto `true`) |
| `streambox.security.rate-limit.*` | `security/ratelimit/RateLimitProperties` | Límites de login, registro y refresh, y bloqueo de cuentas |

Las duraciones se escriben como `1m`, `1h`, `15m` y Spring las convierte en `java.time.Duration` automáticamente.

### 4.4 Variables de entorno

| Variable | Obligatoria | Uso |
| :--- | :--- | :--- |
| `JWT_SECRET` | Sí | Secreto de firma de los tokens (≥32 caracteres). Genera uno: `openssl rand -base64 48` |
| `JWT_ACCESS_TOKEN_TTL` | No (`15m`) | Vida del token de acceso (máximo `1h`). La sesión dura más gracias al *refresh token* (capítulo 10) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | No | Crean el primer administrador. Al crearlo, la contraseña debe cumplir la política del registro (capítulo 10.5); si ya existe, no se valida |
| `ADMIN_USERNAME` | No (`admin`) | Nombre del administrador |
| `STREAMBOX_AUTH_COOKIE_SECURE` | No (`true`) | Atributo `Secure` de la cookie de sesión. Solo debe ser `false` para probar por HTTP plano (el `docker-compose.yml` lo pone a `false` por defecto); detrás de HTTPS, `true` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Solo en `prod` | Conexión a la base de datos |
| `SPRING_PROFILES_ACTIVE` | No | `prod` en producción |

### 4.5 Base de datos de desarrollo: Supabase (o PostgreSQL local)

Supabase es PostgreSQL gestionado, así que el backend funciona sin cambios de código: solo cambia a qué base de datos se conecta. La conexión se define en **`streambox/src/main/resources/application-local.properties`**, el archivo que Spring importa siempre (`spring.config.import` en `application.properties`) y que git ignora. No hace falta activar ningún perfil.

1. Copia `application-local.properties.example` como `application-local.properties` (misma carpeta).
2. Deja la **opción A** (PostgreSQL local, la URL por defecto es `localhost:5432/streambox`) o sustitúyela por la **opción B** (Supabase) con los valores del botón **Connect** del panel.
3. Arranca como siempre: `.\mvnw.cmd spring-boot:run`.

Funciona porque lo importado desde `application.properties` sobrescribe sus valores por defecto (sección 4.1). Las variables de entorno `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` ganan a todo (sección 4.2): es lo que usan los tests E2E para apuntar a su propia base y lo que debe usarse para cualquier prueba, porque **arrancar la app sin más se conecta a la base real**.

**Los tests nunca tocan esa base.** Algunos borran tablas enteras al empezar y al terminar, así que se comprobó: con una conexión falsa e inalcanzable en `application-local.properties`, toda la suite con H2 pasa (se comprobó cuando eran 387 tests). Las propiedades de `application-test.properties` ganan a las del archivo local.

Puntos que conviene entender:

- **Pooler.** Usa la cadena *Session pooler* (puerto **5432**). El *Transaction pooler* (puerto 6543) no admite las sentencias preparadas que usa Hibernate. La conexión directa suele ser solo IPv6 y puede no funcionar en tu red.
- **SSL.** `sslmode=require` es obligatorio: Supabase no acepta conexiones sin cifrar.
- **Usuario.** Con el pooler es `postgres.<id-del-proyecto>`, no solo `postgres`.
- **El esquema lo crea Flyway** al arrancar contra la base vacía (aplica `V1` a `V4`), igual que en local.
- **Seguridad: la API pública de Supabase.** Supabase publica automáticamente una API REST sobre el esquema `public`, accesible con una clave pública, y da privilegios por defecto a los roles `anon` y `authenticated`. Como las tablas viven ahí, hay que cerrarla. **Desde la auditoría de octubre de 2026 lo hace la propia aplicación**, en cada arranque: un *callback* de Flyway (`db/callback/postgresql/afterMigrate__close_public_api.sql`, sección 5.4) activa RLS sin políticas en todas las tablas de `public`, retira los privilegios a `anon` y `authenticated` (también sobre secuencias y funciones) y cambia los privilegios por defecto para que lo que se cree después nazca cerrado. La aplicación no se ve afectada porque conecta con el rol `postgres`, propietario de las tablas, que se salta RLS. [`docs/supabase-seguridad.sql`](supabase-seguridad.sql) queda como **respaldo manual** (el mismo bloque, para aplicarlo sin arrancar la app). *Por qué:* antes era un paso manual que solo protegía las tablas que existían el día que se ejecutaba, y las que creaba Flyway después (las de series, por ejemplo) nacían abiertas hasta que alguien se acordaba de repetirlo. **Alcance:** el callback actúa sobre **todas** las tablas y funciones del esquema `public`, no solo las de StreamBox. Si ese mismo proyecto de Supabase aloja otra aplicación que usa la Data API con sus propias políticas RLS, se la rompería; para esa situación se quita la ubicación del callback (`spring.flyway.locations=classpath:db/migration`) y se aplica el cierre a mano solo a las tablas propias. Además, **el primer arranque contra tu Supabase aplica RLS y retira privilegios a `anon`/`authenticated`** (lo mismo que ya hiciste con el script; no toca datos). Lo que sigue sin poder hacer el código: ver cuál es el estado real de tu proyecto Supabase. Lo más robusto es **desactivar la Data API del proyecto** (StreamBox accede por conexión directa), y mirar el log de arranque: un `WARNING` de este callback significa que algo sigue abierto.
- **Mover los datos** de una base a otra: ver `docs/PLAN_DE_ACCION.md` (migración a Supabase). Los `id` se conservan, y por eso hay que comprobar que las secuencias de identidad quedan por encima del mayor `id` (si no, el siguiente `INSERT` chocaría con una fila existente).

### 4.6 Docker y CI

Con Docker, `docker compose up -d --build` levanta la aplicación completa en `http://localhost:8088`. Uso básico en el README; aquí, cómo está montado y por qué.

#### Las imágenes

- **Backend** (`streambox/Dockerfile`), en dos etapas:
  1. **Compilación** con la imagen `maven:3.9-eclipse-temurin-26` (el `pom.xml` compila con `release 21`, así que el bytecode es de Java 21; la etapa de ejecución usa JRE 21). Primero se copia solo `pom.xml` y se descargan las dependencias en una capa propia (`dependency:go-offline`); así, un cambio de código no vuelve a descargarlas. Se compila **sin tests** (`-Dmaven.test.skip=true`), porque los tests van en el CI.
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
- **Caché:** `index.html` (y con él todas las rutas de la SPA) con `no-store`: el navegador no guarda copia del documento, así que tras cerrar sesión el botón Atrás no puede enseñar desde la caché una pantalla ya cargada con datos de la cuenta, y tras un despliegue nadie se queda con la versión vieja. `/assets/` (nombres con hash) un año e `immutable`; `/covers/` un día. Va en un `map` a nivel de `server` y no en un `add_header` del `location`, que anularía las cabeceras de seguridad (comprobado con `curl -I`: `/`, `/series/7` e `/index.html` llevan `no-store` y todas las cabeceras de seguridad). Un recurso que no existe da 404, no `index.html`.
- **No se publica:** `/actuator` no es accesible desde fuera, y las rutas con `;` dan 400. El `;` es un truco clásico para saltarse reglas de seguridad por ruta en Java.
- **Cabeceras de seguridad**, en todas las respuestas (`always`), declaradas una sola vez en el `server`. Si un `location` tuviera su propio `add_header`, dejaría de heredarlas.
  - Una **CSP** estricta: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https:; connect-src 'self'; frame-src https://www.youtube-nocookie.com; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`. Es la segunda línea de defensa contra XSS: aunque se colara HTML, el navegador no ejecutaría scripts en línea, y `connect-src 'self'` impide que un script colado envíe datos a otro servidor. (El token ya no es accesible para JavaScript: va en una cookie HttpOnly.)
  - **`frame-src https://www.youtube-nocookie.com`** es la única apertura: deja cargar el `<iframe>` del modal «Ver tráiler» (`/embed/<id>` en el dominio «sin cookies» de YouTube) y nada más, ni `youtube.com` ni comodines. Sin ella, los iframes caen en `default-src 'self'` y el reproductor queda bloqueado. No se añade `child-src`: los navegadores actuales miran `frame-src` primero, uno antiguo que no la entendiera bloquearía el tráiler (falla cerrado) y `child-src` rige además los *workers*, así que abriría más de lo necesario. Lo que el reproductor cargue dentro de su iframe lo controla la CSP de YouTube, no la nuestra.
  - `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, una `Permissions-Policy` restrictiva y `Cross-Origin-Opener/Resource-Policy: same-origin`.
  - **`Permissions-Policy` y el reproductor:** deniega a todos (`()`) la cámara, el micrófono, la ubicación, los pagos, etc. Las cuatro funciones que usa el reproductor (`autoplay`, `fullscreen`, `encrypted-media` y `picture-in-picture`) se permiten **solo** a `self` y a `"https://www.youtube-nocookie.com"`. Hace falta `self` porque una página solo puede delegar en un iframe lo que ella misma tiene permitido. Antes, `autoplay=()` impedía que el tráiler arrancara solo, y las otras tres no aparecían, así que valían los valores por defecto del navegador (`picture-in-picture` para cualquier iframe). Ahora están limitadas a YouTube. El iframe tiene que pedirlas además en su atributo `allow`. `accelerometer` y `gyroscope` (vídeos de 360°) siguen denegados también para YouTube.
  
  Se comprobó recorriendo toda la app en Chromium: **0 violaciones**, y un script en línea de control quedó bloqueado. La CSP no se aplica en `npm run dev`, porque Vite inyecta código en línea. HSTS está comentado porque por HTTP los navegadores lo ignoran; se activará cuando haya HTTPS.

#### CI (`.github/workflows/ci.yml`)

Se ejecuta en cada `push` a `main`, en cada pull request y a mano. Lanza cuatro jobs en paralelo en `ubuntu-latest`:

| Job | Qué hace |
| :--- | :--- |
| `backend` | `./mvnw -B -ntp verify` con Temurin 21. Los runners traen Docker, así que **los tests de PostgreSQL se ejecutan siempre**. Si falla, sube los informes de surefire |
| `frontend` | `npm ci --ignore-scripts`, `build`, `lint` y `test` con Node 24 |
| `e2e` | Playwright con su propio backend H2 (8099) y Vite (5199), y caché de los navegadores. Si falla, sube el informe |
| `docker` | Construye las dos imágenes (caché `type=gha`, sin publicarlas en ningún registro), levanta el stack con un `.env` de secretos aleatorios (`openssl rand`, enmascarados en el log) y comprueba con `curl` la CSP (que su `frame-src` admita solo `https://www.youtube-nocookie.com`), el 401 de la API y el login del administrador (204 con la cookie `streambox_token` HttpOnly, y `GET /api/users/me` con esa cookie devuelve rol `ADMIN`). Siempre termina con `down -v` |

Ningún job usa secretos del repositorio y el token solo tiene `contents: read`. Las *actions* van fijadas **por SHA** y no por etiqueta, porque una etiqueta se puede mover a código malicioso (ocurrió con `tj-actions` en 2025); Dependabot actualiza el SHA y su comentario. La sintaxis se validó con `actionlint`.

#### Escaneo de seguridad (`.github/workflows/seguridad.yml`)

**El problema.** Dependabot propone versiones nuevas, pero nada avisaba de que una dependencia que ya usamos (directa o transitiva) tiene una vulnerabilidad conocida, ni paraba un pull request que la introdujera. Y una vulnerabilidad nueva se publica **sin que cambie nuestro código**: un CI que solo corre al hacer push no se entera.

**Cuándo corre:** en cada `push` a `main`, en cada pull request, a mano (Actions → Seguridad → *Run workflow*) y **cada lunes a las 04:17 UTC** aunque nadie toque nada, antes de los pull requests de Dependabot (07:00). Va en un workflow aparte de `ci.yml` por esa ejecución semanal y para que «el código funciona» y «las dependencias son seguras» salgan como resultados separados.

| Job | Qué escanea | ¿Rompe el CI? |
| :--- | :--- | :--- |
| `npm-audit` | `frontend/package-lock.json` contra la base de avisos de GitHub (la de `npm audit`). No instala nada: `--package-lock-only` lee el árbol del lockfile, que ya marca qué es de desarrollo | **Sí**, con una vulnerabilidad **alta o crítica en producción** (`--omit=dev --audit-level=high`) o **crítica en cualquiera** (`--audit-level=critical`) |
| `osv` | OSV-Scanner (Google, base `osv.dev`, sin claves) sobre `streambox/pom.xml` **con las transitivas** y `frontend/package-lock.json` (incluidas las de desarrollo) | **Sí**, con **cualquier** vulnerabilidad sin excepción vigente en `osv-scanner.toml`, si el escaneo **no termina** (registro caído, manifiesto que no se resuelve) y si `osv-scanner.toml` incumple las reglas de las excepciones |
| `codeql` | Análisis estático del código: Java (`java-kotlin`), TypeScript (`javascript-typescript`) y los propios workflows (`actions`: inyección de `${{ }}` en `run:`, permisos excesivos...) | **No** por sí solo (ver abajo) |

- **¿Por qué dos umbrales en npm?** Las dependencias de producción (React, TanStack Query...) acaban en el navegador del usuario; las de desarrollo (Vite, Vitest, Playwright) solo corren en tu equipo y en el CI. OSV-Scanner sigue viéndolas todas, y es más estricto: falla con cualquiera.
- **Las transitivas de Maven.** El `pom.xml` solo declara las directas (18 paquetes) y las versiones las pone el padre `spring-boot-starter-parent`. OSV-Scanner v2 resuelve el árbol completo él solo descargando los pom de Maven Central, sin ejecutar Maven: en local vio **176 paquetes de Maven** (las 18 directas y 158 transitivas), con las versiones de Tomcat y Jackson que gestiona Spring Boot, y también respeta las propiedades que las sobrescriben en nuestro `pom.xml` (ver más abajo: resuelve las mismas versiones que `mvnw dependency:list`). No hace falta generar antes un `dependency:tree`.
- **Por qué un job propio y no el workflow reutilizable oficial de OSV** (`google/osv-scanner-action`): el reutilizable **falla abierto**. Con Maven Central u osv.dev inalcanzables, el escáner sale con error (código 127, `failed resolution for streambox/pom.xml`) pero deja un `results.json` con `"results": []`; el reutilizable solo comprueba que el archivo exista, su informe dice «No issues found» y el job sale **en verde sin haber analizado nada**. Peor aún, en `main` ese informe vacío **cerraría las alertas** abiertas en la pestaña Security. Por eso el job `osv` llama a `.github/scripts/osv-escanear.sh`, que **falla cerrado**:
  1. Ejecuta el escáner de la imagen oficial con `docker run` (código en solo lectura, resultados en el directorio temporal del runner). Llama al binario directamente porque el punto de entrada de la imagen convierte el 128 («no encontré ningún manifiesto») en 0.
  2. Falla si el código de salida no es 0 (sin vulnerabilidades) ni 1 (hay vulnerabilidades), si el log contiene `Error during extraction` o `failed resolution` (un manifiesto roto puede convivir con un 1 por las vulnerabilidades de otro) o si no hay `results.json`. Y además comprueba **en positivo** que se analizó lo que tenía que analizarse, porque la lista negra de textos sola no basta (si una versión futura cambia esos mensajes, o un manifiesto deja de detectarse, saldría 0 sin haber mirado Maven y, en `main`, se cerrarían las alertas): con `--all-packages`, `results.json` lista **todos** los paquetes analizados (sin la opción, un escaneo limpio y uno vacío dan el mismo `"results": []`; el SARIF no cambia), y el script exige que `streambox/pom.xml` tenga al menos **100** (hoy 176: 18 directas + 158 transitivas; si la resolución de transitivas fallara en silencio saldrían 18) y `frontend/package-lock.json` al menos **150** (hoy 222). Se cuenta en el JSON y no en la línea `Scanned … found N packages` del log porque esa línea solo cuenta las 18 directas del `pom.xml` (comprobado: con `--no-resolve` el log es idéntico al de un escaneo bueno) y porque el log se puede falsear con texto del repositorio. **La lista de manifiestos exigidos (`MANIFIESTOS_EXIGIDOS`, al principio del script) se mantiene a mano:** un manifiesto nuevo se escanea igualmente, pero no se exige hasta añadirlo; si uno de los dos se mueve o se borra, el job falla hasta actualizar la ruta.
  3. Solo entonces genera el SARIF con `osv-reporter` (misma imagen; `--fail-on-vuln=true` hace que salga con 1 si hay vulnerabilidades y pinta la tabla en el log) y publica la salida `sarif` del paso.
  4. El paso de subida (`github/codeql-action/upload-sarif`, categoría fija `osv-scanner`) solo corre si existe esa salida: se sube con vulnerabilidades (es cuando interesa verlas), **nunca** tras un escaneo incompleto.
  5. La salida de `osv-scanner` y `osv-reporter` va entre `::stop-commands::<token>` y `::<token>::` (token aleatorio de `openssl rand`): el runner trata como comando cualquier línea que empiece por `::`, y el log de OSV repite texto del repositorio, que controla quien abre un pull request. Comprobado con v2.6.0: escapa los saltos de línea de los nombres de archivo (`%0A`), pero un `groupId` con `&#10;::set-output name=sarif::/ruta` en un `pom.xml` sale tal cual en el error de resolución; sin el bloque, eso inyectaba anotaciones `::error::` falsas y podía dar valor a la salida `sarif` de un escaneo fallido. Los `::error::`/`::group::` del propio script van fuera del bloque.
- **La versión de OSV-Scanner se sube a mano.** La imagen va fijada por **digest** en la variable `OSV_IMAGEN` del job (`ghcr.io/google/osv-scanner-action:v2.6.0@sha256:…`): una etiqueta se puede mover a otra imagen, el digest no. Pero **Dependabot no actualiza un digest escrito en un `run:` o en una variable** (solo los de `uses:`, Dockerfiles y compose). Cuando salga una versión nueva de `google/osv-scanner-action`, cambia la etiqueta y su digest juntos; el digest lo da `docker buildx imagetools inspect ghcr.io/google/osv-scanner-action:vX.Y.Z` (la línea `Digest:` de arriba, la de la lista de manifiestos, que vale para cualquier arquitectura), y prueba el script en local (ver abajo) antes de subirlo. **Con la versión nueva, vuelve a comprobar que el script sigue fallando cerrado**: que con Maven Central bloqueado salen los mismos textos de la lista negra (`Error during extraction`, `failed resolution`) y que `results.json` mantiene el formato que usa la comprobación en positivo (`results[].source.path` con la ruta bajo `/src` y `results[].packages`, con las transitivas de Maven incluidas). El comentario junto a `OSV_IMAGEN` lo recuerda.
- **Permisos:** global `contents: read`; `security-events: write` solo en `osv` y `codeql`, que publican en la pestaña Security. Ningún job usa secretos. Como el repositorio es público, la subida no necesita `actions: read`; si pasara a privado, habría que añadirlo a esos dos jobs (github/codeql-action#2117). Cada permiso lleva su porqué en un comentario en la misma línea (lo pide `zizmor`, el analizador de seguridad de workflows).

**Cómo se añade una excepción (OSV).** Lo normal es arreglar: subir la versión, o fijar la transitiva (en Maven, sobrescribiendo la propiedad de versión del padre, p. ej. `<tomcat.version>`; en npm, con `overrides`). Solo si no hay arreglo o el aviso no nos afecta, se añade en `osv-scanner.toml` (raíz del repositorio) un bloque por aviso:

```toml
[[IgnoredVulns]]
id = "GHSA-xxxx-xxxx-xxxx"       # el de la columna «OSV URL» del informe
ignoreUntil = 2026-12-31          # caducidad: después, el CI vuelve a fallar
reason = "Qué parte no nos afecta y por qué, o qué versión estamos esperando."
```

Nunca un paquete entero ni todo de golpe: cada excepción es un aviso concreto, con motivo y caducidad (corta: semanas mejor que meses), para que se revise en lugar de olvidarse. Si una excepción sobra, OSV lo dice en el log (`has unused ignores`).

**Estas reglas las hace cumplir el CI**, no solo el comentario del `.toml`: OSV acepta sin quejarse un `[[IgnoredVulns]]` sin `reason` ni `ignoreUntil` (ignora el aviso **para siempre**), un `ignoreUntil` entre comillas (es una cadena, no una fecha) y `[[PackageOverrides]]` con `ignore = true` (ignora un paquete **entero**, también las vulnerabilidades que se publiquen mañana). Por eso el primer paso del job `osv` ejecuta `.github/scripts/validar_osv_toml.py` (Python estándar con `tomllib`), que falla si alguna excepción no tiene `id`, tiene un `reason` con menos de **20 letras o cifras** (no cuentan los espacios, la puntuación ni los caracteres invisibles como U+200B, así que no se puede rellenar), no tiene `ignoreUntil`, lo tiene entre comillas o con hora, o caduca a **más de 90 días** de hoy. Funciona por **lista blanca**: rechaza cualquier clave que no sea `IgnoredVulns` (con solo `id`, `reason` e `ignoreUntil`), así que caen `PackageOverrides`, cualquier opción futura para ignorar en bloque y las variantes con otras mayúsculas (`packageOverrides`, `ignoreuntil`), que OSV sí acepta porque su lector de TOML no distingue mayúsculas en los campos. Una excepción ya caducada solo da un aviso: OSV ya no la aplica. Los textos sacados del `.toml` (claves, ids) salen en los mensajes con `ascii()` (entre comillas y con `\n`, `​` o `K` a la vista) y cada anotación se escapa como pide GitHub (`%`→`%25`, `\r`→`%0D`, `\n`→`%0A`): si no, un `id` con un salto de línea partiría la anotación y su segunda línea podría ser un comando inyectado. Probarlo: `python3 .github/scripts/validar_osv_toml.py osv-scanner.toml`. `npm audit` no tiene lista de excepciones: si una alta de producción no tiene arreglo, se fuerza la versión con `overrides` o se documenta y se decide.

**Sobrescrituras de versión vigentes en `streambox/pom.xml` (temporales).** El primer escaneo encontró 17 vulnerabilidades en transitivas que llegan a producción, todas con parche publicado pero aún no gestionado por Spring Boot (4.1.1 es el último). Se arreglaron sobrescribiendo en `<properties>` las propiedades que usa `spring-boot-dependencies`, que cambian a la vez **todos** los artefactos de cada grupo (sin mezclar versiones):

| Propiedad | Padre 4.1.1 | Fijada | Por qué |
| :--- | :--- | :--- | :--- |
| `tomcat.version` | 11.0.24 | 11.0.27 | 3 críticas en `tomcat-embed-core` (GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5), arregladas en 11.0.25. Cubre también `tomcat-embed-el` y `-websocket` |
| `jackson-2-bom.version` | 2.21.5 | 2.21.7 | Altas y medias en `jackson-core`/`jackson-databind` 2 (las usa `jjwt-jackson` para leer los JWT) |
| `jackson-bom.version` | 3.1.5 | 3.1.7 | Altas y medias en `jackson-core`/`jackson-databind` 3 (el JSON de Spring MVC) |

Solo subidas de parche, y la versión más reciente con al menos 7 días publicada (el mismo criterio que el `cooldown` de Dependabot). Se comprueba con `./mvnw dependency:list | grep -iE "jackson|tomcat"`: todas salen con la versión fijada (`jackson-annotations` sale como `2.21`, sin parche: es el esquema de versiones de Jackson desde la 2.20, no una mezcla).

**Hay que quitarlas.** Cuando Dependabot (u otra persona) suba el padre a un Spring Boot que ya gestione esa versión o una mayor (búscalo en su `spring-boot-dependencies-X.pom`), borra la propiedad. Si se queda, **gana a la del padre nuevo** y podría dejarnos en una versión *más antigua* que la que traería: Dependabot sube el padre pero no toca estas propiedades. El comentario del `pom.xml` lo recuerda junto a cada una y, para que no dependa de leerlo, **el build falla** si cambia la versión del padre: una regla `requireProperty` del `maven-enforcer-plugin` (versión gestionada por el padre, fase `validate`, la primera de cualquier comando de Maven) exige que `project.parent.version` sea exactamente `4.1.1`. El pull request de Dependabot que suba Spring Boot se quedará en rojo en el CI con el mensaje «Has cambiado la version de Spring Boot… revisa tomcat.version, jackson-2-bom.version y jackson-bom.version…» hasta que alguien quite las propiedades que el padre nuevo ya cubre y actualice la versión esperada de la regla. **La regla se quita junto con la última sobrescritura** (sin parches no hay nada que recordar). Un `-Dproject.parent.version=…` en la línea de comandos no la engaña: se evalúa sobre el modelo del proyecto.

**Cómo ver los resultados.** En el log del job (tabla con paquete, versión, aviso y versión que lo arregla) y en **Security → Code scanning** del repositorio: las alertas de CodeQL y de OSV-Scanner (filtro `tool:CodeQL` o `tool:osv-scanner`), con la línea afectada, la explicación y el botón para descartarlas con un motivo (falso positivo, no se usa...). En un pull request, las alertas nuevas salen también como comentarios en el código.

**Por qué CodeQL no rompe el CI.** Tiene falsos positivos y lo razonable es revisarlos uno a uno, no bloquear todo hasta descartarlos. Para que bloquee, la forma limpia es una regla del repositorio y no el workflow: *Settings → Rules → Rulesets → New branch ruleset* sobre `main` → **Require code scanning results** con CodeQL (y OSV-Scanner si se quiere), umbral *Security alerts: High or higher*. Así no se puede fusionar un pull request que **introduce** una alerta alta, y las existentes no bloquean el resto del trabajo.

**Lo que hay que activar en GitHub** (una vez, en *Settings → Advanced Security*, antes llamado *Code security and analysis*): *Dependency graph*, *Dependabot alerts* y *Dependabot security updates* (pull requests automáticos que arreglan una vulnerabilidad sin esperar al lunes), y comprobar que *Code scanning* no tiene activada la configuración por defecto (*default setup*) de CodeQL, que chocaría con este workflow (*advanced setup*). Las ejecuciones programadas se desactivan solas si el repositorio pasa 60 días sin actividad; se reactivan en la pestaña Actions.

**Fuera de alcance:** los paquetes del sistema de las imágenes Docker (Alpine, Debian) no se escanean; Dependabot ya propone los parches de las imágenes base.

**Probarlo en local** (desde la raíz, con Docker en marcha; en Git Bash, con `MSYS_NO_PATHCONV=1` y las rutas que se montan en Windows, p. ej. `"$(pwd -W)"`). No montes el repositorio entero en un contenedor: por eso actionlint y zizmor reciben solo `.github` y OSV una copia sacada de git:

```bash
docker run --rm -v "$PWD/.github:/repo/.github:ro" -w /repo rhysd/actionlint:1.7.12 .github/workflows/ci.yml .github/workflows/seguridad.yml
docker run --rm -v "$PWD/.github:/repo/.github:ro" ghcr.io/zizmorcore/zizmor:1.31.0 --offline --persona=pedantic /repo/.github/workflows
(cd frontend && npm audit --package-lock-only --omit=dev --audit-level=high && npm audit --package-lock-only --audit-level=critical)
python3 .github/scripts/validar_osv_toml.py osv-scanner.toml
# OSV con el MISMO script que el CI, sobre una copia limpia de lo que hay en git (sin archivos
# ignorados como application-local.properties) más los cambios sin commit que quieras probar:
mkdir -p /tmp/sb && git archive HEAD | tar -x -C /tmp/sb && cp osv-scanner.toml /tmp/sb/ && cp streambox/pom.xml /tmp/sb/streambox/
OSV_IMAGEN="$(grep -oE 'ghcr\.io/google/osv-scanner-action:v[0-9.]+@sha256:[0-9a-f]{64}' .github/workflows/seguridad.yml)" \
  bash .github/scripts/osv-escanear.sh /tmp/sb /tmp/sb-osv
echo "código: $?"   # 0 = limpio, 1 = vulnerabilidades o escaneo incompleto (lo dice el ::error::)
```

Para comprobar que falla cerrado, repite la última orden con Maven Central bloqueado: pon delante en el `PATH` un `docker` de prueba que añada `--add-host repo.maven.apache.org:127.0.0.1` (o `--network none`) a cada `docker run` (en Git Bash, la carpeta en el `PATH` con la forma `/c/...`: con `C:/...` los dos puntos la parten y se usa el `docker` real sin avisar). Debe salir con 1, con el error «El escaneo de OSV-Scanner NO se completó» y sin `results.sarif`. Para la comprobación en positivo: borra de la copia `frontend/package-lock.json` (o `streambox/pom.xml`), o usa un `docker` de prueba que añada `--no-resolve` detrás de `scan source` (simula que las transitivas dejan de resolverse sin error); debe salir con 1, con el error «El escaneo no analizó todos los manifiestos exigidos» y sin `results.sarif`.

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
             varchar video_url "opcional (V6)"
             varchar trailer_url "opcional, solo YouTube (V6)"
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

**Vídeo y tráiler (migración `V6`).** StreamBox no aloja vídeos, así que el vídeo del contenido es **opcional**: `movies.video_url` y `episodes.video_url` admiten `NULL`. El tráiler va en su propia columna, `movies.trailer_url` y `series.trailer_url` (los episodios no tienen tráiler: es de la serie), también opcional y **siempre un vídeo de YouTube normalizado** (`https://www.youtube.com/watch?v=<id de 11 caracteres>`), cosa que exige un `CHECK` (sección 5.4, «V6»).

La tabla **`refresh_tokens`** (migración `V4`) guarda las sesiones renovables (capítulo 10): una fila por *refresh token*, con su usuario (`ON DELETE CASCADE`: borrar un usuario revoca sus sesiones), el **SHA-256** del token (`token_hash`, `UNIQUE`; nunca el token en claro: un `CHECK` exige 64 caracteres hexadecimales en minúsculas, así que guardar el token por error falla en vez de pasar en silencio), la familia de rotación (`family_id`, `UUID`), sus caducidades (`expires_at` y el tope de la familia `family_expires_at`) y, al rotar, `revoked_at` y `replaced_by_id` (el sucesor, `ON DELETE SET NULL`). Decisiones:

- `created_at` **no** usa `@CreationTimestamp`: lo pone el servicio con el mismo `Clock` que `expires_at`, porque un `CHECK` compara las dos columnas y mezclar el reloj del sistema con el fijo de los tests lo haría fallar.
- `RefreshTokenRepository.findByTokenHashForUpdate` usa **bloqueo pesimista** (`FOR NO KEY UPDATE` en PostgreSQL): dos refresh simultáneos del mismo token (dos pestañas) se ponen en fila y solo uno rota. Exige estar dentro de una transacción (`Propagation.MANDATORY`). Probado contra PostgreSQL real (`PostgresRefreshTokenIntegrationTest`): sin el `@Lock`, el test ve dos rotaciones.
- Sin índices para la limpieza periódica (tabla pequeña; cada índice encarece cada refresh). Si crece, se añaden en una migración nueva.

### 5.2 Las restricciones (y por qué están en la base de datos)

Están en `V1__create_schema.sql`:

| Restricción | Qué impide |
| :--- | :--- |
| `UNIQUE (username)`, `UNIQUE (email)`, `UNIQUE (name)` en géneros | Duplicados |
| `CHECK (role IN ('USER','ADMIN'))` | Roles inventados |
| `CHECK (duration > 0)`, `CHECK (release_year BETWEEN 1888 AND 2100)` | Datos absurdos |
| `NOT NULL` en todas las columnas obligatorias (desde la `V6`, `video_url` y `trailer_url` son opcionales) | Campos vacíos |
| `ck_movies_trailer_url`, `ck_series_trailer_url` (`V6`) | Un tráiler que no sea un vídeo de YouTube en la forma normalizada (el frontend lo incrusta en un `<iframe>`) |
| `FOREIGN KEY ... ON DELETE CASCADE` | Al borrar una película o un usuario, desaparecen sus filas en las tablas de unión |
| `FOREIGN KEY` sin cascada en `movie_genres.genre_id` | No se puede borrar un género que alguna película usa |

**¿Por qué en la base de datos si Java ya valida?** Porque Java solo valida lo que entra por la API. Un script SQL, otra aplicación o un bug saltarían esas validaciones; la base de datos es la última defensa y siempre se cumple. Además, la base de datos resuelve bien las **condiciones de carrera** (dos peticiones a la vez), cosa que una comprobación en Java no puede (capítulo 15).

### 5.3 Los índices

Una clave primaria compuesta `(user_id, movie_id)` sirve para buscar por `user_id`, pero **no** por `movie_id` solo. Por eso V1 crea índices para las búsquedas por la segunda columna:

| Índice | Para qué consulta |
| :--- | :--- |
| `idx_movie_genres_genre_id` | Filtro por género en `/api/movies/search?genreId=` y «películas similares» (§14.6) |
| `idx_favorites_movie_id` | Borrar una película (encontrar sus favoritos) |
| `idx_movies_release_year` | Filtro por año |

### 5.4 Flyway: cómo se crea y evoluciona el esquema

Flyway es una herramienta que ejecuta scripts SQL **en orden y una sola vez**. Los scripts están en `streambox/src/main/resources/db/migration/` y se llaman `V<número>__<descripción>.sql`.

Al arrancar, Flyway:

1. Mira la tabla `flyway_schema_history` de la base de datos, donde apunta qué migraciones ya aplicó.
2. Ejecuta, en orden, las que falten.
3. Guarda en el historial cada migración aplicada junto a un **checksum** (una huella de su contenido).

**Regla de oro: nunca edites una migración ya aplicada**, ni siquiera un comentario. Flyway recalcula el checksum, ve que no coincide con el guardado y se niega a arrancar. Para cambiar algo, crea una migración nueva (la siguiente es `V7__...`; mira la carpeta por si ya hay más).

#### Callbacks: SQL que no es una migración (`afterMigrate`)

Además de las migraciones versionadas, Flyway ejecuta **callbacks**: scripts que se lanzan en un momento concreto del ciclo. `afterMigrate__*.sql` se ejecuta **después de cada `migrate`, aunque no hubiera nada que migrar**. No lleva versión, no queda en `flyway_schema_history` y no tiene checksum, así que se puede mejorar sin romper las bases ya migradas.

El proyecto tiene uno, **solo para PostgreSQL**, que cierra la API pública de Supabase (capítulo 4.5): `src/main/resources/db/callback/postgresql/afterMigrate__close_public_api.sql`. Se activa con

```properties
spring.flyway.locations=classpath:db/migration,classpath:db/callback/{vendor}
```

Spring Boot sustituye `{vendor}` por `postgresql` o `h2` según la base conectada. Con H2 (los tests) busca `db/callback/h2`, que no existe, y no ejecuta nada: ni `ENABLE ROW LEVEL SECURITY` ni los roles de Supabase existen allí. Con un PostgreSQL sin los roles `anon`/`authenticated` (docker compose, local) comprueba `pg_roles` y no hace nada.

Decisiones que merece la pena saber defender:

- **Idempotente**: solo hace `ALTER TABLE` donde RLS aún no está activo, porque ese comando pide un bloqueo exclusivo de la tabla y no se quiere pedir en cada arranque.
- **Sin `FORCE ROW LEVEL SECURITY`**: el propietario (el rol de la aplicación y de Flyway) debe seguir saltándose RLS.
- **Avisa en lugar de abortar si faltan permisos** (error `42501`): endurece algo que ya estaba abierto, no es un requisito de funcionamiento, y un fallo de permisos no se arregla reintentando; abortar convertiría un fallo de seguridad en una caída total. A cambio hay que vigilar el log. Cualquier otro error sí aborta.
- **No toca objetos de extensiones** (`pg_depend`, `deptype = 'e'`): no son de StreamBox y revocar sus funciones rompería la extensión.
- Se prueba contra PostgreSQL real (`PostgresPublicApiClosureIntegrationTest`, con roles al estilo Supabase y un propietario no superusuario) y las pruebas fallan si se quita el callback.

#### Adopción de bases antiguas (baseline)

Antes de Flyway, Hibernate creaba las tablas solo (`ddl-auto=update`). Para que esas bases antiguas sigan funcionando:

```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
```

Si Flyway encuentra una base **con tablas pero sin historial**, la marca como «versión 0» (baseline) y aplica todas las migraciones encima (V3 y V4 solo crean tablas nuevas, sin `IF NOT EXISTS`; V5 añade funciones e índices; V6 añade columnas y mueve los tráileres). Por eso V1 usa `CREATE TABLE IF NOT EXISTS` y `CREATE INDEX IF NOT EXISTS`: sobre una base antigua no falla, solo añade lo que falte.

> **Matiz importante.** En una base antigua, V1 solo añade los **índices**. Las tablas ya existían, así que `IF NOT EXISTS` no las toca y conservan sus claves foráneas **sin `ON DELETE CASCADE`** y **sin los `CHECK`**. Por eso `MovieService.deleteMovie` sigue borrando a mano los favoritos antes de borrar la película (`deleteFromAllFavorites`). Los tests de `PostgresBaselineAdoptionIntegrationTest` lo comprueban.

#### V2: fechas con zona horaria

V1 creó `created_at` como `TIMESTAMP` (sin zona). V2 lo cambia a `TIMESTAMP WITH TIME ZONE`, que guarda un **instante absoluto**. En Java se usa `Instant` (capítulo 6). Al convertir datos antiguos, PostgreSQL interpreta las horas existentes en la zona horaria de la sesión: `22:00` en Madrid queda como `20:00Z`.

#### V6: tráiler propio y vídeo opcional (una migración que mueve datos)

`V6__trailer_url_and_optional_video.sql` (SQL portable, en `db/migration`) nació de un problema real: un script de datos de un solo uso (ejecutado a mano en Supabase el 2026-10-16 para ampliar el catálogo) había guardado como `video_url` de cada película y de cada episodio el **tráiler** de YouTube, así que «Ver ahora» abría un tráiler y el botón mentía. La migración:

1. Añade `movies.trailer_url` y `series.trailer_url` (`VARCHAR(500)`, opcionales) y quita el `NOT NULL` de `movies.video_url` y `episodes.video_url` (`ALTER COLUMN ... DROP NOT NULL`, válido en los dos motores y también sobre el `NOT NULL` sin nombre de una base adoptada).
2. **Mueve los datos.** Una película cuyo `video_url` es exactamente `https://www.youtube.com/watch?v=<id>` pasa ese valor a `trailer_url` y deja `video_url` en `NULL`. Cada serie toma como `trailer_url` el enlace de ese tipo de su episodio con **temporada y número más bajos** (en los datos del autor todos los episodios llevan el mismo; si difirieran, así el resultado es determinista), y los episodios cuyo `video_url` es igual a ese tráiler se quedan sin vídeo. Un episodio con **otro** enlace de YouTube no se toca: podría ser un vídeo propio del episodio y no hay forma segura de saberlo.
3. Añade `ck_movies_trailer_url` y `ck_series_trailer_url`: el tráiler es `NULL` o tiene 43 caracteres, empieza por `https://www.youtube.com/watch?v=` y su id solo usa `[A-Za-z0-9_-]`. Como en V4, el juego de caracteres se comprueba con `TRANSLATE` (cada carácter válido pasa a `0` y solo un id válido queda en once ceros), porque las expresiones regulares no son portables entre PostgreSQL y H2. No se usa `LOWER` para acortar la lista: el signo Kelvin (U+212A) se convierte en una `k` ASCII al pasarlo a minúsculas y se colaría.

Decisiones que conviene saber defender:

- **Solo mueve lo que el `CHECK` acepta.** Una URL de YouTube con otra forma (`youtu.be/...`, `&t=10`, `http:`) se queda en `video_url`. Si la migración la moviera, la restricción la rechazaría, la migración fallaría y la aplicación no arrancaría sobre la base del autor. El administrador puede corregirlas desde el panel, donde la API sí normaliza esas formas.
- **Es segura sobre datos reales**: no borra filas ni columnas, ninguna URL se pierde (lo que sale de `video_url` queda en un `trailer_url`), todo va en una transacción (en PostgreSQL el DDL también es transaccional) y cada `UPDATE` solo toca filas aún sin mover.
- **No hay vuelta atrás de versión**: una aplicación anterior arrancaría (Hibernate no valida la nulabilidad ni las columnas que no conoce) pero no sabría nada de `trailer_url`.
- **Tests:** `TrailerUrlMigrationH2Test` y `PostgresTrailerUrlMigrationIntegrationTest` migran hasta la V5 (`target` de Flyway), insertan un escenario con casos que se mueven y que no (`TrailerUrlMigrationScenario`), aplican la V6 y comprueban fila a fila el resultado; el de PostgreSQL comprueba además que un fallo a mitad no deja nada movido y que el `CHECK` rechaza las mismas formas que en H2. `FlywaySchemaIntegrationTest` cubre columnas, nulabilidad y `CHECK` en H2.

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

Todas las entidades (`Movie`, `User`, `Genre`, `Series`, `Episode`) implementan la igualdad así:

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
| `csrf.disable()` | CSRF es un ataque que aprovecha las **cookies** que el navegador envía solo. Como el token viaja en una cookie, el CSRF sí aplica: se desactiva el filtro de Spring porque la API es stateless y la defensa propia está en `JwtAuthenticationFilter` (sección 9.3, punto 5): cookie `SameSite=Strict` y cabecera obligatoria `X-Requested-With: StreamBox` en las peticiones no seguras autenticadas por cookie (con `Authorization: Bearer` no hace falta) |
| `SessionCreationPolicy.STATELESS` | No se crean sesiones HTTP (ni cookie `JSESSIONID`). Cada petición se autentica por sí misma con su token. El servidor no recuerda nada entre peticiones |
| `exceptionHandling(...)` | Sustituye las páginas de error HTML de Spring por respuestas JSON (capítulo 16) |
| `authorizeHttpRequests(...)` | Las reglas de acceso por ruta y método (ver abajo) |
| `addFilterBefore(...)` | Inserta nuestros dos filtros en la cadena, en el orden correcto |

### 9.2 Reglas de acceso

Se evalúan **de arriba abajo** y gana la primera que coincide:

| Método y ruta | Quién puede |
| :--- | :--- |
| `POST /api/users`, `POST /api/auth/login` | Cualquiera (registro y login) |
| `POST /api/auth/logout` | Cualquiera (público e idempotente: revoca la sesión del *refresh token* y borra las dos cookies), **siempre con `X-Requested-With: StreamBox`** (si no, 403 `CSRF_REJECTED`) |
| `/api/admin/**` (cualquier método) | Solo `ADMIN` (vistas de gestión, p. ej. series sin episodios) |
| `/api/users` con **cualquier método salvo `POST`** (el listado de cuentas; `GET`, `HEAD`, `PUT`...) | Solo `ADMIN` |
| `GET`, `HEAD /api/movies/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/movies/**` | Solo `ADMIN` |
| `GET`, `HEAD /api/genres/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/genres/**` | Solo `ADMIN` |
| `GET`, `HEAD /api/series/**` | Cualquier usuario autenticado |
| `POST`, `PUT`, `PATCH`, `DELETE /api/series/**` (incluye los episodios) | Solo `ADMIN` |
| **Cualquier otro método** sobre `/api/movies/**`, `/api/genres/**` y `/api/series/**` (regla de cierre del catálogo) | Solo `ADMIN` |
| `/actuator/health`, `/actuator/health/**` | Cualquiera (comprobaciones de salud) |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | Cualquiera (en `prod` están desactivados) |
| **Todo lo demás** (`anyRequest()`) | Cualquier usuario autenticado |

**La regla de `/api/users` es por ruta, no por método** (auditoría de octubre de 2026). Antes decía solo «`GET /api/users` → `ADMIN`». Pero `HEAD /api/users` no coincidía con esa regla, caía en `anyRequest().authenticated()` y Spring MVC lo atendía con el `@GetMapping`: cualquier usuario normal ejecutaba el listado completo de cuentas y, como Tomcat envía `Content-Length` en el `HEAD`, deducía cuántas cuentas hay (125 bytes con 1 usuario, 387 con 3). Se reprodujo con una prueba contra el Tomcat real. **Lección: una regla de autorización atada a un método HTTP deja fuera a los demás; para recursos privilegiados, escribe la regla por ruta** (como ya se hace con `/api/admin/**`). La coincidencia es exacta: `/api/users/me` y `/api/users/me/**` no entran en ella y siguen siendo «autenticado».

La última regla es una red de seguridad: un endpoint nuevo que se olvide de añadir aquí queda **protegido por defecto**, no abierto. Por ejemplo, `/api/users/me` y `/api/users/me/favorites` caen en ella.

**Pero «autenticado» no basta para escribir.** Hasta octubre de 2026 solo había reglas para `GET` y `POST` de géneros, así que `PUT` y `DELETE` habrían caído en `anyRequest().authenticated()`: en cuanto se crearon esos endpoints, **cualquier usuario normal** habría podido editar o borrar géneros. Por eso se añadieron antes que los endpoints, y además una **regla de cierre del catálogo**: cualquier método que no sea lectura (`GET`/`HEAD`) sobre películas, géneros o series es solo para `ADMIN`. Así, un endpoint de escritura que se añada en el futuro nace protegido aunque se olvide su regla. `HEAD` va con `GET` porque es la misma lectura sin cuerpo (Spring MVC lo atiende con los `@GetMapping`). Lo vigilan `CatalogWriteAuthorizationIntegrationTest` (401/403 en cada método de escritura) y `CatalogClosureRuleRegressionIntegrationTest` (la regla de cierre no bloquea lecturas, endpoints personales, login/registro, Actuator ni Swagger; y un cambio de rol se aplica con el mismo token).

`hasRole("ADMIN")` comprueba que el usuario tiene la autoridad `ROLE_ADMIN`. El prefijo `ROLE_` lo añade el filtro JWT (sección 9.3).

### 9.3 `JwtAuthenticationFilter`: quién eres

Para cada petición:

1. Busca el token: primero la cabecera `Authorization: Bearer` (si existe, **manda ella**; un Bearer inválido da 401 aunque haya cookie válida) y, si no hay, la cookie `streambox_token`. Si no hay ninguna, **no hace nada** y deja pasar la petición (sin autenticar). Login, registro, refresh y logout ignoran la cookie de acceso para que una vieja no los bloquee. Refresh y logout, además, exigen **siempre** `X-Requested-With: StreamBox` (también con Bearer): si no, 403 `CSRF_REJECTED` antes de tocar la base de datos.
2. Extrae el token y pide a `JwtService.extractEmail(token)` que lo valide y devuelva el email. Si el token está manipulado, caducado o lo emitió otro sistema, jjwt lanza una `JwtException` (o `IllegalArgumentException` si viene vacío): se registra en `DEBUG` **solo la clase** de la excepción y la petición sigue **sin autenticar**. No se registra el mensaje de jjwt, que puede repetir contenido del token que manda el cliente (p. ej. su `alg`), ni se usa `WARN`: cualquiera puede mandar tokens basura sin límite y llenaría el log; el 401 ya lo cuenta. Un token sin `subject` también es anónimo.
3. **Busca el usuario en la base de datos** por email. Si no existe (por ejemplo, se borró la cuenta), la petición sigue sin autenticar. Esta consulta está **fuera** del `try`: si la base de datos falla, el error **no** se confunde con «sin autenticar». Antes el filtro capturaba `Exception` y una caída de la BD daba 401 a un usuario con token válido, y el frontend le cerraba la sesión. Ahora la excepción sube, Tomcat la reenvía a `/error` y el cliente recibe **500 `INTERNAL_ERROR`** (sección 9.5); el frontend muestra el error con opción de reintentar y conserva la sesión (`JwtUserLookupFailureTomcatIntegrationTest`). Que jjwt solo lance esas dos excepciones ante cualquier token hostil lo vigila `JwtServiceTest` (43 tokens maliciosos): si una versión futura lanzara otra, ese test fallaría al actualizar la librería.
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

### 9.5 El reenvío a `/error` y el «401 falso»

Cuando algo llama a `response.sendError(...)` (o una excepción escapa de un filtro), Tomcat **reenvía la petición a `/error`** con otro tipo de despacho (`DispatcherType.ERROR`). En ese reenvío `JwtAuthenticationFilter` no vuelve a actuar (es `OncePerRequestFilter`), así que la petición llega **sin autenticar**. Antes, como `/error` no estaba permitida, cualquier error que tomara ese camino acababa en un **401 «Autenticación requerida» con `"path":"/error"`, aunque el token fuera válido**: el frontend lo trataba como sesión caducada. Casos reales encontrados (2026-10-08, tras la auditoría 2), todos con Tomcat real (MockMvc no hace el reenvío):

- **URL que rechaza el cortafuegos de Spring Security** (`StrictHttpFirewall`): `/api/genres;x=1`, `/api//genres`, `/api/genres/%2e%2e/x`, `/api/genres%25`. Ahora `security/JsonRequestRejectedHandler` (un bean `RequestRejectedHandler`, que Spring Security 7 recoge solo) responde directamente **400 `MALFORMED_REQUEST`** con el formato `ErrorResponse`, con un mensaje propio (el de la excepción describe la regla y la cadena maliciosa) y `X-Content-Type-Options: nosniff`, sin pasar por `sendError`. Registra el rechazo solo a DEBUG, para que un escáner no llene el log. Ojo: el cortafuegos comprueba algunas cabeceras **cuando alguien las lee**, no al entrar. Si la primera lectura ocurre ya dentro de Spring MVC (el `Content-Type` al leer el `@RequestBody`, p. ej. con un byte `0x85`), la excepción llega a `GlobalExceptionHandler` y no a ese manejador. Por eso `GlobalExceptionHandler` también trata `RequestRejectedException` (400 con el mismo mensaje y log a DEBUG); antes daba 500 y una traza a ERROR desde una ruta pública.
- **Errores con un `Accept` que no es JSON**: los resuelve `GlobalExceptionHandler` respondiendo siempre en JSON (capítulo 16).
- **Cualquier otro `sendError` o excepción en un filtro**: `SecurityConfig` permite el despacho de error (`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`, primera regla). **No abre la ruta `/error`**: un `GET /error` pedido por el cliente es un despacho `REQUEST` y sigue exigiendo autenticación (lo prueba `ErrorDispatchTomcatIntegrationTest`). La petición original ya pasó la autorización; el reenvío solo describe su error.

Al dejar pasar el despacho de error, lo que responde `/error` se ve de verdad. Lo responde **`exception/ApiErrorController`**, que sustituye al `BasicErrorController` de Spring Boot (basta con registrar un bean `ErrorController`: el de Boot lleva `@ConditionalOnMissingBean`). Su respuesta:

- siempre `ErrorResponse` en JSON, también con `Accept: text/html` (nada de la página «whitelabel»);
- el estado sale de `jakarta.servlet.error.status_code`, con la **misma tabla** estado → `code` que `GlobalExceptionHandler` (`exception/GenericHttpError`);
- mensajes genéricos (nunca el de `sendError` ni el de la excepción) y `path` con la ruta original, no `/error`;
- un `GET /error` pedido a mano con token responde 404, para que nadie pueda llenar el log de errores falsos.
- pone `X-Content-Type-Options: nosniff` (con `setHeader`, para no duplicarla): los errores que nacen en Tomcat antes de la seguridad, como un `TRACE` (405), no pasan por `HeaderWriterFilter`.

Como defensa extra, por si algún día vuelve a responder el controlador de Boot, `application.properties` fija `spring.web.error.include-*=never` en todos los perfiles (`ErrorDetailsNeverExposedPropertiesTest`), porque en `dev` `spring-boot-devtools` los pone a `always` y mostraría trazas. **Ojo, Spring Boot 4:** el prefijo es `spring.web.error`; el antiguo `server.error.include-*` ya no se aplica.

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
| `exp` (*expiration*) | Cuándo caduca: **15 minutos** después, por defecto (`jwt.access-token-ttl`). La sesión dura más gracias al *refresh token* (sección 10.4 bis) |

La tercera parte es la **firma**: un HMAC-SHA256 de las otras dos partes calculado con el secreto `JWT_SECRET`, que solo conoce el servidor. Si alguien cambia una letra del contenido (por ejemplo, el email), la firma deja de coincidir y el token se rechaza. Sin el secreto es imposible fabricar una firma válida.

### 10.2 `JwtService`

- **Constructor**: construye la clave de firma **una sola vez** a partir de `JwtProperties` (ya validada: ≥32 caracteres = 256 bits, lo mínimo para HS256).
- **El algoritmo está fijado: HS256**, al firmar y al verificar (el analizador solo admite `HS256`). Antes lo elegía la librería según la longitud del secreto (`Keys.hmacShaKeyFor`: un secreto largo daba HS384 o HS512), y la documentación decía HS256. Un token con otro `alg` se rechaza (401).
- **`generateToken(user)`**: crea el token con los claims de arriba y lo firma. Usa el `Clock` de la aplicación, así que los tests pueden adelantar el reloj para probar la caducidad.
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
    A->>DB: findByEmail(email)
    A->>L: reserveAttempt(email)
    L-->>F: 429 ACCOUNT_LOCKED + Retry-After si la cuenta ya acumula 5 fallos en 15 min
    A->>A: BCrypt.matches(password, hash real o hash falso)
    alt incorrecto
        A->>L: recordFailure(intento) → intentos restantes
        A-->>F: 401 INVALID_CREDENTIALS + remainingAttempts (4, 3, 2, 1)
        L-->>F: o 429 ACCOUNT_LOCKED si este fallo era el 5.º
    else correcto
        A->>L: recordSuccess(intento) (borra los fallos)
        A->>A: JwtService.generateToken(user)
        A-->>F: 204 + Set-Cookie streambox_token (JWT, 15 min, Path=/api)<br/>+ Set-Cookie streambox_refresh (sesión nueva, 7 días, Path=/api/auth)
    end
```

Detalles de seguridad que conviene saber defender:

1. **Normalizar el email.** El registro guarda el email en minúsculas y sin espacios. El login hace lo mismo; si no, registrarse con `Ana@Mail.com` impediría entrar escribiendo exactamente lo mismo.
2. **Mismo mensaje para todo.** Tanto si el email no existe como si la contraseña es incorrecta, la respuesta es la misma: «Email o contraseña incorrectos». Si fueran distintos, un atacante podría averiguar qué emails están registrados (*enumeración de usuarios*).
3. **El hash falso.** Si el email no existe, no hay hash con el que comparar, y la respuesta sería instantánea; si existe, BCrypt tarda ~100 ms. Esa diferencia de tiempo delataría qué emails existen. Por eso, cuando el usuario no existe, se compara contra un hash falso (`dummyPasswordHash`, calculado una vez en el constructor) para que el tiempo sea parecido.
4. **El bloqueo se comprueba antes que la contraseña.** Una cuenta bloqueada rechaza el login aunque la contraseña sea correcta; si no, el bloqueo no protegería nada.
5. **El intento se reserva antes de comprobar la contraseña** (`reserveAttempt`), de forma atómica. Si solo se contara al fallar, 20 peticiones simultáneas pasarían todas la comprobación de bloqueo mientras BCrypt trabaja y se probarían 20 contraseñas en vez de 5.
6. **La contraseña del login no tiene mínimo** (las cuentas antiguas de 8 caracteres siguen entrando: la política de 12 solo se aplica al registrarse), pero sí un **máximo de 1024 caracteres**, para no leer cuerpos enormes ni pasárselos a BCrypt. Ese 400 ocurre antes del servicio, así que no gasta intento de la cuenta.

### 10.4 bis La sesión renovable (*refresh token*)

**El problema.** Un JWT es *stateless*: el servidor no guarda nada, así que no puede «anularlo». Con un token de 24 horas, uno robado valía 24 horas, y cerrar sesión solo borraba la cookie del navegador. La solución estándar son **dos tokens**:

| | Token de acceso (`streambox_token`) | *Refresh token* (`streambox_refresh`) |
| :--- | :--- | :--- |
| Qué es | JWT firmado (sección 10.1) | 32 bytes aleatorios en base64url (43 caracteres), **sin significado** |
| Vida | 15 minutos | 7 días, y la sesión entera como mucho 30 desde el login |
| Dónde se guarda en el servidor | En ningún sitio | En `refresh_tokens`, **solo su SHA-256** (capítulo 5) |
| Cookie | HttpOnly, SameSite=Strict, `Path=/api` | HttpOnly, SameSite=Strict, **`Path=/api/auth`**: solo viaja a login, refresh y logout, nunca al resto de la API |
| Para qué | Cada petición a la API | Solo para pedir un token de acceso nuevo |

Así, un token de acceso robado vale como mucho 15 minutos, y cerrar sesión **sí** anula la sesión: el servidor revoca el *refresh token*. Se guarda solo el hash para que una copia de la base de datos no sirva para entrar (igual que con las contraseñas, aunque aquí basta SHA-256: el token es aleatorio y largo, no hace falta un hash lento como BCrypt).

**`POST /api/auth/refresh`** (`AuthController` → `RefreshTokenService.refresh`), sin cuerpo:

1. Lee **solo** la cookie `streambox_refresh` (ignora la de acceso y `Authorization`). Exige `X-Requested-With: StreamBox` (403 `CSRF_REJECTED`); esas peticiones sin cabecera, que es lo único que puede mandar otra web, se rechazan antes de tocar la base de datos y **no** gastan el límite por IP (30 por minuto; aquí la regla de NV-A del capítulo 11 no sirve, porque el refresh no lleva cuerpo ni `Content-Type`).
2. Busca el hash con **bloqueo pesimista** (`findByTokenHashForUpdate`): si dos pestañas refrescan a la vez, la segunda espera a la primera.
3. Si el token está vigente, lo **rota**: crea un sucesor de la misma «familia» (la sesión), marca el viejo como revocado y lo enlaza con el sucesor, todo en la misma transacción. Responde **204** con las dos cookies nuevas.
4. Si llega un token **ya rotado**, alguien está usando una copia vieja: es la señal clásica de robo. Se **revoca toda la familia** (también el sucesor, que quizá tenga el ladrón) y responde 401. Es la *reuse detection* de OAuth 2.0. La transacción usa `noRollbackFor = SessionExpiredException` para que la revocación se guarde aunque la respuesta sea un error. **La revocación de la familia se hace en dos pasadas** (`revokeWholeFamily`), y no por descuido. En PostgreSQL (READ COMMITTED), si mientras se revoca otra petición está rotando el último token de la familia, el `UPDATE` espera a que esa rotación confirme y entonces vuelve a evaluar las filas que ya había elegido, pero **no ve el sucesor que se insertó después de empezar la sentencia**: quedaba vivo, justo el que podría tener un ladrón. La segunda pasada empieza ya con la rotación confirmada y lo revoca. Lo encontró la revisión de seguridad y lo prueban dos tests contra PostgreSQL real (una reutilización y un logout durante una rotación): sin la segunda pasada, queda un token activo.
5. **Gracia de 10 segundos:** si el token se rotó hace menos de 10 s y su sucesor sigue vivo, casi seguro son dos pestañas que refrescaron a la vez. Entonces se emite solo un token de acceso nuevo, sin rotar ni revocar (el navegador ya tiene la cookie del sucesor, que comparten todas las pestañas). Coste asumido: quien use un token robado en esos 10 s consigue un token de acceso de 15 minutos; su siguiente uso del token viejo ya revoca la sesión.
6. En cualquier otro caso (sin cookie, desconocido, caducado, sesión de 30 días agotada, revocado por un logout, usuario borrado): **401 `SESSION_EXPIRED`**, el mismo cuerpo siempre (no se revela por qué), y las dos cookies se borran. El cliente no debe reintentar: va al login.

**Logout:** exige `X-Requested-With: StreamBox`; sin ella, 403 `CSRF_REJECTED` sin revocar nada ni borrar cookies. ¿Por qué, si es «solo» cerrar sesión? Un formulario de otra web no manda las cookies (SameSite=Strict), pero la respuesta de esa navegación sí traería los `Set-Cookie` que las borran: cualquier web podría cerrarte la sesión por fastidiar. Con la cabecera hace falta un *preflight* que, sin CORS, falla. Con ella, revoca la familia del *refresh token* de la cookie (si la hay) y borra las dos cookies; responde 204 aunque no haya sesión. Si la base de datos falla, responde 500 **sin** borrar las cookies, y el frontend avisa en vez de fingir que cerró (capítulo 19.2). Un token de acceso copiado sigue valiendo hasta su `exp` (≤15 min), pero ya no se puede renovar.

**Cambiar la contraseña (`PUT /api/users/me/password`) y cerrar sesión en todos los dispositivos (`POST /api/auth/logout-all`).** Decisiones del autor: cambiar la contraseña cierra las **demás** sesiones y mantiene la actual; «todos los dispositivos» cierra **todas**, también la actual.

- **Cambio de contraseña** (`PasswordController` → `PasswordChangeService`): exige la contraseña actual (si no coincide, **400** `CURRENT_PASSWORD_INCORRECT`, no 401: la sesión es válida y un 401 haría que el frontend intentara renovar y te echara), aplica `PasswordPolicy` a la nueva y comprueba que sea distinta (solo después de verificar la actual, para no dar pistas). **Límite de intentos** por cuenta (`PasswordChangeAttemptService`: 5 fallos en 15 min → 429): sin él, quien te robe la sesión podría adivinar tu contraseña actual por fuerza bruta. BCrypt se calcula **fuera** de la transacción, y al guardar se bloquea la fila del usuario y se comprueba que el hash no ha cambiado entre medias (dos cambios cruzados: el segundo recibe 400 en vez de pisar al primero).
- **Cómo se mantiene la sesión actual:** la cookie `streambox_refresh` tiene `Path=/api/auth`, así que el navegador **no la envía** a `/api/users/me/password` y el servidor no sabe cuál es «tu» sesión. Por eso revoca **todas** (`revokeAllSessions`, en dos pasadas por la misma carrera de READ COMMITTED que `revokeWholeFamily`) y abre una nueva para esta petición, en la misma transacción que guarda el hash: la respuesta (204) trae cookies nuevas, sigues dentro y una copia antigua de tu *refresh token* ya no sirve.
- **Logout en todos los dispositivos:** revoca todas las sesiones del usuario y borra las dos cookies; con la BD caída responde 500 sin borrarlas.
- **Ambos olvidan las «IPs conocidas» del login** (11.2): el cambio de contraseña conserva solo la IP de quien lo hace; el logout en todos los dispositivos no conserva ninguna.
- **Lo que no cubre (asumido):** el token de acceso de los otros dispositivos sigue valiendo **hasta 15 minutos** (es *stateless*); los textos de la interfaz lo dicen. Cortarlo al instante exigiría una columna «credenciales cambiadas en» que el filtro comparase con el `iat` del JWT.

**Limpieza:** `RefreshTokenCleanupConfig` programa cada hora `deleteExpired`, que borra las sesiones caducadas y los tokens caducados hace más de 3 días. Se conservan unos días los rotados porque son los que delatan una reutilización. Se desactiva con `streambox.auth.refresh.cleanup.enabled=false` (así está en el perfil `test`).

**El frontend** (`lib/api.ts`) no ve nunca ninguno de los dos tokens. Si una petición recibe 401, pide un refresh (uno solo aunque fallen varias a la vez) y repite la petición una vez (capítulo 19.2).

**Cómo defenderlo en una entrevista:** «Tokens de acceso cortos y *stateless* para no consultar una lista de revocación en cada petición, más un *refresh token* opaco, guardado con hash, rotatorio y con detección de reutilización, para poder revocar sesiones. Las dos cookies son HttpOnly y SameSite=Strict, y la del refresh tiene un `Path` restringido para que no viaje con cada petición.»

Tests: `RefreshTokenIntegrationTest` (30 casos: rotación, reutilización, gracia, caducidades, CSRF, límite, logout), concurrencia en H2 y contra PostgreSQL real (`PostgresRefreshTokenServiceConcurrencyIntegrationTest`: sin el bloqueo rotan todas), limpieza programada y validación de propiedades.

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
| **Por IP** | `RateLimitingFilter` | IP del cliente | Login: 10/min. Registro: 5/h. Refresh: 30/min (solo cuentan los que traen `X-Requested-With`; ver 10.4 bis) | Un atacante que prueba muchas cuentas desde una IP |
| **Por cuenta** | `LoginAttemptService` | Email (y, para una «IP conocida», email + IP; ver 11.2) | 5 fallos en 15 min | Un atacante que prueba muchas contraseñas contra una cuenta desde muchas IPs |

Ambas responden **429 Too Many Requests** con la cabecera **`Retry-After`** (segundos que hay que esperar), que el frontend usa para la cuenta atrás. Se distinguen por el `code`: **`RATE_LIMIT_EXCEEDED`** (límite por IP) o **`ACCOUNT_LOCKED`** (cuenta bloqueada).

**Contrato del bloqueo por cuenta** (5 fallos en 15 minutos, configurable en `streambox.security.rate-limit.lockout.*`):

| Intento | Respuesta |
| :--- | :--- |
| Fallos 1.º a 4.º | 401 `INVALID_CREDENTIALS` «Email o contraseña incorrectos» con **`remainingAttempts`** = 4, 3, 2, 1 |
| 5.º fallo | 429 `ACCOUNT_LOCKED` + `Retry-After: 900` «Has superado el número máximo de intentos. La cuenta queda bloqueada durante 15 minutos.» |
| Cualquier intento durante el bloqueo (también con la contraseña correcta) desde una IP **desconocida** | 429 `ACCOUNT_LOCKED` con el tiempo que falta («…Inténtalo de nuevo en N minutos.») |
| Intento desde una **IP conocida** de esa cuenta mientras otros la tienen bloqueada | Se procesa con normalidad (contraseña correcta → 204 con `Set-Cookie`); sus propios fallos tienen su contador de 5 en 15 min (ver 11.2) |
| Login correcto | 204 con `Set-Cookie`; borra los fallos acumulados del contador contra el que se reservó y deja esa IP como «conocida» |

- `remainingAttempts` son los intentos que le quedan a la cuenta **con este fallo ya descontado**. Por eso nunca vale 0: el fallo que agota los intentos ya responde 429. En el resto de errores de la API el campo **no aparece** (ni siquiera como `null`).
- **No revela qué emails existen:** los números, los mensajes, las cabeceras y el bloqueo son idénticos para un email registrado y para uno inexistente (lo comprueba `LoginLockoutContractIntegrationTest`, que también mide que los tiempos de respuesta sean parecidos).
- El frontend muestra «Te quedan N intentos antes de que la cuenta se bloquee 15 minutos» y, con `ACCOUNT_LOCKED`, un aviso de cuenta bloqueada con cuenta atrás en minutos y segundos (capítulo 20).

**Cómo reconoce el filtro las rutas.** `RateLimitingFilter` usa los mismos `PathPatternRequestMatcher` que las reglas de autorización, y las rutas (`LOGIN_PATH`, `REGISTER_PATH`) son constantes que `SecurityConfig` también usa en su `permitAll()`: lo público y lo limitado son siempre lo mismo. **Antes había un fallo de seguridad (encontrado por `qa` en octubre de 2026):** el filtro comparaba `request.getRequestURI()` con `equals`, pero ese método devuelve la ruta **sin decodificar**, mientras que Spring MVC decodifica cada segmento. `POST /api/auth/%6cogin` (la `l` codificada) llegaba al login sin pasar por el contador, y `POST /api/%75sers` creaba cuentas sin límite. El cortafuegos de Spring Security no lo impide, porque una letra codificada es legal. Lección: **para decidir sobre una ruta, compárala igual que la compara quien la enruta.**

**Qué peticiones gastan el límite por IP (según su `Content-Type`).** Login y registro no exigen la cabecera `X-Requested-With` y no hay CORS, así que una web ajena puede hacer que el navegador de la víctima les envíe `POST` «simples», los que no necesitan *preflight*: con `Content-Type` `text/plain`, `application/x-www-form-urlencoded` o `multipart/form-data` (la lista *CORS-safelisted* del estándar Fetch), o sin `Content-Type`. Spring las rechaza (415, o 400 sin cuerpo), pero **antes el filtro ya las había contado**: con unas pocas, la víctima quedaba en 429 sin poder entrar (pista NV-A de la auditoría 2, reproducida: tres `text/plain` → 415, y el login legítimo desde esa IP → 429). Ahora `RateLimitingFilter.countsTowardsLimit` **no cuenta** las peticiones sin `Content-Type`, con uno mal formado o con un tipo de esa lista (compara tipo y subtipo, sin parámetros ni mayúsculas), y **cuenta todo lo demás**. Las que no cuentan nunca llegan al controlador, así que no gastan BCrypt ni la base de datos.

- **Por qué no «contar solo JSON», que parece lo obvio:** Spring MVC puede leer el cuerpo con más tipos que JSON. Cuando se hizo el arreglo, también con `application/yaml`, porque springdoc trae `jackson-dataformat-yaml` y Spring registraba su conversor solo. Contar solo JSON habría dejado probar contraseñas y crear cuentas **sin límite** con `Content-Type: application/yaml` (lo descubrió el agente `security` al comprobar el arreglo: un login YAML devolvía 204). Después se quitó el conversor YAML (capítulo 13, «Los cuerpos solo se leen en JSON»): un YAML ya da 415, **pero sigue contando**, y el filtro no depende de qué conversores haya. Lección: **una lista blanca de lo que «cuenta» es frágil si no controlas qué acepta el que viene después; aquí es más seguro excluir solo lo que se sabe inofensivo.**
- **Por qué otra web no puede gastar el presupuesto con un tipo que sí cuenta:** cualquier otro `Content-Type` obliga al navegador a hacer un *preflight*, y sin CORS el servidor no lo autoriza.
- **La red de seguridad:** `RateLimitingContentTypeIntegrationTest` comprueba que ningún conversor de Spring sabe leer `LoginRequest` ni `CreateUserRequest` desde un tipo que no cuenta, y que todo tipo desde el que alguno los lee sí cuenta. Si mañana se añade un conversor de formularios, ese test falla.

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
- **Tope de claves (`streambox.security.rate-limit.max-keys`, 100 000 por contador).** Antes solo se borraban las claves caducadas cada 500 operaciones, pero una ventana larga (la de registro dura 1 hora) deja vivas todas las claves nuevas: un atacante con muchísimas IPs distintas podía llenar el *heap*. Ahora el mapa es un `LinkedHashMap` ordenado por el **último evento registrado**; en cada operación se purgan las caducadas desde la cabeza (se para en la primera viva, así que cuesta poco) y, si aun así está lleno, se **expulsa la clave con la actividad más antigua**. Se prefirió olvidar claves viejas a rechazar las nuevas: rechazarlas dejaría a cualquier usuario nuevo sin poder entrar ni registrarse mientras dure el ataque. Contrapartida: con más de `max-keys` claves distintas en una misma ventana se puede olvidar el contador de otro; por eso las IPv6 se agrupan (ver abajo). Con los valores por defecto el peor caso ronda los 30 MB por contador.
- **IPv6 por /64.** Un atacante con IPv6 suele controlar un bloque `/64` entero (2⁶⁴ direcciones) y rotar dentro de él para no repetir clave. `ClientAddress.counterKey` convierte cualquier IPv6 en la clave de su `/64` (`2001:db8:1:2::/64`), trata `::ffff:a.b.c.d` como la IPv4 `a.b.c.d` y deja la IPv4 como está (una clave por dirección). **Nunca resuelve nombres (DNS):** `InetAddress.getByName` solo trata un texto como literal IPv6 si empieza por un dígito hexadecimal, `:` o `[`, y cualquier otra cadena iría al DNS; por eso solo se analiza texto con hexadecimales, `:` y `.` (más una zona `%eth0` opcional, que se descarta) y se entrega entre corchetes, de modo que un texto raro devuelve error en vez de consultar un servidor. Cualquier otra cadena se usa tal cual como clave.
- **`synchronized`**: todos los métodos están sincronizados porque Tomcat atiende muchas peticiones a la vez en hilos distintos.

**¿Por qué «deslizante»?** Con una ventana fija (por ejemplo, «10 por minuto natural»), un atacante podría hacer 10 intentos a las 12:00:59 y otros 10 a las 12:01:00. La ventana deslizante mira siempre «los últimos 60 segundos» desde ahora.

### 11.2 La «IP conocida»: que el bloqueo no se pueda usar como arma contra el titular

**El problema (auditoría de seguridad, octubre de 2026).** El bloqueo por cuenta rechaza el login aunque la contraseña sea correcta. Un anónimo que sepa el email de otra persona (el registro responde «el correo ya está en uso») solo necesita **5 fallos cada 15 minutos**, unas 20 peticiones por hora desde una sola IP, para mantener esa cuenta bloqueada **indefinidamente**: cada vez que caduca un fallo, ocupa el hueco. Con el email del administrador, la aplicación se queda sin nadie que gestione el catálogo. Se reprodujo con una prueba sobre `LoginAttemptService` y un reloj simulado: 480 peticiones del atacante en 24 h simuladas y los 288 intentos del titular rechazados.

**La solución.** Una IP pasa a ser **«conocida» para una cuenta** cuando alguien ha iniciado sesión con éxito en ella desde esa IP (contraseña verificada: un intento fallido o un email inexistente nunca «conocen» una IP). Cuando entra un intento:

| Origen del intento | Contador que se usa | Qué pasa con los fallos |
| :--- | :--- | :--- |
| IP **desconocida** | El de la cuenta (`email`), como siempre | Bloquean a las IPs desconocidas; **no** afectan a las conocidas |
| IP **conocida** | El suyo propio (`email\|ip`), con los mismos límites (5 en 15 min) | Solo bloquean a esa IP; **no** afectan a la cuenta ni a otras IPs |

Así el titular sigue entrando desde su casa aunque un atacante tenga la cuenta bloqueada para el resto del mundo. El atacante no puede hacer que su IP sea «conocida» sin conocer la contraseña, y adivinar contraseñas desde una IP conocida sigue limitado a 5 fallos por 15 minutos (más el límite por IP del filtro). **Pero no es un cierre total:** protege al titular que ya ha entrado antes desde esa IP (ver las limitaciones de abajo).

- **Respuestas idénticas desde la misma IP.** Los códigos, mensajes y cabeceras (401 con `remainingAttempts`, 429 `ACCOUNT_LOCKED`) son idénticos para IP conocida o desconocida y para email existente o inexistente **cuando se consulta desde la misma IP**; el 429 no revela si la IP era conocida (lo comprueban los tests). **Excepción conocida (revisión independiente de `qa`, octubre de 2026):** si el atacante comparte salida con el titular (NAT móvil, red de empresa o campus, VPN, un `/64` compartido), su IP *sí* es conocida para la cuenta, y entonces puede (a) distinguir si esa cuenta tiene un login correcto reciente desde esa IP, cruzando dos orígenes (llena el contador de la cuenta desde otra IP y mira si desde la IP compartida recibe 401 o 429), y (b) puede gastar hasta **10 fallos por ventana desde esa IP en lugar de 5** (5 en el contador de la cuenta mientras la IP era desconocida y otros 5 en el de `email|ip` desde que el titular la «conoce»). Requiere estar co-localizado con la víctima y unos 6 intentos por sonda; no filtra contraseñas.
- **La IP sale de `request.getRemoteAddr()`** (el mismo criterio que `RateLimitingFilter`; nunca se lee `X-Forwarded-For` a mano). `AuthController` la pasa a `AuthenticationService.login(email, password, clientIp)`.
- **`KnownIpRegistry`** guarda por cuenta un conjunto acotado: máximo 5 IPs (`streambox.security.rate-limit.lockout.max-known-ips`), expulsando la más antigua, con caducidad de 30 días desde el último acierto (`…lockout.known-ip-ttl`), y como mucho `max-keys` cuentas. Solo crece con logins correctos de cuentas reales.
- **Cambiar la contraseña o cerrar sesión en todos los dispositivos olvida las IPs conocidas** (revisión de `qa`, plan §2.5). Sin esto, quien entró con la contraseña robada conservaba 30 días su IP conocida y, desde ella, seguía probando la contraseña nueva con su propio contador, sin sufrir el bloqueo de la cuenta. `PasswordChangeService` llama a `LoginAttemptService.forgetKnownIpsExcept(email, ip)` **después de confirmar la transacción** (si se deshace, la contraseña antigua sigue valiendo y sus IPs también) y deja como única conocida la IP de esa petición (`getRemoteAddr()`, por `/64` en IPv6): el cambio exige la contraseña actual verificada desde ahí, la misma prueba que un login correcto, y así el intruso recién expulsado no puede dejar fuera al titular bloqueando la cuenta. `AuthController.logoutAll` llama a `forgetKnownIps(email)` tras revocar las sesiones y **no** re-registra ninguna IP: esa petición solo prueba un JWT válido, no la contraseña (quien robara la cookie haría conocida su IP). Los contadores `email|ip` de las IPs olvidadas no se borran: dejan de usarse y caducan solos en la ventana. Tests: `KnownIpForgetIntegrationTest`.
- **Limitaciones.** Un administrador con IP dinámica o móvil, un reinicio, la caducidad de 30 días o un dispositivo nuevo hacen que su IP sea desconocida, y entonces el ataque original sigue funcionando contra su primer login. *Alternativa estructural, no implementada:* una **cookie de dispositivo** firmada con HMAC y ligada al email, emitida en el login correcto; no depende de la IP ni del reinicio (el atacante no puede presentarla). Queda como decisión pendiente en el plan. El estado vive en memoria (se pierde al reiniciar, como los contadores: tras un reinicio nadie es «conocido» hasta su siguiente acierto). Un **primer** login desde una IP nueva mientras la cuenta está bloqueada sigue rechazado hasta que caduque el bloqueo, y se puede seguir bloqueando a propósito para las IPs desconocidas. Tras un proxy, un NAT o un `/64` compartido, la «IP conocida» es la del grupo: quien la comparta con el titular comparte su contador.

### 11.3 Limitaciones conocidas

- **Vive en memoria.** Si la aplicación se ejecuta en varias copias (réplicas), cada una lleva su cuenta y el límite real se multiplica; un reinicio lo pone a cero. Para escalar habría que moverlo a un almacén compartido como Redis.
- **La IP.** Se usa `request.getRemoteAddr()`. Detrás de un proxy inverso (nginx, un balanceador) esa IP sería la del proxy; por eso el perfil `prod` activa `server.forward-headers-strategy=native`, que hace que Tomcat lea la IP real de la cabecera `X-Forwarded-For`. **Nunca se lee esa cabecera a mano**: un cliente podría falsificarla para evadir el límite. Si se pone otro proxy (p. ej. un terminador TLS) delante de nginx, hay que configurar `set_real_ip_from`/`real_ip_header` en nginx: de lo contrario todos los clientes compartirían IP y, con ella, el límite por IP y la «IP conocida».
- **Bloqueo como arma.** Alguien puede seguir bloqueando 15 minutos la cuenta de otra persona fallando su login a propósito, **pero ya no al titular que entra desde una IP conocida** (11.2). Se acepta porque es temporal y acotado.

Los fallos se cuentan **también para emails que no existen**: si solo se contaran los existentes, el bloqueo delataría qué emails están registrados.

---

## 12. Seguridad (IV): roles, administrador y «mis» recursos

### 12.1 Roles

Hay dos: `USER` y `ADMIN` (`entity/Role`). El `USER` consulta el catálogo y gestiona su lista; el `ADMIN` además crea, modifica y borra películas, géneros, series y episodios, usa las vistas de gestión `/api/admin/**` y lista usuarios.

### 12.2 Cómo se crea un administrador: `AdminAccountInitializer`

El registro público siempre crea `USER`. Los administradores solo se crean así:

1. Defines `ADMIN_EMAIL` y `ADMIN_PASSWORD` (y opcionalmente `ADMIN_USERNAME`) antes de arrancar.
2. Al arrancar, `AdminAccountInitializer.run` valida los datos (email con `@`, usuario de 3 a 50 caracteres).
3. Si ya existe un usuario con ese email **y es `ADMIN`**, **no lo toca** (nunca sobrescribe una contraseña) y **no valida la contraseña configurada**: validarla solo serviría para que una instalación que funcionaba dejara de arrancar al endurecerse la política. Si esa contraseña no cumple la política, escribe un aviso en el log **sin la contraseña ni la regla que falla** (sería una pista sobre la contraseña de una cuenta activa).
   - **Si existe una cuenta con ese email que NO es `ADMIN`, la aplicación no arranca**, con un error que nombra `ADMIN_EMAIL` y dice qué hacer (elegir otro email o corregir/eliminar esa cuenta). **Nunca se promueve** una cuenta existente. *Por qué (auditoría de octubre de 2026):* antes bastaba `existsByEmail` y se daba por hecho que «el administrador ya existe». Como el registro es público, un anónimo que adivinara el email (o registrara el usuario `admin`) antes de que el operador configurase `ADMIN_*` dejaba la instancia **sin administrador** y con un log INFO que afirmaba lo contrario. Ahora el fallo es ruidoso, no silencioso.
   - El mensaje también sugiere la salida si lo que quieres es arrancar sin administrador: dejar `ADMIN_EMAIL`/`ADMIN_PASSWORD` vacíos.
   - Si el nombre de usuario lo tiene otra cuenta, también falla, con un mensaje que nombra `ADMIN_USERNAME`.
   - La carrera entre «comprobar» y «guardar» (otra réplica, un registro simultáneo) se traduce al mismo error claro; si lo que ya existe es un `ADMIN` con ese email, no es un error.
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
| `POST` | `/api/auth/logout` | Público | `AuthController.logout` |
| `POST` | `/api/users` | Público | `UserController.createUser` → `UserService.registerUser` |
| `GET` | `/api/users/me` | Autenticado | `UserController.getCurrentUser` |
| `GET` | `/api/users` | ADMIN | `UserController.getUsers` |
| `GET` | `/api/movies` | Autenticado | `MovieController.getMovies` (paginado) |
| `GET` | `/api/movies/{id}` | Autenticado | `MovieController.getMovieById` |
| `GET` | `/api/movies/search` | Autenticado | `MovieController.searchMovies` (paginado, filtros) |
| `GET` | `/api/movies/{id}/similar?limit=` | Autenticado | `MovieController.getSimilarMovies` → `MovieService.getSimilarMovies` (lista, no página; §14.7) |
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

Las reglas viven en los DTOs de petición. Por ejemplo, `MovieRequest` (simplificado: en el código cada anotación lleva su `message`):

```java
public record MovieRequest(
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(max = 1000) String description,
    @NotNull @Min(1) Integer duration,
    @NotNull @Min(1888) @Max(2100) Integer releaseYear,
    @NotBlank @Size(max = 500) @HttpsUrl(allowLocalCovers = true, maxLength = 500) String imageUrl,
    @Size(max = 500) @HttpsUrl(maxLength = 500) String videoUrl,       // opcional (V6)
    @Size(max = 500) @YouTubeUrl(maxLength = 500) String trailerUrl,   // opcional, solo YouTube
    @NotEmpty @Size(max = 20) Set<@NotNull Long> genreIds) { }
```

**Todas las restricciones llevan su mensaje en español.** Sin `message`, Hibernate Validator usa el suyo, que depende del idioma de la JVM: en un servidor en inglés el cliente recibía «must not be blank». En `MovieRequest` los textos son **literalmente los del formulario del panel** (`frontend/src/lib/movieValidation.ts`: «El título es obligatorio», «La sinopsis es obligatoria», «Indica la duración en minutos», «El año debe estar entre 1888 y 2100», «La URL de la portada es obligatoria», «Elige al menos un género»…), en constantes del propio `record`, para que el administrador lea lo mismo lo detecte el cliente o el servidor. Lo mismo en el registro y el login («El correo electrónico es obligatorio», «La contraseña es obligatoria»…).

**Ids de género nulos:** `Set<@NotNull Long>` (en películas y series) convierte `"genreIds": [1, null]` en un 400 `VALIDATION_ERROR` («Los identificadores de género no pueden ser nulos»). Antes, en películas, el nulo llegaba a `findAllById`, que no admite ids nulos, y el cliente recibía un **500**.

**Longitud del email (registro y login):** como mucho 100 caracteres, la columna `users.email VARCHAR(100)`. `@Email` no limita la longitud total (solo la parte local y cada trozo del dominio), así que un email de formato válido y más largo llegaba a la base de datos y respondía un **409 engañoso**. Además, `UserService.normalizeEmail` vuelve a medir el email **tras pasarlo a minúsculas**, porque puede crecer (la `İ` se convierte en dos caracteres; el mismo problema que con los géneros, capítulo 14.5): mismo 400 y mismo mensaje. En el login el tope no deja fuera a nadie (ninguna cuenta puede tener un email más largo que su columna) y evita que un email enorme llegue al servicio y a los contadores de intentos en memoria.

**URLs de portada y vídeo (`validation/HttpsUrl` + `HttpsUrlValidator`).** Antes se usaba `@URL` de Hibernate Validator, que acepta cualquier esquema (`http:`, `file:`, `ftp:`, `jar:`…), y no había límite de longitud: una URL de más de 500 caracteres llegaba a la columna `VARCHAR(500)` y el cliente recibía un **409 engañoso**. Ahora:

- Una URL vale si solo tiene **caracteres ASCII visibles** (sin espacios; lo demás va codificado con `%XX`), empieza exactamente por **`https://`** (en minúsculas: `HTTPS://` se rechaza en lugar de normalizarse, porque un validador no debe cambiar el valor), **tiene host** y **no lleva credenciales** (`https://usuario:clave@host`). Se analiza con `java.net.URI`, no con una expresión regular.
- Solo en `imageUrl` (`allowLocalCovers = true`) vale también una **portada propia** `/covers/<archivo>`, con el archivo de la forma `[A-Za-z0-9][A-Za-z0-9._-]*` (sin subcarpetas, sin `..`, sin `?` ni `%`). Es como se sirven las de `frontend/public/covers`. Antes no pasaban `@URL`, así que `docs/portadas-locales.sql` no se podía reproducir por la API; ahora sí.
- **Un solo mensaje por campo:** `@HttpsUrl` no evalúa lo que ya rechazan `@NotBlank` (vacío) ni `@Size` (si es demasiado larga, manda el mensaje de longitud). Los mensajes y el máximo son constantes de `MovieRequest`, y el frontend usa exactamente los mismos.
- **El vídeo es opcional** (desde la `V6`, en películas y episodios: StreamBox no aloja vídeos): `videoUrl` ya no lleva `@NotBlank`. Ausente, `null`, vacío o en blanco significa «sin vídeo»: los validadores dejan pasar el texto en blanco y el mapper (`MovieMapper`, `EpisodeMapper`) lo guarda como `null`, así que «no tiene» tiene una sola representación y la respuesta trae `"videoUrl": null`. Si viene con contenido, se valida igual que antes.
- ¿Por qué validar en el servidor si el frontend ya filtra `videoUrl` con `getSafeVideoUrl`? Porque la API la pueden usar otros clientes y no debe depender de que cada uno se proteja. Además, `https` encaja con la CSP prevista (`img-src 'self' data: https:`).
- Consecuencia práctica: una película antigua con una URL `http://` no se puede guardar desde el panel hasta corregir la URL (el formulario muestra el error junto al campo). El servidor nunca descarga estas URLs, así que no hay riesgo de peticiones internas (SSRF).

**Tráiler (`validation/YouTubeUrl` + `YouTubeUrlValidator` + `YouTubeUrls`).** Películas (`MovieRequest.trailerUrl`) y series (`SeriesRequest.trailerUrl`) tienen un tráiler opcional que el frontend **incrusta** en un `<iframe>` de `youtube-nocookie.com`. Por eso no basta con `@HttpsUrl` (cualquier URL https): solo se aceptan unas pocas formas de enlace de YouTube, y se **guardan normalizadas**:

- Formas aceptadas (siempre `https://` en minúsculas y el host exacto, en minúsculas): `https://www.youtube.com/watch?v=<id>` (también `m.youtube.com` y `youtube.com`; el resto de parámetros se ignora, pero tiene que haber **un único** `v`), `https://youtu.be/<id>`, `https://www.youtube.com/embed/<id>` y `https://www.youtube-nocookie.com/embed/<id>` (estas tres con parámetros opcionales). `<id>` son exactamente 11 caracteres de `[A-Za-z0-9_-]`.
- Rechazadas: `http:`, `HTTPS:`, `javascript:`, otros hosts y los parecidos (`youtube.com.evil.com`, `evilyoutube.com`, `music.youtube.com`), el host en mayúsculas (el frontend compara igual; las dos validaciones deben coincidir), credenciales (`usuario@`, incluido el truco `https://www.youtube.com@evil.com/...`), cualquier puerto (también `:443`), fragmentos (`#`), espacios, caracteres de control o no ASCII, ids de 10 o 12 caracteres o con `%`, y rutas con algo más (`/watch/`, `/embed/<id>/x`, `/shorts/<id>`).
- **Un único análisis para validar y normalizar.** `YouTubeUrls.videoId(...)` analiza el enlace con `java.net.URI` (no con una regex sobre toda la URL): exige que la autoridad en bruto sea exactamente el host (eso descarta credenciales y puertos de una vez), compara el host exacto con una lista y solo usa una regex para el id. El validador (`isValid`) y el normalizador (`normalize`, que devuelve `https://www.youtube.com/watch?v=<id>`) usan ese mismo método, así que **todo lo que la API acepta se puede normalizar y todo lo normalizado cumple los `CHECK`** `ck_movies_trailer_url` / `ck_series_trailer_url` de la `V6`. Si el servicio guardara el enlace tal cual, un `youtu.be/<id>` válido para la API acabaría en un error de integridad. Lo prueba `TrailerUrlIntegrationTest` (H2) y `PostgresTrailerUrlApiIntegrationTest` (PostgreSQL real) guardando **cada** forma aceptada (el catálogo de casos está en `YouTubeUrlSamples`, compartido con `YouTubeUrlsTest`).
- La normalización la hacen los mappers (`MovieMapper`/`SeriesMapper.copyFields`), dentro de la transacción del servicio. Vacío o en blanco = sin tráiler (`null`). Si alguien llamara al servicio sin validar, `normalize` lanza `IllegalArgumentException` (un error de programación, mejor que llegar al `CHECK`).
- Mensajes (contrato con el frontend, `frontend/src/lib/youtube.ts`): «El tráiler debe ser un enlace de un vídeo de YouTube» y, con más de 500 caracteres, «La URL del tráiler no puede superar los 500 caracteres» (el validador no opina por encima de `maxLength`, como `@HttpsUrl`).
- **`PUT` sin tráiler o sin vídeo los borra.** El `PUT` sustituye el recurso completo: un campo que no llega queda a `null`, como cualquier otro. El panel siempre reenvía los valores que quiere conservar.
- En las respuestas (`MovieResponse`, `SeriesResponse`, `SeriesDetailResponse`; también las de `/api/admin/series`, la búsqueda y «Mi lista») `trailerUrl` siempre está presente: la forma normalizada o `null`. `videoUrl` de `MovieResponse` y `EpisodeResponse` puede ser `null`. En el OpenAPI (3.1) estos campos tienen el tipo `["string", "null"]`. No cambia el coste de ningún listado: `trailer_url` es una columna más de la misma fila.

**Géneros por título:** `genreIds` admite entre 1 y 20 géneros, en películas y en series (`MovieRequest.MAX_GENRES`). Sin tope, la lista acaba en un `IN (...)` sin límite dentro de `findAllById`; PostgreSQL admite como mucho 32 767 parámetros.

**Decimales en campos enteros:** un número con decimales en un campo entero del JSON (`"duration": 100.5`, `"seasonNumber": 1.5`, incluso `100.0`) responde 400 `MALFORMED_REQUEST` en lugar de truncarse en silencio. Jackson trae esa conversión activada por defecto y se desactiva con `spring.jackson.deserialization.accept-float-as-int=false` en `application.properties`. Se aplica al `JsonMapper` de Jackson 3, con el que Spring Boot 4 lee los cuerpos; Jackson 2 solo lo usa `SecurityErrorResponseWriter` para escribir los 401/403. Lo más grave era `"genreIds": [5.5]`, que asignaba **otro género** (el 5). Los parámetros de consulta (`?page=1`) no se ven afectados, porque los convierte Spring. Tests: `JsonIntegerCoercionIntegrationTest`.

**Cada campo acepta solo su tipo JSON (sin conversión de escalares).** Jackson también convertía por defecto entre escalares: `"password": 123456789012.5` (un número) creaba la cuenta con la contraseña `"123456789012.5"`, `"title": true` una película titulada «true» y `"duration": "90"` se leía como 90. El cliente mandaba una cosa y se guardaba otra, y la validación se aplicaba a un texto que nadie escribió. `config/StrictJsonScalarsConfig` (un `JsonMapperBuilderCustomizer`, porque `spring.jackson.*` no configura coerciones) hace dos cosas, y hacen falta las dos:

- desactiva `MapperFeature.ALLOW_COERCION_OF_SCALARS`, que cubre los destinos numéricos y booleanos (texto → número, número → booleano);
- pone a `Fail` la coerción del tipo lógico `Textual` desde `Integer`, `Float` y `Boolean`. `ALLOW_COERCION_OF_SCALARS` **no** cubre el texto: Jackson no considera `String` un escalar «convertible» para esa característica, así que sin este ajuste `"password": 123` seguía entrando.

El resultado es un 400 `MALFORMED_REQUEST` con el mensaje genérico (sin detalles del parser), como un JSON mal formado. El frontend no se ve afectado: envía cada campo con su tipo (`lib/types.ts`; los números del formulario se convierten con `Number(...)` antes de enviar). Los campos `Object` de `UpdateProfileRequest` siguen aceptando cualquier valor (no hay nada que convertir) y los parámetros de consulta no pasan por Jackson. Tests: `InputValidationHardeningIntegrationTest`.

**Los cuerpos solo se leen en JSON.** Spring MVC elige con qué «conversor» leer un `@RequestBody` según el `Content-Type`, y registra automáticamente los de cualquier formato cuya librería esté en el *classpath*. springdoc trae `jackson-dataformat-yaml`, así que **toda la API aceptaba también YAML** sin que nadie lo hubiera pedido: entrada del cliente analizada por SnakeYAML, sin necesidad, y además un `login` en YAML se saltaba el arreglo de NV-A (capítulo 11). `config/JsonOnlyMessageConvertersConfig` quita los conversores YAML con `configureMessageConverters(HttpMessageConverters.ServerBuilder)` (la API de Spring Framework 7; la de la `List` está obsoleta). Los reconoce por los tipos que anuncian (subtipo `yaml`, `x-yaml` o `*+yaml`) y no con `isCompatibleWith`, porque los conversores genéricos anuncian `*/*` y se quitarían por error. Resultado: un cuerpo YAML recibe 415 `UNSUPPORTED_MEDIA_TYPE`, pedir la respuesta con `Accept: application/yaml` da 406, y `/v3/api-docs.yaml` sigue funcionando (springdoc genera el YAML él mismo y lo devuelve como `byte[]`). `JsonOnlyRequestBodyIntegrationTest` falla si cualquier `@RequestBody` de la API se puede leer desde un tipo que no sea JSON, por ejemplo si mañana una dependencia trae un conversor XML o CBOR.

**Sin multipart.** La API no tiene ninguna subida de archivos (las portadas son URLs), así que `spring.servlet.multipart.enabled=false`. Con el valor por defecto de Spring Boot, `DispatcherServlet` analizaba cualquier `multipart/form-data` (hasta 10 MB, con ficheros temporales en disco) **antes** de descubrir que el endpoint no lo acepta, también en las rutas públicas (login, registro, logout, `/actuator/health`). Ahora el cuerpo ni se lee: login y registro responden 415 (lo prueba `MultipartDisabledTomcatIntegrationTest` contra Tomcat real con un archivo de más de 1 MB, que daría 413 si se analizara). Si algún día hay subidas (p. ej. portadas a MinIO o S3), hay que reactivarlo con límites bajos y solo para esa ruta (`resolve-lazily=true`), no para toda la API.

**Sin `FormContentFilter`.** Spring Boot registra por defecto un filtro que, en `PUT`, `PATCH` y `DELETE` con `application/x-www-form-urlencoded`, lee el cuerpo entero (sin límite y **antes de la autenticación**) para exponerlo como parámetros. Un anónimo podía obligar al servidor a leer 20 MB en cualquier ruta. La API no usa formularios, así que `spring.mvc.formcontent.filter.enabled=false` (`FormContentFilterDisabledTomcatIntegrationTest`). Los `POST` con formulario los sigue leyendo Tomcat, con su límite `maxPostSize` de 2 MB.

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

`PageableFactory.build` (la usan `MovieController` y `SeriesController`) construye el `Pageable` y hace tres comprobaciones:

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

**`hasTitle`: «contiene», sin distinguir mayúsculas ni acentos** (`specification/TitleSearch`; ADR 0013)

```sql
streambox_unaccent(lower(title)) like '%' || <texto normalizado y escapado> || '%' escape ''
```

- **Sin acentos.** `streambox_unaccent` es una función propia (migración `V5`): en PostgreSQL envuelve la extensión `unaccent` (instalada en el esquema `extensions`) y es `IMMUTABLE`, porque un índice sobre una expresión lo exige y `unaccent` no lo es; en H2 es un alias a Java (`java.text.Normalizer`). «manana» encuentra «Mañana», «nono» encuentra «Ñoño», «strasse» encuentra «Straße».
- **Con índice.** En PostgreSQL hay un índice **GIN de trigramas** (`pg_trgm`) sobre `streambox_unaccent(lower(title))` en `movies` y `series`: un `LIKE '%x%'` normal no puede usar un índice B-tree, este sí (desde 3 letras).
- **Comodines escapados, después de normalizar.** En `LIKE`, `%` es «cualquier texto» y `_` «cualquier carácter»: si el usuario busca `100%`, sin escapar encontraría todo lo que empiece por «100». Se escapan en SQL **después** de quitar acentos, porque `unaccent` convierte los comodines de ancho completo (`％ ＿ ＼`) en los normales; en el orden contrario, buscar `＿` devolvía todo el catálogo (hay test).
- **`lower()` en los dos lados, en la base de datos.** Java y PostgreSQL no convierten igual algunas letras (`İ` turca, la sigma griega final); los tests contra PostgreSQL lo descubrieron (H2 lo ocultaba). Ahora lo hace el mismo motor.
- **El texto va como parámetro enlazado** (`HibernateCriteriaBuilder.value(texto)`), nunca como literal dentro del SQL. Con el literal (como hacía `LikePatterns`), un carácter nulo (`?title=a%00b`) llegaba dentro del mensaje del protocolo, PostgreSQL lo rechazaba y la conexión del pool quedaba rota en cada petición. Con el parámetro, el mismo nulo es un error de datos que deja la conexión sana (y ni llega: ver abajo). Los tests de `O'Brien` y `'; DROP TABLE` siguen pasando.
- **La clave no puede quedar vacía:** un texto de solo acentos sueltos (`́`) no está «en blanco», pero `streambox_unaccent` lo deja en `''` y el patrón sería `LIKE '%%'` (todo el catálogo). Se exige en la base de datos `length(clave) > 0`.
- **Validación del parámetro:** `title` admite como mucho 150 caracteres (el título más largo posible) y ningún carácter de control (tabulador, saltos de línea, nulo): si no, 400 `VALIDATION_ERROR` (`controller/TitleSearchParameter`). El frontend limita el campo y limpia esos caracteres antes de enviar.

**Tolerancia a erratas** (solo PostgreSQL). Si la búsqueda exacta **no encuentra nada** y tiene **5 letras o más**, `service/TypoTolerantTitleSearch` repite la búsqueda con los mismos filtros y `hasSimilarTitle`: `streambox_word_similarity(búsqueda, título) ≥ 0.4` (de `pg_trgm`). Así «interstelar» encuentra «Interstellar» y «matirx», «The Matrix». La respuesta lleva `approximate: true` y los resultados van **ordenados por parecido**, ignorando el `sort` pedido. Por qué así:

- **Solo si la exacta no da nada:** sumarla siempre traería ruido («matrix» bien escrito traería «Matilda»).
- **Umbral 0,4 y no el 0,6 por defecto:** con 0,6, «matirx» no encontraría «Matrix».
- **Mínimo de 5 letras:** con menos, «amro» se parece igual a «Amor», «Amadeus» y «Amélie».
- **En H2 no hay tolerancia** (la función devuelve 0): imitar `pg_trgm` en Java podría aprobar en los tests resultados que PostgreSQL no da. Se prueba contra PostgreSQL real (`PostgresTitleSearchIntegrationTest`, `PostgresTypoTolerantSearchIntegrationTest`).
- **Límite asumido:** recorre la tabla (no usa el índice), y por cómo funcionan los trigramas «matirx» también se parece a «Matilda». Si el catálogo creciera mucho, los operadores indexables `<%` serían el siguiente paso (ADR 0013).

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

**Borrado.** Si alguna película usa el género, se responde 409 `GENRE_IN_USE` con el recuento («lo usan 3 películas»; `MovieRepository.countByGenres_Id`, un `COUNT` que no carga películas). ¿Por qué no borrar y ya? La clave foránea `movie_genres.genre_id` **no** tiene `ON DELETE CASCADE` a propósito, y toda película debe tener al menos un género: borrar en cascada podría dejar películas sin ninguno. La base de datos respalda la regla: si alguien asigna el género justo entre la comprobación y el borrado, la FK salta en el `flush` y cualquier `DataIntegrityViolationException` en ese punto se traduce también a `GENRE_IN_USE`, esta vez sin cifra (tras un `flush` fallido la transacción ya no admite más consultas). `SqlStates` es el ayudante que saca el `SQLSTATE` de la cadena de causas; lo usan `GenreService` (solo para la unicidad, `23505`), `FavoriteService`, `SeriesFavoriteService` y `EpisodeService`.

### 14.6 Películas similares (la consulta)

Primera versión, sin IA: dada una película, las que **comparten más géneros** con ella. Viven en `MovieRepository` dos métodos que el servicio usa uno detrás de otro:

```java
List<Long>  findSimilarMovieIds(Long movieId, int limit);       // ids ya ordenados
List<Movie> findAllWithGenresByIdIn(Collection<Long> ids);       // películas + géneros, SIN orden
```

**El criterio.** Son candidatas las películas con al menos un género en común, excluida la propia. Orden total y determinista: (1) géneros en común, de más a menos; (2) distancia en años `ABS(año candidata − año origen)`, de menos a más; (3) `created_at` más reciente primero; (4) `id` ascendente (sin él, dos películas empatadas en todo podrían cambiar de sitio entre llamadas, como en §14.2).

**La consulta de ids (SQL nativo):**

```sql
SELECT candidata.movie_id
FROM movie_genres origen
JOIN movie_genres candidata ON candidata.genre_id = origen.genre_id
                           AND candidata.movie_id <> origen.movie_id
JOIN movies pelicula_origen ON pelicula_origen.id = origen.movie_id
JOIN movies m               ON m.id = candidata.movie_id
WHERE origen.movie_id = :movieId
GROUP BY candidata.movie_id, m.release_year, m.created_at, pelicula_origen.release_year
ORDER BY COUNT(*) DESC, ABS(m.release_year - pelicula_origen.release_year), m.created_at DESC, candidata.movie_id
FETCH FIRST :limit ROWS ONLY
```

- **Auto-join de `movie_genres`.** `origen` son las filas de la película dada; `candidata`, las de otras películas con el mismo género. Cada fila del join es *un género compartido*, así que `COUNT(*)` por película es el número de géneros en común (la PK `(movie_id, genre_id)` impide contar uno dos veces). Las películas sin nada en común no llegan a aparecer: no hace falta filtrarlas.
- **Por qué nativo y no JPQL:** es una agregación sobre la tabla de unión; en JPQL habría que pasar por la entidad `Genre` y repetir la película de origen en subconsultas. En SQL se lee tal cual y su plan es el que se ha revisado.
- **Portable:** `FETCH FIRST n ROWS ONLY` es el `LIMIT` del estándar SQL y lo entienden PostgreSQL y H2 (con parámetro enlazado). Las columnas del `ORDER BY` se repiten en el `GROUP BY` porque H2 no deduce que dependen del `id`.
- **Nulos:** `release_year` y `created_at` son `NOT NULL` (también en las bases anteriores a Flyway), así que `ABS` nunca recibe un nulo.
- **Solo ids:** el `LIMIT` se aplica en la base de datos sobre películas, no sobre filas película×género (ver la nota de §8.3 sobre paginar con `JOIN FETCH`).

**La carga sin N+1.** `findAllWithGenresByIdIn` es un `select m from Movie m where m.id in :ids` con `@EntityGraph("genres")`: **una** consulta con `JOIN` a los géneros. Aquí el `JOIN FETCH` de la colección sí es seguro, porque no hay paginación (el límite, como mucho 24, ya se aplicó al elegir los ids); con la carga por lotes serían dos consultas. En total, **dos sentencias** pase lo que pase (lo cuentan los tests con las estadísticas de Hibernate). Como `IN` no garantiza orden, **el servicio reconstruye el orden** a partir de la lista de ids (un `Map<Long, Movie>`).

**Índices (`EXPLAIN ANALYZE` en PostgreSQL 16, datos sintéticos con 20 géneros):** la película de origen se localiza con la PK de `movie_genres` (empieza por `movie_id`), las candidatas con `idx_movie_genres_genre_id` y las películas por su PK (o un `Seq Scan` + `Hash Join` cuando hay muchas candidatas, que es lo barato). El orden final es un *top-N heapsort* que solo guarda las `limit` primeras. Tiempos: 0,3 ms con 200 películas, 2 ms con 5 000 y 33 ms con 100 000 (13 000 candidatas). **No hace falta ningún índice nuevo.** El coste crece con el número de películas que comparten algún género (hay que contarlas todas para saber cuáles son las mejores); si el catálogo llegara a esos tamaños, un índice `(genre_id, movie_id)` permitiría leer las candidatas solo del índice, pero la mejora real sería precalcular las similitudes.

**Tests:** los casos están una sola vez en `repository/SimilarMoviesQueryContract` (una *interfaz de test* de JUnit 5: sus métodos `default` con `@Test` se ejecutan en cada clase que la implementa) y se ejecutan en H2 (`SimilarMoviesRepositoryIntegrationTest`) y en PostgreSQL real (`postgres/PostgresSimilarMoviesIntegrationTest`). Cada caso fija los datos para que solo decida el criterio que prueba (por ejemplo, en el de `created_at` los ids crecen en sentido contrario, para que el desempate por `id` no pueda dar el resultado por casualidad).

### 14.7 Películas similares (el endpoint)

`GET /api/movies/{id}/similar?limit=12` → `MovieController.getSimilarMovies` → `MovieService.getSimilarMovies`. Lo usa la fila «Películas similares» de la página de detalle (`/peliculas/:id`).

**Contrato.**

| Caso | Respuesta |
| :--- | :--- |
| Película con similares | 200, **array** de `MovieResponse` (el mismo DTO que los listados, con sus géneros), ya ordenado según §14.6 |
| Existe pero no comparte géneros con ninguna (o no tiene) | 200 `[]` |
| No existe | 404 `RESOURCE_NOT_FOUND`, **el mismo cuerpo** que `GET /api/movies/{id}` («Película no encontrada») |
| `limit` fuera de 1..24 | 400 `VALIDATION_ERROR` con `validationErrors.limit` («El límite mínimo es 1» / «El límite máximo es 24») |
| `limit` no entero (`abc`, `1.5`, desborda `int`) | 400 `VALIDATION_ERROR` («El valor no tiene el formato esperado») |
| Sin sesión | 401 |

- **Lista y no página.** Es una fila de recomendaciones de tamaño acotado: no hay «página 2» que pedir, así que no lleva `page`/`size`/`sort` (el orden lo decide el servidor). El límite por defecto (12), el mínimo y el máximo (24) son constantes de `MovieService` (`DEFAULT_SIMILAR_LIMIT`, `MIN_SIMILAR_LIMIT`, `MAX_SIMILAR_LIMIT`) que usan también las anotaciones `@Min`/`@Max` del controlador. El máximo es bajo a propósito: la carga de géneros va en un `IN` sin paginar.
- **¿Por qué 404 y no `[]` si no existe?** Para que el cliente distinga «esa película no existe» de «no se parece a ninguna», y para que la ruta se comporte igual que el detalle.

**El servicio, en tres pasos** (todo dentro de un `@Transactional(readOnly = true)`, porque el mapeo a DTO lee los géneros):

1. `existsById(id)`: un `count` por clave primaria que no carga la película ni sus géneros. Si es falso, `MovieNotFoundException` (la misma que `getMovieById`).
2. `findSimilarMovieIds(id, limit)`: los ids ya ordenados y limitados (§14.6). Si la lista está vacía, devuelve `List.of()` **sin** lanzar la carga.
3. `findAllWithGenresByIdIn(ids)` → `Map<Long, Movie>` → se recorre la lista de ids para **reconstruir el orden** (el `IN` no lo conserva) y se mapea con `MovieMapper`. Si una película se borra entre los pasos 2 y 3, simplemente no aparece (no hay `NullPointerException`).

El servicio también rechaza un `limit` fuera de rango (`InvalidParameterException`): el controlador ya lo valida, pero ese número acaba en un `FETCH FIRST` y el servicio podría tener otros llamantes. *Defensa en profundidad.*

**Coste medido** (estadísticas de Hibernate): el servicio ejecuta **3 sentencias** con resultados (existencia + ids + películas con géneros) y **2** sin ninguna similar, sea cual sea el límite. De punta a punta, con la autenticación (que lee el usuario en cada petición), son 4 sentencias, dos más que el 404.

**Seguridad.** No hizo falta tocar `SecurityConfig`: la ruta cae en la regla de lectura del catálogo (`GET`/`HEAD` sobre `/api/movies/**`, cualquier autenticado) y cualquier otro método en la de escritura (solo `ADMIN`), así que un `POST` de un USER es 403 antes de llegar a Spring MVC.

**Tests.** `controller/SimilarMoviesIntegrationTest` (MockMvc, datos confirmados de verdad): el orden de punta a punta con los cuatro criterios a la vez, la exclusión de la propia, `[]`, el límite por defecto y explícito, los 400, el 404 idéntico al del detalle, 401/403/`HEAD` y el número de sentencias. `service/MovieServiceSimilarTest` (Mockito) fija lo que la base de datos no deja ver: que el orden sale de la lista de ids aunque la carga devuelva las películas desordenadas, que sin candidatas no se lanza la segunda consulta y que una película borrada entre medias se omite.

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

- **Quitar**: primero se comprueba que la película existe (si no, 404 `RESOURCE_NOT_FOUND`); después el `DELETE` devuelve cuántas filas borró. Si es 0, la película no estaba en la lista → **404** `MOVIE_NOT_IN_FAVORITES`.
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

- `series`: título, sinopsis, `release_year`, `end_year` (**nulo = en emisión**; `CHECK` de años entre 1888 y 2100 y fin ≥ estreno), `image_url`, `created_at` y, desde la `V6`, `trailer_url` (opcional, solo YouTube normalizado; sección 5.4). Desde la `V6`, el `video_url` de los episodios es opcional.
- `series_genres`: el mismo catálogo de géneros que las películas. Borrar una serie limpia sus filas (cascada); borrar un género usado por una serie falla por clave foránea, y el servicio lo traduce a 409.
- `episodes`: `season_number`, `episode_number`, título, sinopsis opcional, duración y `video_url`, con `CHECK` (temporada, número y duración positivos) y **`UNIQUE (series_id, season_number, episode_number)`**: no puede haber dos «T1:E3» en la misma serie. Ese índice único empieza por `series_id`, así que sirve también para listar los episodios de una serie ya ordenados y para el `EXISTS` de visibilidad.
- `user_favorite_series`: «Mi lista» de series.
- **Cascadas en la base de datos:** al borrar una serie se borran sus episodios, sus géneros y sus favoritos; al borrar un usuario, sus favoritos de series.
- V3 **solo crea** tablas e índices (es segura sobre una base con datos), todas las restricciones tienen nombre (`pk_`, `fk_`, `ck_`, `uk_`, `idx_`) y **no usa `IF NOT EXISTS`**: si existiera ya una tabla `series` distinta, es mejor que falle de forma visible a que la adopte en silencio.
- **Supabase:** las tablas de V3 nacen sin RLS, pero el callback de Flyway (sección 5.4) las cierra en el mismo arranque; ya no hay que repetir el script a mano (capítulo 4.5). *(El aviso que lleva `V3__create_series.sql` en su cabecera quedó obsoleto, pero no se puede editar sin cambiar su checksum.)*

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
- **Código compartido con películas:** `controller/PageableFactory` (paginación y orden) y `specification/TitleSearch` (búsqueda por título sin mayúsculas ni acentos, con comodines escapados; antes `LikePatterns`). Se extrajeron de películas sin cambiar su comportamiento, para que las dos no puedan divergir.

### 15 bis.5 Frontend del usuario

- **Componentes genéricos.** Las piezas visuales de películas se generalizaron para servir a las dos: `PosterCard`, `PosterRow<T>`, `FeaturedBanner` y `MetaTags`, sobre un tipo común `CatalogItem` (`lib/types.ts`). `MovieCard`, `MovieRow`, `HeroBanner`… quedan como envoltorios finos con la misma API, y por eso sus tests no cambiaron. `useCatalog` se apoya en `usePagedCatalog`, que también usan las series, y `buildCatalogRows<T>` es genérica (`CatalogRow.items`).
- **`/series` (`SeriesPage`):** sin filtros, la misma estructura que la portada. Banner con la serie más reciente (marca «Novedad»; la acción principal «Ver episodios» enlaza a su página, porque una serie no tiene un único vídeo; si la serie tiene tráiler, «Ver tráiler» va detrás como secundaria, ver 21.4), «Novedades», filas por género (≥3 títulos **sin contar el del banner**), «Cargar más series» y los estados de carga, vacío y error. Con la **misma barra de filtros que `/peliculas`** (género, año de estreno y orden en la URL; capítulo 20.8), una cuadrícula con los resultados de `GET /api/series/search` (`useSeriesResults` en `hooks/useSeriesCatalog.ts`).
- **`/series/:id` (`SeriesDetailPage`):**
  - Cabecera con `FeaturedBanner` («Volver a series» encima, h1, sinopsis completa, «Empezar a ver», «Ver tráiler», «Mi lista» y, solo para ADMIN, «Editar»), centrada como la de una película, y ficha lateral junto a los episodios (ver 20.8 bis). La acción principal lleva al **primer episodio que tiene vídeo** (`firstWatchableEpisode`, con la misma validación que `WatchButton`) y **no aparece si ningún episodio tiene vídeo**. Su texto dice la verdad (`StartWatchingButton`): **«Empezar a ver»** solo si ese episodio es el primero de la serie (el primero de la primera temporada); si no, **«Ver T2:E3»** (`episodeCode`), porque un «Empezar a ver» que abre la segunda temporada engañaría. Nombres accesibles: «Empezar a ver Dark: T1:E1 …» y «Ver T2:E3 de Dark: El regreso» (distinto del «Ver T2:E3 El regreso» de la lista). **«Ver tráiler»** solo si la serie tiene tráiler: principal si no hay episodio que ver, secundaria si lo hay (21.4). Sin tráiler ni episodio con vídeo, un aviso según el rol (20.9).
  - **Selector de temporada** (`SeasonPicker`): son enlaces `?temporada=N` con `aria-current` y navegación con `replace`, no un `<select>` ni el patrón `tablist`. La temporada vive en la URL, así que se puede compartir y abrir en otra pestaña; con un `<select>`, en Windows cada flecha cambiaría la URL. Solo aparece con dos o más temporadas. Una temporada inválida o ausente muestra la primera (`resolveSeasonNumber`). «Temporada 2 · 8 episodios» está en una región `role="status"`, que anuncia el cambio sin mover el foco.
  - `EpisodeList`: una `<ol>` con un h3 «N. Título», la duración, la sinopsis si la hay y «Ver», que abre el vídeo validado con `getSafeVideoUrl` (nombre accesible «Ver T1:E3 Título»). **Sin vídeo (es opcional desde la `V6`), el episodio no tiene «Ver»**: la fila es solo texto, sin hueco ni botón desactivado (21.4). Sin miniatura por episodio, porque aún no tienen imagen propia.
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
  - Campo **«Tráiler de YouTube (opcional)»** (`trailerUrl`): mismas reglas y mensajes que el de una película (`validateTrailerUrl`, 21.4); vacío se envía como `null`. La serie no tiene vídeo propio: los vídeos son de cada episodio.
  - **Al crear, lleva a la edición** de la serie nueva («Serie creada. Añade episodios para que sea visible»).
  - Al guardar cambios se queda en la página, porque debajo está la gestión de episodios.
  - La vuelta es «Volver al listado» o, si se llegó con «Editar» desde la página de la serie (`/series/:id`, solo ADMIN), «Volver a la serie» (validada por lista blanca con `getReturnTo`; ver 20.8 bis).
- **Episodios** (`SeriesEpisodesSection` + `EpisodeFormDialog`):
  - Están agrupados por temporada. Cada uno tiene las acciones «Editar episodio T1:E3 Título» y «Borrar episodio T1:E3 Título»; el código hace único el nombre accesible.
  - **Añadir y editar usan un único diálogo.** Al añadir, propone la última temporada y el número siguiente al más alto, y lo recalcula si cambias la temporada, salvo que ya hayas escrito el número a mano.
  - El 409 `EPISODE_ALREADY_EXISTS` aparece junto a «Temporada» y «Número», con el foco ahí.
  - **«URL del vídeo (opcional)»**: vacío = sin vídeo (`null`), y entonces el episodio se ve en la lista pero sin «Ver» (la ayuda del campo lo dice). Los tráileres no van aquí, sino en la serie.
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
  "validationErrors": { "title": "El título es obligatorio", "releaseYear": "El año debe estar entre 1888 y 2100" }
}
```

`validationErrors` solo aparece en errores de validación, y **`remainingAttempts`** solo en el 401 de un login fallido (capítulo 11). `@JsonInclude(NON_NULL)` omite los campos nulos, así que en el resto de errores no aparecen.

**El campo importante para los clientes es `code`**, no `message`: el mensaje es para mostrar al usuario y puede cambiar; el código es un contrato estable.

### 16.2 Códigos de error (`dto/ErrorCode`)

| `code` | HTTP | Cuándo |
| :--- | :--- | :--- |
| `VALIDATION_ERROR` | 400 | `@Valid` falla, parámetro mal formado, `sort`/`direction`/`page` no permitidos |
| `MALFORMED_REQUEST` | 400 | JSON mal escrito o con un valor de otro tipo (número en un texto, texto o decimal en un número: no se convierten; capítulo 13.3) |
| `INVALID_CREDENTIALS` | 401 | Login incorrecto (con `remainingAttempts`), y también 401 por falta de token o token de acceso caducado |
| `SESSION_EXPIRED` | 401 | `POST /api/auth/refresh` sin una sesión renovable válida: hay que volver a iniciar sesión (capítulo 10.4 bis) |
| `ACCESS_DENIED` | 403 | Autenticado sin el rol necesario |
| `CSRF_REJECTED` | 403 | Petición no segura (POST/PUT/PATCH/DELETE) autenticada por cookie sin la cabecera `X-Requested-With: StreamBox`; refresh y logout la exigen **siempre**, también con Bearer |
| `RESOURCE_NOT_FOUND` | 404 | Película, género o usuario inexistente; ruta inexistente |
| `MOVIE_NOT_IN_FAVORITES` | 404 | Quitar de la lista algo que no estaba |
| `SERIES_NOT_IN_FAVORITES` | 404 | Quitar de la lista una serie (visible) que no estaba |
| `SERIES_ALREADY_IN_FAVORITES` | 409 | Añadir a la lista una serie que ya estaba |
| `EPISODE_ALREADY_EXISTS` | 409 | Crear o mover un episodio a una temporada y número ya ocupados («Ya existe el episodio N de la temporada T») |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP no soportado en esa ruta |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Cuerpo que no es JSON (también YAML, formulario o multipart) |
| `NOT_ACCEPTABLE` | 406 | La cabecera `Accept` pide un formato que la API no da (solo responde en JSON) |
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
   - **Jerarquía**: `MovieNotFoundException`, `GenreNotFoundException`, `UserNotFoundException`, `SeriesNotFoundException`, `EpisodeNotFoundException`, `MovieNotInFavoritesException` y `SeriesNotInFavoritesException` heredan de `ResourceNotFoundException`. Un solo manejador atiende todos los 404, y una excepción nueva «no encontrado» funciona sin tocar el manejador. (`MovieNotInFavoritesException` y `SeriesNotInFavoritesException` tienen su propio manejador para darle un código más específico; Spring siempre elige el manejador más concreto.)
2. **Excepciones estándar de Spring MVC.** La clase **hereda de `ResponseEntityExceptionHandler`**, que ya sabe convertir cada error del framework en su código correcto (404 ruta inexistente, 405, 415, JSON ilegible…). Se sobrescriben sus métodos solo para cambiar el **formato** a `ErrorResponse`. Sin esta herencia, el manejador genérico de `Exception` capturaría todo eso y lo convertiría en 500.
3. **El último recurso** (`@ExceptionHandler(Exception.class)`): cualquier error no previsto se registra en el log **con su traza completa** y el cliente recibe un mensaje genérico. **Nunca se devuelve el mensaje de una excepción del framework**, porque puede revelar nombres de clases, tablas o rutas internas.

**Los errores salen siempre en JSON, pida lo que pida el cliente.** Cada respuesta de `GlobalExceptionHandler` fija `Content-Type: application/json` (método `jsonError`). Con un tipo concreto ya puesto, Spring no negocia con el `Accept` (la RFC 9110 permite ignorarlo). Antes, un cliente con un token válido y `Accept: application/yaml`, `application/xml` o `text/html` que provocaba un 404 o un 400 recibía un **401 falso**: el `ErrorResponse` no se podía escribir en ese formato, Spring acababa en `sendError`, Tomcat reenviaba la petición a `/error` y ese reenvío llega sin autenticar. MockMvc no hace ese reenvío, así que solo lo detecta un test con Tomcat real (`ErrorResponseAlwaysJsonTomcatIntegrationTest`). Lección: **lo que pasa tras un `sendError` solo se ve con el servidor de verdad.**

Lo que no pasa por `GlobalExceptionHandler` (un `sendError`, una excepción en un filtro, una URL que rechaza el cortafuegos) lo responden `ApiErrorController` y `JsonRequestRejectedHandler` con el **mismo formato** y la misma tabla de códigos (capítulo 9.5). Así, el cliente recibe un `ErrorResponse` venga de donde venga el error.

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

`config/OpenApiConfig` define el título y el esquema de seguridad `bearerAuth`: en Swagger, pulsas «Authorize», pegas un token y todas las peticiones lo llevan. Como el login entrega el JWT en una cookie HttpOnly (204, sin cuerpo), el token hay que copiarlo del valor de la cookie `streambox_token` en las herramientas del navegador; con `Bearer` no se exige `X-Requested-With`. Los controladores con `@SecurityRequirement(name = "bearerAuth")` muestran el candado. Además, `OpenApiConfig` declara un segundo esquema, **`cookieAuth`** (`apiKey` en la cookie `streambox_token`), que es la autenticación real del navegador, y `config/CookieAuthOperationCustomizer` hace que cada operación autenticada acepte **cualquiera de los dos** y documenta en las no seguras (POST/PUT/PATCH/DELETE) el 403 `CSRF_REJECTED` de la defensa CSRF; login y registro no lo llevan. Swagger UI no puede fijar la cookie, así que para probar desde ahí sigue usándose `bearerAuth`. La especificación versionada (`docs/api/openapi.yaml`) y la colección de Postman (`docs/api/postman/`, verificada con Newman: 62 peticiones y 117 aserciones, incluidos refresh y logout) se regeneran con el procedimiento de `docs/api/README.md`.

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
├── pages/              una por pantalla: HomePage, LoginPage, RegisterPage, MyListPage, ProfilePage, MoviesPage, SeriesPage, SeriesDetailPage
│   └── admin/          panel de administración: AdminLayout, AdminMoviesPage, MovieFormPage, AdminSeriesPage, SeriesFormPage,
│                       SeriesEpisodesSection, EpisodeFormDialog, AdminGenresPage y piezas compartidas (CoverPreview, GenreCheckboxes...)
├── components/         piezas reutilizables (Navbar, Modal, MoviePoster, Pagination, Button...) y las bases
│                       genéricas del catálogo (PosterCard, PosterRow, FeaturedBanner, MetaTags), comunes a películas y series
├── context/            estado compartido: AuthContext, ToastContext, FavoritesContext
├── hooks/              lógica reutilizable: useCatalog/usePagedCatalog, useCatalogFilters, useSeriesCatalog, useSeriesDetail, useModalDialog,
│                       useCountdown, useGenres, useDebouncedValue, useAdminSearchList, useCatalogDelete, usePageVisible...
├── lib/                sin React: api.ts, types.ts, utils.ts, catalog.ts, series.ts, catalogFilters.ts, validation.ts, movieValidation.ts,
│                       seriesValidation.ts, episodeValidation.ts, profile.ts, posterFallback.ts
└── test/               utilidades de los tests (setup, helpers, fakeTimers, pageVisibility)
```

### 18.2 Proveedores y rutas (`App.tsx`)

```tsx
<BrowserRouter>                    // enrutador (URLs sin recargar la página)
 <QueryClientProvider>             // caché de datos del servidor (TanStack Query, ver 19.3)
  <ToastProvider>                  // avisos emergentes
    <AuthProvider>                 // sesión (necesita los avisos y la caché: la vacía al cambiar de sesión)
      <SkipLink />
      <Routes>
        /login, /registro          → envueltas en RedirectIfAuthenticated
        (layout) RequireAuth + AppShell   // AppShell monta FavoritesProvider + Navbar + <main>
            /            → HomePage
            /favorites   → MyListPage
            /my-list     → redirige a /favorites
            /perfil      → ProfilePage
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
                *                     → redirige a /admin/peliculas
        *                → redirige a /
```

Un **contexto** de React es una forma de compartir un valor con todos los componentes de debajo sin pasarlo de padre a hijo. Los proveedores van en este orden porque cada uno usa al de fuera.

**Guardas de ruta** (`components/RouteGuards.tsx`):

- `RequireAuth`: sin sesión, redirige a `/login`.
- `RedirectIfAuthenticated`: con sesión, `/login` y `/registro` redirigen a `/`.
- Mientras comprueban la sesión («Comprobando tu sesión...») o si ese chequeo falla («No se pudo comprobar tu sesión», con «Reintentar»), estas dos guardas son la página entera: la pintan dentro de su propio `<main id="contenido">` (destino de «Saltar al contenido») y, en el error, con el título como `<h1>` **dentro** de la alerta, para que se anuncie una sola vez (`ErrorState` con `headingLevel={1}`).
- `RequireAdmin` (envuelve todo `/admin`): mientras se carga el usuario muestra «Comprobando permisos...» (así, recargar en `/admin` no expulsa a un administrador real antes de saber su rol); si la carga falla, no enseña el contenido y ofrece «Reintentar»; si el usuario es `USER`, redirige a `/`.

`RequireAuth` y `RedirectIfAuthenticated` esperan al primer chequeo de sesión (`isCheckingSession`, ver 19.2) y muestran «Comprobando tu sesión...» en lugar de redirigir o enseñar el formulario, así que no hay «parpadeo». Recuerda que esto es solo experiencia de usuario: **la seguridad real la pone el backend**, que rechaza cualquier petición sin token válido.

**`FavoritesProvider` vive dentro de `AppShell`**: solo existe en la zona autenticada. Pero su lista está en la caché de TanStack Query, que sobrevive a los componentes: por eso `AuthProvider` **vacía la caché** en cada cambio de sesión (19.3) y la lista de un usuario no puede verla el siguiente.

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
| 401 en endpoint privado | — | Primero intenta **renovar la sesión** con el *refresh token* y repetir la petición una vez (paso 8) |
| 401 en endpoint privado que no se arregla renovando | «Tu sesión ha caducado…» | Avisa a `AuthProvider` para cerrar sesión (`sessionExpired: true`) |
| 401 en endpoint público | El del servidor («Email o contraseña incorrectos») | **No** cierra sesión: aquí significa credenciales incorrectas |
| 403 | «No tienes permisos…» | **No** cierra sesión: el usuario sí está identificado |
| 429 | «Demasiados intentos. Inténtalo de nuevo en N s.» | `retryAfterSeconds` sale de la cabecera `Retry-After`; el `code` distingue `ACCOUNT_LOCKED` de `RATE_LIMIT_EXCEEDED` |
| 5xx | «El servidor ha tenido un problema…» | — |
| Otros 4xx | El `message` del servidor (ya en español y específico) | `validationErrors` se conserva para pintarlos junto a cada campo |

7. Las **cancelaciones** (`AbortController`) se relanzan tal cual. `isAbortError` las reconoce para ignorarlas: no son errores, sino peticiones que ya no interesan (el usuario salió de la pantalla o escribió otra letra en el buscador).
8. **Renovación de la sesión** (*refresh token*, capítulo 10.4 bis). El JWT de acceso dura 15 minutos; al caducar, el navegador descarta su cookie y la siguiente petición da 401. Entonces `apiFetch` hace `POST /api/auth/refresh` (sin cuerpo; el navegador pone la cookie `streambox_refresh`) y, si responde 204, **repite la petición original una sola vez**. Es seguro también en un `POST`: un 401 significa que el servidor no llegó a ejecutarla (la autenticación va antes). Según la respuesta del refresh:

| Refresh | Qué pasa |
| :--- | :--- |
| 204 | Se repite la petición y se devuelve lo que diga. Si vuelve a dar 401, sesión caducada: no hay un segundo refresh (sin bucles) |
| 401 (`SESSION_EXPIRED`, o cualquier 401) | Sesión caducada: el 401 original sigue su curso normal (aviso único, login). **Nunca** se reintenta el refresh; el servidor ya ha borrado las dos cookies |
| Red caída, 5xx, 429, 403 | **No** se cierra la sesión (no se sabe si sigue viva). Se lanza el error del refresh (`sessionExpired: false`), que la pantalla enseña con «Reintentar» |

No se intenta renovar en rutas públicas (login, registro, logout) ni en `/auth/refresh`, en peticiones canceladas, sin `AuthProvider`, ni si la petición es de una sesión ya cerrada o anterior (`canRefresh` del puente, ver 19.2): un refresh tardío podría devolver al navegador cookies válidas de la sesión que el usuario acaba de cerrar.

**Tres reglas de diseño** (todas en `lib/api.ts`, con su porqué en los comentarios):

1. ***Single-flight*.** Si varias peticiones reciben 401 a la vez (la portada lanza tres o cuatro), todas esperan la **misma** promesa de refresh (`refreshInFlight`). Cada refresh rota el token y gasta una de las 30 peticiones por minuto del límite por IP.
2. **No renovar dos veces la misma caducidad: la «generación».** `sessionGeneration` es un contador que sube con cada renovación correcta. Cada petición anota la generación con la que **sale**; si al recibir su 401 ya es otra, es que alguien renovó mientras viajaba (salió con la cookie vieja) y basta con repetirla. Es un contador y no una hora: no le afectan los cambios del reloj. Las demás pestañas se enteran por un `BroadcastChannel` (`streambox-session`), que también sube su contador.
3. **Login, logout y refresh van en fila** con el Web Lock `streambox-session` (`navigator.locks`), común a todas las pestañas del mismo origen. Así dos pestañas no renuevan a la vez y, sobre todo, un refresh no se cruza con un logout: si la respuesta del refresh llegara después de la del logout, dejaría en el navegador una cookie de acceso válida (15 min) y al recargar se «resucitaría» la sesión. Dentro del lock, antes de pedir el refresh, se vuelve a mirar la generación: si mientras se esperaba otra pestaña renovó (y lo avisó), solo se repite la petición. Las peticiones que van dentro del lock tienen un tiempo máximo de 15 s (`SESSION_REQUEST_TIMEOUT_MS`): una petición colgada no puede bloquear el login de todas las pestañas. Sin `navigator.locks` (navegadores antiguos, o http fuera de `localhost`, que no es «contexto seguro») se usa una cola local de la pestaña.

*Por qué es robusto aunque falle la coordinación.* Las cookies son comunes a todas las pestañas: un refresh de más (p. ej. si el aviso del canal llega después de soltarse el lock) envía el refresh token **actual**, no uno viejo, así que solo gasta una rotación; no cierra la sesión. El caso peligroso —reenviar un token ya rotado fuera de los 10 s de gracia, que revoca la sesión entera— solo ocurre si el navegador nunca recibe el `Set-Cookie` de una rotación (se corta la red justo después de que el servidor rote): entonces el siguiente refresh lleva el token viejo y el servidor cierra la sesión. Es el precio conocido de la rotación con detección de reutilización.

*Cancelaciones.* Si la petición se cancela antes de leer su 401, no se pide refresh. Si se cancela mientras se renueva, no se repite; pero si el refresh acaba en `SESSION_EXPIRED`, se avisa igual a `AuthProvider` (la caducidad es de la sesión, no de la petición): si no, las peticiones que siguieran llegando con 401 pedirían otro refresh condenado a la misma respuesta. Lo destapó el E2E: en desarrollo, StrictMode cancela la primera petición de cada pantalla.

Tests: `lib/api.test.ts` (*describe* «renovación de la sesión»: reintento, sin bucle, errores del refresh, concurrencia, generación, rutas públicas, cancelaciones, el canal y los Web Locks simulados con `src/test/webLocks.ts`; `src/test/setup.ts` sustituye `BroadcastChannel` por uno en memoria) y E2E en `e2e/auth.spec.ts` («Sesión renovable»).

### 19.2 `AuthContext`: la sesión

`context/AuthContext.tsx` gestiona la sesión, pero **ya no hay token en el cliente**: vive en la cookie HttpOnly `streambox_token`, invisible para JavaScript (un XSS ya no puede robarlo). La sesión se descubre al arrancar con `GET /api/users/me` (200 = sesión; 401 = `apiFetch` intenta renovarla con el *refresh token* y, si tampoco, sin sesión, en silencio; red/5xx/429 = error con «Reintentar»: «No hemos podido confirmar si tu sesión sigue abierta. Inténtalo de nuevo en unos instantes.»). Así quien vuelve al día siguiente (sin cookie de acceso, con el *refresh token* aún válido) entra sin login, todo dentro de «Comprobando tu sesión...». Hay tres estados (`unknown`, `active`, `none`) y un contador `epoch` que descarta respuestas tardías. Expone `isAuthenticated`, `isCheckingSession`, `login(email, password)` (llama a `/auth/login` y luego a `/users/me`), `logout()` (ver abajo), `isLoggingOut`, `user`, `isAdmin`, `userStatus` y `refreshUser`. Se borra al arrancar la clave `token` heredada de `localStorage`.

**Cerrar sesión lo decide el servidor (`lib/logout.ts`).** Con la sesión en una cookie HttpOnly, JavaScript no puede borrarla: solo lo hace el `Set-Cookie` de la respuesta a `POST /auth/logout`. Por eso `logout()` **no es optimista**: llama primero al servidor y solo cuando responde 2xx (o 401, «ya no había sesión») limpia la interfaz; las rutas protegidas llevan entonces al login. Si falla por red o 5xx reintenta **una vez** tras `LOGOUT_RETRY_DELAY_MS` (1 s); si vuelve a fallar —o el rechazo no es pasajero, como un 403— la sesión **sigue abierta** en pantalla y un aviso lo dice («No se ha podido cerrar la sesión: no hay conexión con el servidor. Tu sesión sigue abierta; inténtalo de nuevo.», con variante para 5xx y otra genérica; un 502/503/504 cuenta como «no hay conexión»: es lo que responde el proxy de Vite o nginx con el backend parado, y decir «el servidor ha tenido un problema» sería falso). Mientras tanto `isLoggingOut` es `true`: los botones «Cerrar sesión» de la barra y de `/perfil` dicen «Cerrando sesión...» con `aria-disabled` (no `disabled`, que sacaría el foco del menú y lo cerraría) y una copia síncrona en un `ref` impide un segundo cierre con un doble clic. *Por qué:* antes la interfaz pasaba a «sin sesión» antes de la petición y se tragaba el error; si el servidor no respondía, la cookie seguía valiendo y al recargar `GET /users/me` devolvía la sesión: en un equipo compartido, la siguiente persona entraba en la cuenta. El cierre por «sesión caducada» (401 de otra petición que el refresh no arregla) no cambia: limpia la interfaz al momento y llama al logout en modo *best-effort* para borrar las cookies. Esta petición es también la que **revoca el *refresh token*** en el servidor, y va en fila con los refresh (19.1, regla 3).

**El puente con `apiFetch`.** `api.ts` necesita saber a qué sesión pertenece cada respuesta y avisar de los 401, pero no puede importar `AuthContext` (sería una dependencia circular: el contexto ya importa `api.ts`). La solución: `api.ts` ofrece `configureAuth(puente)` y el `AuthProvider` se registra al montarse, pasando tres funciones: `getSessionKey` (devuelve el contador `epoch` de la sesión), `onUnauthorized` y `canRefresh(claveUsada)`, que solo permite renovar si hay (o puede haber, en el arranque) sesión y la petición es de la época actual. Al registrarse, `api.ts` abre además el canal entre pestañas.

**El 401 sin bucles.** Si la sesión caduca sin remedio (el refresh también da 401) y la portada lanza tres peticiones a la vez, llegan tres 401. Sin cuidado, habría tres cierres de sesión, tres avisos y tres redirecciones. El diseño lo evita:

1. `apiFetch` **no navega nunca**; tras un único refresh compartido que no renueva, solo llama a `onUnauthorized(claveUsada)`, la época de sesión con la que se hizo la petición.
2. `onUnauthorized` ignora el aviso si ya no hay sesión o si la clave usada **no es la actual** (una respuesta tardía de una sesión anterior). Un 401 durante el primer chequeo de sesión (`unknown`) deja la sesión en «sin sesión» sin toast.
3. El primer aviso cierra la sesión y muestra un único toast. Los siguientes ya encuentran la sesión cerrada y no hacen nada.
4. Al quedar `isAuthenticated = false`, `RequireAuth` redirige a `/login` **una vez**.

**Detalles que explican el código:**

- `useLayoutEffect` registra el puente **antes** que cualquier `useEffect`. Los componentes hijos lanzan peticiones en sus `useEffect` al montarse, y esos efectos se ejecutan antes que los del padre; los *layout effects* se ejecutan antes que todos ellos.
- **Los cambios de sesión se propagan a las demás pestañas.** Las cookies son comunes a todas las pestañas, pero el estado de React no: si en una pestaña se cerraba sesión y entraba otra persona, otra pestaña abierta seguía mostrando el nombre, el rol y la caché del usuario anterior mientras sus peticiones ya salían con la cookie nueva (y sus clics en favoritos cambiaban la cuenta de otro). Ahora, tras un login correcto, un logout confirmado por el servidor o una sesión caducada, la pestaña publica por el `BroadcastChannel` de `lib/api.ts` una señal `{ type: 'session-changed' }` (**sin identidad ni datos**). Las demás pasan a `applySession('unknown')`: vacían su caché, cambian de época (las respuestas viejas se ignoran) y vuelven a pedir `/users/me`. **No la reenvían**, así que no hay bucles. Avisos (`lib/sessionMessages.ts`), elegidos para ser verdad para quien mira esa pestaña: «Se ha iniciado sesión en otra pestaña como «X».» (el nombre sale de su propio `/users/me`, no del mensaje), «Se ha cerrado la sesión en otra pestaña. Inicia sesión de nuevo.», y ninguno si para esa pestaña nada ha cambiado (misma cuenta). Nunca dicen «caducada», porque sería falso. Efecto asumido: la pestaña que recibe la señal muestra un instante «Comprobando tu sesión...» y pierde un formulario sin guardar, lo que solo ocurre cuando la sesión ha cambiado de verdad (y enviarlo habría ido con la cookie de otra cuenta). Las renovaciones del *refresh token* se coordinan aparte (19.1).

**El usuario actual y su rol.** Además de descubrir la sesión, `AuthProvider` carga el usuario con `GET /api/users/me` al arrancar, tras `login()` y en cada `refreshUser()`. `useAuth()` expone `user`, `isAdmin`, `userStatus` (`idle` sin sesión, `loading`, `ready` o `error`) y `refreshUser()` para reintentar.

- **Por qué se pregunta al servidor y no se lee del JWT.** El token no lleva el rol (solo el email como `sub` y el emisor). Y aunque lo llevara, quedaría desfasado hasta que caducase (15 min, y se renueva sin cesar), mientras que el backend lee el usuario de la base de datos en cada petición y un cambio de rol es inmediato. Preguntar a `/users/me` mantiene esa misma coherencia en el cliente.
- `login(email, password)` es asíncrono: espera a `POST /auth/login` y a `GET /users/me` antes de abrir la sesión en la interfaz (así no se pinta un instante «sin rol»); si `/users/me` falla, la sesión se abre igualmente con `userStatus = 'error'`.
- **Respuestas tardías.** El resultado se guarda junto a la época de sesión (`epoch`) y al número de intento que lo pidieron, y `user`/`userStatus` se calculan comparándolo con la sesión actual: el usuario de una sesión anterior nunca se asigna a la nueva. Además, cada cambio cancela la petición en curso con `AbortController`. Son dos defensas independientes.
- **No usa TanStack Query (a propósito).** `/users/me` no es un dato más que cachear: es cómo se descubre la sesión, y su lógica de épocas y estados (`unknown`/`active`/`none`) es justo la que decide cuándo hay que vaciar la caché de los demás datos. Pasarla a `useQuery` mezclaría las dos cosas y obligaría a reescribir sus tests de casos límite (refresh, logout en vuelo, 401 tardíos) sin ganar nada. Cada cambio de sesión (`applySession`: entrar, salir, sesión caducada) llama a `queryClient.clear()`.
- **Errores.** Un 401 lo gestiona `apiFetch` como cualquier otro (cierra sesión y avisa una vez). Si falla por red o un 5xx, la sesión se mantiene con `userStatus = 'error'` e `isAdmin = false`: **falla cerrado** (ante la duda, no se muestra nada de administrador).
- **El rol del cliente solo decide qué se pinta**: la etiqueta «Administrador» del menú de usuario (`Navbar`, que muestra también «Sesión iniciada como» y el nombre), y la guarda `RequireAdmin`. La seguridad real es el 403 del backend.
- En los tests, `routeFetch` (`src/test/helpers.tsx`) responde por defecto a `/users/me` con un usuario `USER` (`makeUser`) y a `POST /api/auth/refresh` con 401 `SESSION_EXPIRED` (así un 401 simulado sigue significando «sesión caducada»), y cada test puede sobrescribirlos. `apiCalls` no cuenta ni `/users/me` ni ese refresh del arranque.

**El login** (`pages/LoginPage.tsx`) llama a `login(email, password)` del contexto (que hace `POST /auth/login` con `public: true` y luego carga `/users/me`; no hay token que guardar) y ya está: `RedirectIfAuthenticated` ve la sesión y lleva a `/`.

> **Hecho en la tarea 29.** El JWT va en cookie HttpOnly (`SameSite=Strict`, `Path=/api`, `Secure` según `streambox.auth.cookie.secure`/`STREAMBOX_AUTH_COOKIE_SECURE`, `true` por defecto; el compose HTTP en localhost lo pone a `false`). Después llegaron la vida corta (15 min) y el *refresh token* revocable (10.4 bis), con su renovación transparente en el cliente (19.1, paso 8), e `index.html` con `Cache-Control: no-store` (4.6). Riesgos residuales: un JWT de acceso copiado vale hasta su `exp` (≤15 min) aunque se cierre la sesión, login CSRF mitigado con SameSite, y sigue pendiente HSTS con HTTPS.


### 19.3 Caché de datos del servidor (TanStack Query)

**El problema que resuelve.** Antes cada hook de datos (`usePagedCatalog`, `useGenres`, `useSeriesDetail`, `useAdminSearchList`, `FavoritesContext`...) reimplementaba a mano la carga, el error, la cancelación de respuestas viejas (con contadores de «época» y `AbortController`) y «cargar más». Y no había caché: volver a la portada repetía todas las peticiones, los géneros los pedían cuatro pantallas por separado y, si un administrador editaba una película, los demás listados no se enteraban hasta recargar. `@tanstack/react-query` (v5, ≈10 kB gzip de lo que se usa) es la librería estándar para esto: guarda cada respuesta bajo una **clave**, la comparte entre pantallas y la invalida cuando cambia.

**Lo que no cambia: `apiFetch` sigue siendo el único cliente.** Las `queryFn` lo llaman pasando el `signal` de TanStack (`({ signal }) => apiFetch(path, { params, signal })`), así que la sesión (cookie, refresh, 401/403/429, red caída) se sigue gestionando en un solo sitio (19.1). TanStack decide **cuándo** pedir y **qué** guardar; `apiFetch`, **cómo**.

**Configuración** (`lib/queryClient.ts`, `createQueryClient`; `App` crea uno por montaje con `useState`):

| Opción | Valor | Por qué |
| :--- | :--- | :--- |
| `staleTime` | 60 s | Volver a una pantalla dentro del minuto la pinta desde la caché sin pedir nada. El catálogo cambia poco y las escrituras del panel invalidan al momento; lo que cambie OTRA persona se ve como mucho un minuto después. Con 0 (el valor por defecto) cada navegación lo pediría todo otra vez. |
| `gcTime` | 5 min | Lo que ya no usa ninguna pantalla se guarda 5 minutos («Atrás» lo enseña al instante) y luego se libera. |
| `retry` | `shouldRetryQuery` | **Un** reintento y solo en fallos pasajeros: red (status 0) o 5xx. Nunca un 4xx: un 401 ya pasó por el refresh dentro de `apiFetch` (repetirlo pediría otro refresh condenado a fallar), un 403/404 no cambia en un segundo y repetir un 429 alarga el bloqueo. El valor por defecto (3 reintentos ante todo) tardaría ~7 s en enseñar un 404. Las mutaciones no se reintentan nunca: un `POST` repetido a ciegas podría duplicar una escritura. |
| `refetchOnWindowFocus` | `true` | Al volver a la pestaña se refresca solo lo anticuado (más de un minuto). Así «Mi lista» se pone al día si se cambió en otra pestaña. |
| `networkMode` | `'always'` | Con el modo por defecto, sin conexión las consultas quedarían «en pausa» (esqueleto de carga sin fin) y los favoritos esperando sin aviso. Así `apiFetch` devuelve su error de red y la pantalla enseña «No se pudo conectar» con «Reintentar». |

`loadStatusOf(query)` traduce el estado de TanStack a los tres de las pantallas, con las reglas de siempre: `ready` si hay datos (aunque falle un refresco en segundo plano: mejor seguir enseñando lo cargado), `error` si no los hay y la carga falló, `loading` en otro caso, también mientras se **reintenta** (al pulsar «Reintentar» vuelve el esqueleto). Los textos y estados que ve el usuario no han cambiado.

**Claves** (`lib/queryKeys.ts`, todas en un sitio y tipadas). Cada una cuelga de una raíz: `movies` (portada, `/peliculas` con y sin filtros, listado del panel, presencia), `series` (listados, detalle `['series','detail',id]`, panel), `genres` y `favorites`. Un listado paginado lleva en la clave el endpoint, el tamaño, el orden y los filtros de la URL (`queryKeys.paged`), así que cada combinación de filtros es una entrada: volver a un filtro ya visto (o «Atrás») no repite la petición, y los resultados de dos filtros no se mezclan nunca. Cambiar de filtros es cambiar de clave: la lista nueva empieza en «cargando» (nunca se pinta la anterior como si fuera de los filtros nuevos) y la petición de la clave vieja se cancela al quedarse sin pantalla.

**Hooks migrados:**

- `usePagedCatalog` (y con él `useCatalog`, `useMovieResults`, `useSeriesResults`, `useLatestSeries` y `useCatalogPresence`): `useInfiniteQuery` con `getNextPageParam` según `hasNext`. «Cargar más» es `fetchNextPage` (los títulos se juntan con `mergeById`; si falla, aviso y `loadMoreFailed`); «Reintentar» es `resetQueries` (vuelve a la página 0, como antes; un `refetch` pediría una tras otra todas las páginas cargadas). La portada y `/peliculas` sin filtros comparten la misma entrada.
- `useSeriesDetail`: `useQuery`; un 404 es «no encontrada» (sin reintento), también si llega en un refresco.
- `useGenres`: una sola entrada para las cuatro pantallas; el orden lo pone `select: sortGenres`. La pestaña Géneros escribe en ella con `setQueryData` tras cada alta, renombrado o borrado confirmado.
- `useAdminSearchList`: `useQuery` con `placeholderData: keepPreviousData` (mientras llega otra página o búsqueda se ve la anterior, atenuada).
- `FavoritesContext`: ver 20.2.

**Invalidación tras escribir desde el panel** (`hooks/useInvalidateCatalog`, `keysAffectedBy`). Tras crear, editar o borrar una película se invalida `movies` + `favorites`; una serie o un episodio, `series` + `favorites`; un género, `movies` + `series` + `favorites` (los títulos llevan los nombres de sus géneros dentro). «Mi lista» va en todas porque guarda copias de los títulos. Invalidar marca la entrada como anticuada: lo que está en pantalla se pide al momento y lo demás, al volver a abrirlo (mientras llega, se ve un instante la versión anterior: es el patrón *stale-while-revalidate*). También se invalida cuando el servidor responde 404 («ya no existía»): es la prueba de que la copia local estaba desfasada.

**Al cambiar de sesión, la caché se vacía.** `AuthProvider` llama a `queryClient.clear()` en cada cambio (entrar, salir, sesión caducada). Sin eso, tras cerrar sesión y entrar con otra cuenta en el mismo navegador se verían los favoritos de la persona anterior (la caché sobrevive a los componentes). `clear()` además cancela las peticiones en vuelo de la sesión que se cierra.

**Lo que se dejó sin migrar, y por qué:**

- `AuthContext` (19.2): su `/users/me` es el mecanismo de sesión, no un dato.
- `SearchBar`: un *typeahead* efímero (debounce + dos búsquedas en paralelo que se pintan juntas, con fallo parcial). Nada lo comparte y la caché apenas aportaría; su único `AbortController` está en un solo efecto y probado con reloj falso.
- La carga de la película o serie a **editar** (`MovieFormPage`, `SeriesFormPage`) y el refresco de `SeriesEpisodesSection`: copian el dato una vez en el estado editable del formulario (una caché no podría actualizar lo que el administrador está escribiendo) y el diálogo de episodios **espera** a ese refresco para cerrarse con la lista nueva. Sí invalidan la caché tras guardar.
- Login, registro y logout: son acciones de un solo uso, sin datos que cachear.

**Tests.** `src/test/queryClient.tsx` crea un `QueryClient` nuevo por test (`retry: false`, `gcTime: Infinity`, sin refresco al enfocar) y `renderWithProviders` lo monta; los tests de hooks usan `queryWrapper()`. En `src/test/setup.ts`, TanStack avisa de los cambios en una microtarea en lugar de en un `setTimeout(0)`, para que `await act(...)` los incluya (y para que el reloj falso no los congele). La política de reintentos se prueba con el `QueryClient` real en `lib/queryClient.test.tsx`; la caché entre navegaciones, la invalidación tras editar y el cambio de usuario, con las rutas reales en `src/serverCache.test.tsx`; y en E2E, `admin-peliculas.spec.ts` comprueba que `/peliculas` enseña el título editado sin recargar la página.
---

## 20. Frontend: pantallas y estado

### 20.1 Portada (`pages/HomePage.tsx` + `hooks/useCatalog.ts`)

`useCatalog` carga el catálogo **de la película más reciente a la más antigua**:

- Carga inicial: `GET /movies?page=0&size=20&sort=createdAt&direction=desc`.
- «Cargar más películas»: pide la página siguiente mientras la respuesta diga `hasNext: true`, y la **añade al final** sin duplicados (`mergeById`). El banner no cambia y el scroll no salta.
- Estados separados para la carga inicial (`status`: cargando, listo, error) y para «cargar más» (`loadingMore`, `loadMoreFailed`): si falla «cargar más», no se pierde lo ya cargado; aparece un aviso y el botón pasa a «Reintentar».
- Datos en la caché de TanStack Query (19.3): la petición en vuelo se cancela al salir de la pantalla, y volver a la portada dentro del minuto no repite nada. `/peliculas` sin filtros comparte esta misma entrada.

`HomePage` reparte las películas así:

- **Banner (hero)** (`components/HeroBanner.tsx`): la primera, es decir, la más reciente. Lleva póster, título, metadatos, una sinopsis recortada a 3 líneas y sus acciones, con **una sola principal** (variante `light`) que depende de lo que tenga la película (vídeo y tráiler son opcionales, 21.4): con vídeo, «Ver ahora» (principal), «Ver tráiler» si lo hay y «Mi lista» (secundarias, `outline`) y «Más información» (terciaria, `ghost`, sin borde), que lleva a la página de la película; sin vídeo pero con tráiler, «Ver tráiler» pasa a ser la principal; sin ninguno de los dos, la principal es «Más información», que va primero, seguida de «Mi lista». Nunca hay un «Ver ahora» sin vídeo. En los metadatos, «Estreno reciente» es una etiqueta de acento (el primer `<li>` de la misma lista de datos: como `<span>` aparte, en 375 px la lista entera no cabía a su lado y la etiqueta quedaba sola en una línea), el año y la duración van en texto plano con cifras tabulares, y solo los géneros llevan etiqueta (`MovieMetaTags`): si todo es etiqueta, nada destaca. Va a sangre (todo el ancho) bajo la barra superior. Ver en 21.3 por qué el póster se muestra dos veces y por qué va junto al título.
- **Mientras carga**, la portada muestra `CatalogSkeleton`: siluetas con la forma real del banner y de una fila (`role="status"` con texto para lectores de pantalla; las siluetas están ocultas a la accesibilidad). Con un spinner, toda la pantalla cambiaba de golpe al llegar los datos; con el esqueleto, el contenido aparece donde ya se esperaba.
- **Filas** (`lib/catalog.ts` → `buildCatalogRows`, con el resto de películas): «Novedades» con las 12 primeras, y **una fila por género** que tenga al menos 3 películas (una fila con 1 o 2 queda casi vacía). Las filas de género se ordenan de más a menos películas.
- **Flechas de las filas** (`components/PosterRow.tsx`, la base de `MovieRow` y `SeriesRow`, también en `/peliculas` y `/series`): «Ver anteriores/siguientes» solo se pintan **si la fila desborda** (`scrollWidth - clientWidth > 1`; el píxel de margen es por el redondeo con zoom). Motivo: en una pantalla ultraancha (~3400 px) las 12 tarjetas de «Novedades» cabían enteras y aun así se veían las flechas, que no movían nada y daban a entender que había más. Se mide en un `useLayoutEffect` (antes de pintar: no parpadean), al desplazar (`scroll`) y con un `ResizeObserver` sobre la fila **y sobre cada tarjeta** (avisa al cambiar el ancho de la ventana o el de las tarjetas, sin temporizadores); si cambian los títulos, se vuelve a medir y a observar. Con desbordamiento siguen marcándose `aria-disabled` en cada extremo, y solo se ven desde `md` (en táctil se arrastra). La cabecera de la fila reserva desde `md` el alto de las flechas (`md:min-h-10`), así que el título no salta al ocultarlas; y si la flecha que desaparece tenía el foco (p. ej. se ensancha la ventana), el foco pasa a la primera tarjeta en vez de caer al `<body>`. Tests: `components/PosterRow.test.tsx` (medidas simuladas con `src/test/fakeResizeObserver.ts`) y `e2e/catalog.spec.ts` («flechas de las filas»: 3440 px sin flechas, 1280 px con ellas en «Novedades» y sin ellas en «Series», y el paso de un ancho a otro sin mover las tarjetas).

### 20.2 «Mi lista» y favoritos (`context/FavoritesContext.tsx`)

Antes cada pantalla guardaba su propia copia de la lista y se desincronizaban. Ahora hay **una sola copia compartida**: la portada, el banner, el modal, el buscador y «Mi lista» ven siempre lo mismo.

**Actualización optimista.** Al pulsar «Mi lista», la interfaz cambia **al instante** y la petición va detrás:

1. Se marca la película como «pendiente» (el botón se bloquea; evita el doble clic).
2. Se añade o quita de la lista local inmediatamente.
3. Se envía `POST` o `DELETE` al servidor.
4. Si va bien → aviso de éxito.
5. Si falla → se **deshace solo ese cambio** (no se restaura una copia vieja de toda la lista, que podría pisar otros cambios en curso) y se avisa.
6. **Excepción**: un 409 al añadir («ya estaba») o un 404 al quitar («ya no estaba») significan que el servidor **ya tiene el estado deseado** (por ejemplo, lo cambiaste en otra pestaña). No se deshace nada; solo se informa.

**Con TanStack Query** (19.3) las dos listas van en UNA entrada de la caché (`queryKeys.favorites.all`) y añadir o quitar es una mutación (`useMutation`): `onMutate` cancela un refresco de la lista que estuviera en vuelo (traería la lista de antes del clic y pisaría el cambio) y aplica el cambio en la caché; `onError` deshace solo ese título (salvo 409/404); `onSettled` desbloquea el botón y, cuando no queda ningún cambio en vuelo, marca la lista como anticuada **sin pedirla** (pedirla tras cada clic costaría una petición por clic y reordenaría las series bajo el puntero). Si se pulsa mientras la lista aún carga, al terminar se vuelve a pedir: esa carga pudo salir antes del cambio.

Tres detalles añadidos tras la revisión de `qa`:

- **Se reafirma lo confirmado.** `onMutate` solo cancela los refrescos que ya estaban en vuelo; uno que empezara mientras viajaba la petición (volver a la pestaña, reconectar, una invalidación del panel) traía la lista sin el cambio y lo pisaba: el aviso decía «se ha añadido» y el corazón salía vacío. Ahora `onSuccess` (y la rama 409/404 de `onError`) vuelven a aplicar el estado que confirmó el servidor, sin duplicados y sin pedir la lista otra vez (`applyConfirmed`).
- **Un aviso por racha de fallos.** Con el servidor caído, cada vuelta a la pestaña volvía a pedir la lista y lanzaba otro «No se pudo cargar tu lista». Ahora se avisa una vez hasta la siguiente carga correcta.
- **Nada de avisos de una sesión ya cerrada.** `onMutate` guarda la época de la sesión; si cuando responde el servidor la sesión ya es otra (se cerró sesión con la petición en vuelo), no se avisa ni se toca la caché, que ya es de otra persona.

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

`useToast()` ofrece `success`, `error`, `info` y `errorFrom(error, mensajePorDefecto)`. Máximo 4 a la vez; desaparecen solos (5 s, o 9 s los errores, que necesitan más tiempo de lectura) y se pausan al pasar el ratón o el foco por encima (al salir, la cuenta vuelve a empezar entera). `errorFrom` ignora los errores de sesión caducada, que ya tienen su propio aviso.

**Avisos en una pestaña oculta.** Con dos pestañas abiertas, cerrar la sesión en una hace que la otra, en segundo plano, avise «Se ha cerrado la sesión en otra pestaña…» (señal `session-changed`, cap. 19). Antes ese aviso caducaba a los 5 s aunque nadie mirase esa pestaña, y al volver ya no estaba. Ahora se tiene en cuenta `document.visibilityState` (hook `hooks/usePageVisible`, con `useSyncExternalStore` y el evento `visibilitychange`):

- **Si el aviso nace con la pestaña oculta**, `ToastProvider` lo guarda pero no lo pinta (`revealed: false`) hasta que la pestaña vuelve a verse. Como la cuenta atrás vive en `Toast` y empieza al montarse, dura sus segundos completos a partir de ese momento. Además, así un lector de pantalla lo anuncia al volver: las regiones `aria-live` solo anuncian *cambios*, y un cambio en una pestaña de fondo no se anuncia (si se hubiera insertado entonces, al volver estaría ahí, en silencio).
- **Si la pestaña se oculta con el aviso ya en pantalla**, `Toast` pausa la cuenta atrás y, al volver, sigue con el tiempo que le **quedaba** (lo mide con `Date.now()` al pausar y lo guarda en un `ref`). El aviso no se desmonta, así que no se anuncia dos veces; por lo mismo, los avisos ya pintados se marcan `revealed: true` y no se esconden si la pestaña se vuelve a ocultar.

Solo `'hidden'` cuenta como oculta (el antiguo `'prerender'` cuenta como visible, para no dejar nada esperando para siempre). Tests: `context/ToastContext.test.tsx` (reloj falso con `Date`, `installManualTimers({ withDate: true })`, y visibilidad simulada con `test/pageVisibility.ts`) y, de punta a punta, el de la pestaña oculta en `context/AuthContext.test.tsx`.

### 20.5 Buscador (`components/SearchBar.tsx`)

> **Límite y limpieza del texto** (`lib/titleSearch.ts`): el campo admite como mucho `TITLE_SEARCH_MAX_LENGTH = 150` caracteres (lo mismo que valida el backend) y, antes de enviar, cada tabulador o salto de línea pegado se convierte en un solo espacio (borrarlo pegaría las palabras: «Blade	Runner» → «BladeRunner»). Si aun así llega el 400 `VALIDATION_ERROR` de `title`, se muestra el motivo del servidor junto al campo (`aria-invalid`), nunca el error genérico, y no se cierra la sesión. El panel hace lo mismo y, en ese caso, ofrece «Borrar búsqueda» en vez de «Reintentar» (la misma petición fallaría igual).

- **Debounce de 300 ms**: no busca con cada tecla, sino cuando dejas de escribir 300 ms.
- **Cancelación**: si escribes otra letra mientras una búsqueda está en vuelo, la anterior se cancela. Sin esto, una respuesta lenta antigua podría llegar después y sustituir a la nueva.
- Pide a la vez `/movies/search?title=...&size=10&sort=title` y `/series/search?...` (mismo texto) y pinta dos grupos, «Películas» y «Series».
- **Títulos parecidos (`approximate`).** Si ningún título *contiene* el texto, el servidor puede devolver los de título parecido (tolerancia a erratas, solo en PostgreSQL; `approximate: true` en la página, ordenados por parecido). Cada tipo decide por su cuenta, así que el texto depende de la combinación (`summarizeApproximate`): si los dos son aproximados, o uno lo es y el otro no trae nada, encima de la lista sale «No hay títulos que contengan «interstelar». Quizá buscabas:»; si el otro grupo sí trae coincidencias, solo «Ninguna película contiene «interstelar»: las películas de la lista tienen un título parecido.» (o «serie»), porque decir «no hay títulos» sería mentira; si el otro falló, «Ninguna película contiene «…». Quizá buscabas:» (de ese no se sabe nada). El grupo aproximado se llama «Películas con título parecido» / «Series con título parecido», el aviso describe el `listbox` (`aria-describedby`) y la región `aria-live` lo anuncia («No hay títulos que contengan interstelar. 1 título parecido: 1 película. …»). Un «aproximado» sin títulos es «Sin resultados».
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
- Pone el foco inicial donde toca («Cerrar el tráiler» en el del tráiler; «Cancelar» en la confirmación, que es la opción segura).
- Escape y clic en el fondo cierran.

Un detalle: un `<dialog>` modal vuelve inerte todo lo de fuera, incluidos los avisos. Por eso, mientras hay un modal abierto, `ToastContext` mueve los avisos **dentro** del diálogo (con un *portal*, `registerHost`). **Solo los avisos nacidos con el modal abierto**: los anteriores se quedan en la página, detrás del fondo. Antes se trasladaban todos, y el aviso de la acción anterior («Episodio añadido») se colaba en el siguiente diálogo y le tapaba los botones durante 5 s. Era un fallo real que destapó el E2E de episodios; lo cubre un test de `ConfirmDialog.test.tsx`.

**Entrada con movimiento.** El `<dialog>` aparece con un fundido y una escala de 96 % a 100 % en 200 ms. Los avisos suben 8 px en 250 ms. Las dos animaciones son **transiciones** y no `@keyframes`: una transición se puede interrumpir a mitad (si cierras el modal mientras entra, vuelve desde donde está, sin saltos). El estado inicial se define con `@starting-style` (en `index.css` para el diálogo y con la variante `starting:` de Tailwind en `Toast.tsx`). La curva es `ease-out-strong` (`cubic-bezier(0.23, 1, 0.32, 1)`, token en `@theme`): arranca rápido y frena suave, de modo que la respuesta se percibe inmediata. Con `prefers-reduced-motion` se **conservan los fundidos de opacidad** (no marean y avisan de que algo apareció) y se quita el movimiento: el diálogo no crece (`@starting-style` con `scale: 1` dentro del `@media` de `index.css`), el aviso no sube (`motion-reduce:starting:translate-y-0`), y los hundidos al pulsar, el zoom de `PosterCard` y los giros/pulsos se anulan con la variante `motion-reduce:`. La regla global solo deja a ~0 ms las animaciones sueltas (`@keyframes`) y el scroll suave. `src/index.css.test.ts` comprueba que esa regla no vuelva a anular las transiciones.

### 20.7 Panel de administración (`pages/admin/`)

Solo para `ADMIN`. La barra muestra el enlace **«Administrar»** únicamente si `isAdmin` (y no mientras se carga el usuario). En 375 px se reduce al icono, con su nombre accesible. Todo `/admin` va dentro de `RequireAdmin`, pero recuerda que **esto es solo interfaz**: quien protege los datos es el backend (403 para un `USER`, regla de cierre del catálogo; capítulo 9.2).

- **`AdminLayout`**: un único `h1` «Administración» y tres pestañas, Películas, Series y Géneros. Como cada pestaña es una **ruta**, son enlaces (`NavLink` con `aria-current="page"`) dentro de un `<nav aria-label="Secciones de administración">`, no el patrón ARIA `tablist`. Ese patrón es para paneles que se muestran sin cambiar de URL, y con enlaces funcionan el botón «Atrás», abrir en otra pestaña y compartir la dirección.
- **`AdminMoviesPage`** (listado):
  - Tabla con portada pequeña (`MoviePoster`), título, año, duración, géneros y acciones «Editar X» y «Borrar X» (el nombre accesible incluye el título).
  - En móvil la tabla se compacta (año, duración y géneros bajo el título, y acciones solo con icono) para no provocar scroll horizontal.
  - Buscador por título con debounce de 300 ms (`useDebouncedValue`); cada búsqueda y página es una entrada de la caché (19.3), y la petición de la búsqueda anterior se cancela. Sin texto pide `/movies?sort=createdAt&direction=desc`; con texto, `/movies/search?title=…&sort=title`.
  - **Títulos parecidos:** con `approximate: true` (ninguna contiene el texto y el servidor devuelve las parecidas, ordenadas por parecido e ignorando `sort=title`), encima de la tabla sale «Ninguna película contiene «interstelar». Estas tienen un título parecido, de la más parecida a la menos (no van por orden alfabético).» (`ApproximateResultsNotice`; en Series, «Ninguna serie contiene…»), la región `role="status"` lo anuncia y el `<caption>` pasa a «Películas con un título parecido a «…»». El panel no tiene selector de orden: el aviso dice que el orden alfabético habitual no se aplica. El aviso habla de la búsqueda de los resultados **que se ven** (`resultsQuery` de `useAdminSearchList`, que guarda cada página con su búsqueda), no de la que se está pidiendo mientras tanto.
  - Paginación con «Anterior»/«Siguiente», «Página X de Y» y el total (`components/Pagination`). La búsqueda y la página viven en la URL (`?q=&page=`), así que recargar o volver atrás conserva el estado.
  - Estados de carga, vacío y error con reintento.
- **Borrar película**: `ConfirmDialog` («¿Borrar "Título"? También se quitará de las listas de todos los usuarios…»). Espera al servidor (no es optimista, porque es destructivo). Después avisa e invalida la caché de películas (19.3), lo que recarga la página actual (retrocede una si queda vacía) y pone al día los listados públicos. Un 404 significa que ya estaba borrada: se informa y se recarga.
- **`MovieFormPage`** (alta y edición, el mismo componente):
  - Campos: título, sinopsis (con contador de caracteres), duración, año, URL de portada, **«URL del vídeo (opcional)»**, **«Tráiler de YouTube (opcional)»** y géneros (casillas en un `fieldset` con `legend`, cargadas con `useGenres`; al menos uno).
  - La validación en el cliente (`lib/movieValidation.ts`) replica **exactamente** las reglas y los mensajes del servidor, incluidas las de URL (capítulo 13.3). Los errores del servidor (`validationErrors`) se pintan junto a cada campo y el foco va al primero.
  - **Vídeo y tráiler son opcionales y distintos**: el vídeo es la película completa («Ver ahora»; solo `https://`), el tráiler, su avance de YouTube («Ver tráiler»; `validateTrailerUrl` de `lib/youtube.ts`, 21.4). Vacíos (o solo espacios) se envían como `null` (`optionalUrl`). El tráiler se envía como se escribió (`https://youtu.be/…` vale) y el servidor lo guarda normalizado, así que al volver a editar se ve como `https://www.youtube.com/watch?v=…`. Como con la portada, un vídeo o un tráiler guardados que ya no se admiten se marcan nada más abrir la edición.
  - **Vista previa de la portada**: se actualiza al escribir la URL, con un debounce de 400 ms, usando `MoviePoster` con un objeto película «borrador». Así se respeta la regla de que las imágenes salen siempre de un objeto película. Si la URL no es válida no se intenta cargar y se ve el respaldo.
  - En edición se carga `GET /api/movies/{id}` (un 404 muestra un mensaje con enlace al listado). Si no hay géneros, el formulario lo dice y enlaza a la pestaña Géneros.
- **`AdminGenresPage`**:
  - Alta arriba y lista con **renombrar en línea** (el campo sustituye al nombre; Escape cancela y el foco vuelve al botón) y «Borrar X» con confirmación.
  - Un 409 `GENRE_ALREADY_EXISTS` se muestra junto al campo. Un 409 `GENRE_IN_USE` muestra el mensaje del servidor («lo usan 3 películas…») y el género sigue en la lista.
- **Foco bajo la barra fija.** La barra superior es `sticky` en todos los anchos. Cuando un formulario mueve el foco al primer error, el navegador podía dejar el campo justo debajo de la barra, tapado (incumple WCAG 2.2 · 2.4.11). `Navbar` publica su altura real en la variable CSS `--navbar-height` (`hooks/useHeightCssVariable`, con un `ResizeObserver`, porque la barra mide 57 px en escritorio, 165 px en móvil y 213 px a 320 px con «Administrar»). `index.css` aplica `scroll-margin-top: calc(var(--navbar-height) + 2rem)` al contenido de `<main>` (no a los `<dialog>`, que van por encima de todo). Se descartó `scroll-padding-top` en `html` porque también desplazaba la página al enfocar los controles de la propia barra.

### 20.8 Páginas Películas y Series con filtros (`pages/MoviesPage.tsx`, `pages/SeriesPage.tsx`)

`/peliculas` y `/series` son los destinos de «Películas» y «Series» en la barra. Las dos funcionan igual y comparten **todo** lo de los filtros (`lib/catalogFilters.ts`, `hooks/useCatalogFilters.ts`, `components/CatalogFilterBar.tsx`, `components/FilteredCatalogResults.tsx`); cada página indica su tipo (`CatalogKind`: `'movies'` o `'series'`) y decide solo qué pinta. Tienen **dos vistas**:

- **Sin filtros:** la estructura de la portada. Un h1 («Películas» o «Series»), el banner con el título más reciente (`HeroBanner` / `SeriesHeroBanner`), «Novedades» y filas por género (`buildCatalogRows`, con al menos 3 títulos sin contar el del banner). Debajo, «Mostrando N de M películas/series» con «Cargar más», y los estados de carga (esqueleto), vacío (según el rol, 20.9) y error.
- **Con algún filtro:** una **cuadrícula de resultados** (`FilteredCatalogResults`) del buscador de su catálogo, `GET /api/movies/search` o `GET /api/series/search`, con `genreId`, `releaseYear`, `sort` y `direction`. No filtra por título, porque eso ya lo hace el buscador de la barra. Tiene:
  - el recuento en una región `role="status"`, que existe en todos los estados (si no, el lector de pantalla no anunciaría el cambio);
  - «Cargar más» sin duplicados;
  - el vacío con «Quitar filtros» («No hay películas con estos filtros»; en series, distinto según el rol, ver 20.9);
  - el error, con «Reintentar».

Cada tarjeta es un enlace real a la **página propia del título** (`/peliculas/:id`, `/series/:id`): se puede abrir en otra pestaña, compartir y volver con «Atrás» (con los mismos filtros, porque están en la URL). El antiguo modal de detalle de película (`MovieDetailsModal`) se retiró el 2026-10-14.

**La barra de filtros** (`CatalogFilterBar`, un `<form>` «Filtrar películas» o «Filtrar series») tiene tres controles con su etiqueta: **Género** (`<select>` con «Todos los géneros», de `GET /api/genres`), **Año** y **Ordenar por**. «Quitar filtros» solo aparece si hay algún filtro y va **unido al desplegable de orden** (prop `trailing` de `SelectField`). Antes llevaba un margen fijo para alinearse con los controles: cuando bajaba solo de línea a 768 px, dejaba un hueco de 38 px. Ahora baja junto con el desplegable, y lo vigila un E2E (en las dos páginas). Si fallan los géneros, se dice junto a la barra con «Reintentar» y se puede seguir filtrando por año y orden.

- **¿Por qué el año es un campo de texto** (`inputmode="numeric"`) y no `type="number"` ni una lista?
  - Con `type="number"`, la rueda del ratón cambia el valor al desplazar la página, y además admite `e` y decimales.
  - Una lista tendría 213 opciones (1888–2100), y no hay endpoint de «años con títulos».
  - Se valida en el cliente con las mismas constantes que el backend (1888–2100, iguales para películas y series). El error («Escribe un año entre 1888 y 2100.») aparece al llegar a 4 cifras o al salir del campo, no mientras escribes «20…», y un año inválido no se aplica.

**Los filtros viven en la URL** (`?genero=<id>&anio=<año>&orden=<clave>`, **los mismos nombres en las dos páginas**). Así se pueden compartir, «Atrás» vuelve al filtro anterior, y los valores inválidos de la URL se ignoran sin romper la página. Las claves de orden son:

| Clave | Orden | En `/series` |
| :--- | :--- | :--- |
| `recientes` (por defecto, no se escribe en la URL) | `createdAt desc` | sí |
| `titulo-asc` / `titulo-desc` | Título A–Z / Z–A | sí |
| `anio-desc` / `anio-asc` | Año: más nuevas / más antiguas | sí |
| `duracion-asc` / `duracion-desc` | Duración: más cortas / más largas | **no** |

Cada catálogo solo ofrece los órdenes de la lista blanca de su controlador (`SORT_OPTIONS` en `lib/catalogFilters.ts`, frente a `MovieController.SORTABLE_FIELDS` y `SeriesController.SORTABLE_FIELDS`): una serie no tiene duración y `sort=duration` en `/api/series/search` sería un 400. Por eso `?orden=duracion-asc` en `/series` se ignora como cualquier otro valor desconocido, y `catalogFiltersToQuery` nunca manda un orden que su catálogo no admita.

**`useCatalogFilters(kind, …)`** guarda un borrador de los controles y lo pasa a la URL tras 300 ms, o al momento con Intro. El retraso se aplica **también a los desplegables**: en Windows, las flechas sobre un `<select>` cerrado disparan un `change` cada una, y sin espera recorrer los órdenes con el teclado dejaba una entrada de historial y una petición por flecha. **`useCatalogFilterBar(kind)`** lo junta con los géneros y con «Quitar filtros» (que lleva el foco al desplegable de género, porque el botón pulsado desaparece) y devuelve las propiedades listas para la barra: así las dos páginas no repiten esa conexión.

Un detalle que costó un bug: `setSearchParams` de React Router cambia de identidad con cada URL. Por eso, tras aplicar con Intro, se volvía a aplicar el borrador anterior y se deshacía el filtro. Lo evita la marca `handledDraft`, y hay un test que lo reproduce.

**Datos:** en cada página, las dos vistas usan una sola llamada a `usePagedCatalog` (`useMovieResults` en `hooks/useCatalog.ts`, `useSeriesResults` en `hooks/useSeriesCatalog.ts`), que acepta una `CatalogQuery` (`sort`, `direction`, `genreId`, `releaseYear`) y cambia de endpoint (`/movies` ↔ `/movies/search`, `/series` ↔ `/series/search`) según haya filtros. Los filtros forman parte de la clave de la caché (19.3; `/series/search` cuelga de la raíz `series`, así que editar una serie en el panel también lo pone al día): al cambiarlos la lista empieza en «cargando», la carga y el «cargar más» del filtro anterior se cancelan y una respuesta lenta suya nunca pisa la del nuevo; volver a un filtro ya visto lo pinta al instante. El pie «Mostrando N de M / Cargar más» es `LoadMoreFooter`, compartido con la portada.

**Tests:** `lib/catalogFilters.test.ts` (lógica pura de los dos catálogos), `pages/MoviesPage.test.tsx` y `pages/SeriesPage.test.tsx` (vistas, URL, espera, «Atrás», cuadrícula y vacíos por rol), y en Playwright `e2e/movies.spec.ts`, `e2e/series.spec.ts` («Series: filtros», contra el servidor real: la serie sin episodios nunca aparece aunque coincida) y `e2e/responsive.spec.ts` (las dos barras caben a 375, 768 y 1280 px).

### 20.8 bis Página de una película (`pages/MovieDetailPage.tsx` + `hooks/useMovieDetail.ts`) y cambios de página

- **`/peliculas/:id`**, como `/series/:id`: «Volver a películas», portada, datos, «Ver ahora» (solo si tiene vídeo), «Ver tráiler» (solo si tiene tráiler; principal si no hay vídeo, secundaria si lo hay; 21.4), «Mi lista» y, solo para ADMIN (mientras carga el usuario se trata como USER), «Editar». Datos con TanStack Query en la clave `['movies', 'detail', id]`, dentro de la raíz `movies`: editar o borrar en el panel la invalida. Si la película ya estaba en un listado, se pinta al instante con esa copia (`placeholderData`), que nunca tapa un 404 ni un error. Un `:id` que no es un entero positivo seguro es «Película no encontrada» sin hacer petición.
- **Maquetación (también la de `/series/:id`).** La cabecera es el `FeaturedBanner` de la portada con `layout="page"`: el fondo desenfocado sigue de borde a borde, pero el contenido va en un contenedor centrado `max-w-6xl` (`DETAIL_CONTAINER_CLASS` en `components/bannerGridStyles.ts`, el mismo ancho que `/perfil` y el panel), con un póster mayor (`DETAIL_POSTER_SIZE_CLASS`) y la columna de texto ocupando lo que deja el póster (la sinopsis, a ≤65 caracteres por línea). Antes, en escritorio, todo quedaba en una franja a la izquierda y más de media pantalla vacía: en la portada el banner se alinea a la izquierda con las filas que tiene debajo, pero aquí no hay filas. Debajo va la **ficha** (`components/FactSheet.tsx`: `<section>` «Ficha» con un `<dl>`; solo datos de la API, nunca un valor vacío): en una película, «Estreno», «Duración», «Géneros» como enlaces a `/peliculas?genero=…` (`components/GenreLinks.tsx`, con `catalogByGenrePath` de `lib/catalogFilters.ts`) y «En el catálogo desde» (`formatAddedDate` en `lib/catalog.ts`, en UTC como «Miembro desde»), en banda bajo una línea; en una serie, «Emisión» («Desde 2021» si no tiene año de fin; «2021–2027» si lo tiene), «Estado» (`isOnAir` en `lib/series.ts`: «En emisión» si no hay año de fin **o si es futuro** —el backend admite hasta 2100—; «Finalizada» solo si ya ha llegado; el año actual es un parámetro para poder probarlo; lo usan también el banner y el panel), temporadas, episodios, géneros como enlaces a `/series?genero=…` (el mismo `GenreLinks`: desde una serie se sigue explorando series, no películas) y la fecha, en una tarjeta lateral junto a los episodios desde `lg` (`SERIES_BODY_GRID_CLASS`) y debajo de ellos en móvil y tableta. El esqueleto de carga (`DetailSkeleton`, en `components/CatalogSkeleton.tsx`) usa las mismas constantes, así que nada salta al llegar los datos. Tras la ficha, la fila «Películas similares» (punto siguiente). Lo vigila `e2e/responsive.spec.ts` («Pantalla ancha» a 1440 y 1920 px: mismo margen a los dos lados).
- **«Películas similares»** (`components/SimilarMoviesRow.tsx` + `hooks/useSimilarMovies.ts`): al pie de la página, en el mismo contenedor centrado que la ficha, la `MovieRow` de la portada (mismas tarjetas, enlaces a `/peliculas/:id`, flechas solo si la fila desborda) con un `<h2>` «Películas similares». Pide `GET /api/movies/{id}/similar?limit=12` (`SIMILAR_MOVIES_LIMIT`; el servidor admite 1–24) y pinta el array **en el orden en que llega** (el servidor ya ordena por géneros en común y excluye la propia película). Clave `['movies', 'similar', id, { limit }]`, bajo la raíz `movies`: editar o borrar una película o renombrar un género en el panel (`keysAffectedBy`) la vuelve a pedir. **Solo se pide con la película confirmada por el servidor**: `useMovieDetail` devuelve `confirmed`, falso mientras se enseña la copia provisional de un listado (`placeholderData`) y en «no encontrada», así que un 404 de la película no llega a pedir similares. Estados: cargando → `RowSkeleton` («Cargando películas similares...»; su cabecera reserva ya desde `md` la altura de las flechas, como `PosterRow`, para no saltar); **lista vacía o 404 → no se pinta nada**, ni el título (un encabezado sin tarjetas prometería algo que no hay); error (red, 5xx, 429...) → `ErrorState compact` «No se pudieron cargar las películas similares» con «Reintentar», que solo repite la fila: la película sigue en pantalla. Sin `placeholderData` propio: al pulsar una similar, la fila pasa a cargando y nunca enseña las de la película anterior (que pueden incluir la nueva). A cambio, `findCachedMovie` también busca en estos arrays, así que **la película pulsada se pinta al instante** con la copia de la fila; el cambio de `pathname` hace que `RouteChangeFocus` suba el scroll y lleve el foco a `<main>` (la tarjeta pulsada desaparece en el mismo render, así que el foco no se queda en ella). Mismo texto para USER y ADMIN. Tests: `components/SimilarMoviesRow.test.tsx` (URL exacta, orden, estados, copia provisional, invalidación y navegación con foco y scroll), `hooks/useMovieDetail.test.tsx` (`confirmed` y la búsqueda en arrays), `lib/queryKeys.test.ts` y el bloque «Películas similares» de `e2e/movies.spec.ts` (el catálogo sembrado da un resultado exacto sin depender del desempate: «Dune: Parte Dos» tiene los tres géneros y exactamente 12 películas comparten dos con ella).
- **Nombres accesibles sin `sr-only` pegado al texto.** El enlace de un género se anuncia «Drama, ver todas las películas del género» (o «…las series del género» en la ficha de una serie) con `aria-label` (empieza por el texto visible, WCAG 2.5.3). Con el patrón anterior (texto visible + `<span class="sr-only">, ver todas…</span>`) Chromium leía «Drama **,** ver…»: el `sr-only` lleva `position: absolute`, el navegador lo trata como bloque y separa su texto con un espacio. jsdom no aplica CSS y no lo ve, así que **esto solo se prueba en Chromium real** (`toHaveAccessibleName` o `toMatchAriaSnapshot` en Playwright: `e2e/movies.spec.ts`, `series.spec.ts`, `profile.spec.ts`, `admin.spec.ts`). Donde `aria-label` no vale (un `<li>` o un `status`, como «Tus géneros» del perfil y `Pagination`), lo visible va con `aria-hidden` y la frase entera en un único `sr-only`.
- **«Editar» vuelve al título (película o serie).** Las dos páginas lo tienen, solo para ADMIN, con el nombre accesible «Editar Interstellar». El enlace pasa `state={{ returnTo: moviePath(id) }}` (o `seriesPath(id)`) y `getReturnTo` (`pages/admin/adminPaths.ts`) solo acepta como vuelta el listado de **su** panel o la página exacta de **ese** título (lista blanca: ni otra película u otra serie, ni `/series/4?temporada=2`, ni una URL externa; nada de redirecciones abiertas). Es una sola función para los dos formularios: cada uno le pasa su listado y su página, así que el de series no acepta la página de una película y al revés. El formulario dice entonces «Volver a la película» / «Volver a la serie» (arriba y junto a «Guardar»). Diferencia: la película **vuelve sola al guardar**; la serie **se queda en la edición** (es donde se gestionan sus episodios, 20.7) y se vuelve a mano. Si la página del título ya no se puede ver (la película o la serie no existe, o a la serie se le acaban de quitar todos los episodios y queda oculta), la vuelta es al listado: volver daría otro «no encontrada».
- **«Volver a películas» / «Volver a series»** (`components/BackLink.tsx`): enlace discreto (texto pequeño con flecha, sin caja de botón) en su propia fila **encima** del póster y del `<h1>`, dentro del banner (prop `backLink` de `FeaturedBanner`) para que el fondo desenfocado siga llegando hasta la barra superior. Es un enlace a la sección y no `navigate(-1)`: sirve justo cuando el historial no ayuda (enlace compartido, marcador, se viene de la portada o de «Mi lista»), dice adónde lleva y se abre en otra pestaña; para volver a una búsqueda filtrada, «Atrás» la conserva. Color `text-white/85` (y no `text-muted`) porque va sobre el fondo del banner, donde ese blanco ya tiene calculado su contraste en el peor caso (6,0:1). Área táctil de 44 px. Su fila (`DETAIL_BACK_ROW_CLASS`) la reproduce el esqueleto, y la rejilla de la página (`DETAIL_BANNER_GRID_CLASS`) lleva por eso menos relleno arriba que la del banner de la portada (las dos parten de una base sin relleno vertical, para no tener dos `pt-*` en el mismo elemento). No sale en «no encontrada», que ya ofrece «Ver todas las películas/series». Tras un cambio de página es **la primera parada del tabulador** en el contenido (el foco va a `<main>`, ver el punto siguiente), antes de la primera acción («Ver ahora», o «Ver tráiler» si no hay vídeo).
- **Acciones en móvil** (`FeaturedBanner`): fila flexible que se parte (`flex flex-wrap`), con dos reglas. **Ningún texto se parte en dos líneas** (`*:min-w-max`: cada acción mide al menos lo que su texto). **De dos en dos o a todo el ancho, nunca con un hueco**: bajo `sm`, cada acción parte de media fila (`max-sm:*:basis-[calc(50%-0.375rem)]`) y crece para llenar la suya (`max-sm:*:grow`); si dos caben, quedan a partes iguales, y si una no cabe en media fila o se queda sola, baja a su propia fila y la ocupa entera. Como el número de acciones cambia (de una a cuatro según vídeo, tráiler y rol), lo decide el CSS según lo que mide cada texto y no cada página contando botones. Antes era una rejilla de dos columnas con `[&>*:last-child:nth-child(odd)]:col-span-2`, que no sabía de textos largos: a 375 px «Más información» no cabía en media fila y se partía en dos líneas (incidencia QA trailer-7); ahora, con las cuatro del banner, queda «Ver ahora | Ver tráiler», «Mi lista» y «Más información», y desde ~400 px de dos en dos. Desde `sm`, todas en línea con su ancho natural. No se debe meter entre las acciones nada invisible (un `<dialog>`, un texto oculto): ocuparía sitio; el diálogo del tráiler va en un *portal* a `<body>`, y un texto que acompañe a las acciones va aparte, en la prop `actionsNote` (debajo; ver 20.9). Lo vigila `e2e/trailer-layout.spec.ts` (a 320, 375, 768, 1280 y 1920 px: filas, anchos, una sola línea por acción y sin desplazamiento horizontal).
- **Al cambiar de página** (`components/RouteChangeFocus.tsx`, en `App`): en un `useLayoutEffect` que solo corre cuando cambia el `pathname`, sube el scroll arriba **salvo en «Atrás»/«Adelante»** (`POP`, donde el navegador restaura la posición) y lleva el foco a `<main id="contenido">` con `preventScroll` (si la página nueva ya puso el foco dentro de su `<main>`, lo respeta). Antes, al abrir una película desde lo bajo de `/peliculas`, en móvil se entraba a mitad de página, y el foco caía en `<body>`, con lo que un lector de pantalla no anunciaba la página nueva. Los cambios solo de búsqueda (filtros, temporada) no mueven nada. Se eligió el `<main>` y no el `<h1>` porque el `<h1>` aún no existe (o cambia) mientras la página carga.

### 20.9 Estados vacíos según el rol

Un estado vacío tiene que **decir la verdad a quien lo lee**. El caso que lo motivó: un administrador creó 15 series sin episodios (ocultas por diseño) y `/series` le decía «Todavía no hay series… echa un vistazo a las películas». Para él era falso y no explicaba nada. Desde entonces:

- **`/series` y `/peliculas` vacías:**
  - Al **usuario** le dicen «Todavía no hay series/películas. Cuando se publiquen aparecerán aquí.», con «Ir al inicio».
  - Al **administrador** le explican el motivo («las series solo aparecen cuando tienen al menos un episodio») y le ofrecen «Gestionar series/películas», que lleva al panel.
- **`/series` con filtros y sin resultados:** los dos roles ven «Quitar filtros». Al **usuario** se le dice «No hay series con estos filtros» y que pruebe con otro género u otro año o los quite (no «para ver todas»: el catálogo podría estar vacío, y el texto debe ser verdad siempre). Al **administrador**, «No hay series **visibles** con estos filtros» y que las series sin episodios no aparecen aunque coincidan: es justo la duda que tendría al no ver una serie que sabe que creó (el buscador del servidor también las excluye). Por eso el administrador tiene además **«Gestionar series»** (acción secundaria, `outline`, después de «Quitar filtros»; prop `emptySecondaryAction` de `FilteredCatalogResults`): lleva al panel, donde están esas series ocultas y se les añaden episodios. En `/peliculas` no hay títulos ocultos, así que el texto es el mismo para los dos y no hay acción secundaria.
- **Fila «Series» de la portada:** para un usuario, si no hay series visibles, no se pinta. Un administrador ve en su lugar una sola línea que explica por qué los usuarios no la ven, con «Gestionar series».
- **Portada sin películas:** delega en la fila «Series» si las hay. Solo si no hay nada se muestra «El catálogo está vacío» (con su versión para administradores).
- **`/series/:id` con 404:** el administrador lee además que una serie sin episodios solo se ve en el panel. El texto no confirma que la serie exista.
- **Un título sin vídeo ni tráiler** (`components/NothingToWatchNote.tsx`, bajo las acciones con la prop `actionsNote` de `FeaturedBanner`; incidencia QA trailer-5). Sin vídeo no hay «Ver ahora» y sin tráiler no hay «Ver tráiler», así que la cabecera se quedaba con «Mi lista» (y «Editar») sin que nada explicara por qué. Ahora un texto breve lo dice:
  - Al **usuario** (y mientras carga el rol): «Todavía no está disponible para ver.». Es verdad, no promete fecha ni manda a otra página donde tampoco podría hacer nada.
  - Al **administrador**, qué falta y dónde se añade: en `/peliculas/:id`, «No tiene vídeo ni tráiler: puedes añadirlos desde «Editar».»; en `/series/:id` (sin tráiler y sin ningún episodio con vídeo), «No tiene vídeo ni tráiler: añade el tráiler o el vídeo de los episodios desde «Editar».» (el formulario de la serie tiene los dos); en el banner de la portada, que no tiene «Editar», «No tiene vídeo ni tráiler: puedes añadirlos desde su página, con «Editar».».
  - El banner de `/series` no lo lleva: su listado no trae los episodios (no sabe si hay vídeo) y su acción principal es siempre «Ver episodios».
  - Va **fuera** de la rejilla de acciones (no ocupa el sitio de un botón) y en `text-white/75`, no en `text-muted`: va sobre el fondo desenfocado del póster, donde en el peor caso calculado (gris ≈85) `muted` da 2,4:1 y `white/75` 5,1:1 (WCAG AA). Tests en `HeroBanner.test.tsx`, `MovieDetailPage.test.tsx` y `SeriesDetailPage.test.tsx` (por rol, rol cargando, con vídeo o tráiler no hay aviso, fuera de las acciones) y en `e2e/trailer-layout.spec.ts`.
- **«Mi lista»:** cada sección vacía solo ofrece «Explorar películas/series» si de verdad hay algo visible allí. `hooks/useCatalogPresence` lo pregunta pidiendo una página de tamaño 1 al mismo endpoint que usa la página de destino. Así ningún botón lleva a otra página vacía.

**Reglas que se comprobaron en todos los estados vacíos:**

1. Que el texto sea verdad para cada rol.
2. Que título, descripción y botón hablen de lo mismo.
3. Que el botón lleve a un sitio útil, nunca a otra página vacía.
4. Que explique qué hacer o por qué.

Mientras se carga el usuario, se trata como usuario normal (falla cerrado). En los tests, `src/test/UserStatusProbe` espera a que el rol esté confirmado antes de comprobar el texto.

**Enlaces:** el estado vacío de la sección «Películas» de «Mi lista» lleva a `/peliculas`. El vacío general de «Mi lista» lleva a la portada, que mezcla películas y series.

### 20.10 Perfil (`pages/ProfilePage.tsx`, `components/profile/` + `lib/profile.ts`)

`/perfil` responde a «¿quién soy y qué he guardado?». Se llega desde **«Mi perfil»** en el menú de usuario de la barra (no está en la fila de navegación principal, que ya va justa de ancho). Es una ruta privada dentro de `AppShell`, como «Mi lista».

**De dónde sale cada dato.** Para ENSEÑAR los datos la página no hace ninguna petición propia (las únicas son las de «Editar perfil», abajo): el usuario (nombre, correo, rol y fecha de alta) viene de `useAuth()` (`GET /api/users/me`) y las películas y series de `useFavorites()`, que `AppShell` ya cargó. Por eso no hay «títulos vistos», «horas», suscripción ni idioma: el modelo de datos no tiene historial de reproducción ni planes, y **no se inventan datos** para que la pantalla se parezca a un diseño.

**Qué enseña:**

- *Cabecera:* avatar con la inicial, el rol (en mayúsculas con CSS `uppercase`, para que el lector de pantalla lea «Administrador» y no deletree), el nombre (el `<h1>`), «Miembro desde octubre de 2026» (formateado en UTC: la API da un instante UTC y, sin fijar la zona, un alta a las 00:30 del día 1 podría salir en el mes anterior), «Panel de administración» (solo `isAdmin`) y «Cerrar sesión». No hay un botón «Editar perfil» genérico: cada dato se cambia en su fila de «Cuenta».
- *Estadísticas:* películas en la lista, series en la lista y géneros distintos. Son una lista de descripción (`dl`): pares etiqueta → valor. Solo se pintan con la lista ya cargada; con la lista cargando, un «0» sería falso.
- *Cuenta* (`components/profile/AccountCard.tsx`): nombre, correo y contraseña. El nombre y la contraseña tienen un botón «Cambiar» (nombre accesible «Cambiar nombre» / «Cambiar contraseña») que abre su formulario en línea; el correo dice «No se puede cambiar: es el dato con el que inicias sesión.» (decisión del autor: es el `subject` del JWT y cambiarlo sin verificar el correo nuevo abriría la puerta a robos de cuenta). La contraseña no se muestra («No se muestra por seguridad.»): ya no hay puntos, que parecían un campo editable.
- *Sesiones* (`components/profile/SessionsCard.tsx`): «Cerrar sesión en todos los dispositivos».
- *Tus géneros:* los 6 más frecuentes de la lista con su recuento. `countGenres` cuenta **por id de género** (no por nombre) y cada título cuenta una vez por género; orden: recuento descendente, nombre e id.
- *De tu lista:* hasta 5 títulos, primero películas (`FavoritesContext` las da de más reciente a más antigua) y después series, con el enlace «Ver toda mi lista». Reutiliza `MovieCard` y `SeriesCard`.

**Editar perfil.** Tres acciones, cada una con su estado de envío, éxito y error, y ninguna cierra la sesión por un 400 (solo un 401 sin arreglo, que gestiona `apiFetch`):

- *Nombre* (`UsernameForm`, `PATCH /api/users/me` con **solo** `{username}`): se normaliza como el servidor (`normalizeUsername`: recorta y reduce espacios) y se valida la longitud 3–50 (`usernameError`, compartida con el registro). Si no cambia, se cierra sin petición. Con el 200, `updateUser` de `AuthContext` sustituye el usuario por el de la respuesta: la cabecera y la barra cambian al instante, sin volver a pedir `/users/me`. El 409 `USER_ALREADY_EXISTS` y el 400 con `validationErrors.username` van junto al campo (con el foco); lo demás, en un aviso del formulario. Aviso: «Nombre de usuario cambiado a «X».». **No se avisa a las demás pestañas**: el canal `session-changed` les haría vaciar la caché y mostrar «Comprobando tu sesión...» por un dato cosmético (los permisos los decide el servidor); lo verán al recargar.
- *Contraseña* (`PasswordForm`, `PUT /api/users/me/password` con `{currentPassword, newPassword}`): actual, nueva y repetición, validadas antes de enviar con `validatePasswordChange` (actual obligatoria, 12–64, distinta de la actual, repetición igual: así no se gastan los 5 intentos). `CURRENT_PASSWORD_INCORRECT` va junto a «Contraseña actual», cada `validationErrors` junto a su campo y el 429 muestra «Demasiados intentos con una contraseña actual incorrecta. Podrás volver a intentarlo en N.» con el botón «Reintentar en N» (`useCountdown`, como el login). Con el 204 llegan cookies nuevas y esta sesión sigue; aviso: «Contraseña cambiada. Se han cerrado tus otras sesiones; en otros dispositivos puede tardar hasta 15 minutos.» (su JWT de acceso vale hasta caducar).
- *Todos los dispositivos* (`logoutAll` de `AuthContext` → `logoutAllOnServer` de `lib/logout.ts`, `POST /api/auth/logout-all`): `ConfirmDialog` (ahora con `busyLabel` y `error`) con el foco en «Cancelar». Con el 204, como el logout: sesión cerrada, aviso a las demás pestañas, `/login` y «Se ha cerrado la sesión en todos tus dispositivos. En los demás puede tardar hasta 15 minutos en cerrarse.». Con 500/red el servidor no ha borrado las cookies: la sesión sigue y el diálogo queda abierto con el motivo para reintentar (sin reintento automático, a diferencia del logout).

**Lock de sesión.** El cambio de contraseña y el logout global cambian las cookies, así que `apiFetch` los mete en el mismo Web Lock que login, logout y refresh (`SESSION_ROTATING_PATHS` en `lib/api.ts`): si un refresh con el token viejo respondiera después, su `Set-Cookie` de borrado se llevaría las cookies nuevas. A diferencia del login, un 401 suyo sí se renueva: el lock solo abarca cada envío, se suelta para renovar y la repetición vuelve a cogerlo.

**Dos fuentes, cada una con sus tres estados.** El usuario (cargando, error con «Reintentar» → `refreshUser`) y la lista (cargando, error con «Reintentar» → `reload`). Si la lista falla, la cuenta sigue visible y el error aparece solo dentro de la tarjeta «Tus géneros» (`ErrorState` con la prop `compact`, que no reserva media pantalla).

**Estados vacíos (ver 20.9).** Con la lista vacía hay un único estado vacío, en «Tus géneros», y «De tu lista» no se pinta para no repetirlo. «Explorar películas» usa `ExploreLink` (extraído de «Mi lista» para compartirlo): solo aparece si `useCatalogPresence` confirma que hay películas visibles. Los textos son verdad para `USER` y `ADMIN`.

**Accesibilidad.** Un solo `<h1>` (mientras carga o si falla, «Mi perfil»; con el usuario cargado, su nombre), secciones con `<h2>`, `<dl>` para los pares etiqueta-valor y el avatar `aria-hidden`. En el menú de usuario, «Mi perfil» es un `NavLink` (marca `aria-current` cuando ya estás en `/perfil`) y va **antes** de «Cerrar sesión»: con el teclado, el primer Tab desde el botón del menú llega a «Mi perfil» y el segundo a «Cerrar sesión» (lo comprueba el E2E de accesibilidad).

**Tests:** `lib/profile.test.ts` (lógica pura), `lib/validation.test.ts` (`normalizeUsername`, `validatePasswordChange`), `lib/api.test.ts` (las dos rutas en el lock y el 401 que se renueva), `pages/ProfilePage.test.tsx` (usuario y administrador, estados, lista vacía y llena, y cada éxito y error del contrato de «Editar perfil», incluida la cuenta atrás del 429 y el 500/502 del logout global), casos nuevos en `Navbar.test.tsx` y `App.test.tsx` (la ruta exige sesión) y `e2e/profile.spec.ts` (flujo completo, 320/375 px sin scroll horizontal, cambiar el nombre y verlo en la barra, cambiar la contraseña y entrar solo con la nueva, y cerrar sesión en todos los dispositivos; en los dos últimos, el refresh token de otra sesión deja de valer).
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
- Las portadas de ejemplo están en `public/covers/*.webp` (se sirven como `/covers/...`). El script opcional `docs/portadas-locales.sql` apunta las películas de ejemplo a ellas. Las 30 películas y series añadidas el 2026-10-16 apuntan a `/covers/<slug>.webp`; mientras no exista el archivo, `MoviePoster` muestra el respaldo con el título (la lista de archivos que faltan está en el plan, 2.4).

### 21.4 «Ver ahora» y «Ver tráiler»

**Vídeo y tráiler son dos campos distintos y opcionales** (migración `V6`, sección 5.4): `videoUrl` es el contenido (película o episodio) y `trailerUrl` el avance de YouTube (película o serie; los episodios no tienen). El motivo fue un texto que mentía: con el tráiler guardado como vídeo, «Ver ahora», «Empezar a ver» y el «Ver» de cada episodio abrían un tráiler. Regla: **ningún botón dice «Ver» si no hay un vídeo de verdad**.

**«Ver ahora» / «Empezar a ver» (o «Ver T2:E3», 15 bis.5) / «Ver»** (`components/WatchButton.tsx`):

- Abre `videoUrl` en una pestaña nueva **solo si es una URL `http`/`https` válida y sin credenciales** (`lib/utils.ts` → `getSafeVideoUrl`). Así una URL maliciosa como `javascript:...` nunca se ejecuta. Lleva `rel="noopener noreferrer"` para que la página nueva no pueda controlar la nuestra.
- **Sin vídeo (o con una URL insegura) no pinta nada.** Antes enseñaba un botón desactivado con el motivo; con el catálogo entero sin vídeos, la aplicación se llenaría de botones que prometen algo que no existe. Quien decide la jerarquía de las demás acciones usa la misma condición (`getSafeVideoUrl(videoUrl) !== null`).

**«Ver tráiler»** (`components/TrailerButton.tsx` + `components/TrailerDialog.tsx` + `lib/youtube.ts`):

- **Dónde:** banner de la portada y de `/peliculas` (`HeroBanner`), banner de `/series` (`SeriesHeroBanner`) y páginas `/peliculas/:id` y `/series/:id`, solo si hay un tráiler válido. Principal (`light`) cuando no hay «Ver ahora»/«Empezar a ver»; secundaria (`outline`) cuando lo hay (en el banner de `/series` siempre secundaria: la principal es «Ver episodios»). Icono de claqueta, distinto del triángulo de reproducir. Es un **botón** (abre un diálogo, no navega) con `aria-haspopup="dialog"` y nombre accesible «Ver tráiler de Dune» (`aria-label`, que empieza por el texto visible).
- **Validación** (`lib/youtube.ts`, gemela de `@YouTubeUrl` del backend, con sus mensajes: «El tráiler debe ser un enlace de un vídeo de YouTube» y «La URL del tráiler no puede superar los 500 caracteres»). Formas aceptadas, siempre `https://` en minúsculas, sin credenciales, sin puerto, sin fragmento y solo ASCII visible: `https://(www.|m.|)youtube.com/watch?v=<id>` (con **un solo** `v`; otros parámetros se ignoran), `https://youtu.be/<id>` (parámetros opcionales), `https://www.youtube.com/embed/<id>` y `https://www.youtube-nocookie.com/embed/<id>`; id = `[A-Za-z0-9_-]{11}`. Son expresiones ancladas de principio a fin: tras el host viene obligatoriamente `/`, así que `youtube.com.evil.com`, `youtube.com:443` o `usuario@youtube.com` no encajan. `normalizeYouTubeUrl` devuelve la forma normalizada `https://www.youtube.com/watch?v=<id>`, la única que guarda el servidor (la exige también un `CHECK` de la base). **Los parámetros (`?…`) siguen el criterio de `java.net.URI`** del backend (`QUERY_CHAR`): ni `#`, ni espacios o controles, ni no ASCII, ni `< > " { } | ^`, acento grave o barra invertida; un `%` solo seguido de dos hexadecimales (`%20` sí; `%`, `%4`, `%zz` no), y los corchetes `[ ]` sí (`a[]=1`). Antes el cliente aceptaba un `%` mal formado y rechazaba los corchetes (incidencia QA trailer-1): el formulario daba por bueno un enlace que el servidor rechazaba con 400, o al revés. **La paridad la vigila un catálogo común**, `frontend/src/test/youtubeTrailerUrlCases.json` (url → normalizada, «formato» o «longitud»), que leen `lib/youtube.parity.test.ts` y el `YouTubeUrlParityContractTest` del backend: si se cambia la validación de un lado, falla la suite del otro. Ya no tiene excepciones toleradas.
- **El `<iframe>` nunca recibe el texto de la API.** `getTrailer` (y `getYouTubeEmbedUrl`) solo aceptan la forma **normalizada**, sacan el id con una expresión anclada, lo revalidan y construyen desde cero `https://www.youtube-nocookie.com/embed/<id>?autoplay=1&rel=0`. Si el valor no es esa forma exacta, no hay botón. `youtube-nocookie.com` es el modo de privacidad mejorada (sin cookies de seguimiento hasta reproducir) y el único origen que abre la CSP (`frame-src`, capítulo 4); `autoplay=1` porque ya se ha pulsado «Ver tráiler»; `rel=0` para que al acabar solo sugiera vídeos del mismo canal.
- **Diálogo** (`TrailerDialog`, sobre `Modal`): título «Tráiler de <título>», foco inicial en «Cerrar el tráiler», reproductor 16:9 con `aspect-video` (en un móvil apaisado se limita también por el alto, `max-w-[calc((100dvh-10rem)*16/9)]`, para verse entero) y debajo «Ver en YouTube» (pestaña nueva, `noopener noreferrer`, al enlace normalizado). Atributos del `<iframe>`: `title`, `loading="lazy"`, `referrerPolicy="strict-origin-when-cross-origin"` (YouTube solo recibe el origen), `allow="autoplay; encrypted-media; picture-in-picture; fullscreen"` (lo mismo que permite la `Permissions-Policy`), `allowFullScreen` y `sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"` (lo mínimo para que el reproductor funcione y para que la pestaña de youtube.com que abre su logotipo no herede el sandbox; sin `allow-top-navigation`, el marco no puede sacar al usuario de StreamBox).
- **Teclado dentro del reproductor: limitación conocida** (incidencia QA trailer-2). Escape cierra el diálogo solo mientras el foco está en StreamBox. Cuando entra en el `<iframe>` (con Tab o al hacer clic en el vídeo), las teclas las recibe el documento de YouTube, que es de **otro origen**, y el navegador no deja que la página las vea ni las capture: es una barrera de seguridad, no un fallo que se pueda arreglar aquí. Mayús+Tab sí saca el foco del marco (vuelve a «Cerrar el tráiler») y entonces Escape cierra. Por eso el diálogo enseña, junto a «Ver en YouTube», la pista «Pulsa Esc para cerrar. Si estás dentro del vídeo, sal antes con Mayús+Tab.» (`text-xs text-muted`, 5,5:1 sobre `surface`). Dice «sal antes» y no «pulsa una vez» porque el reproductor real puede tener varios controles detrás. Se oculta en pantallas táctiles (`pointer-coarse:hidden`): allí nadie pulsa Esc y se cierra con el botón o tocando el fondo. Lo prueba `e2e/trailer-keyboard.spec.ts` con un reproductor de otro origen simulado (`page.route`): Tab entra, Mayús+Tab vuelve a «Cerrar», Escape cierra y el foco vuelve a «Ver tráiler»; comprobado también a mano con el reproductor real de YouTube en Chromium.
- **Cerrar** (Escape, botón o clic en el fondo) **desmonta el diálogo**: desaparece el `<iframe>` y el vídeo se detiene (un `<dialog>` cerrado pero montado seguiría sonando). El foco vuelve a «Ver tráiler» explícitamente (`flushSync` + `focus()`), porque Safari no enfoca un botón al pulsarlo con el ratón y `Modal` devolvería el foco a `<body>`. El diálogo se pinta en un *portal* a `<body>` para no contar como una acción más en la rejilla del banner (20.8 bis).
- **Tests:** `lib/youtube.test.ts` (formas aceptadas y rechazadas, `%` y corchetes en los parámetros, longitud, `getTrailer`), `lib/youtube.parity.test.ts` (el catálogo común con el backend), `components/TrailerButton.test.tsx` (sin tráiler no hay botón, `src` y atributos exactos, título del diálogo, pista de teclado, Escape y «Cerrar» con el foco devuelto, `<iframe>` desmontado, portal), `e2e/trailer-keyboard.spec.ts` (teclado dentro del reproductor), `e2e/trailer-layout.spec.ts` (acciones de 1 a 4 a varios anchos), `WatchButton.test.tsx`, `HeroBanner.test.tsx`, `SeriesHeroBanner.test.tsx`, los de las dos páginas de detalle y de los formularios del panel, y `e2e/trailer.spec.ts` (combinaciones del catálogo sembrado: el banner con vídeo y tráiler, «Tenet» solo con tráiler —sembrado con `youtu.be`, así que prueba la normalización del servidor—, «Noches de Invierno» sin nada, la serie del banner con tráiler y «Estación Polar» con episodios sin vídeo; las peticiones a YouTube se responden con una página vacía).

---

## 22. Tests

### 22.1 Resumen

| Suite | Herramienta | Nº | Comando |
| :--- | :--- | :--- | :--- |
| Backend (H2) | JUnit 5, Spring Boot Test, MockMvc, Mockito | 1887 | `.\mvnw.cmd test` (desde `streambox/`) |
| Backend (PostgreSQL real) | Testcontainers | 352 | Se ejecutan con el anterior (2239 en total); se omiten si Docker no está en marcha. Sin Docker, Maven cuenta cada test parametrizado omitido como uno solo, así que la cifra de omitidos no coincide con la de métodos |
| Frontend (lógica y componentes) | Vitest, Testing Library | 1032 | `npm run test` (desde `frontend/`) |
| Frontend (flujos completos) | Playwright (Chromium) | 150 (+24 de capturas, que se omiten) | `npm run test:e2e` |

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
- **Specs que modifican el catálogo** (`admin-peliculas.spec.ts` y `admin-series.spec.ts`: crean, editan y borran títulos) van en un proyecto aparte, `catalogo-mutable`, que Playwright solo empieza cuando el resto ha terminado. Mientras existe una película creada por un test, ella pasa a ser la más reciente, y los tests que comprueban el banner o «Mostrando 25 de 25» fallarían según el orden. Contrapartida: si falla algún test del proyecto principal, este se omite. Para ejecutarlo solo: `npx playwright test admin-peliculas admin-series --no-deps`.
- **La caché en los tests.** Cada test tiene su propio `QueryClient` (`src/test/queryClient.tsx`): ninguno ve lo que cargó otro. Ver 19.3.
- **El tiempo en los tests.** Ningún test de Vitest espera tiempo real. Los que dependen de un debounce (buscador de la barra, buscador del panel, vista previa de la portada) usan el reloj falso de `src/test/fakeTimers.ts`: `installManualTimers()` en `beforeEach` y `passTime(ms)` para dejar pasar el tiempo. Así se comprueba el retraso exacto (nada a los 299 ms, la petición a los 300) y el resultado no depende de lo cargada que esté la máquina. Con el reloj real y `waitFor` (1 s), algunos fallaban a veces con la suite en paralelo.
- **Esperar como una persona en E2E.** Antes de pulsar en el formulario de película se espera a que esté completo (`waitForMovieForm(page)` en `e2e/support/fixtures.ts`). Los géneros llegan aparte y, al aparecer, desplazan los botones 128 px en móvil. Playwright solo comprueba qué hay bajo el puntero en el primer evento del clic, así que el `mouseup` podía caer en otro elemento.

### 22.4 Regla del proyecto

Cada bug corregido deja un test que **falla sin el arreglo**. Se comprueba quitando el arreglo temporalmente: si el test sigue pasando, no protege nada.

---

## 23. Recetas

### 23.1 Añadir un endpoint nuevo

Ejemplo: `GET /api/movies/{id}/similar` (ya existe: §14.7 sirve de modelo completo).

1. **Servicio**: método en `MovieService` con `@Transactional(readOnly = true)` que devuelva DTOs (mapeo dentro de la transacción).
2. **Controlador**: método en `MovieController` con `@GetMapping("/{id}/similar")`, `@Operation` y `@ApiResponses` en español con los códigos reales (401, 404…).
3. **Seguridad**: comprueba qué regla de `SecurityConfig` le aplica. `GET /api/movies/**` ya exige autenticación; si fuera solo para administradores, habría que añadir una regla **antes** de esa.
4. **Errores**: si hay un caso nuevo, crea una excepción de dominio (heredando de `ResourceNotFoundException` si es un 404) y, si hace falta, su `ErrorCode` y su manejador.
5. **Tests**: de integración con MockMvc (éxito, 401 sin token, 404, parámetros inválidos) y, si es un listado, comprueba que no hay N+1.
6. **Documentación**: tabla de endpoints del README y el plan de acción.

### 23.2 Añadir una columna a una tabla

Ejemplo: `age_rating` en `movies`.

1. **Migración nueva**: `V4__add_movie_age_rating.sql`, en SQL que funcione en PostgreSQL y en H2. Piensa qué pasa con las filas existentes (¿`NOT NULL` con valor por defecto?).
2. **Entidad**: añade el campo a `Movie` con su `@Column`.
3. **DTOs y mapper**: `MovieRequest` (con validación), `MovieResponse` y `MovieMapper`.
4. **Arranca la aplicación**: Hibernate valida que entidad y migración coinciden.
5. **Tests**: `FlywaySchemaIntegrationTest` y `PostgresSchemaIntegrationTest` para la columna y sus restricciones.
6. **Nunca** edites una migración ya aplicada (V1 a V4).

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
| **CSRF** | Ataque que aprovecha las cookies que el navegador envía solo. StreamBox lo mitiga con `SameSite=Strict` y exigiendo la cabecera `X-Requested-With` en las peticiones que modifican datos |
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
| **JWT** | Token firmado que identifica al usuario en cada petición (viaja en la cookie HttpOnly `streambox_token`) |
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
