# 0008. Series con tablas propias; las series sin episodios son invisibles

**Estado:** Aceptada

## Contexto
Se añadieron series con temporadas y episodios a un sistema que ya funcionaba con películas, favoritos y datos reales en Supabase. Había que decidir cómo modelarlas sin arriesgar lo existente.

## Decisión
- **Tablas propias** (`series`, `episodes`, `series_genres`, `user_favorite_series`) sin tocar `movies`; la migración `V3` solo crea tablas e índices. Los géneros se comparten con las películas.
- La **temporada es un número dentro del episodio** (`season_number`), con `UNIQUE (series_id, season_number, episode_number)`.
- Cascadas en la base de datos (`ON DELETE CASCADE`), no `CascadeType.REMOVE`; sin colección `Series.episodes` (los episodios se piden siempre con una consulta ordenada, evitando N+1).
- Una serie **sin episodios** existe para el administrador y no para el usuario: no aparece en listados ni búsquedas, y su detalle da el **mismo 404, con el mismo cuerpo**, que una serie inexistente. Los ids son secuenciales: una respuesta distinta permitiría recorrerlos y descubrir qué prepara el administrador. El panel usa vistas de gestión `GET /api/admin/series[/{id}]`.
- El detalle de una serie es una **página propia** (`/series/:id`), no un modal.

## Alternativas descartadas
- **Supertipo común «contenido» con herencia JPA:** más elegante en teoría, pero obligaba a migrar `movies` y sus favoritos.
- **Tabla `seasons`:** se añadirá con otra migración si una temporada necesita título o año propios.
- **Mostrar las series vacías como «próximamente»:** produce páginas vacías.

## Consecuencias
- (+) Cero riesgo para el catálogo y los datos de películas existentes.
- (−) Algunas columnas y lógica se repiten entre películas y series.
- (−) La regla de visibilidad debe aplicarse en cada ruta pública; está cubierta por tests de autorización por objeto.
