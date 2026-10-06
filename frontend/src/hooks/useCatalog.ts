import { hasActiveFilters, movieFiltersToQuery } from '../lib/movieFilters';
import type { MovieFilters } from '../lib/movieFilters';
import type { Movie } from '../lib/types';
import { usePagedCatalog } from './usePagedCatalog';

/** Películas por petición. Suficiente para llenar la portada sin pedir demasiado. */
const PAGE_SIZE = 20;

/** Aviso si falla "Cargar más películas". */
const LOAD_MORE_ERROR = 'No se pudieron cargar más películas.';

/**
 * Carga el catálogo de PELÍCULAS paginado, de la más reciente a la más antigua
 * (`GET /movies?page=N&size=20&sort=createdAt&direction=desc`).
 *
 * Es una capa fina sobre {@link usePagedCatalog}, que tiene toda la lógica
 * (carga inicial, "cargar más" sin duplicados, reintento, cancelación). Solo
 * fija el endpoint y expone los elementos con el nombre `movies`, que es el que
 * ya usaba la portada.
 */
export function useCatalog() {
  const { items, ...rest } = usePagedCatalog<Movie>('/movies', PAGE_SIZE, LOAD_MORE_ERROR);
  return { movies: items, ...rest };
}

/**
 * Películas de la página `/peliculas` según sus filtros (ver `lib/movieFilters.ts`).
 *
 * - **Sin filtros:** exactamente lo mismo que la portada ({@link useCatalog}):
 *   `GET /movies?...&sort=createdAt&direction=desc`, para el banner y las filas.
 * - **Con algún filtro** (género, año u otro orden):
 *   `GET /movies/search?page=N&size=20&sort=…&direction=…&genreId=…&releaseYear=…`.
 *   No se manda `title`: buscar por texto ya lo hace el buscador de la barra.
 *
 * Es UNA sola llamada a {@link usePagedCatalog} cuyo endpoint y consulta cambian
 * con los filtros: al cambiarlos, el hook reinicia la lista y cancela la
 * petición anterior, así que una respuesta lenta de un filtro viejo nunca se
 * pinta como si fuera del nuevo.
 *
 * @param filters filtros aplicados (los de la URL, ya validados)
 * @returns lo mismo que `useCatalog` y, además, `filtered` (si hay filtros activos)
 */
export function useMovieResults(filters: MovieFilters) {
  const filtered = hasActiveFilters(filters);
  const { items, ...rest } = usePagedCatalog<Movie>(
    filtered ? '/movies/search' : '/movies',
    PAGE_SIZE,
    LOAD_MORE_ERROR,
    filtered ? movieFiltersToQuery(filters) : undefined,
  );
  return { movies: items, filtered, ...rest };
}
