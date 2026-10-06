import { useCallback, useEffect, useRef, useState } from 'react';
import { useToast } from '../context/ToastContext';
import { apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import { mergeById } from '../lib/catalog';
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

/**
 * Carga un catálogo paginado (`/movies`, `/series` o `/movies/search`). Es la
 * lógica común de `useCatalog` (portada), los hooks de series y la página
 * `/peliculas` (`useMovieResults`): antes estaba escrita solo para películas y
 * las series la habrían duplicado línea a línea.
 *
 * La ordenación la hace el servidor (por defecto `sort=createdAt&direction=desc`,
 * con desempate por `id` en la misma dirección), así que la paginación es
 * estable y "Cargar más" solo pide la página siguiente mientras `hasNext` sea `true`.
 *
 * **Estados independientes:** la carga inicial (`status`) y la de "cargar más"
 * (`loadingMore`) no se mezclan. Un fallo al cargar más NO destruye lo ya
 * cargado: se avisa con un toast y el botón pasa a "Reintentar"
 * (`loadMoreFailed`). Los títulos nuevos se añaden al final (sin duplicados,
 * ver `mergeById`), así que el banner (el primero) no cambia y la posición de
 * scroll no salta.
 *
 * **Cambio de consulta** (otro `path` u otros filtros, p. ej. al elegir un
 * género en `/peliculas`): el catálogo vuelve a empezar. El estado se reinicia
 * DURANTE el render (el patrón que recomienda React para "ajustar el estado
 * cuando cambia una prop"), de modo que nunca se pinta, ni un solo fotograma,
 * la lista anterior como si fuera de los filtros nuevos. Las peticiones en vuelo
 * de la consulta anterior (la inicial y la de "cargar más") se cancelan con su
 * `AbortController`: una respuesta tardía no puede colarse en la lista nueva.
 *
 * Los parámetros son valores simples (y los de `query` se desestructuran en
 * valores simples) para que, al ser dependencias de los efectos, no provoquen
 * recargas por cambiar de identidad en cada render: quien llama puede pasar un
 * objeto `query` nuevo en cada render sin problema.
 *
 * @param path ruta del listado relativa a `/api` (`/movies`, `/series`, `/movies/search`)
 * @param pageSize títulos por petición
 * @param loadMoreErrorMessage aviso si falla "cargar más" y el error no trae mensaje propio
 * @param query orden y filtros (opcional; ver {@link CatalogQuery})
 */
export function usePagedCatalog<T extends { id: number }>(
  path: string,
  pageSize: number,
  loadMoreErrorMessage: string,
  query: CatalogQuery = {},
) {
  const { sort = 'createdAt', direction = 'desc', genreId, releaseYear } = query;
  const toast = useToast();
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<CatalogStatus>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreFailed, setLoadMoreFailed] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  /** Próxima página que falta por pedir (la 0 se pide en la carga inicial). */
  const nextPage = useRef(1);
  const loadingMoreRef = useRef(false);
  const moreController = useRef<AbortController | null>(null);

  // Otra consulta: se olvida la anterior antes de pintar (ver «Cambio de consulta» arriba).
  const queryKey = [path, pageSize, sort, direction, genreId ?? '', releaseYear ?? ''].join('|');
  const [currentQueryKey, setCurrentQueryKey] = useState(queryKey);
  if (currentQueryKey !== queryKey) {
    setCurrentQueryKey(queryKey);
    setItems([]);
    setTotal(0);
    setHasMore(false);
    setStatus('loading');
    setLoadingMore(false);
    setLoadMoreFailed(false);
  }

  /** Pide una página del catálogo con el orden y los filtros actuales. */
  const fetchPage = useCallback(
    (page: number, signal: AbortSignal) =>
      apiFetch<PageResponse<T>>(path, {
        params: { page, size: pageSize, sort, direction, genreId, releaseYear },
        signal,
      }),
    [path, pageSize, sort, direction, genreId, releaseYear],
  );

  // Carga inicial (y recarga con "Reintentar", y cada cambio de consulta).
  useEffect(() => {
    const controller = new AbortController();
    // Un "cargar más" de la consulta anterior ya no sirve: se cancela y se libera el candado.
    moreController.current?.abort();
    loadingMoreRef.current = false;

    (async () => {
      try {
        const response = await fetchPage(0, controller.signal);
        // Red de seguridad: si la respuesta llega justo después de cancelar, se descarta igual.
        if (controller.signal.aborted) return;
        nextPage.current = 1;
        setItems(mergeById<T>([], response.content));
        setTotal(response.totalElements);
        setHasMore(response.hasNext);
        setStatus('ready');
      } catch (error) {
        if (isAbortError(error)) return;
        setErrorMessage(getErrorMessage(error));
        setStatus('error');
      }
    })();

    return () => controller.abort();
  }, [fetchPage, reloadKey]);

  // Al salir de la pantalla se cancela cualquier "cargar más" en vuelo.
  useEffect(() => () => moreController.current?.abort(), []);

  /** Pide la página siguiente y la añade al final sin duplicar ni perder lo cargado. */
  const loadMore = useCallback(async () => {
    if (loadingMoreRef.current) return;

    loadingMoreRef.current = true;
    setLoadingMore(true);
    setLoadMoreFailed(false);
    const controller = new AbortController();
    moreController.current = controller;

    try {
      const response = await fetchPage(nextPage.current, controller.signal);
      if (controller.signal.aborted) return;
      setItems((current) => mergeById(current, response.content));
      setTotal(response.totalElements);
      nextPage.current += 1;
      setHasMore(response.hasNext);
    } catch (error) {
      if (isAbortError(error)) return;
      setLoadMoreFailed(true);
      toast.errorFrom(error, loadMoreErrorMessage);
    } finally {
      // Si se canceló por un cambio de consulta, el candado ya es de la consulta nueva: no se toca.
      if (moreController.current === controller) {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      }
    }
  }, [fetchPage, loadMoreErrorMessage, toast]);

  /**
   * Vuelve a cargar desde cero. El estado se reinicia aquí (en el evento que lo
   * provoca) y no dentro del efecto, para no encadenar renders innecesarios.
   */
  const reload = useCallback(() => {
    moreController.current?.abort();
    loadingMoreRef.current = false;
    setStatus('loading');
    setLoadingMore(false);
    setLoadMoreFailed(false);
    setReloadKey((key) => key + 1);
  }, []);

  return { items, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload };
}
