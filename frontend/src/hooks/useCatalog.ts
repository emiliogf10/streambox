import { useCallback, useEffect, useRef, useState } from 'react';
import { useToast } from '../context/ToastContext';
import { apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import { mergeById } from '../lib/catalog';
import type { Movie, PageResponse } from '../lib/types';

/** Películas por petición. Suficiente para llenar la portada sin pedir demasiado. */
const PAGE_SIZE = 20;

/** Estado de la carga inicial del catálogo. */
type CatalogStatus = 'loading' | 'ready' | 'error';

/** Pide una página del catálogo, de la película más reciente a la más antigua. */
function fetchPage(page: number, signal: AbortSignal): Promise<PageResponse<Movie>> {
  return apiFetch<PageResponse<Movie>>('/movies', {
    params: { page, size: PAGE_SIZE, sort: 'createdAt', direction: 'desc' },
    signal,
  });
}

/**
 * Carga el catálogo paginado, de la película MÁS RECIENTE a la más antigua.
 *
 * La ordenación la hace el servidor (`sort=createdAt&direction=desc`, con
 * desempate por `id` en la misma dirección), así que la paginación es estable y
 * "Cargar más" solo pide la página siguiente mientras `hasNext` sea `true`.
 *
 * **Estados independientes:** la carga inicial (`status`) y la de "cargar más"
 * (`loadingMore`) no se mezclan. Un fallo al cargar más NO destruye lo ya
 * cargado: se avisa con un toast y el botón pasa a "Reintentar"
 * (`loadMoreFailed`). Las películas nuevas se añaden al final (sin duplicados,
 * ver `mergeById`), así que el banner (la primera) no cambia y la posición de
 * scroll no salta.
 */
export function useCatalog() {
  const toast = useToast();
  const [movies, setMovies] = useState<Movie[]>([]);
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

  // Carga inicial (y recarga con "Reintentar").
  useEffect(() => {
    const controller = new AbortController();

    (async () => {
      try {
        const response = await fetchPage(0, controller.signal);
        nextPage.current = 1;
        setMovies(mergeById([], response.content));
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
  }, [reloadKey]);

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
      setMovies((current) => mergeById(current, response.content));
      setTotal(response.totalElements);
      nextPage.current += 1;
      setHasMore(response.hasNext);
    } catch (error) {
      if (isAbortError(error)) return;
      setLoadMoreFailed(true);
      toast.errorFrom(error, 'No se pudieron cargar más películas.');
    } finally {
      loadingMoreRef.current = false;
      setLoadingMore(false);
    }
  }, [toast]);

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

  return { movies, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload };
}
