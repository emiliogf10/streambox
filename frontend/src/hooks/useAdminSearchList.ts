import { useCallback, useEffect, useRef, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ApiError, getErrorMessage } from '../lib/api';
import type { PageResponse } from '../lib/types';
import { useDebouncedValue } from './useDebouncedValue';

/** Espera tras la última tecla antes de buscar (igual que el buscador de la barra). */
export const ADMIN_SEARCH_DEBOUNCE_MS = 300;

/**
 * Pide una página del listado. La página llega empezando en 1 (la de la URL);
 * quien la implementa la pasa a la API, que cuenta desde 0.
 */
export type AdminPageFetcher<T> = (query: string, page: number, signal: AbortSignal) => Promise<PageResponse<T>>;

/** Clave de la caché de una búsqueda y página (`queryKeys.movies.admin`, `queryKeys.series.admin`). */
export type AdminListKey = (query: string, page: number) => QueryKey;

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
 * **Caché sin carreras (TanStack Query).** Cada combinación de búsqueda y página
 * es una entrada de la caché (`queryKey`): una respuesta antigua nunca se pinta
 * como si fuera la actual, la petición de la combinación anterior se cancela al
 * dejar de usarse, y volver a una página ya vista (o «Atrás») la enseña al
 * instante. Mientras llega la nueva página se sigue viendo la anterior
 * (`keepPreviousData`). Las claves cuelgan de la raíz `movies` o `series`, así
 * que un alta, edición o borrado del panel (que invalida esa raíz) refresca el
 * listado solo.
 *
 * Si la página pedida queda fuera de rango (`?page=99`, o la última se quedó
 * vacía tras borrar), se lleva a la última que exista.
 *
 * @param queryKey clave de la caché de cada búsqueda y página (p. ej. `queryKeys.movies.admin`)
 * @param fetchPage cómo pedir una página (con el `signal` de TanStack para poder cancelarla)
 * @param fallbackError mensaje si la carga falla sin un mensaje útil del servidor
 */
export function useAdminSearchList<T>(
  queryKey: AdminListKey,
  fetchPage: AdminPageFetcher<T>,
  fallbackError: string,
): AdminSearchList<T> {
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

  const list = useQuery({
    queryKey: queryKey(query, page),
    queryFn: ({ signal }) => fetchPage(query, page, signal),
    // Mientras llega otra página u otra búsqueda se sigue viendo la anterior (atenuada por la pantalla).
    placeholderData: keepPreviousData,
  });
  const { refetch } = list;

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

  // Una sesión caducada ya la gestiona AuthProvider (aviso y salida al login): no se pinta como error del listado.
  const sessionExpired = list.error instanceof ApiError && list.error.sessionExpired;
  const failed = list.isError && !list.isFetching && !sessionExpired;
  /** Datos de la búsqueda y página ACTUALES (no los de la anterior que se enseñan mientras tanto). */
  const current = list.isPlaceholderData ? undefined : list.data;
  const data = list.data ?? null;
  // Cargando: falta la respuesta de la búsqueda/página actual, o se está refrescando (tras borrar, «Reintentar»).
  const loading = !failed && (current === undefined || list.isFetching);

  // Página fuera de rango: ir a la última que exista.
  useEffect(() => {
    if (!current) return;
    const { content, totalPages } = current;
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
  }, [setInput, setSearchParams]);

  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  return {
    query,
    page,
    input,
    setInput,
    searching: input.trim() !== query || (loading && data !== null),
    data,
    loading,
    errorMessage: failed ? getErrorMessage(list.error, fallbackError) : null,
    pendingPageFix: data !== null && data.content.length === 0 && data.totalElements > 0 && page > 1,
    goToPage,
    clearSearch,
    reload,
  };
}
