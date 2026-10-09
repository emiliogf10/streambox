import { useCallback, useMemo, useRef } from 'react';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useToast } from '../context/ToastContext';
import { apiFetch, getErrorMessage } from '../lib/api';
import { mergeById } from '../lib/catalog';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import type { CatalogPath } from '../lib/queryKeys';
import type { PageResponse } from '../lib/types';

/** Estado de la carga inicial de un catálogo paginado. */
export type CatalogStatus = 'loading' | 'ready' | 'error';

/**
 * Orden y filtros opcionales de un catálogo paginado. Sin ellos, el catálogo va
 * del título más reciente al más antiguo (`sort=createdAt&direction=desc`).
 *
 * `genreId` y `releaseYear` solo los entiende `GET /api/movies/search`: quien
 * los pasa debe pedir ese endpoint.
 */
export interface CatalogQuery {
  /** Campo de orden de la lista blanca del servidor (`createdAt`, `title`, `releaseYear`, `duration`, `id`). */
  sort?: string;
  direction?: 'asc' | 'desc';
  genreId?: number;
  releaseYear?: number;
}

/** Lista vacía compartida: mientras no hay datos, `items` no cambia de identidad en cada render. */
const NO_ITEMS: never[] = [];

/**
 * Carga un catálogo paginado (`/movies`, `/series` o `/movies/search`) con
 * «cargar más». Es la lógica común de `useCatalog` (portada), los hooks de
 * series, `useCatalogPresence` y la página `/peliculas` (`useMovieResults`).
 *
 * **Con TanStack Query** (`useInfiniteQuery`): cada página pedida se guarda en
 * la caché bajo una clave con el endpoint, el tamaño, el orden y los filtros
 * (`queryKeys.paged`). De ahí salen las ventajas frente a la versión anterior,
 * que lo hacía todo a mano con `AbortController` y contadores:
 * - **Caché compartida:** la portada y `/peliculas` sin filtros piden lo mismo y
 *   comparten la entrada; volver a una pantalla ya visitada la pinta al instante
 *   (y, si el dato tiene más de un minuto, lo refresca en segundo plano).
 * - **Sin carreras:** cambiar de filtros es cambiar de clave. La lista nueva
 *   empieza vacía («cargando»: nunca se pinta, ni un fotograma, la anterior como
 *   si fuera de los filtros nuevos) y la petición de la clave vieja se cancela
 *   (`signal`) al quedarse sin pantalla que la use.
 * - **Invalidación:** tras una escritura del panel, la raíz de la clave
 *   (`movies` o `series`) se invalida y este listado se pone al día solo.
 *
 * La ordenación la hace el servidor (con desempate por `id`), así que la
 * paginación es estable y «Cargar más» pide la página siguiente mientras el
 * servidor diga `hasNext`. Los títulos de cada página se añaden al final sin
 * duplicados (`mergeById`): el banner (el primero) no cambia y el scroll no salta.
 *
 * **Estados independientes:** la carga inicial (`status`) y la de «cargar más»
 * (`loadingMore`) no se mezclan. Un fallo al cargar más NO destruye lo ya
 * cargado: se avisa con un toast y el botón pasa a «Reintentar» (`loadMoreFailed`).
 *
 * @param path ruta del listado relativa a `/api`
 * @param pageSize títulos por petición
 * @param loadMoreErrorMessage aviso si falla «cargar más» y el error no trae mensaje propio
 * @param query orden y filtros (opcional; ver {@link CatalogQuery}); puede ser un objeto nuevo en cada render
 */
export function usePagedCatalog<T extends { id: number }>(
  path: CatalogPath,
  pageSize: number,
  loadMoreErrorMessage: string,
  query: CatalogQuery = {},
) {
  const { sort = 'createdAt', direction = 'desc', genreId, releaseYear } = query;
  const toast = useToast();

  const queryClient = useQueryClient();
  // Valores simples en la clave: un objeto `query` nuevo en cada render no provoca otra petición.
  const queryKey = queryKeys.paged(path, { pageSize, sort, direction, genreId, releaseYear });

  const catalog = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) =>
      apiFetch<PageResponse<T>>(path, {
        params: { page: pageParam, size: pageSize, sort, direction, genreId, releaseYear },
        signal,
      }),
    initialPageParam: 0,
    // La página siguiente es la que toca por orden (no la que diga el servidor): la 0, la 1, la 2...
    getNextPageParam: (lastPage, _pages, lastPageParam) => (lastPage.hasNext ? lastPageParam + 1 : undefined),
  });

  const { data, fetchNextPage, isFetchingNextPage, isFetchNextPageError } = catalog;
  const items = useMemo(
    () => data?.pages.reduce<T[]>((all, page) => mergeById(all, page.content), []) ?? NO_ITEMS,
    [data],
  );
  const lastPage = data?.pages.at(-1);
  const status: CatalogStatus = loadStatusOf(catalog);

  /**
   * Candado síncrono de «cargar más»: un doble clic llega antes de que React
   * pinte `loadingMore`, y sin él los dos clics esperarían la misma petición y
   * mostrarían dos avisos si fallara.
   */
  const loadingMoreRef = useRef(false);

  /** Pide la página siguiente; si falla, avisa (lo cargado se queda y el botón ofrece reintentar). */
  const loadMore = useCallback(async () => {
    if (loadingMoreRef.current) return;
    loadingMoreRef.current = true;
    try {
      // Si había un refresco en segundo plano en curso, se corta (el valor por defecto): el clic
      // siempre trae la página siguiente, en vez de esperar al refresco y no hacer nada más.
      const result = await fetchNextPage();
      if (result.isFetchNextPageError) toast.errorFrom(result.error, loadMoreErrorMessage);
    } finally {
      loadingMoreRef.current = false;
    }
  }, [fetchNextPage, loadMoreErrorMessage, toast]);

  /**
   * Vuelve a cargar DESDE CERO («Reintentar» del estado de error): olvida las
   * páginas cargadas y pide solo la primera, y mientras tanto `status` es
   * `loading`. Un `refetch` volvería a pedir, una tras otra, todas las páginas
   * ya cargadas.
   */
  const reload = useCallback(() => {
    const key = queryKeys.paged(path, { pageSize, sort, direction, genreId, releaseYear });
    void queryClient.resetQueries({ queryKey: key, exact: true });
  }, [queryClient, path, pageSize, sort, direction, genreId, releaseYear]);

  return {
    items,
    total: lastPage?.totalElements ?? 0,
    status,
    errorMessage: status === 'error' ? getErrorMessage(catalog.error) : '',
    loadingMore: isFetchingNextPage,
    // Mientras se reintenta, el botón dice «Cargando...», no «Reintentar».
    loadMoreFailed: isFetchNextPageError && !isFetchingNextPage,
    hasMore: lastPage?.hasNext ?? false,
    loadMore,
    reload,
  };
}
