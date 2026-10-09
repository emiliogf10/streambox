# Registros de decisiones de arquitectura (ADR)

Un **ADR** (*Architecture Decision Record*) es una nota corta que responde a «¿por qué está hecho así?» para una decisión concreta: el contexto, lo que se decidió, las alternativas descartadas y las consecuencias (también las malas). El código dice *qué* hace el sistema; el ADR guarda el *porqué*, que es lo primero que se olvida y lo que más se pregunta en una revisión o entrevista.

Formato de cada ADR: **Estado** · **Contexto** · **Decisión** · **Alternativas descartadas** · **Consecuencias**. El detalle de cómo funciona cada pieza está en el [manual del programador](../MANUAL_PROGRAMADOR.md); aquí solo la decisión.

## Índice

| Nº | Decisión | Estado |
| :--- | :--- | :--- |
| [0001](0001-backend-spring-boot-java-maven.md) | Backend con Java 21, Spring Boot 4 y Maven (con wrapper) | Aceptada |
| [0002](0002-frontend-spa-react-vite.md) | Frontend como SPA independiente con React, Vite, TypeScript y Tailwind | Aceptada |
| [0003](0003-flyway-y-sql-portable.md) | Esquema gestionado por Flyway; Hibernate solo valida; SQL portable | Aceptada |
| [0004](0004-jwt-stateless.md) | Autenticación con JWT sin estado y rol consultado a la base en cada petición | Aceptada; sustituida en parte por la 0011 |
| [0005](0005-endpoints-me-sin-ids.md) | Recursos personales en `/api/users/me/...` con el id del token | Aceptada |
| [0006](0006-rate-limiting-en-memoria.md) | Rate limiting y bloqueo de cuentas en memoria | Aceptada (con límite conocido) |
| [0007](0007-favoritos-con-sql-directo.md) | Favoritos con `INSERT`/`DELETE` directos, sin cargar la colección | Aceptada |
| [0008](0008-series-tablas-propias.md) | Series con tablas propias; las series sin episodios son invisibles | Aceptada |
| [0009](0009-estrategia-de-tests.md) | Tests con H2 y, para lo que depende del motor, PostgreSQL real con Testcontainers | Aceptada |
| [0010](0010-docker-compose-y-nginx.md) | Despliegue con Docker Compose y un único puerto publicado (nginx) | Aceptada |
| [0011](0011-token-corto-y-refresh-rotatorio.md) | Token de acceso de 15 minutos y *refresh token* rotatorio, revocable y con detección de reutilización | Aceptada |
| [0012](0012-datos-del-servidor-con-tanstack-query.md) | Datos del servidor en el frontend con TanStack Query (caché, invalidación y mutaciones optimistas) | Aceptada |

## Cómo añadir uno

1. Copia el último ADR, numera el siguiente (`00NN-titulo-corto.md`) y rellena las cinco secciones.
2. Añádelo a la tabla de arriba.
3. **No reescribas un ADR aceptado.** Si la decisión cambia, escribe otro que lo sustituya y marca el antiguo como «Sustituida por 00NN» (igual que con las migraciones de Flyway: la historia no se edita).
