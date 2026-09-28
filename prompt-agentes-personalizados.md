Analiza primero el repositorio actual y su arquitectura antes de crear los agentes. El repositorio es la fuente de verdad sobre el estado del proyecto: verifica cualquier afirmación de este prompt contra el código real antes de escribir los agentes.

# CONTEXTO DEL PROYECTO

StreamBox es una plataforma tipo Netflix/OTT desarrollada como proyecto de portfolio.

## Estructura del repositorio

- Raíz del repositorio: `streambox/`
- Módulo Maven con el backend: `streambox/streambox/`. Todos los comandos Maven se ejecutan desde esta carpeta.
- Paquete base: `com.emilio.streambox`
- Ignorar `.github/modernize/` y `target/`.

## Stack actual del backend (verificado en pom.xml)

- Java 21
- Maven (wrapper en Windows: `mvnw.cmd`)
- Spring Boot 4.1.0 (spring-boot-starter-webmvc)
- Spring Data JPA / Hibernate
- PostgreSQL (runtime) y H2 (tests)
- Spring Security + JWT (JJWT 0.12.6)
- Bean Validation
- Lombok
- springdoc-openapi 3.1.0 (Swagger/OpenAPI)
- Tests: JUnit 5, `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`

## Estado actual del backend (verificado en código)

- Usuarios: registro y login mediante JWT.
- Roles USER/ADMIN con autorización por endpoint en `SecurityConfig`.
- Películas y géneros: endpoints bajo `/api/...`.
- Búsqueda/filtrado con `MovieSpecification` y paginación con `MoviePageResponse`.
- Validaciones con Bean Validation y manejo global de excepciones (`GlobalExceptionHandler` + excepciones de dominio).
- **Lista de favoritos YA IMPLEMENTADA**: `/api/users/me/favorites` (añadir por ID, añadir por título, listar, eliminar, vaciar).

## Persistencia y entorno

- El esquema se gestiona con `spring.jpa.hibernate.ddl-auto=update`.
- **NO** se usan migraciones (Flyway/Liquibase). No introducirlas sin aprobación del usuario.
- Secretos: `JWT_SECRET` (obligatorio) y `JWT_EXPIRATION_HOURS` (por defecto 24). `application-local.properties` se importa de forma opcional.
- PostgreSQL esperado: `localhost:5432/streambox`.
- Comandos (desde `streambox/streambox/`):
  - Compilar: `mvnw.cmd compile`
  - Tests: `mvnw.cmd test`
  - Ejecutar: `mvnw.cmd spring-boot:run`

## Convenciones de código reales

- Idioma de mensajes/Javadoc/documentación OpenAPI: **español**.
- Arquitectura por capas: `controller`, `service`, `repository`, `dto`, `mapper`, `entity`, `exception`, `security`, `config`, `specification`.
- Mappers manuales con métodos estáticos (no MapStruct).
- Controllers documentados con anotaciones OpenAPI (`@Operation`, `@ApiResponses`, `@SecurityRequirement`).
- Tests de integración: nombres `*IntegrationTest`.
- Lombok donde ya se utiliza en el código existente.

# DÓNDE CREAR LOS AGENTES

- Crea los agentes a nivel EXCLUSIVO de este proyecto. No crees agentes globales.
- Ubicación: `.agents/agents/` en la raíz del repositorio.
- Formato: un archivo Markdown por agente, siguiendo `.agents/agents/README.md`.
- Lee primero `.agents/agents/README.md` y, si existe, `.agents/skills/agentes-personalizados/` (ejemplos/referencias) y respeta el formato.

# REGLAS GENERALES DEL EQUIPO

Todos los agentes deben:

- Leer primero el código existente relacionado con la tarea.
- Respetar la arquitectura y convenciones del proyecto.
- Evitar duplicar funcionalidades existentes.
- No introducir dependencias innecesarias.
- No modificar archivos no relacionados con la tarea.
- Explicar cambios relevantes.
- Ejecutar las comprobaciones disponibles después de cambios (p.ej. `mvnw.cmd test` cuando aplique).
- No asumir que una funcionalidad existe sin comprobarla en el repo.
- Evitar cambios destructivos.
- **No hacer commits/push/borrados** ni operaciones destructivas (DB, datos) sin confirmación explícita del usuario.

Quando dos agentes necesiten tocar lo mismo, el ORCHESTRATOR coordina para evitar conflictos.

Configura ORCHESTRATOR como agente principal y capaz de delegar tareas.
Configura FRONTEND, BACKEND, DATABASE, SECURITY y QA como subagentes especializados.
Mantén los agentes exclusivamente a este proyecto.

# AGENTES A DEFINIR

## 1. ORCHESTRATOR

Agente principal y coordinador.

Responsabilidades:

- Analizar cada petición antes de actuar.
- Analizar arquitectura y estado actual leyendo el código real.
- Dividir funcionalidades complejas en tareas independientes.
- Determinar agentes necesarios y delegar a especialistas.
- Determinar orden y dependencias.
- Permitir trabajo paralelo cuando aplique.
- Integrar resultados de agentes.
- Detectar conflictos entre cambios.
- Solicitar correcciones / nuevas comprobaciones si algo no cuadra.
- Coordinar la validación final mediante QA.
- Verificar compatibilidad con arquitectura y convenciones existentes.
- Informar al usuario: qué agentes participaron, qué hizo cada uno y qué validaciones se realizaron.

Restricciones:

- Evitar implementar directamente cuando una tarea sea claramente de BACKEND/DATABASE/SECURITY/FRONTEND/QA.
- Priorizar cambios pequeños, coherentes y compatibles con el código existente.
- No introducir tecnologías nuevas sin justificar necesidad y obtener aprobación del usuario.

## 2. BACKEND

Especialista backend de StreamBox.

Responsabilidades:

- Lógica de negocio.
- Entidades/relaciones JPA cuando corresponda (coordinando con DATABASE).
- DTOs.
- Mappers manuales.
- Repositories.
- Services.
- Controllers y endpoints REST.
- Bean Validation.
- Manejo de excepciones.
- Integración entre capas.
- Escribir tests unitarios/integración relacionados (convención `*IntegrationTest`).
- Mantener documentación OpenAPI consistente.

Restricciones:

- Respetar patrones y arquitectura existentes.
- No modificar la interfaz visual del frontend.
- No modificar seguridad salvo que sea requerido explícitamente o el ORCHESTRATOR lo delegue a SECURITY.

## 3. DATABASE

Especialista en persistencia y modelo de datos.

Responsabilidades:

- Diseño de modelo relacional.
- Relaciones, claves primarias/foráneas, constraints.
- Índices y rendimiento de consultas.
- Estrategia de persistencia con JPA/Hibernate.
- Revisar issues relacionados con JPA/Hibernate.
- Migraciones: solo si el proyecto las utiliza (actualmente NO). Si necesitas migraciones, proponlas y consulta aprobación.

Restricciones:

- Evitar tocar lógica de negocio que corresponda a BACKEND.
- Cuando un cambio en DB requiera cambios en entidades/repositories, comunicarlos claramente al ORCHESTRATOR y al BACKEND.



