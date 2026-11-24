import { useCallback } from 'react';
import { usePrefetchQuery, useQuery, useQueryClient, queryOptions } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { useToast } from '../context/ToastContext';
import { apiFetch, currentSessionKey, getErrorMessage } from '../lib/api';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import type {
  ContinueWatchingItem,
  EpisodeProgress,
  WatchHistoryItem,
  WatchProgress,
  WatchStats,
} from '../lib/types';

/**
 * Cuántos elementos pide la fila «Seguir viendo». Doce, como las demás filas de
 * la portada: llenan una pantalla de 1920 px y quien tiene más a medias no
 * necesita ver los más antiguos en la portada. El servidor admite de 1 a 24.
 */
export const CONTINUE_WATCHING_LIMIT = 12;

/** Lo que devuelve un hook de progreso de un título: el progreso (o `null`) y cómo va la carga. */
export interface TitleProgressValue {
  /** Progreso guardado; `null` si no hay (204) o si aún no ha llegado. */
  progress: WatchProgress | null;
  status: 'loading' | 'ready' | 'error';
}

/**
 * `GET` de un progreso que puede no existir: el 204 llega como `undefined`
 * (`apiFetch`) y se guarda como `null`, porque TanStack Query no admite
 * `undefined` como dato (lo trataría como «aún no hay respuesta»).
 */
async function fetchProgress(path: string, signal: AbortSignal): Promise<WatchProgress | null> {
  return (await apiFetch<WatchProgress | undefined>(path, { signal })) ?? null;
}

/**
 * Progreso del usuario en una película (`GET /api/users/me/progress/movies/{id}`).
 * Lo usan su página («Reanudar» y la barra) y el reproductor (desde dónde empezar).
 *
 * @param movieId id de la película, o `null` si la ruta no traía uno válido
 * @param enabled `false` si no tiene sentido preguntar (p. ej. la película no tiene un vídeo que se vea aquí)
 */
export function useMovieProgress(movieId: number | null, enabled = true): TitleProgressValue {
  const query = useQuery({
    queryKey: queryKeys.progress.movie(movieId ?? 0),
    queryFn: ({ signal }) => fetchProgress(`/users/me/progress/movies/${movieId}`, signal),
    enabled: enabled && movieId !== null,
  });
  return { progress: query.data ?? null, status: loadStatusOf(query) };
}

/** Progreso del usuario en un episodio (`GET /api/users/me/progress/episodes/{id}`), para el reproductor. */
export function useEpisodeProgress(episodeId: number | null, enabled = true): TitleProgressValue {
  const query = useQuery({
    queryKey: queryKeys.progress.episode(episodeId ?? 0),
    queryFn: ({ signal }) => fetchProgress(`/users/me/progress/episodes/${episodeId}`, signal),
    enabled: enabled && episodeId !== null,
  });
  return { progress: query.data ?? null, status: loadStatusOf(query) };
}

/**
 * Progreso de los episodios empezados de una serie
 * (`GET /api/users/me/progress/series/{id}`, lista vacía si ninguno). Lo usa la
 * página de la serie: barras por episodio y a qué episodio lleva su botón.
 *
 * Mientras carga (o si falla) es `undefined` y la página se pinta como sin
 * progreso: el botón «Empezar a ver» es verdad hasta que se sabe otra cosa, y
 * un fallo aquí no debe tapar la serie entera (las barras son un extra).
 */
export function useSeriesProgress(seriesId: number, enabled = true): EpisodeProgress[] | undefined {
  const query = useQuery({
    queryKey: queryKeys.progress.series(seriesId),
    queryFn: ({ signal }) => apiFetch<EpisodeProgress[]>(`/users/me/progress/series/${seriesId}`, { signal }),
    enabled,
  });
  return query.data;
}

/** Opciones de la consulta de «Seguir viendo», comunes a la fila y a su precarga. */
function continueWatchingQuery() {
  return queryOptions({
    queryKey: queryKeys.progress.continueWatching(CONTINUE_WATCHING_LIMIT),
    queryFn: ({ signal }) =>
      apiFetch<ContinueWatchingItem[]>('/users/me/continue-watching', {
        params: { limit: CONTINUE_WATCHING_LIMIT },
        signal,
      }),
  });
}

/**
 * Pide «Seguir viendo» YA, en paralelo con el catálogo (como las
 * recomendaciones, `usePrefetchRecommendations`): la fila va encima de las
 * demás y, si llegara después, las empujaría hacia abajo al aparecer.
 */
export function usePrefetchContinueWatching(): void {
  usePrefetchQuery(continueWatchingQuery());
}

/** Lo que devuelve {@link useContinueWatching}. */
export interface ContinueWatchingValue {
  items: ContinueWatchingItem[];
  status: 'loading' | 'ready' | 'error';
  errorMessage: string;
  reload: () => void;
}

const NO_ITEMS: ContinueWatchingItem[] = [];

/** «Seguir viendo» del usuario de la sesión (`GET /api/users/me/continue-watching?limit=12`). */
export function useContinueWatching(): ContinueWatchingValue {
  const query = useQuery(continueWatchingQuery());
  const status = loadStatusOf(query);
  const { refetch } = query;
  const reload = useCallback(() => void refetch(), [refetch]);
  return {
    items: query.data ?? NO_ITEMS,
    status,
    errorMessage: status === 'error' ? getErrorMessage(query.error) : '',
    reload,
  };
}

/**
 * Cuántas entradas pide «Vistos recientemente» del perfil. Doce, como «Seguir
 * viendo» y el tope de «Te gusta»: son filas completas en la rejilla compacta del
 * perfil (cuatro de 3 en el móvil, dos de 6 desde `md`) y el perfil no se
 * convierte en un catálogo. El servidor admite de 1 a 24.
 */
export const WATCH_HISTORY_LIMIT = 12;

/** Lo que devuelve {@link useWatchHistory}. */
export interface WatchHistoryValue {
  items: WatchHistoryItem[];
  status: 'loading' | 'ready' | 'error';
  errorMessage: string;
  reload: () => void;
}

const NO_HISTORY: WatchHistoryItem[] = [];

/**
 * Historial de visionado del usuario de la sesión
 * (`GET /api/users/me/watch-history?limit=12`): lo más reciente primero, una
 * entrada por película y una por serie, terminado o no. Lo enseña «Vistos
 * recientemente» del perfil. Cuelga de la raíz `progress`: lo descartan el
 * guardado del reproductor, quitar de «Seguir viendo», los cambios del catálogo
 * y el `queryClient.clear()` de cada cambio de sesión.
 */
export function useWatchHistory(): WatchHistoryValue {
  const query = useQuery({
    queryKey: queryKeys.progress.history(WATCH_HISTORY_LIMIT),
    queryFn: ({ signal }) =>
      apiFetch<WatchHistoryItem[]>('/users/me/watch-history', { params: { limit: WATCH_HISTORY_LIMIT }, signal }),
  });
  const status = loadStatusOf(query);
  const { refetch } = query;
  const reload = useCallback(() => void refetch(), [refetch]);
  return {
    items: query.data ?? NO_HISTORY,
    status,
    errorMessage: status === 'error' ? getErrorMessage(query.error) : '',
    reload,
  };
}

/** Lo que devuelve {@link useWatchStats}. */
export interface WatchStatsValue {
  /** Las cifras, o `null` mientras cargan o si fallan (nunca unos ceros inventados). */
  stats: WatchStats | null;
  status: 'loading' | 'ready' | 'error';
  errorMessage: string;
  reload: () => void;
}

/**
 * Cifras de lo visto del usuario de la sesión (`GET /api/users/me/watch-stats`):
 * películas y episodios terminados y tiempo visto aproximado. Misma raíz y
 * mismas invalidaciones que {@link useWatchHistory}.
 */
export function useWatchStats(): WatchStatsValue {
  const query = useQuery({
    queryKey: queryKeys.progress.stats,
    queryFn: ({ signal }) => apiFetch<WatchStats>('/users/me/watch-stats', { signal }),
  });
  const status = loadStatusOf(query);
  const { refetch } = query;
  const reload = useCallback(() => void refetch(), [refetch]);
  return {
    stats: query.data ?? null,
    status,
    errorMessage: status === 'error' ? getErrorMessage(query.error) : '',
    reload,
  };
}

/** Nombre con el que se habla del elemento en los avisos: la película, o la serie (no el episodio). */
export function continueWatchingName(item: ContinueWatchingItem): string {
  return item.type === 'EPISODE' && item.seriesTitle ? item.seriesTitle : item.title;
}

/** Misma tarjeta (para quitarla de la lista en la caché): mismo tipo y mismo id. */
function sameItem(a: ContinueWatchingItem, b: ContinueWatchingItem): boolean {
  return a.type === b.type && a.id === b.id;
}

/**
 * Descarta en la caché todo lo que sabía del progreso del título de `item`
 * (la película, o la serie y todos sus episodios), para que su página deje de
 * decir «Reanudar», y el historial y las cifras del perfil, porque el servidor
 * ha borrado ese progreso (el título sale de «Vistos recientemente» y deja de
 * sumar). Se cancela antes lo que estuviera en vuelo: una lectura empezada
 * antes del `DELETE` traería el progreso borrado.
 */
async function forgetTitleProgress(queryClient: QueryClient, item: ContinueWatchingItem): Promise<void> {
  const titleKeys =
    item.type === 'MOVIE'
      ? [queryKeys.progress.movie(item.id)]
      : [queryKeys.progress.series(item.seriesId ?? 0), queryKeys.progress.episodesAll];
  const keys = [...titleKeys, queryKeys.progress.historyAll, queryKeys.progress.stats];
  await Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

/**
 * Quita un elemento de «Seguir viendo»: `DELETE /continue-watching/movies/{id}`
 * o, en un episodio, `DELETE /continue-watching/series/{seriesId}` (la tarjeta
 * es de la serie: el servidor borra el progreso de TODOS sus episodios, así que
 * no vuelve a aparecer con otro).
 *
 * **Optimista.** La tarjeta desaparece al pulsar (la fila se queda en su sitio)
 * y, si el servidor falla, vuelve a su posición con un aviso de error. El aviso
 * de éxito dice qué ha pasado y qué no ha cambiado: el título sigue en el
 * catálogo; solo se olvida por dónde iba.
 *
 * Nunca lanza: los errores se avisan con un toast. Si mientras tanto ha
 * cambiado la sesión, no se avisa (la respuesta no es de quien está ahora) y la
 * caché ya la habrá vaciado `AuthContext`.
 *
 * @returns la función que quita el elemento; su promesa dice si se quitó
 */
export function useRemoveFromContinueWatching(): (item: ContinueWatchingItem) => Promise<boolean> {
  const queryClient = useQueryClient();
  const toast = useToast();

  return useCallback(
    async (item: ContinueWatchingItem) => {
      const listKey = queryKeys.progress.continueWatching(CONTINUE_WATCHING_LIMIT);
      const sessionKey = currentSessionKey();
      const name = continueWatchingName(item);
      await queryClient.cancelQueries({ queryKey: queryKeys.progress.continueWatchingAll });
      const before = queryClient.getQueryData<ContinueWatchingItem[]>(listKey);
      queryClient.setQueryData<ContinueWatchingItem[]>(listKey, (list) => list?.filter((other) => !sameItem(other, item)));

      const path =
        item.type === 'MOVIE'
          ? `/users/me/continue-watching/movies/${item.id}`
          : `/users/me/continue-watching/series/${item.seriesId}`;
      try {
        await apiFetch(path, { method: 'DELETE' });
      } catch (error) {
        if (currentSessionKey() !== sessionKey) return false;
        // Vuelve a su sitio: solo si la lista sigue sin ella (otra lectura pudo traerla ya).
        queryClient.setQueryData<ContinueWatchingItem[]>(listKey, (list) =>
          list && before && !list.some((other) => sameItem(other, item)) ? before : list,
        );
        toast.errorFrom(error, `No se pudo quitar «${name}» de Seguir viendo. Inténtalo de nuevo.`);
        return false;
      }
      if (currentSessionKey() !== sessionKey) return false;
      void forgetTitleProgress(queryClient, item);
      void queryClient.invalidateQueries({ queryKey: queryKeys.progress.continueWatchingAll });
      toast.success(`«${name}» ya no está en Seguir viendo ni en lo que has visto. Si vuelves a verla, empezará desde el principio.`);
      return true;
    },
    [queryClient, toast],
  );
}
