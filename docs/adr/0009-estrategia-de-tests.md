# 0009. Tests con H2 y, para lo que depende del motor, PostgreSQL real con Testcontainers

**Estado:** Aceptada

## Contexto
Los tests de integración con una base real son lentos y exigen Docker; con una base en memoria son rápidos pero pueden ocultar diferencias del motor (ya ocultó un bug de búsqueda por `lower()`/collation).

## Decisión
- La mayoría de tests son de integración con `@SpringBootTest`, `@ActiveProfiles("test")` y MockMvc sobre **H2 en memoria** (modo PostgreSQL) con Flyway aplicado; la lógica aislada usa Mockito.
- Lo que depende del motor (migraciones, adopción de una base antigua, SQL nativo, orden/collation, concurrencia real de favoritos) se prueba **además** contra **PostgreSQL 16 real** con Testcontainers, en el paquete `postgres/`. Esos tests **se omiten solos si Docker no está**; el CI sí los ejecuta.
- Suites que hacen commit real no usan `@Transactional` y limpian la base; el resto hace rollback.
- Cada bug corregido deja un test que falla sin el arreglo. No se debilita un test para que pase.
- Frontend: Vitest + Testing Library (se localiza por rol/etiqueta, sin `data-testid`) y Playwright para flujos completos, con su propio backend (puerto 8099) y Vite (5199), aislados de la base y los puertos de desarrollo.

## Alternativas descartadas
- **Solo H2:** rápido, pero engañoso.
- **Solo PostgreSQL en contenedor:** fiel, pero obliga a tener Docker para cualquier test.

## Consecuencias
- (+) Se puede desarrollar sin Docker y el CI aun así cubre el motor real.
- (−) Dos motores que mantener alineados; el SQL de las migraciones debe ser portable ([0003](0003-flyway-y-sql-portable.md)).
- (−) El número de tests ejecutados varía según haya Docker o no; hay que dar siempre la cifra real.
