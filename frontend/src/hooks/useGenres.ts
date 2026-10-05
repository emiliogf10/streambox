import { useCallback, useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import type { Genre } from '../lib/types';

/** Estado de la carga de géneros. */
export type GenresStatus = 'loading' | 'ready' | 'error';

/** Resultado de una carga, junto con el intento que la originó (ver {@link useGenres}). */
interface GenresResult {
  attempt: number;
  status: 'ready' | 'error';
  errorMessage: string;
}

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

/**
 * Carga la lista de géneros (`GET /api/genres`), ordenada por nombre.
 *
 * La usan el formulario de película (casillas de géneros) y la pestaña de
 * géneros del panel. Esta última además modifica la lista en el cliente tras
 * crear, renombrar o borrar, por eso se expone `setGenres`.
 *
 * **Sin estados mezclados.** El resultado se guarda junto al número de intento
 * que lo pidió y `status` se DERIVA comparándolo con el intento actual (el mismo
 * patrón que `AuthContext`): al pulsar "Reintentar" el estado pasa a `loading`
 * sin tener que reiniciarlo a mano dentro del efecto. `loaded` indica si alguna
 * carga ha ido bien, para poder seguir enseñando la lista mientras se recarga.
 */
export function useGenres(): {
  genres: Genre[];
  setGenres: Dispatch<SetStateAction<Genre[]>>;
  status: GenresStatus;
  loaded: boolean;
  errorMessage: string;
  reload: () => void;
} {
  const [genres, setGenres] = useState<Genre[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<GenresResult | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    apiFetch<Genre[]>('/genres', { signal: controller.signal })
      .then((list) => {
        setGenres(sortGenres(list));
        setLoaded(true);
        setResult({ attempt, status: 'ready', errorMessage: '' });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        setResult({ attempt, status: 'error', errorMessage: getErrorMessage(error, 'No se pudieron cargar los géneros.') });
      });
    return () => controller.abort();
  }, [attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const current = result?.attempt === attempt ? result : null;

  return {
    genres,
    setGenres,
    status: current?.status ?? 'loading',
    loaded,
    errorMessage: current?.errorMessage ?? '',
    reload,
  };
}
