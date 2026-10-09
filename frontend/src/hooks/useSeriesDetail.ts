import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { queryKeys } from '../lib/queryKeys';
import type { SeriesDetail } from '../lib/types';

/**
 * Estado del detalle de una serie. `not-found` es distinto de `error`: un 404
 * no se arregla reintentando (la serie no existe o no tiene episodios), así que
 * la pantalla no ofrece "Reintentar" sino volver al listado.
 */
export type SeriesDetailStatus = 'loading' | 'ready' | 'not-found' | 'error';

/**
 * Convierte el `:id` de la URL en un id válido (entero positivo) o `null`.
 * `/series/abc` o `/series/1.5` no llegan a pedir nada: son "no encontrada".
 */
function parseSeriesId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** ¿Es el 404 con el que la API dice «no existe o no tiene episodios»? */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * Carga el detalle de una serie (`GET /api/series/{id}`) con sus temporadas y
 * episodios, guardado en la caché bajo `queryKeys.series.detail(id)`.
 *
 * - **404 → `not-found`**: la API responde 404 tanto si la serie no existe como
 *   si no tiene episodios (no revela que exista). Un id mal formado en la URL
 *   se trata igual, sin petición. Un 404 no se reintenta (ver `lib/queryClient.ts`).
 *   Si llega en un refresco (un administrador acaba de borrarla o de quitarle
 *   el último episodio), también manda sobre los datos que hubiera en la caché.
 * - **Cambio de serie sin datos viejos**: cada id es una clave distinta, así que
 *   al navegar de una serie a otra la pantalla pasa a «cargando» en el mismo
 *   render y nunca enseña la serie anterior con la URL nueva; la petición de la
 *   anterior se cancela al quedarse sin pantalla.
 * - **Volver a una serie ya vista** la pinta al instante desde la caché.
 *
 * @param rawId el parámetro `:id` de la ruta, tal cual
 */
export function useSeriesDetail(rawId: string | undefined) {
  const seriesId = parseSeriesId(rawId);
  const detail = useQuery({
    // Sin id válido la consulta está desactivada; la clave da igual (nunca se pide).
    queryKey: queryKeys.series.detail(seriesId ?? 0),
    queryFn: ({ signal }) => apiFetch<SeriesDetail>(`/series/${seriesId}`, { signal }),
    enabled: seriesId !== null,
  });
  const { refetch } = detail;

  /** Reintenta la carga (botón "Reintentar" del estado de error). */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const settledError = detail.isError && !detail.isFetching ? detail.error : null;
  if (seriesId === null || isNotFound(settledError)) {
    return { status: 'not-found' as const, series: null, errorMessage: '', reload };
  }
  if (detail.data !== undefined) return { status: 'ready' as const, series: detail.data, errorMessage: '', reload };
  if (settledError) return { status: 'error' as const, series: null, errorMessage: getErrorMessage(settledError), reload };
  return { status: 'loading' as const, series: null, errorMessage: '', reload };
}
