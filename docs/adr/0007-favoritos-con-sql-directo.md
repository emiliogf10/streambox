# 0007. Favoritos con `INSERT`/`DELETE` directos, sin cargar la colección

**Estado:** Aceptada

## Contexto
La forma «JPA de libro» de añadir un favorito es cargar el `User`, hacer `getFavoriteMovies().add(movie)` y dejar que Hibernate sincronice. Eso carga **toda** la lista del usuario (y sus géneros) para añadir una sola película, y el filtro JWT ya carga el `User` en cada petición.

## Decisión
- `FavoriteService` ejecuta `INSERT` y `DELETE` directos sobre la tabla de unión `user_favorite_movies` (y `user_favorite_series`), con consultas nativas en los repositorios.
- Se apoya en la **clave primaria compuesta** `(user_id, movie_id)` para la concurrencia: una comprobación previa en Java no evita que dos «añadir» simultáneos pasen a la vez, pero la base rechaza el segundo `INSERT` y el servicio traduce el `SQLSTATE` (no el texto del mensaje) a `409`. Con 8 hilos a la vez, el test contra PostgreSQL ve siempre 1 éxito y 7 respuestas 409.
- El frontend trata 409/404 al añadir o quitar como «el estado ya es el correcto».
- Las entidades no tienen colecciones de favoritos de series para no engordar la carga de `User`.

## Alternativas descartadas
- **Modificar la colección `@ManyToMany`:** simple de escribir, costosa y con condiciones de carrera.
- **Entidad intermedia `Favorite`:** más modelo para una tabla de dos columnas.

## Consecuencias
- (+) Una sentencia por operación, sin importar el tamaño de la lista.
- (−) SQL nativo: depende del motor y se prueba también contra PostgreSQL real (concurrencia incluida, [0009](0009-estrategia-de-tests.md)).
- (−) `User` conserva el mapeo `favoriteMovies`, pero los servicios no lo modifican: la fuente de verdad son estas sentencias.
