import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import type { CatalogKind } from '../lib/catalogFilters';
import { queryKeys } from '../lib/queryKeys';
import type { Movie, Series } from '../lib/types';

/**
 * Cuántos títulos similares se piden. Doce llenan la fila en una pantalla de
 * 1920 px (con flechas para el resto en las más estrechas) sin convertirla en
 * otro catálogo; el servidor admite de 1 a 24 (películas y series).
 */
export const SIMILAR_TITLES_LIMIT = 12;

/** Qué devuelve `/{kind}/{id}/similar` para cada tipo de catálogo: la misma forma que sus listados. */
export interface SimilarTitleOf {
  movies: Movie;
  series: Series;
}

/**
 * Estado de una fila de similares («Películas similares», «Series similares»):
 * - `loading`: aún no hay respuesta (o se espera a que el título se confirme).
 * - `ready`: llegó la lista (puede estar vacía: entonces la fila no se pinta).
 * - `hidden`: no hay nada que enseñar ni que reintentar (404: el título ya no
 *   existe, o la serie se ha quedado sin episodios; su página pasará a «no
 *   encontrada» por su cuenta).
 * - `error`: fallo que puede arreglarse reintentando (red, 5xx, 429...).
 */
export type SimilarTitlesStatus = 'loading' | 'ready' | 'hidden' | 'error';

/**
 * Pide los títulos parecidos a uno (`GET /api/movies/{id}/similar?limit=12` o
 * `GET /api/series/{id}/similar?limit=12`), ya ordenados por el servidor (más
 * géneros en común primero) y sin el propio título. Las dos rutas tienen el
 * mismo contrato (un array, 404 si el título no existe o no es visible), así
 * que un solo hook sirve a las dos páginas de detalle: lo único que cambia es
 * el tipo (`kind`), que da la ruta, la clave de la caché y el tipo de los datos.
 *
 * Se guardan en `queryKeys.movies.similar(id, limit)` o
 * `queryKeys.series.similar(id, limit)`, bajo la raíz de su catálogo: editar o
 * borrar un título (o, en series, sus episodios), o renombrar un género, desde
 * el panel las vuelve a pedir (`keysAffectedBy`).
 *
 * - **Solo con el título confirmado** (`enabled`): si su página es un 404 no se
 *   pide nada; y mientras se enseña la copia provisional de un listado (solo
 *   películas), se espera a que el servidor diga que existe.
 * - **Sin datos del título anterior al cambiar de título**: cada id es una
 *   clave distinta y no se usa `placeholderData`, porque los similares de otro
 *   título (que pueden incluir el que ahora está en pantalla) serían falsos.
 * - Reintentos: los de la aplicación (`lib/queryClient.ts`), uno y solo en red/5xx.
 *
 * @param kind catálogo del título (`movies` o `series`), igual que su ruta en la API
 * @param id id del título de la página
 * @param enabled `true` cuando el servidor ha confirmado el título
 */
export function useSimilarTitles<K extends CatalogKind>(kind: K, id: number, enabled: boolean) {
  const similar = useQuery({
    queryKey: queryKeys[kind].similar(id, SIMILAR_TITLES_LIMIT),
    queryFn: ({ signal }) => apiFetch<SimilarTitleOf[K][]>(`/${kind}/${id}/similar?limit=${SIMILAR_TITLES_LIMIT}`, { signal }),
    enabled,
  });
  const { refetch } = similar;

  /** Vuelve a pedir la fila (botón «Reintentar» de su aviso de error). */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const none: SimilarTitleOf[K][] = [];
  // Con datos ya cargados, un refresco fallido no los tapa: se siguen enseñando.
  if (similar.data !== undefined) return { status: 'ready' as const, items: similar.data, errorMessage: '', reload };
  const settledError = similar.isError && !similar.isFetching ? similar.error : null;
  if (settledError instanceof ApiError && settledError.status === 404) {
    return { status: 'hidden' as const, items: none, errorMessage: '', reload };
  }
  if (settledError) return { status: 'error' as const, items: none, errorMessage: getErrorMessage(settledError), reload };
  return { status: 'loading' as const, items: none, errorMessage: '', reload };
}
