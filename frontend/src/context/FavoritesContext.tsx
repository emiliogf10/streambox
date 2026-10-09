import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, apiFetch, currentSessionKey, getErrorMessage } from '../lib/api';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import type { CatalogItem, Movie, Series } from '../lib/types';
import { useToast } from './ToastContext';

/** Estado de la carga inicial de la lista. */
type FavoritesStatus = 'loading' | 'ready' | 'error';

/**
 * Tipo de título de la lista. Películas y series son DOS listas en el servidor
 * (y sus ids pueden coincidir: la película 3 y la serie 3 son cosas distintas),
 * así que todo lo que identifica un favorito va acompañado de su tipo.
 */
export type FavoriteKind = 'movie' | 'series';

/** Endpoint de cada lista: `GET` la lee, `DELETE` la vacía y `/{id}` añade (`POST`) o quita (`DELETE`) un título. */
const ENDPOINTS: Record<FavoriteKind, string> = {
  movie: '/users/me/favorites',
  series: '/users/me/favorites/series',
};

/** Textos que cambian según el tipo (mensajes de error al añadir o quitar). */
const NOUN: Record<FavoriteKind, string> = { movie: 'la película', series: 'la serie' };

/** Valor que expone {@link useFavorites}. */
interface FavoritesContextValue {
  /** Películas de la lista, la más recién añadida primero. */
  movies: Movie[];
  /** Series de la lista (el servidor las da por título; las añadidas en esta sesión van primero). */
  series: Series[];
  /** Estado de la carga inicial (de las DOS listas: está lista cuando lo están ambas). */
  status: FavoritesStatus;
  /** Mensaje del último fallo de carga (solo con `status === 'error'`). */
  errorMessage: string;
  /** `true` si el título está en la lista. Sin `kind`, se entiende película. */
  isFavorite: (id: number, kind?: FavoriteKind) => boolean;
  /** `true` mientras hay una petición en curso para ese título (para bloquear su botón). */
  isPending: (id: number, kind?: FavoriteKind) => boolean;
  /** Añade o quita la película según su estado actual (actualización optimista). */
  toggle: (movie: Movie) => Promise<void>;
  /** Añade o quita la serie según su estado actual (actualización optimista). */
  toggleSeries: (series: Series) => Promise<void>;
  /**
   * Vacía la lista entera (películas y series) en el servidor. Devuelve `true`
   * solo si se vaciaron las dos. No es optimista: es destructivo.
   */
  clear: () => Promise<boolean>;
  /** Vuelve a cargar la lista desde el servidor (botón "Reintentar"). */
  reload: () => void;
}

/** Las dos listas guardadas juntas: así una actualización toca solo la de su tipo sin pisar la otra. */
interface Lists {
  movie: Movie[];
  series: Series[];
}

/** Listas vacías compartidas mientras no han llegado las de verdad (misma identidad en cada render). */
const NO_LISTS: Lists = { movie: [], series: [] };

/** Clave de la caché de «Mi lista» (las dos listas en UNA entrada). */
const FAVORITES_KEY = queryKeys.favorites.all;

/**
 * Clave de las mutaciones de añadir/quitar: sirve para saber cuántas quedan en
 * vuelo (`isMutating`) antes de marcar la lista como anticuada.
 */
const TOGGLE_MUTATION_KEY = ['favorites', 'toggle'] as const;

/** Lo que recibe la mutación de añadir/quitar un título. */
interface ToggleVariables {
  kind: FavoriteKind;
  item: CatalogItem;
  /** Si estaba en la lista al pulsar: decide entre `DELETE` (quitar) y `POST` (añadir). */
  wasFavorite: boolean;
}

/** Lo que `onMutate` deja preparado para `onError` y `onSettled`. */
interface ToggleContext {
  /** Posición que tenía el título en su lista (para devolverlo a su sitio si hay que deshacer); -1 si no estaba. */
  originalIndex: number;
  /** `true` si al pulsar aún no había lista en la caché (cargando): no hubo cambio optimista. */
  withoutList: boolean;
  /**
   * Sesión con la que se pulsó (`currentSessionKey`). Si al responder el
   * servidor ya es otra (se cerró sesión, o entró otra cuenta), la respuesta no
   * es de quien está ahora: ni se avisa ni se toca su lista.
   */
  sessionKey: number;
}

/**
 * ¿La respuesta de una mutación es aún de la sesión actual? Sin contexto
 * (`onMutate` no llegó a terminar) se da por buena, como antes de esta comprobación.
 */
function isSameSession(context: ToggleContext | undefined): boolean {
  return context === undefined || context.sessionKey === currentSessionKey();
}

/**
 * Deja la lista de un tipo en el estado que el servidor acaba de CONFIRMAR para
 * un título: dentro (la primera, sin duplicarla si ya estaba) o fuera.
 */
function applyConfirmed(list: CatalogItem[], item: CatalogItem, inList: boolean): CatalogItem[] {
  if (!inList) return list.some((m) => m.id === item.id) ? list.filter((m) => m.id !== item.id) : list;
  return list.some((m) => m.id === item.id) ? list : [item, ...list];
}

const FavoritesContext = createContext<FavoritesContextValue | null>(null);

/** Clave única de un favorito entre los dos tipos (`movie:3` ≠ `series:3`). */
function pendingKey(kind: FavoriteKind, id: number): string {
  return `${kind}:${id}`;
}

/**
 * Pide las DOS listas a la vez. Si falla una, se cancela la otra (ya no
 * interesa: la lista solo está «lista» con las dos). El `signal` de TanStack
 * cancela ambas si ya nadie las espera (p. ej. al cerrar sesión).
 */
async function fetchLists(signal: AbortSignal): Promise<Lists> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener('abort', abort);
  try {
    const [movie, series] = await Promise.all([
      apiFetch<Movie[]>(ENDPOINTS.movie, { signal: controller.signal }),
      apiFetch<Series[]>(ENDPOINTS.series, { signal: controller.signal }),
    ]);
    return { movie, series };
  } catch (error) {
    controller.abort();
    throw error;
  } finally {
    signal.removeEventListener('abort', abort);
  }
}

/**
 * Aplica un cambio a la lista de UN tipo. Los elementos de cada lista solo
 * entran por la carga de su endpoint o por `toggle`/`toggleSeries` con su tipo,
 * así que el cambio de tipos (`CatalogItem` ↔ `Movie`/`Series`) es seguro.
 */
function changeList(lists: Lists, kind: FavoriteKind, change: (list: CatalogItem[]) => CatalogItem[]): Lists {
  return { ...lists, [kind]: change(lists[kind]) } as Lists;
}

/**
 * Proveedor de la lista "Mi lista": UNA copia compartida por todas las pantallas
 * (el modal abierto desde el buscador, el banner, la página de una serie, «Mi
 * lista» y el perfil ven siempre el mismo estado).
 *
 * **Datos en la caché de TanStack Query** (`queryKeys.favorites.all`). Las dos
 * listas del servidor (`/users/me/favorites` y `/users/me/favorites/series`)
 * van en UNA entrada y se cargan juntas: la lista solo está «lista» cuando han
 * llegado las dos, y si falla cualquiera se muestra el error con «Reintentar»
 * (que vuelve a pedir ambas). Enseñar solo la mitad haría creer al usuario que
 * perdió la otra. Es un dato PERSONAL: `AuthProvider` vacía la caché al cambiar
 * de sesión, así que la lista de un usuario no puede aparecerle al siguiente
 * (desmontar este proveedor ya no basta: la caché sobrevive a los componentes).
 *
 * **Actualización optimista** (`useMutation`, igual para los dos tipos):
 * - `onMutate`: cancela un refresco en vuelo (traería la lista de antes del
 *   clic y pisaría el cambio) y cambia la lista en la caché al instante.
 * - `onSuccess`: vuelve a aplicar el estado CONFIRMADO (sin duplicar). Un
 *   refresco que empezó durante el `POST`/`DELETE` (volver a la pestaña, una
 *   invalidación) pudo traer la lista de antes y pisar el cambio optimista; se
 *   corta si sigue en vuelo, por el mismo motivo.
 * - `onError`: deshace SOLO ese título y avisa. Dos respuestas son «el estado
 *   ya es el correcto» y NO error: 409 al añadir (ya estaba) y 404 al quitar
 *   (ya no estaba); se aplica ese estado (como en `onSuccess`) y se informa.
 * - Si al responder el servidor la sesión ya es otra (se cerró, o entró otra
 *   cuenta), no se avisa ni se toca la lista: la caché ya es de otra persona.
 * - `onSettled`: desbloquea el botón y, cuando no queda ningún cambio en vuelo,
 *   marca la lista como anticuada SIN pedirla: el próximo refresco natural
 *   (volver a la pestaña, recargar) la sincroniza con el servidor. Pedirla tras
 *   cada clic costaría una petición por clic y reordenaría las series bajo el
 *   puntero (el servidor las da por título).
 *
 * Mientras hay una petición para un título, su botón se bloquea (`isPending`).
 */
export function FavoritesProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const favorites = useQuery({ queryKey: FAVORITES_KEY, queryFn: ({ signal }) => fetchLists(signal) });
  const lists = favorites.data ?? NO_LISTS;
  const status: FavoritesStatus = loadStatusOf(favorites);
  const errorMessage = status === 'error' ? getErrorMessage(favorites.error) : '';
  const { refetch, error: loadError } = favorites;

  const [pendingKeys, setPendingKeys] = useState<ReadonlySet<string>>(new Set());
  // Copia síncrona de `pendingKeys` para bloquear dobles clics antes del siguiente render.
  const inFlight = useRef(new Set<string>());

  // Aviso UNO por racha de fallos (y por sesión) cuando la lista no se ha podido
  // cargar. Con el servidor caído, cada vuelta a la pestaña reintenta la carga
  // (`refetchOnWindowFocus`) y vuelve a fallar: avisar en cada una llenaría la
  // pantalla de avisos iguales. La racha acaba con una carga correcta. Si falla
  // un refresco con la lista ya en pantalla no se avisa: lo que se ve sigue valiendo.
  const warnedInSession = useRef<number | null>(null);
  useEffect(() => {
    if (status === 'ready') {
      warnedInSession.current = null;
      return;
    }
    const sessionKey = currentSessionKey();
    if (status !== 'error' || warnedInSession.current === sessionKey) return;
    warnedInSession.current = sessionKey;
    toast.errorFrom(loadError, 'No se pudo cargar tu lista.');
  }, [status, loadError, toast]);

  const ids = useMemo(
    () => ({ movie: new Set(lists.movie.map((m) => m.id)), series: new Set(lists.series.map((s) => s.id)) }),
    [lists],
  );

  const isFavorite = useCallback((id: number, kind: FavoriteKind = 'movie') => ids[kind].has(id), [ids]);
  const isPending = useCallback(
    (id: number, kind: FavoriteKind = 'movie') => pendingKeys.has(pendingKey(kind, id)),
    [pendingKeys],
  );

  const setPending = useCallback((key: string, pending: boolean) => {
    if (pending) inFlight.current.add(key);
    else inFlight.current.delete(key);
    setPendingKeys(new Set(inFlight.current));
  }, []);

  /** Cambia la lista de un tipo en la caché (si aún no hay lista, no hay nada que cambiar). */
  const updateCachedList = useCallback(
    (kind: FavoriteKind, change: (list: CatalogItem[]) => CatalogItem[]) => {
      queryClient.setQueryData<Lists>(FAVORITES_KEY, (current) => (current ? changeList(current, kind, change) : current));
    },
    [queryClient],
  );

  /**
   * Aplica en la caché el estado que el servidor acaba de confirmar para un
   * título (sin petición extra). Antes corta un refresco que siga en vuelo: salió
   * durante el `POST`/`DELETE` y puede traer la lista de antes del cambio. Solo
   * si había lista al pulsar: si no, de la recarga se ocupa `onSettled`.
   */
  const confirmState = useCallback(
    async (kind: FavoriteKind, item: CatalogItem, inList: boolean, context: ToggleContext | undefined) => {
      if (context?.withoutList) return;
      await queryClient.cancelQueries({ queryKey: FAVORITES_KEY });
      updateCachedList(kind, (current) => applyConfirmed(current, item, inList));
    },
    [queryClient, updateCachedList],
  );

  const { mutateAsync: toggleOnServer } = useMutation<void, unknown, ToggleVariables, ToggleContext>({
    mutationKey: TOGGLE_MUTATION_KEY,
    mutationFn: ({ kind, item, wasFavorite }) =>
      apiFetch(`${ENDPOINTS[kind]}/${item.id}`, { method: wasFavorite ? 'DELETE' : 'POST' }),
    onMutate: async ({ kind, item, wasFavorite }) => {
      const sessionKey = currentSessionKey();
      const before = queryClient.getQueryData<Lists>(FAVORITES_KEY);
      // Sin lista todavía (cargando) no se corta esa carga: no hay nada optimista que proteger.
      if (!before) return { originalIndex: -1, withoutList: true, sessionKey };
      await queryClient.cancelQueries({ queryKey: FAVORITES_KEY });
      // Cambio optimista: la interfaz responde sin esperar al servidor.
      updateCachedList(kind, (current) => (wasFavorite ? current.filter((m) => m.id !== item.id) : [item, ...current]));
      return { originalIndex: before[kind].findIndex((m) => m.id === item.id), withoutList: false, sessionKey };
    },
    onSuccess: async (_data, { kind, item, wasFavorite }, context) => {
      if (!isSameSession(context)) return;
      await confirmState(kind, item, !wasFavorite, context);
      toast.success(wasFavorite ? `«${item.title}» se ha quitado de tu lista.` : `«${item.title}» se ha añadido a tu lista.`);
    },
    onError: async (error, { kind, item, wasFavorite }, context) => {
      if (!isSameSession(context)) return;
      const alreadyInDesiredState =
        error instanceof ApiError &&
        ((!wasFavorite && error.status === 409) || (wasFavorite && error.status === 404));
      if (alreadyInDesiredState) {
        // El servidor ya tenía ese estado (p. ej. otra pestaña lo cambió): no hay nada que revertir,
        // pero sí que reafirmarlo por si un refresco a medias trajo la lista de antes.
        await confirmState(kind, item, !wasFavorite, context);
        toast.info(wasFavorite ? `«${item.title}» ya no estaba en tu lista.` : `«${item.title}» ya estaba en tu lista.`);
        return;
      }
      // Vuelta atrás SOLO de este título (y no de una copia antigua de toda la lista),
      // para no deshacer por error cambios de otros títulos en curso.
      updateCachedList(kind, (current) => {
        if (!wasFavorite) return current.filter((m) => m.id !== item.id);
        if (current.some((m) => m.id === item.id)) return current;
        const restored = [...current];
        restored.splice(Math.max(context?.originalIndex ?? 0, 0), 0, item);
        return restored;
      });
      toast.errorFrom(
        error,
        wasFavorite ? `No se pudo quitar ${NOUN[kind]} de tu lista.` : `No se pudo añadir ${NOUN[kind]} a tu lista.`,
      );
    },
    onSettled: async (_data, _error, { kind, item }, context) => {
      setPending(pendingKey(kind, item.id), false);
      // La lista de la caché ya es de otra sesión: este cambio no le afecta.
      if (!isSameSession(context)) return;
      if (context?.withoutList) {
        // Se pulsó mientras la lista cargaba: esa carga pudo salir antes del cambio y traer la lista vieja.
        // Se corta (si sigue en vuelo; si no, no hace nada) y se pide de nuevo. Sin cortarla, TanStack
        // reutilizaría la carga en curso en vez de lanzar otra.
        await queryClient.cancelQueries({ queryKey: FAVORITES_KEY });
        void queryClient.invalidateQueries({ queryKey: FAVORITES_KEY });
      } else if (queryClient.isMutating({ mutationKey: TOGGLE_MUTATION_KEY }) <= 1) {
        // Es el último cambio en vuelo (él mismo aún cuenta): la lista queda «anticuada» sin pedirla ya.
        void queryClient.invalidateQueries({ queryKey: FAVORITES_KEY, refetchType: 'none' });
      }
    },
  });

  /** Lógica común de añadir/quitar, sea película o serie. Nunca lanza: los fallos se avisan con un toast. */
  const toggleItem = useCallback(
    async (kind: FavoriteKind, item: CatalogItem) => {
      const key = pendingKey(kind, item.id);
      if (inFlight.current.has(key)) return; // ya hay una petición para este título
      setPending(key, true);
      // Se lee la caché (y no `ids`, de este render): tras varios clics seguidos es lo más reciente.
      const wasFavorite = queryClient.getQueryData<Lists>(FAVORITES_KEY)?.[kind].some((m) => m.id === item.id) ?? false;
      await toggleOnServer({ kind, item, wasFavorite }).catch(() => undefined);
    },
    [queryClient, setPending, toggleOnServer],
  );

  const toggle = useCallback((movie: Movie) => toggleItem('movie', movie), [toggleItem]);
  const toggleSeries = useCallback((series: Series) => toggleItem('series', series), [toggleItem]);

  /**
   * Vacía las dos listas con dos peticiones en paralelo (el servidor tiene un
   * `DELETE` por lista). Se envían SIEMPRE las dos, aunque aquí una parezca
   * vacía: la copia local puede estar desfasada (otra pestaña pudo añadir algo)
   * y "vaciar" significa vaciar en el servidor. No es optimista: es destructivo.
   *
   * Cada lista se vacía en pantalla solo si SU petición fue bien, así que lo que
   * se ve coincide con el servidor también cuando una falla. Avisos:
   * - las dos bien → "Tu lista se ha vaciado." y devuelve `true`;
   * - las dos mal → el error (p. ej. "El servidor ha tenido un problema...");
   * - una sí y otra no → dice exactamente qué se quitó y qué no, con el motivo,
   *   para que el usuario sepa que puede volver a intentarlo.
   */
  const clear = useCallback(async () => {
    const [movieResult, seriesResult] = await Promise.allSettled([
      apiFetch(ENDPOINTS.movie, { method: 'DELETE' }),
      apiFetch(ENDPOINTS.series, { method: 'DELETE' }),
    ]);
    const moviesCleared = movieResult.status === 'fulfilled';
    const seriesCleared = seriesResult.status === 'fulfilled';
    // Un refresco en vuelo traería la lista de antes de vaciarla.
    await queryClient.cancelQueries({ queryKey: FAVORITES_KEY });
    queryClient.setQueryData<Lists>(FAVORITES_KEY, (current) =>
      current ? { movie: moviesCleared ? [] : current.movie, series: seriesCleared ? [] : current.series } : current,
    );
    void queryClient.invalidateQueries({ queryKey: FAVORITES_KEY, refetchType: 'none' });

    if (moviesCleared && seriesCleared) {
      toast.success('Tu lista se ha vaciado.');
      return true;
    }
    if (movieResult.status === 'rejected' && seriesResult.status === 'rejected') {
      toast.errorFrom(movieResult.reason, 'No se pudo vaciar tu lista.');
      return false;
    }
    const failure = movieResult.status === 'rejected' ? movieResult.reason : (seriesResult as PromiseRejectedResult).reason;
    // Una sesión caducada ya tiene su propio aviso (y lleva al login): no se añade otro.
    if (!(failure instanceof ApiError && failure.sessionExpired)) {
      const [done, pending] = moviesCleared ? ['las películas', 'las series'] : ['las series', 'las películas'];
      toast.error(`Se han quitado ${done} de tu lista, pero no ${pending}. ${getErrorMessage(failure)}`);
    }
    return false;
  }, [queryClient, toast]);

  /** Vuelve a pedir las dos listas («Reintentar»): mientras tanto, `status` es `loading`. */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  const value = useMemo<FavoritesContextValue>(
    () => ({
      movies: lists.movie,
      series: lists.series,
      status,
      errorMessage,
      isFavorite,
      isPending,
      toggle,
      toggleSeries,
      clear,
      reload,
    }),
    [lists, status, errorMessage, isFavorite, isPending, toggle, toggleSeries, clear, reload],
  );

  return <FavoritesContext.Provider value={value}>{children}</FavoritesContext.Provider>;
}

/**
 * Acceso a la lista "Mi lista" compartida.
 *
 * @throws si se usa fuera de {@link FavoritesProvider}
 */
// oxlint-disable-next-line react/only-export-components -- patrón habitual: proveedor y hook comparten archivo
export function useFavorites(): FavoritesContextValue {
  const ctx = useContext(FavoritesContext);
  if (!ctx) throw new Error('useFavorites debe usarse dentro de <FavoritesProvider>.');
  return ctx;
}
