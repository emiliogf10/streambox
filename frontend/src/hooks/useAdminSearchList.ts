import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError, getErrorMessage, isAbortError } from '../lib/api';
import type { PageResponse } from '../lib/types';
import { useDebouncedValue } from './useDebouncedValue';

/** Espera tras la última tecla antes de buscar (igual que el buscador de la barra). */
export const ADMIN_SEARCH_DEBOUNCE_MS = 300;

/**
 * Pide una página del listado. La página llega empezando en 1 (la de la URL);
 * quien la implementa la pasa a la API, que cuenta desde 0.
 */
export type AdminPageFetcher<T> = (query: string, page: number, signal: AbortSignal) => Promise<PageResponse<T>>;

/** Resultado de una carga, junto con la "clave" (búsqueda + página + intento) que lo pidió. */
interface ListResult<T> {
  key: string;
  status: 'ready' | 'error';
  page: PageResponse<T> | null;
  errorMessage: string;
}

/** Lo que devuelve {@link useAdminSearchList}. */
export interface AdminSearchList<T> {
  /** Búsqueda aplicada (la de la URL, `?q=`), ya recortada. */
  query: string;
  /** Página actual, empezando en 1 (`?page=`). */
  page: number;
  /** Texto del campo de búsqueda (puede ir por delante de `query` mientras dura el debounce). */
  input: string;
  setInput: (value: string) => void;
  /** `true` si lo escrito aún no se ha aplicado (debounce) o si se está cargando otra página sobre la anterior. */
  searching: boolean;
  /** Última página recibida; mientras llega la nueva se sigue enseñando la anterior. */
  data: PageResponse<T> | null;
  /** `true` mientras la petición actual no ha respondido. */
  loading: boolean;
  /** Mensaje de error de la carga actual, o `null` si no ha fallado. */
  errorMessage: string | null;
  /**
   * `true` si la página pedida está vacía pero hay elementos (p. ej. `?page=9`, o se borró el
   * último de la última página): el hook ya está llevando a la última que existe y no hay que
   * enseñar "vacío" mientras tanto.
   */
  pendingPageFix: boolean;
  goToPage: (page: number) => void;
  clearSearch: () => void;
  /** Vuelve a pedir la página actual (tras borrar, o al pulsar «Reintentar»). */
  reload: () => void;
}

/** Página (1, 2, 3...) leída de la URL; cualquier valor raro (`abc`, `0`, `-2`) cuenta como la 1. */
function parsePage(raw: string | null): number {
  const page = Number(raw);
  return Number.isInteger(page) && page >= 1 ? page : 1;
}

/**
 * Estado de un listado del panel con buscador por título y paginación: lo
 * comparten las pestañas Películas y Series, que solo se diferencian en qué
 * endpoint piden y cómo pintan cada fila.
 *
 * **La búsqueda y la página viven en la URL** (`?q=blade&page=2`). Así se puede
 * recargar o compartir el listado tal cual, «Atrás» funciona, y al volver de
 * editar un título el administrador sigue en la misma página (el enlace
 * «Editar» pasa esta URL al formulario, ver `getReturnTo`). El campo de texto
 * tiene su propio estado y se vuelca a la URL con un *debounce* de 300 ms: no se
 * hace una petición por letra.
 *
 * **Peticiones sin carreras.** Cada combinación de búsqueda y página cancela la
 * petición anterior (`AbortController`) y, además, el resultado se guarda con la
 * clave que lo pidió: una respuesta antigua nunca se pinta como si fuera la
 * actual. Mientras llega la nueva página se sigue viendo la anterior.
 *
 * Si la página pedida queda fuera de rango (`?page=99`, o la última se quedó
 * vacía tras borrar), se lleva a la última que exista.
 *
 * @param fetchPage cómo pedir una página. **Debe ser estable** (una función de
 *   módulo o memorizada): forma parte de las dependencias de la carga y una
 *   función nueva en cada render la repetiría sin fin.
 * @param fallbackError mensaje si la carga falla sin un mensaje útil del servidor
 */
export function useAdminSearchList<T>(fetchPage: AdminPageFetcher<T>, fallbackError: string): AdminSearchList<T> {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = (searchParams.get('q') ?? '').trim();
  const page = parsePage(searchParams.get('page'));

  const [input, setInput] = useState(query);
  const debouncedInput = useDebouncedValue(input.trim(), ADMIN_SEARCH_DEBOUNCE_MS);
  /**
   * Última búsqueda que este hook ha llevado a la URL (o ha recibido de ella). Distingue un cambio
   * de `q` PROPIO (el debounce) de uno EXTERNO (Atrás/Adelante, la pestaña del panel): solo el externo
   * debe reescribir el campo. Sin esta marca, si la navegación del debounce llega tarde (React Router la
   * hace en una transición) mientras se sigue escribiendo, el campo volvería al texto anterior y se
   * perderían las últimas letras.
   */
  const appliedQuery = useRef(query);

  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState<ListResult<T> | null>(null);

  const requestKey = `${query}|${page}|${reloadCount}`;

  // El texto ya "asentado" pasa a la URL y vuelve a la página 1. `replace`: el historial no se llena
  // con una entrada por letra. Solo reacciona a lo que se escribe (no a cambios de la URL).
  useEffect(() => {
    if (debouncedInput === appliedQuery.current) return;
    appliedQuery.current = debouncedInput;
    setSearchParams(debouncedInput ? { q: debouncedInput } : {}, { replace: true });
  }, [debouncedInput, setSearchParams]);

  // La búsqueda ha cambiado desde fuera: el campo se pone al día.
  useEffect(() => {
    if (query === appliedQuery.current) return;
    appliedQuery.current = query;
    setInput(query);
  }, [query]);

  useEffect(() => {
    const controller = new AbortController();
    fetchPage(query, page, controller.signal)
      .then((data) => setResult({ key: requestKey, status: 'ready', page: data, errorMessage: '' }))
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        if (error instanceof ApiError && error.sessionExpired) return; // ya lo gestiona AuthProvider
        setResult({ key: requestKey, status: 'error', page: null, errorMessage: getErrorMessage(error, fallbackError) });
      });
    return () => controller.abort();
  }, [fetchPage, fallbackError, query, page, requestKey]);

  const current = result?.key === requestKey ? result : null;
  const data = current?.page ?? result?.page ?? null; // mientras carga, se sigue viendo la página anterior
  const loading = current === null;

  // Página fuera de rango: ir a la última que exista.
  useEffect(() => {
    if (current?.status !== 'ready' || !current.page) return;
    const { content, totalPages } = current.page;
    if (content.length === 0 && page > 1) {
      const last = Math.max(1, totalPages);
      setSearchParams(
        (params) => {
          const next = new URLSearchParams(params);
          if (last > 1) next.set('page', String(last));
          else next.delete('page');
          return next;
        },
        { replace: true },
      );
    }
  }, [current, page, setSearchParams]);

  /** Cambia de página conservando la búsqueda (sí deja entrada en el historial: «Atrás» vuelve a la anterior). */
  const goToPage = useCallback(
    (next: number) => {
      setSearchParams((params) => {
        const updated = new URLSearchParams(params);
        if (next > 1) updated.set('page', String(next));
        else updated.delete('page');
        return updated;
      });
    },
    [setSearchParams],
  );

  const clearSearch = useCallback(() => {
    setInput('');
    setSearchParams({}, { replace: true });
  }, [setSearchParams]);

  const reload = useCallback(() => setReloadCount((n) => n + 1), []);

  return {
    query,
    page,
    input,
    setInput,
    searching: input.trim() !== query || (loading && data !== null),
    data,
    loading,
    errorMessage: current?.status === 'error' ? current.errorMessage : null,
    pendingPageFix: data !== null && data.content.length === 0 && data.totalElements > 0 && page > 1,
    goToPage,
    clearSearch,
    reload,
  };
}
