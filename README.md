# 🎬 StreamBox — Backend API

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?logo=openjdk)](https://www.oracle.com/java/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16+-blue.svg?logo=postgresql)](https://www.postgresql.org/)
[![JWT](https://img.shields.io/badge/JWT-JJWT%200.12.6-black.svg?logo=jsonwebtokens)](https://github.com/jwtk/jjwt)
[![OpenAPI](https://img.shields.io/badge/Swagger-OpenAPI%203-green.svg?logo=swagger)](https://swagger.io/)

**StreamBox** es una API RESTful desarrollada con **Java 21** y **Spring Boot 4** para una plataforma de streaming y vídeo bajo demanda (OTT) al estilo Netflix. Proporciona una arquitectura modular y segura para gestionar usuarios, catálogo cinematográfico, búsquedas avanzadas con especificaciones dinámicas y listas personalizadas de favoritos ("Mi Lista").

---

## 🚀 Características Principales

- **🔐 Autenticación y Autorización Stateless**:
  - Emisión y validación de tokens JWT mediante `io.jsonwebtoken (JJWT 0.12.6)`.
  - Control de acceso basado en roles (**`USER`** y **`ADMIN`**).
  - Manejadores de excepción personalizados para `401 Unauthorized` y `403 Forbidden` en formato JSON estructurado.
- **🎥 Catálogo y Búsqueda Dinámica**:
  - Paginación y ordenación nativa con `Pageable` y DTOs envolventes (`MoviePageResponse`).
  - Filtrado multicriterio combinable (título insensible a mayúsculas, género y año de lanzamiento) implementado mediante **Spring Data JPA `Specification`**.
- **⭐ Lista de Favoritos ("Mi Lista")**:
  - Gestión integral vinculada al usuario autenticado (`/api/users/me/favorites`).
  - Soporte para añadir y eliminar películas por `ID` o por `título exacto`.
  - Acción de vaciado completo en una sola operación.
  - Prevención de duplicados (`409 Conflict`) y validación estricta de propiedad de recursos (prevención de IDOR).
- **🛡️ Validación y Tratamiento Global de Errores**:
  - Bean Validation declarativo (`@Valid`, `@NotBlank`, `@Size`, `@Min`, `@Max`) y validadores propios: URLs solo `https://` (o portadas propias `/covers/...`) con máximo 500 caracteres (`@HttpsUrl`), y política de contraseñas para cuentas nuevas (`@ValidPassword`: 12–64 caracteres, máximo 72 bytes por el límite de BCrypt, sin contraseñas comunes ni el usuario o el email dentro).
  - Controlador global de excepciones (`GlobalExceptionHandler`) que transforma errores de validación, reglas de negocio y fallos 404/409 en respuestas JSON uniformes.
- **📖 Documentación Interactiva OpenAPI/Swagger**:
  - Swagger UI interactivo generado con `springdoc-openapi` completamente documentado en español. Todas las respuestas de error se documentan con el esquema común `ErrorResponse` (y los 429, con la cabecera `Retry-After`).
- **🍿 Cliente Frontend Integrado (SPA OTT)**:
  - Interfaz web inmersiva tipo Netflix (Hero banner con la película más reciente, carruseles por género, "Cargar más", modales).
  - Registro e inicio de sesión (JWT), rutas protegidas con React Router y "Mi lista" de favoritos con actualización optimista.
  - **Panel de administración** (`/admin`, solo `ADMIN`): películas en tabla con buscador y paginación, alta y edición en un formulario con vista previa de la portada, borrado con confirmación, y gestión de géneros (crear, renombrar en línea y borrar si ninguna película lo usa). El enlace «Administrar» solo lo ven los administradores; el rol se consulta al servidor (`GET /api/users/me`) en cada sesión.
  - Login que avisa de los intentos que quedan antes del bloqueo de la cuenta y muestra la cuenta atrás cuando está bloqueada.
  - Navegación con las secciones "Películas" y "Series" ya reservadas en el menú (marcadas como "próximamente" hasta que existan sus páginas).
  - Cliente HTTP único (`apiFetch`) con manejo uniforme de 401, 403 y 429 (cuenta atrás con `Retry-After`) y avisos (toasts) en cada acción.
  - Accesible (teclado, foco visible, modales con `<dialog>`, buscador tipo combobox, contrastes WCAG AA) y responsive (móvil, tablet y escritorio) con Tailwind v4.
  - Llamadas a la API intermediadas mediante Proxy Vite para prevenir CORS.

---

## 🛠️ Stack Tecnológico

| Componente | Tecnología |
| :--- | :--- |
| **Frontend** | React 19, Vite, TypeScript, Tailwind CSS v4 |
| **Lenguaje Backend** | Java 21 LTS |
| **Framework** | Spring Boot 4.1.0 |
| **Módulos Spring** | Spring WebMVC, Spring Data JPA, Spring Security, Spring Validation |
| **Persistencia** | PostgreSQL (producción/local), Hibernate ORM |
| **Testing** | JUnit 5, MockMvc, `@SpringBootTest`, H2 Database (en memoria) |
| **Seguridad** | JJWT (0.12.6), BCryptPasswordEncoder |
| **Documentación** | SpringDoc OpenAPI Starter WebMVC UI 3.1.0 |
| **Productividad** | Lombok |
| **Herramienta de Construcción** | Maven (con Wrapper `mvnw.cmd` / `mvnw`) |

---

## 🏛️ Arquitectura del Proyecto

> 📘 **Explicación detallada de cómo funciona todo por dentro** (recorrido de una petición, seguridad JWT, rate limiting, Flyway, transacciones y N+1, gestión de errores, frontend y tests): [docs/MANUAL_PROGRAMADOR.md](docs/MANUAL_PROGRAMADOR.md).

El backend sigue una arquitectura limpia orientada por capas bajo el paquete base `com.emilio.streambox`:

```
streambox/
├── frontend/          # SPA React + Vite + Tailwind CSS (Interfaz OTT)
└── streambox/         # Backend Spring Boot
    ├── controller/    # Controladores REST (/api/...) y contratos HTTP
    ├── dto/           # Objetos de transferencia de datos (Requests y Responses)
├── mapper/            # Mapeadores manuales puros con métodos estáticos
├── service/           # Lógica de negocio y transaccionalidad (@Transactional)
├── repository/        # Repositorios JPA y JpaSpecificationExecutor
├── entity/            # Entidades de dominio mapeadas a PostgreSQL
├── specification/     # Filtros dinámicos de consulta (MovieSpecification)
├── security/          # Filtros JWT, SecurityConfig y UserDetailsService
├── exception/         # Excepciones de dominio y GlobalExceptionHandler
└── config/            # Configuraciones adicionales de la aplicación
```

---

## 📋 Endpoints de la API

La base de todos los endpoints es `/api`.

### 1. Autenticación y Usuarios
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/users` | Público | Registro de nuevos usuarios (contraseña de 12 a 64 caracteres, no común y sin el usuario ni el email; 400 con el motivo en `validationErrors.password`) |
| `POST` | `/api/auth/login` | Público | Autenticación mediante email y contraseña; retorna JWT. Un fallo responde 401 con `remainingAttempts`; al 5.º, 429 `ACCOUNT_LOCKED` |
| `GET` | `/api/users/me` | `USER`, `ADMIN` | Consulta los datos del usuario autenticado |
| `GET` | `/api/users` | `ADMIN` | Lista todos los usuarios registrados |

### 2. Mi Lista (Favoritos del Usuario)
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `GET` | `/api/users/me/favorites` | `USER`, `ADMIN` | Obtiene las películas en la lista del usuario actual |
| `POST` | `/api/users/me/favorites/{movieId}` | `USER`, `ADMIN` | Añade una película a la lista por su ID |
| `POST` | `/api/users/me/favorites/by-title?title=...` | `USER`, `ADMIN` | Añade una película a la lista por coincidencia de título |
| `DELETE` | `/api/users/me/favorites/{movieId}` | `USER`, `ADMIN` | Elimina una película de la lista por su ID |
| `DELETE` | `/api/users/me/favorites/by-title?title=...` | `USER`, `ADMIN` | Elimina una película de la lista por coincidencia de título |
| `DELETE` | `/api/users/me/favorites` | `USER`, `ADMIN` | Vacía por completo la lista del usuario actual |

### 3. Catálogo de Películas
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `GET` | `/api/movies` | `USER`, `ADMIN` | Catálogo paginado (`page`, `size`, `sort`, `direction=asc\|desc`; por defecto `title` ascendente) |
| `GET` | `/api/movies/{id}` | `USER`, `ADMIN` | Detalle completo de una película y sus géneros |
| `GET` | `/api/movies/search` | `USER`, `ADMIN` | Búsqueda filtrada (`title`, `genreId`, `releaseYear`) paginada, con `sort` y `direction` como el catálogo |
| `POST` | `/api/movies` | `ADMIN` | Alta de nueva película con asignación de géneros (`imageUrl`: `https://` o `/covers/archivo`; `videoUrl`: `https://`; máximo 500 caracteres) |
| `PUT` | `/api/movies/{id}` | `ADMIN` | Modificación de datos y géneros de una película |
| `DELETE` | `/api/movies/{id}` | `ADMIN` | Eliminación de película (desvincula automáticamente de favoritos) |

### 4. Géneros Cinematográficos
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `GET` | `/api/genres` | `USER`, `ADMIN` | Lista completa de géneros disponibles |
| `POST` | `/api/genres` | `ADMIN` | Alta de nuevo género cinematográfico (409 `GENRE_ALREADY_EXISTS` si el nombre ya existe) |
| `PUT` | `/api/genres/{id}` | `ADMIN` | Renombrar un género (mismas reglas de nombre que el alta) |
| `DELETE` | `/api/genres/{id}` | `ADMIN` | Eliminar un género (409 `GENRE_IN_USE` si alguna película lo usa) |

### 5. Operación
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `GET` | `/actuator/health` | Público | Estado de la aplicación y de la base de datos |

---

## ⚙️ Configuración y Ejecución

### Requisitos Previos
- **JDK 21** o superior instalado y configurado en el `PATH`.
- Instancia de **PostgreSQL** en ejecución en `localhost:5432` con una base de datos creada llamada `streambox`.

### Variables de Entorno
Configura las siguientes variables de entorno en tu sistema o en tu IDE:

| Variable | Descripción | Valor por Defecto / Ejemplo |
| :--- | :--- | :--- |
| `JWT_SECRET` | Clave secreta para firmar los tokens JWT (**obligatoria**). Texto de **al menos 32 caracteres**; la aplicación no arranca si es más corta. Genera una con `openssl rand -base64 48` | — |
| `JWT_EXPIRATION_HOURS` | Tiempo de vida del token en horas | `24` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Opcionales. Si ambas están definidas, al arrancar se crea el primer administrador (si no existe ya). Al crearlo, la contraseña debe cumplir la política del registro (12–64 caracteres, no común, sin el usuario ni el email) o la aplicación no arranca; si ya existe, no se valida | — |
| `ADMIN_USERNAME` | Opcional. Nombre de usuario del administrador inicial | `admin` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Solo perfil `prod`: conexión a PostgreSQL | — |

### Perfiles de Spring

| Perfil | Cuándo | Qué hace |
| :--- | :--- | :--- |
| `dev` (por defecto) | Desarrollo local | Esquema gestionado por Flyway (Hibernate solo valida), SQL visible en el log, Swagger activado |
| `prod` | Producción (`SPRING_PROFILES_ACTIVE=prod`) | logs en formato JSON (ECS), sin SQL en logs, Swagger desactivado, errores sin detalles. Requiere `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` y `JWT_SECRET` |
| `test` | Lo activan los tests (`@ActiveProfiles("test")`) | H2 en memoria y un secreto JWT fijo de pruebas: **no necesitas definir `JWT_SECRET` para ejecutar los tests** |

> **Conexión a la base de datos.** Copia `streambox/src/main/resources/application-local.properties.example` como `application-local.properties` (en la misma carpeta, ignorado en Git) y rellénalo con tu conexión: **PostgreSQL local** (opción A, la URL por defecto es `localhost:5432/streambox`) o **Supabase** (opción B, con la cadena *Session pooler*; después ejecuta `docs/supabase-seguridad.sql`, ver el [manual](docs/MANUAL_PROGRAMADOR.md), sección 4.5). Ahí también puedes fijar el secreto JWT. Los tests no usan ese archivo: tienen su propia base H2 en memoria.

### Base de datos y migraciones

El esquema lo gestiona **Flyway** (`streambox/src/main/resources/db/migration`). Al arrancar se aplican las migraciones pendientes y Hibernate solo **valida** que las entidades coinciden con las tablas (`ddl-auto=validate`).

- Para cambiar el modelo: modifica la entidad **y** añade una migración nueva (`V2__descripcion.sql`). Nunca edites una migración ya aplicada: Flyway lo detecta y no arranca.
- Una base de datos creada antes de Flyway (con `ddl-auto=update`) se adopta automáticamente: `V1` es idempotente.
- Para crear el primer administrador define `ADMIN_EMAIL` y `ADMIN_PASSWORD` (el registro público siempre crea usuarios `USER`).

### Comprobaciones de salud (Actuator)

| Endpoint | Acceso | Para qué sirve |
| :--- | :---: | :--- |
| `GET /actuator/health` | Público | Estado general (`UP` / `DOWN`), incluye la conexión a la base de datos |
| `GET /actuator/health/liveness` | Público | El proceso está vivo (para reiniciarlo si deja de responder) |
| `GET /actuator/health/readiness` | Público | Listo para recibir tráfico (para balanceadores y orquestadores) |

Solo se exponen `health` e `info`; el resto de endpoints de Actuator (`env`, `beans`, `heapdump`...) no existen. La salud nunca muestra detalles (solo el estado), para no revelar la infraestructura. `/actuator/info` requiere token.

### Protección contra abuso

| Protección | Valor por defecto | Respuesta |
| :--- | :--- | :--- |
| Intentos de login por IP | 10 por minuto | `429` `RATE_LIMIT_EXCEEDED` + `Retry-After` |
| Registros por IP | 5 por hora | `429` `RATE_LIMIT_EXCEEDED` + `Retry-After` |
| Cuenta bloqueada tras logins fallidos | 5 fallos en 15 min (los fallos previos responden 401 con `remainingAttempts`: 4, 3, 2, 1) | `429` `ACCOUNT_LOCKED` + `Retry-After` |

Configurable en `streambox.security.rate-limit.*` (`application.properties`). Los contadores están en memoria: con varias réplicas de la aplicación cada una lleva su propia cuenta. Detrás de un proxy inverso el perfil `prod` activa `server.forward-headers-strategy=native` para ver la IP real del cliente.

### Ejecución en Local

Sitúate en el subdirectorio del proyecto backend:

```bash
cd streambox
```

Compilar y verificar sintaxis:
```bash
# Windows
.\mvnw.cmd compile

# Linux / macOS
./mvnw compile
```

Ejecutar la batería de tests automatizados (utiliza base de datos H2 en memoria):
```bash
# Windows
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```

Además de H2, hay tests contra **PostgreSQL real** (paquete `com.emilio.streambox.postgres`: migraciones de Flyway, adopción de una base existente, favoritos con concurrencia real y diferencias de orden/búsqueda del catálogo). Usan Testcontainers (imagen `postgres:16`, un único contenedor compartido):

- **Con Docker en marcha** se ejecutan en el mismo `mvnw test` (la primera vez descarga la imagen; después añaden unos 25 s).
- **Sin Docker se omiten** (aparecen como *skipped*) y la suite termina igualmente en `BUILD SUCCESS`: no hace falta Docker para desarrollar.
- Solo los de PostgreSQL: `.\mvnw.cmd test "-Dtest=Postgres*"`. Sin ellos: `.\mvnw.cmd test "-Dtest=!Postgres*"`.

**Tests del frontend** (desde `frontend/`):
```bash
npm run test       # Vitest + Testing Library: lógica (cliente de API, validación, catálogo) y componentes; unos 15 s
npm run test:e2e   # Playwright: flujos completos en un navegador real (Chromium); unos 60 s
```
- Los E2E levantan **su propio backend** (puerto 8099, base de datos H2 en memoria, perfil aislado) y su propio Vite (puerto 5199), siembran 25 películas por la API y lo cierran todo al terminar. **No usan** tu base de datos ni los puertos 8080 y 5173, así que puedes tenerlos abiertos.
- La primera vez hay que descargar el navegador: `npx playwright install chromium` (unos 325 MB comprimidos, se instala fuera del repositorio).
- Para generar capturas de pantalla (375, 768 y 1280 px) y revisarlas a ojo: `E2E_SCREENSHOTS_DIR=<carpeta> npx playwright test capturas` (en PowerShell: `$env:E2E_SCREENSHOTS_DIR='C:\temp\screens'; npx playwright test capturas`).
- Nota técnica: `spring-boot:run -Dspring-boot.run.useTestClasspath=true` añade las dependencias de test (H2) pero **no** carga `application-test.properties`; por eso el E2E configura todo por variables de entorno.

Iniciar el servidor de desarrollo:
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

Una vez levantada la aplicación, la API estará disponible en `http://localhost:8080`.

### Ejecución del Frontend (React + Vite)
En una nueva terminal, sitúate en el directorio `frontend/`:
```bash
cd frontend
npm install
npm run dev
```
La aplicación web estará disponible en `http://localhost:5173` (con el backend en el 8080). Otros comandos: `npm run build` (comprobación de tipos + empaquetado) y `npx oxlint src` (lint).

Estructura de `frontend/src/`: `pages/` (Home, Login, Registro, Mi lista), `components/`, `context/` (sesión, avisos, favoritos), `hooks/`, `lib/` (cliente de API, tipos, utilidades). Las portadas de ejemplo están en `public/covers/`; para que las películas de tu base de datos las usen, ejecuta a mano el script opcional `docs/portadas-locales.sql` (explicado en su cabecera).


### Documentación Interactiva (Swagger UI)
Accede a la interfaz interactiva para explorar y probar los endpoints:
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

---

## 🧪 Estrategia de Testing

El proyecto cuenta con suites de pruebas de integración (`*IntegrationTest`) que validan:
- Flujo completo de registro, login y consumo de endpoints autenticados con Bearer token.
- Control de acceso RBAC (rechazo 403 a usuarios convencionales en rutas de administración).
- Comportamiento de validación Bean Validation y mapeo de excepciones en `GlobalExceptionHandler`.
- Operaciones idempotentes y casos límite en listas de favoritos.
- Paginación y búsqueda con casos límite (página fuera de rango, comodines, acentos y `ñ`, parámetros inválidos que nunca acaban en 500).
- Comportamiento real de PostgreSQL (Testcontainers) y flujos completos del frontend (Vitest y Playwright), descritos arriba.

---

## 🤖 Desarrollo asistido con Claude Code

El proyecto está preparado para trabajar con [Claude Code](https://claude.com/claude-code):

- **`CLAUDE.md`** (raíz): contexto y reglas del proyecto que Claude lee al empezar: arquitectura, convenciones, comandos, reglas de Flyway y de seguridad, cómo ejecutar los tests y dónde está el plan de acción.
- **`.claude/agents/`**: un equipo de agentes con un **orquestador** como sesión principal (se activa con `.claude/settings.json`). El orquestador divide cada petición en subtareas y delega en el especialista adecuado; una tarea de una sola área (por ejemplo, solo frontend) va directa a su agente:

| Agente | Función |
| :--- | :--- |
| `orchestrator` | Principal: divide la petición, delega, ordena dependencias, verifica los resultados y actualiza el plan |
| `backend` | Controladores, servicios, DTOs, mappers, excepciones y sus tests |
| `database` | Entidades JPA, migraciones Flyway, índices, restricciones y rendimiento de consultas |
| `security` | JWT, roles, rate limiting, ownership de recursos y configuración segura |
| `qa` | Revisión independiente y tests; solo escribe en `streambox/src/test/` |
| `frontend` | React, Vite y Tailwind: UI/UX, accesibilidad y consumo de la API |

Un subagente no puede lanzar a otros, por eso el orquestador se ejecuta como sesión principal y los demás son sus especialistas. Para trabajar sin delegar basta con pedirlo ("hazlo tú directamente") o quitar `agent` de `.claude/settings.json`.
