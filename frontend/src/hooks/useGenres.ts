import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch, getErrorMessage } from '../lib/api';
import { queryKeys } from '../lib/queryKeys';
import type { Genre } from '../lib/types';

/** Estado de la carga de géneros. */
export type GenresStatus = 'loading' | 'ready' | 'error';

/** Lista vacía compartida mientras no han llegado los géneros (misma identidad en cada render). */
const NO_GENRES: Genre[] = [];

/**
 * Ordena los géneros por nombre como lo haría una persona en español
 * (`localeCompare('es')`: la «Á» va con la «A», no después de la «Z»).
 *
 * `GET /api/genres` no garantiza ningún orden, y tras crear o renombrar uno en
 * el cliente hay que recolocarlo: así la lista no "salta" de forma distinta
 * según de dónde venga el dato.
 */
export function sortGenres(genres: readonly Genre[]): Genre[] {
  return [...genres].sort((a, b) => a.name.localeCompare(b.name, 'es', { sensitivity: 'base' }));
}

/** Pide la lista de géneros (sin ordenar: el orden lo pone `select` en cada pantalla). */
function fetchGenres(signal: AbortSignal): Promise<Genre[]> {
  return apiFetch<Genre[]>('/genres', { signal });
}

/**
 * Lista de géneros (`GET /api/genres`), ordenada por nombre.
 *
 * La usan la barra de filtros de `/peliculas`, los formularios de película y de
 * serie y la pestaña Géneros del panel. Antes cada una la pedía por su cuenta;
 * ahora es UNA entrada de la caché (`queryKeys.genres.all`): pasar de una
 * pantalla a otra no repite la petición, y cuando la pestaña Géneros crea,
 * renombra o borra uno (con `setQueryData`), todas ven el cambio.
 *
 * El orden se aplica con `select` (al leer), no al guardar: así da igual quién
 * escriba en la caché, la lista siempre sale ordenada.
 *
 * - `status`: `error` si la última carga falló (aunque quede una lista anterior),
 *   `loading` mientras no hay lista (también al reintentar tras un error) y `ready`.
 * - `loaded`: si alguna carga ha ido bien, para seguir enseñando la lista
 *   mientras se recarga o si un refresco falla.
 */
export function useGenres(): {
  genres: Genre[];
  status: GenresStatus;
  loaded: boolean;
  errorMessage: string;
  reload: () => void;
} {
  const query = useQuery({
    queryKey: queryKeys.genres.all,
    queryFn: ({ signal }) => fetchGenres(signal),
    select: sortGenres,
  });
  const { refetch } = query;
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const failed = query.isError && !query.isFetching;
  let status: GenresStatus = 'loading';
  if (failed) status = 'error';
  else if (query.data !== undefined) status = 'ready';

  return {
    genres: query.data ?? NO_GENRES,
    status,
    loaded: query.data !== undefined,
    errorMessage: failed ? getErrorMessage(query.error, 'No se pudieron cargar los géneros.') : '',
    reload,
  };
}
