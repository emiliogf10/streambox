import { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import type { SeriesDetail } from '../lib/types';

/**
 * Estado del detalle de una serie. `not-found` es distinto de `error`: un 404
 * no se arregla reintentando (la serie no existe o no tiene episodios), así que
 * la pantalla no ofrece "Reintentar" sino volver al listado.
 */
export type SeriesDetailStatus = 'loading' | 'ready' | 'not-found' | 'error';

/** Resultado de una carga, guardado junto a la clave (id + intento) que lo pidió. */
interface LoadResult {
  key: string;
  status: Exclude<SeriesDetailStatus, 'loading'>;
  series: SeriesDetail | null;
  errorMessage: string;
}

/**
 * Convierte el `:id` de la URL en un id válido (entero positivo) o `null`.
 * `/series/abc` o `/series/1.5` no llegan a pedir nada: son "no encontrada".
 */
function parseSeriesId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * Carga el detalle de una serie (`GET /api/series/{id}`) con sus temporadas y episodios.
 *
 * - **404 → `not-found`**: la API responde 404 tanto si la serie no existe como
 *   si no tiene episodios (no revela que exista). Un id mal formado en la URL
 *   se trata igual, sin petición.
 * - **Cambio de serie sin datos viejos**: el resultado se guarda junto a la
 *   clave que lo pidió (id + número de intento) y el estado se DERIVA
 *   comparándola con la actual. Así, al navegar de una serie a otra, la
 *   pantalla pasa a "cargando" en el mismo render y nunca enseña un instante la
 *   serie anterior con la URL nueva. Es el mismo enfoque que `AuthContext` con
 *   el usuario actual.
 * - Cancela la petición en vuelo al cambiar de serie o salir (`AbortController`).
 *
 * @param rawId el parámetro `:id` de la ruta, tal cual
 */
export function useSeriesDetail(rawId: string | undefined) {
  const seriesId = parseSeriesId(rawId);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<LoadResult | null>(null);
  const key = `${seriesId}:${attempt}`;

  useEffect(() => {
    if (seriesId === null) return;
    const controller = new AbortController();
    apiFetch<SeriesDetail>(`/series/${seriesId}`, { signal: controller.signal })
      .then((series) => setResult({ key, status: 'ready', series, errorMessage: '' }))
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        if (error instanceof ApiError && error.status === 404) {
          setResult({ key, status: 'not-found', series: null, errorMessage: '' });
        } else {
          setResult({ key, status: 'error', series: null, errorMessage: getErrorMessage(error) });
        }
      });
    return () => controller.abort();
  }, [key, seriesId]);

  /** Reintenta la carga (botón "Reintentar" del estado de error). */
  const reload = useCallback(() => setAttempt((value) => value + 1), []);

  if (seriesId === null) return { status: 'not-found' as const, series: null, errorMessage: '', reload };
  if (result?.key !== key) return { status: 'loading' as const, series: null, errorMessage: '', reload };
  return { status: result.status, series: result.series, errorMessage: result.errorMessage, reload };
}
