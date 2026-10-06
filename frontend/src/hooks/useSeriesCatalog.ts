import { NEWS_ROW_SIZE } from '../lib/catalog';
import type { Series } from '../lib/types';
import { usePagedCatalog } from './usePagedCatalog';

/** Series por petición en la página `/series` (las mismas que películas en la portada). */
const PAGE_SIZE = 20;

/** Aviso si falla "Cargar más series". */
const LOAD_MORE_ERROR = 'No se pudieron cargar más series.';

/**
 * Catálogo de SERIES paginado para la página `/series`, de la más reciente a la
 * más antigua (`GET /series?page=N&size=20&sort=createdAt&direction=desc`).
 *
 * Misma lógica que las películas ({@link usePagedCatalog}); el servidor ya
 * excluye las series sin episodios, así que aquí no hay que filtrar nada.
 */
export function useSeriesCatalog() {
  const { items, ...rest } = usePagedCatalog<Series>('/series', PAGE_SIZE, LOAD_MORE_ERROR);
  return { series: items, ...rest };
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
