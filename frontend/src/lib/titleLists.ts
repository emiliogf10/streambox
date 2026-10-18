/**
 * Listas personales de títulos que llegan en DOS peticiones (una de películas y
 * otra de series) pero se enseñan como una sola: «Mi lista» (`FavoritesContext`)
 * y los «Me gusta» (`useLikes`). Las dos comparten la misma regla de carga, así
 * que vive aquí una sola vez.
 */
import { apiFetch } from './api';
import type { Movie, Series } from './types';

/**
 * Tipo de título de una lista personal. Películas y series son tablas distintas
 * en el servidor y sus ids pueden coincidir (la película 3 y la serie 3 son
 * cosas distintas): todo lo que identifica un elemento va con su tipo.
 */
export type TitleKind = 'movie' | 'series';

/** Las dos listas guardadas juntas: así un cambio toca solo la de su tipo sin pisar la otra. */
export interface TitleLists {
  movie: Movie[];
  series: Series[];
}

/** Endpoints de lectura de cada lista (relativos a `/api`). */
export type TitleListEndpoints = Record<TitleKind, string>;

/**
 * Pide las DOS listas a la vez. Si falla una, se cancela la otra (ya no
 * interesa: la lista solo está «lista» con las dos; enseñar solo la mitad haría
 * creer al usuario que perdió la otra). El `signal` de TanStack cancela ambas si
 * ya nadie las espera (p. ej. al cerrar sesión).
 *
 * @param endpoints `GET` de cada lista
 * @param signal cancelación de TanStack Query
 */
export async function fetchTitleLists(endpoints: TitleListEndpoints, signal: AbortSignal): Promise<TitleLists> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener('abort', abort);
  try {
    const [movie, series] = await Promise.all([
      apiFetch<Movie[]>(endpoints.movie, { signal: controller.signal }),
      apiFetch<Series[]>(endpoints.series, { signal: controller.signal }),
    ]);
    return { movie, series };
  } catch (error) {
    controller.abort();
    throw error;
  } finally {
    signal.removeEventListener('abort', abort);
  }
}
