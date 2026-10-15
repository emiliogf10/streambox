import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { queryKeys } from '../lib/queryKeys';
import type { Movie } from '../lib/types';

/**
 * Cuántas películas similares se piden. Doce llenan la fila en una pantalla de
 * 1920 px (con flechas para el resto en las más estrechas) sin convertirla en
 * otro catálogo; el servidor admite de 1 a 24.
 */
export const SIMILAR_MOVIES_LIMIT = 12;

/**
 * Estado de la fila «Películas similares»:
 * - `loading`: aún no hay respuesta (o se espera a que la película se confirme).
 * - `ready`: llegó la lista (puede estar vacía: entonces la fila no se pinta).
 * - `hidden`: no hay nada que enseñar ni que reintentar (404: la película ya no
 *   existe; su página pasará a «no encontrada» por su cuenta).
 * - `error`: fallo que puede arreglarse reintentando (red, 5xx, 429...).
 */
export type SimilarMoviesStatus = 'loading' | 'ready' | 'hidden' | 'error';

/**
 * Pide las películas parecidas a una (`GET /api/movies/{id}/similar?limit=12`),
 * ya ordenadas por el servidor (más géneros en común primero) y sin la propia
 * película. Se guardan en `queryKeys.movies.similar(id, limit)`, bajo la raíz
 * `movies`: editar o borrar una película, o renombrar un género, desde el panel
 * las vuelve a pedir (`keysAffectedBy`).
 *
 * - **Solo con la película confirmada** (`enabled`): si su página es un 404 no
 *   se pide nada; y mientras se enseña la copia provisional de un listado, se
 *   espera a que el servidor diga que existe.
 * - **Sin datos de la película anterior al cambiar de película**: cada id es una
 *   clave distinta y no se usa `placeholderData`, porque las similares de otra
 *   película (que pueden incluir la que ahora está en pantalla) serían falsas.
 * - Reintentos: los de la aplicación (`lib/queryClient.ts`), uno y solo en red/5xx.
 *
 * @param movieId id de la película de la página
 * @param enabled `true` cuando la película se ha cargado bien
 */
export function useSimilarMovies(movieId: number, enabled: boolean) {
  const similar = useQuery({
    queryKey: queryKeys.movies.similar(movieId, SIMILAR_MOVIES_LIMIT),
    queryFn: ({ signal }) => apiFetch<Movie[]>(`/movies/${movieId}/similar?limit=${SIMILAR_MOVIES_LIMIT}`, { signal }),
    enabled,
  });
  const { refetch } = similar;

  /** Vuelve a pedir la fila (botón «Reintentar» de su aviso de error). */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  // Con datos ya cargados, un refresco fallido no los tapa: se siguen enseñando.
  if (similar.data !== undefined) return { status: 'ready' as const, movies: similar.data, errorMessage: '', reload };
  const settledError = similar.isError && !similar.isFetching ? similar.error : null;
  if (settledError instanceof ApiError && settledError.status === 404) {
    return { status: 'hidden' as const, movies: [], errorMessage: '', reload };
  }
  if (settledError) return { status: 'error' as const, movies: [], errorMessage: getErrorMessage(settledError), reload };
  return { status: 'loading' as const, movies: [], errorMessage: '', reload };
}
