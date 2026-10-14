# 0013. Búsqueda por título sin acentos y tolerante a erratas (`unaccent` + `pg_trgm`)

**Estado:** Aceptada (2026-10-14). Matiza el [0003](0003-flyway-y-sql-portable.md): es la primera migración específica de PostgreSQL, con su equivalente para H2.

## Contexto
La búsqueda por título (`lower(title) LIKE '%x%'`) ignoraba mayúsculas pero no acentos («manana» no encontraba «Mañana»), no perdonaba erratas («interstelar» no daba nada) y no podía usar un índice normal. Los tests usan H2, que no tiene las extensiones de PostgreSQL que resuelven esto, y la base de desarrollo es Supabase.

## Decisión
- **Migración `V5` por motor** en `db/vendor/{vendor}` (`spring.flyway.locations=…,classpath:db/vendor/{vendor},…`): las dos en `src/main/resources` (también la de H2, con su clase `h2/H2SearchFunctions`: el backend que arrancan los E2E con `spring-boot:run -Dspring-boot.run.useTestClasspath=true` no ve `target/test-classes`, y al principio la V5 de H2 estaba en `src/test` y cada búsqueda daba 500 allí; meter `test-classes` en ese arranque habría colado las configuraciones de los tests). En PostgreSQL la de H2 nunca se lee. No vale `db/migration/{vendor}`: Flyway recorre las subcarpetas de `db/migration` y vería las dos `V5`.
- **PostgreSQL:** `unaccent` y `pg_trgm` en el esquema `extensions` (como Supabase; si ya estaban en otro, se reutilizan), para que `public` solo tenga objetos de StreamBox y el *callback* que cierra la API pública los cubra. Funciones propias `streambox_unaccent(text)` y `streambox_word_similarity(text, text)`: `IMMUTABLE STRICT PARALLEL SAFE`, cuerpo `BEGIN ATOMIC` (liga `unaccent` y su diccionario al crear la función: no depende del `search_path` y un `DROP EXTENSION` falla en vez de romper los índices) y `SET search_path = ''`. Índices **GIN de trigramas** sobre `streambox_unaccent(lower(title))` en `movies` y `series`.
- **H2:** las mismas funciones como `CREATE ALIAS` a Java (`unaccent` con NFKD sin marcas, más ß, æ, œ, ø, ł, đ); `word_similarity` devuelve 0, así que **en H2 no hay tolerancia a erratas** (mejor que imitar `pg_trgm` en Java y aprobar en H2 lo que PostgreSQL no da). La tolerancia se prueba contra PostgreSQL real.
- **Consulta** (`specification/TitleSearch`): «contiene» sin mayúsculas ni acentos, normalizando los dos lados en la base de datos y escapando los comodines **después** de normalizar (medido: `unaccent` convierte `％ ＿ ＼` en `% _ \`; al revés, buscar `＿` devolvía todo el catálogo).
- **Erratas:** solo si la búsqueda exacta no encuentra nada y tiene 5 letras o más; `word_similarity ≥ 0.4` (con el 0,6 por defecto, «matirx» no encontraría «Matrix»; por debajo de 5 letras, «amro» se parece igual a «Amor», «Amadeus» y «Amélie»); resultados ordenados por parecido y marcados como aproximados en la respuesta.
- **El texto buscado va como parámetro enlazado** (`HibernateCriteriaBuilder.value`), no como literal en el SQL: con el literal, un carácter nulo hacía que PostgreSQL rechazara el mensaje del protocolo y la conexión del pool quedaba rota. Se exige además que la clave normalizada no quede vacía (un acento suelto «´» se quedaba en `LIKE '%%'` y devolvía todo) y el parámetro se valida (≤150 caracteres, sin caracteres de control → 400). Los tres puntos los encontró la revisión de `qa`.

## Alternativas descartadas
- **Elasticsearch/Meilisearch:** otro servidor que sincronizar para un catálogo pequeño; `pg_trgm` lo resuelve en la misma base y transacción.
- **Sumar la tolerancia con un `OR` a la búsqueda exacta:** trae ruido («matrix» bien escrito traería «Matilda») e impide usar el índice.
- **Operadores indexables `<%`/`%>`:** su umbral es una variable de sesión (`pg_trgm.word_similarity_threshold`, 0,6); cambiarla exige `SET LOCAL` en cada transacción o tocar el rol: estado global incoherente entre conexiones del pool. Es el camino si el catálogo crece mucho (medido con 50 000 títulos: 310 ms recorriendo la tabla frente a 3 ms).
- **Callback `afterMigrate`:** el esquema quedaría sin versión. **Migración Java con comprobación de motor:** el SQL queda menos visible. **Columna generada con la clave precalculada:** solo ahorra un 30 %.

## Consecuencias
- (+) «manana» → «Mañana», «nono» → «Ñoño», «strasse» → «Straße», «interstelar» → «Interstellar»; búsqueda con índice desde 3 letras.
- (+) Nada a mano en Supabase: la `V5` se aplica sola en el siguiente arranque (si las extensiones están disponibles; si no, la transacción se deshace y la app no arranca, con el error en el log).
- (−) Código específico de PostgreSQL (con su gemelo H2) y comportamiento distinto entre motores en la tolerancia a erratas.
- (−) La tolerancia recorre la tabla (no usa el índice) y, por cómo funcionan los trigramas, «matirx» también se parece a «Matilda».
