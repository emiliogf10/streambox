import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { QueryKey, UseQueryResult } from '@tanstack/react-query';
import { apiFetch, getErrorMessage } from '../lib/api';
import type { CatalogKind } from '../lib/catalogFilters';
import { queryKeys } from '../lib/queryKeys';
import type { AdminGenre, Genre } from '../lib/types';

/** Estado de la carga de géneros. */
export type GenresStatus = 'loading' | 'ready' | 'error';

/** Lista vacía compartida mientras no han llegado los géneros (misma identidad en cada render). */
const NO_GENRES: Genre[] = [];

/**
 * Ordena los géneros por nombre como lo haría una persona en español
 * (`localeCompare('es')`: la «Á» va con la «A», no después de la «Z»).
 *
 * El servidor ya los da ordenados, pero tras crear o renombrar uno en el
 * cliente hay que recolocarlo: así la lista no "salta" de forma distinta según
 * de dónde venga el dato. Conserva el tipo (sirve igual para {@link AdminGenre}).
 */
export function sortGenres<T extends Genre>(genres: readonly T[]): T[] {
  return [...genres].sort((a, b) => a.name.localeCompare(b.name, 'es', { sensitivity: 'base' }));
}

/** Lo que devuelven {@link useGenres} y {@link useAdminGenres}. */
export interface GenresState<T extends Genre> {
  genres: T[];
  status: GenresStatus;
  /** Si alguna carga ha ido bien (para seguir enseñando la lista mientras se recarga o si un refresco falla). */
  loaded: boolean;
  errorMessage: string;
  reload: () => void;
}

/**
 * Traduce una consulta de géneros al estado que usan las pantallas.
 * - `status`: `error` si la última carga falló (aunque quede una lista anterior),
 *   `loading` mientras no hay lista (también al reintentar tras un error) y `ready`.
 */
function useGenresState<T extends Genre>(query: UseQueryResult<T[]>): GenresState<T> {
  const { refetch } = query;
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const failed = query.isError && !query.isFetching;
  let status: GenresStatus = 'loading';
  if (failed) status = 'error';
  else if (query.data !== undefined) status = 'ready';

  return {
    genres: query.data ?? (NO_GENRES as T[]),
    status,
    loaded: query.data !== undefined,
    errorMessage: failed ? getErrorMessage(query.error, 'No se pudieron cargar los géneros.') : '',
    reload,
  };
}

/** Opciones de {@link useGenres}. */
export interface UseGenresOptions {
  /**
   * Solo los géneros con algún título visible de ese catálogo
   * (`GET /api/genres?withTitles=movies|series`): con alguna película, o con
   * alguna serie que tenga episodios. Sin la opción, todos (`GET /api/genres`).
   */
  withTitles?: CatalogKind;
  /** `false` para no pedirla todavía (p. ej. solo hace falta en un caso raro). Por defecto, `true`. */
  enabled?: boolean;
}

/**
 * Lista de géneros, ordenada por nombre. Dos variantes, cada una con su
 * entrada en la caché (`lib/queryKeys.ts`):
 *
 * - **Todos** (`GET /api/genres`, `queryKeys.genres.all`): los formularios de
 *   película y de serie del panel, que pueden asignar cualquiera.
 * - **Con títulos** (`withTitles`, `queryKeys.genres.withTitles(kind)`): las
 *   barras de filtros de `/peliculas` y `/series` (D-9). Ofrecer «Terror» sin
 *   ninguna película solo llevaba a «No hay películas con estos filtros».
 *
 * Pasar de una pantalla a otra no repite la petición, y las escrituras del
 * panel las ponen al día (`keysAffectedBy`, y la pestaña Géneros con
 * `setQueryData`).
 *
 * El orden se aplica con `select` (al leer), no al guardar: así da igual quién
 * escriba en la caché, la lista siempre sale ordenada.
 */
export function useGenres({ withTitles, enabled = true }: UseGenresOptions = {}): GenresState<Genre> {
  const queryKey: QueryKey = withTitles ? queryKeys.genres.withTitles(withTitles) : queryKeys.genres.all;
  const path = withTitles ? `/genres?withTitles=${withTitles}` : '/genres';
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => apiFetch<Genre[]>(path, { signal }),
    select: sortGenres<Genre>,
    enabled,
  });
  return useGenresState(query);
}

/**
 * Géneros con su uso para la pestaña Géneros del panel (`GET /api/admin/genres`,
 * solo ADMIN; `queryKeys.genres.admin`): incluye los que no usa nadie y, de
 * cada uno, cuántas películas y series lo tienen (D-4). Con eso la pestaña dice
 * ANTES de intentarlo si se puede borrar, en vez de enterarse por el 409.
 */
export function useAdminGenres(): GenresState<AdminGenre> {
  const query = useQuery({
    queryKey: queryKeys.genres.admin,
    queryFn: ({ signal }) => apiFetch<AdminGenre[]>('/admin/genres', { signal }),
    select: sortGenres<AdminGenre>,
  });
  return useGenresState(query);
}
