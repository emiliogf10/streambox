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
  - Bean Validation declarativo (`@Valid`, `@NotBlank`, `@Size`, `@Min`, `@Max`).
  - Controlador global de excepciones (`GlobalExceptionHandler`) que transforma errores de validación, reglas de negocio y fallos 404/409 en respuestas JSON uniformes.
- **📖 Documentación Interactiva OpenAPI/Swagger**:
  - Swagger UI interactivo generado con `springdoc-openapi` completamente documentado en español.

---

## 🛠️ Stack Tecnológico

| Componente | Tecnología |
| :--- | :--- |
| **Lenguaje** | Java 21 LTS |
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

El backend sigue una arquitectura limpia orientada por capas bajo el paquete base `com.emilio.streambox`:

```
streambox/
├── controller/        # Controladores REST (/api/...) y contratos HTTP
├── dto/               # Objetos de transferencia de datos (Requests y Responses)
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
| `POST` | `/api/users` | Público | Registro de nuevos usuarios |
| `POST` | `/api/auth/login` | Público | Autenticación mediante email y contraseña; retorna JWT |
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
| `GET` | `/api/movies` | `USER`, `ADMIN` | Catálogo paginado (`page`, `size`, `sort`) |
| `GET` | `/api/movies/{id}` | `USER`, `ADMIN` | Detalle completo de una película y sus géneros |
| `GET` | `/api/movies/search` | `USER`, `ADMIN` | Búsqueda filtrada (`title`, `genreId`, `releaseYear`) paginada |
| `POST` | `/api/movies` | `ADMIN` | Alta de nueva película con asignación de géneros |
| `PUT` | `/api/movies/{id}` | `ADMIN` | Modificación de datos y géneros de una película |
| `DELETE` | `/api/movies/{id}` | `ADMIN` | Eliminación de película (desvincula automáticamente de favoritos) |

### 4. Géneros Cinematográficos
| Método | Endpoint | Acceso | Descripción |
| :--- | :--- | :---: | :--- |
| `GET` | `/api/genres` | `USER`, `ADMIN` | Lista completa de géneros disponibles |
| `POST` | `/api/genres` | `ADMIN` | Alta de nuevo género cinematográfico |

---

## ⚙️ Configuración y Ejecución

### Requisitos Previos
- **JDK 21** o superior instalado y configurado en el `PATH`.
- Instancia de **PostgreSQL** en ejecución en `localhost:5432` con una base de datos creada llamada `streambox`.

### Variables de Entorno
Configura las siguientes variables de entorno en tu sistema o en tu IDE:

| Variable | Descripción | Valor por Defecto / Ejemplo |
| :--- | :--- | :--- |
| `JWT_SECRET` | Clave secreta para firmar y validar tokens JWT (**Obligatoria**) | Clave segura de al menos 256 bits en Base64 |
| `JWT_EXPIRATION_HOURS` | Tiempo de vida del token en horas | `24` |

> Alternativamente, puedes crear un archivo `application-local.properties` dentro de `streambox/src/main/resources/` (ignorado en Git) para sobreescribir las credenciales locales de la base de datos o el secreto JWT.

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

Iniciar el servidor de desarrollo:
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

Una vez levantada la aplicación, la API estará disponible en `http://localhost:8080`.

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

---

## 🤖 Sistema de Agentes Especializados (Antigravity)

El desarrollo del proyecto está respaldado por un equipo de **Custom Agents** configurados exclusivamente a nivel de workspace en `.agents/agents/`:

- **`orchestrator`**: Coordinador general y arquitecto de la solución.
- **`backend`**: Implementación de controladores, servicios, DTOs y mappers.
- **`database`**: Modelado relacional, entidades JPA, índices y optimizaciones SQL.
- **`security`**: Blindaje de rutas, filtros JWT, autenticación y prevención de vulnerabilidades.
- **`frontend`**: Futura aplicación cliente SPA/SSR (UI/UX, componentes y consumo de la API).
- **`qa`**: Diseño y ejecución de baterías de pruebas, aseguramiento de contratos y reporte de incidencias.
