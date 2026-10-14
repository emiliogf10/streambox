import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { queryKeys } from '../lib/queryKeys';
import type { Movie } from '../lib/types';
import { parseRouteId } from '../lib/utils';

/**
 * Estado del detalle de una película. `not-found` es distinto de `error`: un 404
 * no se arregla reintentando (la película no existe o se ha borrado), así que la
 * pantalla no ofrece «Reintentar» sino volver al catálogo.
 */
export type MovieDetailStatus = 'loading' | 'ready' | 'not-found' | 'error';

/** ¿Es el 404 con el que la API dice «esa película no existe»? */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** ¿Tiene `value` la forma de una página del servidor (`{ content: [...] }`)? */
function hasContent(value: unknown): value is { content: unknown[] } {
  return typeof value === 'object' && value !== null && Array.isArray((value as { content?: unknown }).content);
}

/** Saca las películas de un dato de la caché: un listado infinito (`pages`), una página suelta o «Mi lista». */
function moviesIn(data: unknown): unknown[] {
  if (typeof data !== 'object' || data === null) return [];
  if ('pages' in data && Array.isArray(data.pages)) return data.pages.flatMap((page) => (hasContent(page) ? page.content : []));
  if (hasContent(data)) return data.content;
  if ('movie' in data && Array.isArray(data.movie)) return data.movie;
  return [];
}

/**
 * Busca la película `id` en lo que la caché YA tiene de otras pantallas: los
 * listados de películas (portada, `/peliculas` y sus filtros, el panel) y «Mi lista».
 *
 * **Para qué.** Quien llega a la página desde una tarjeta acaba de ver esa
 * película en un listado, y el listado trae los mismos datos que el detalle
 * (`MovieResponse`: título, sinopsis, vídeo, géneros...). Usarla como
 * `placeholderData` pinta la página al instante, sin un esqueleto que parpadea
 * medio segundo, mientras se pide la versión buena al servidor.
 *
 * **Por qué `placeholderData` y no `initialData`.** Lo provisional NO se guarda
 * en la caché como si fuera el detalle: la petición se hace siempre y, si
 * responde 404 (alguien la borró desde que se cargó el listado), la página pasa
 * a «no encontrada» en vez de seguir enseñando la copia vieja.
 *
 * Las entradas de la caché tienen formas distintas según quién las guardó; se
 * leen con comprobaciones de forma (`moviesIn`) en lugar de suponerlas. Solo se
 * mira la raíz `movies` y «Mi lista» (de las series no puede salir una película).
 *
 * @param queryClient la caché de la aplicación
 * @param id id de la película buscada
 * @returns la película, o `undefined` si ninguna pantalla la ha cargado
 */
export function findCachedMovie(queryClient: QueryClient, id: number): Movie | undefined {
  const entries = [
    ...queryClient.getQueriesData({ queryKey: queryKeys.movies.all }),
    ...queryClient.getQueriesData({ queryKey: queryKeys.favorites.all }),
  ];
  for (const [, data] of entries) {
    const found = moviesIn(data).find(
      (item): item is Movie => typeof item === 'object' && item !== null && (item as { id?: unknown }).id === id,
    );
    if (found) return found;
  }
  return undefined;
}

/**
 * Carga el detalle de una película (`GET /api/movies/{id}`) para la página
 * `/peliculas/:id`, guardado en la caché bajo `queryKeys.movies.detail(id)`.
 * Mismo comportamiento que `useSeriesDetail` (y por eso devuelve lo mismo):
 *
 * - **404 → `not-found`**, también si llega en un refresco: si un administrador
 *   borra la película que está en pantalla, la invalidación del panel
 *   (`keysAffectedBy('movies')`) vuelve a pedirla y el 404 manda sobre la copia
 *   de la caché. Un id mal formado en la URL es `not-found` sin petición. Un 404
 *   no se reintenta (ver `lib/queryClient.ts`).
 * - **Pintado inmediato** si la película ya estaba en un listado de la caché
 *   (ver {@link findCachedMovie}).
 * - **Cambio de película sin datos viejos**: cada id es una clave distinta, así
 *   que nunca se enseña la película anterior con la URL nueva.
 *
 * @param rawId el parámetro `:id` de la ruta, tal cual
 */
export function useMovieDetail(rawId: string | undefined) {
  const movieId = parseRouteId(rawId);
  const queryClient = useQueryClient();
  const detail = useQuery({
    // Sin id válido la consulta está desactivada; la clave da igual (nunca se pide).
    queryKey: queryKeys.movies.detail(movieId ?? 0),
    queryFn: ({ signal }) => apiFetch<Movie>(`/movies/${movieId}`, { signal }),
    enabled: movieId !== null,
    placeholderData: () => (movieId === null ? undefined : findCachedMovie(queryClient, movieId)),
  });
  const { refetch } = detail;

  /** Reintenta la carga (botón «Reintentar» del estado de error). */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const settledError = detail.isError && !detail.isFetching ? detail.error : null;
  if (movieId === null || isNotFound(settledError)) {
    return { status: 'not-found' as const, movie: null, errorMessage: '', reload };
  }
  if (detail.data !== undefined) return { status: 'ready' as const, movie: detail.data, errorMessage: '', reload };
  if (settledError) return { status: 'error' as const, movie: null, errorMessage: getErrorMessage(settledError), reload };
  return { status: 'loading' as const, movie: null, errorMessage: '', reload };
}
