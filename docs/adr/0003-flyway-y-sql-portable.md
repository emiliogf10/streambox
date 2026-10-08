# 0003. Esquema gestionado por Flyway; Hibernate solo valida; SQL portable

**Estado:** Aceptada

## Contexto
Al principio Hibernate creaba y modificaba las tablas solo (`ddl-auto=update`). Eso no deja historial, no permite revisar un cambio de esquema como código y puede alterar una base real sin avisar. Además, los tests usan H2 en memoria mientras que producción usa PostgreSQL.

## Decisión
- **Flyway** es el dueño del esquema: migraciones `V<N>__descripcion.sql` en `streambox/src/main/resources/db/migration`. Hibernate solo **valida** que las entidades coinciden (`ddl-auto=validate` en dev, prod y test).
- Cambiar una entidad = entidad + **migración nueva**. Nunca se edita una migración ya aplicada (Flyway lo detecta por el checksum y no arranca).
- Las restricciones (`UNIQUE`, `CHECK`, claves foráneas con nombre) viven en la base de datos, no solo en el código: protegen aunque alguien escriba SQL a mano o dos peticiones compitan.
- Las bases creadas antes de Flyway se **adoptan** (`baseline-on-migrate`, versión 0; `V1` es idempotente).
- El SQL de las migraciones debe ser **portable** entre PostgreSQL y H2 en modo PostgreSQL. Lo específico de PostgreSQL se declara y se prueba con Testcontainers ([0009](0009-estrategia-de-tests.md)).
- Lo específico de PostgreSQL que no es una migración (cerrar la API pública de Supabase con RLS y `REVOKE`) vive en un *callback* `afterMigrate` en `db/callback/{vendor}`: con H2 no existe ese directorio y no se ejecuta nada. Cierra también las tablas de migraciones futuras sin que nadie tenga que acordarse. `docs/supabase-seguridad.sql` queda como respaldo manual.

## Alternativas descartadas
- **`ddl-auto=update`:** cómodo, pero sin control ni historial; descartado tras detectar que no aplica restricciones nuevas a tablas existentes.
- **Liquibase:** equivalente; Flyway con SQL plano es más legible para un equipo pequeño.
- **Una base distinta para tests (solo PostgreSQL):** más fiel pero más lenta y exige Docker siempre; se compensa con tests selectivos contra PostgreSQL real.

## Consecuencias
- (+) Cada cambio de esquema se revisa en una PR y se puede reproducir en cualquier base.
- (+) Los índices y restricciones se prueban (`FlywaySchemaIntegrationTest`).
- (−) Hay que escribir SQL además de la entidad; un olvido hace fallar el arranque (es lo que se quiere).
- (−) Limitarse a SQL portable renuncia a funciones solo de PostgreSQL (p. ej. `pg_trgm`) salvo que se justifique.
