import { NEWS_ROW_SIZE } from '../lib/catalog';
import { catalogFiltersToQuery, hasActiveFilters } from '../lib/catalogFilters';
import type { CatalogFilters } from '../lib/catalogFilters';
import type { Series } from '../lib/types';
import { usePagedCatalog } from './usePagedCatalog';

/** Series por petición en la página `/series` (las mismas que películas en la portada). */
const PAGE_SIZE = 20;

/** Aviso si falla "Cargar más series". */
const LOAD_MORE_ERROR = 'No se pudieron cargar más series.';

/**
 * Series de la página `/series` según sus filtros (ver `lib/catalogFilters.ts`),
 * el gemelo de `useMovieResults`.
 *
 * - **Sin filtros:** el catálogo de la más reciente a la más antigua
 *   (`GET /series?page=N&size=20&sort=createdAt&direction=desc`), para el banner
 *   y las filas.
 * - **Con algún filtro** (género, año u otro orden):
 *   `GET /series/search?page=N&size=20&sort=…&direction=…&genreId=…&releaseYear=…`.
 *   No se manda `title`: buscar por texto ya lo hace el buscador de la barra.
 *
 * Es UNA sola llamada a {@link usePagedCatalog} cuyo endpoint y consulta
 * cambian con los filtros: al cambiarlos, la lista se reinicia y la petición
 * anterior se cancela. El servidor excluye las series sin episodios en los dos
 * endpoints, así que aquí no hay que filtrar nada.
 *
 * @param filters filtros aplicados (los de la URL, ya validados)
 * @returns el catálogo paginado con los títulos como `series` y, además, `filtered` (si hay filtros activos)
 */
export function useSeriesResults(filters: CatalogFilters) {
  const filtered = hasActiveFilters(filters);
  const { items, ...rest } = usePagedCatalog<Series>(
    filtered ? '/series/search' : '/series',
    PAGE_SIZE,
    LOAD_MORE_ERROR,
    filtered ? catalogFiltersToQuery(filters, 'series') : undefined,
  );
  return { series: items, filtered, ...rest };
}

/**
 * Las series más recientes para la fila «Series» de la portada: UNA página de
 * {@link NEWS_ROW_SIZE} (`GET /series?page=0&size=12&sort=createdAt&direction=desc`),
 * las mismas que caben en la fila "Novedades". La fila no pagina: para ver
 * todas está el enlace a `/series`.
 */
export function useLatestSeries() {
  const { items, status, errorMessage, reload } = usePagedCatalog<Series>('/series', NEWS_ROW_SIZE, LOAD_MORE_ERROR);
  return { series: items, status, errorMessage, reload };
}
