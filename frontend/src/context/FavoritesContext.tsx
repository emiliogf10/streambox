import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../lib/api';
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

const FavoritesContext = createContext<FavoritesContextValue | null>(null);

/** Clave única de un favorito entre los dos tipos (`movie:3` ≠ `series:3`). */
function pendingKey(kind: FavoriteKind, id: number): string {
  return `${kind}:${id}`;
}

/**
 * Proveedor de la lista "Mi lista": UNA copia compartida por todas las pantallas.
 *
 * Antes cada pantalla (portada, modal, buscador, "Mi lista") pedía y guardaba
 * su propia copia, que se desincronizaba. Ahora el modal abierto desde el
 * buscador, el banner, la página de una serie y "Mi lista" ven siempre el mismo estado.
 *
 * Se coloca dentro de la zona autenticada: al cerrar sesión se desmonta y la
 * lista de un usuario no puede filtrarse al siguiente.
 *
 * **Películas y series.** Son dos listas en el servidor
 * (`/users/me/favorites` y `/users/me/favorites/series`) y aquí también, pero
 * se cargan JUNTAS: la lista solo está "lista" cuando han llegado las dos, y si
 * falla cualquiera se muestra el error con "Reintentar" (que vuelve a pedir
 * ambas). Enseñar solo la mitad haría creer al usuario que perdió la otra.
 *
 * **Actualización optimista** (igual para los dos tipos): al pulsar, la
 * interfaz cambia al instante y la petición va detrás; si falla, se vuelve
 * atrás y se avisa. Dos respuestas del servidor se consideran "estado ya
 * correcto" y NO son error: 409 al añadir (ya estaba) y 404 al quitar (ya no estaba).
 */
export function FavoritesProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [lists, setLists] = useState<Lists>({ movie: [], series: [] });
  const [status, setStatus] = useState<FavoritesStatus>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [pendingKeys, setPendingKeys] = useState<ReadonlySet<string>>(new Set());
  // Copia síncrona de `pendingKeys` para bloquear dobles clics antes del siguiente render.
  const inFlight = useRef(new Set<string>());
  // Contador que cambia con cada "reload" para relanzar el efecto de carga.
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      apiFetch<Movie[]>(ENDPOINTS.movie, { signal: controller.signal }),
      apiFetch<Series[]>(ENDPOINTS.series, { signal: controller.signal }),
    ])
      .then(([movie, series]) => {
        setLists({ movie, series });
        setStatus('ready');
      })
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        // Si falló una, la otra ya no interesa: se cancela en lugar de dejarla terminar para nada.
        controller.abort();
        setErrorMessage(getErrorMessage(error));
        setStatus('error');
        toast.errorFrom(error, 'No se pudo cargar tu lista.');
      });
    return () => controller.abort();
  }, [reloadKey, toast]);

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

  /**
   * Aplica un cambio a la lista de UN tipo. Los elementos de cada lista solo
   * entran por la carga de su endpoint o por `toggle`/`toggleSeries` con su
   * tipo, así que el cambio de tipos (`CatalogItem` ↔ `Movie`/`Series`) es seguro.
   */
  const updateList = useCallback((kind: FavoriteKind, change: (list: CatalogItem[]) => CatalogItem[]) => {
    setLists((current) => ({ ...current, [kind]: change(current[kind]) }) as Lists);
  }, []);

  /** Lógica común de añadir/quitar, sea película o serie. */
  const toggleItem = useCallback(
    async (kind: FavoriteKind, item: CatalogItem) => {
      const key = pendingKey(kind, item.id);
      if (inFlight.current.has(key)) return; // ya hay una petición para este título
      setPending(key, true);

      const wasFavorite = ids[kind].has(item.id);
      const originalIndex = lists[kind].findIndex((m) => m.id === item.id);
      // Cambio optimista: la interfaz responde sin esperar al servidor.
      updateList(kind, (current) => (wasFavorite ? current.filter((m) => m.id !== item.id) : [item, ...current]));

      try {
        await apiFetch(`${ENDPOINTS[kind]}/${item.id}`, { method: wasFavorite ? 'DELETE' : 'POST' });
        toast.success(wasFavorite ? `«${item.title}» se ha quitado de tu lista.` : `«${item.title}» se ha añadido a tu lista.`);
      } catch (error) {
        const alreadyInDesiredState =
          error instanceof ApiError &&
          ((!wasFavorite && error.status === 409) || (wasFavorite && error.status === 404));
        if (alreadyInDesiredState) {
          // El servidor ya tenía ese estado (p. ej. otra pestaña lo cambió): no hay nada que revertir.
          toast.info(wasFavorite ? `«${item.title}» ya no estaba en tu lista.` : `«${item.title}» ya estaba en tu lista.`);
        } else {
          // Vuelta atrás SOLO de este título (y no de una copia antigua de toda la
          // lista), para no deshacer por error cambios de otros títulos en curso.
          updateList(kind, (current) => {
            if (!wasFavorite) return current.filter((m) => m.id !== item.id);
            if (current.some((m) => m.id === item.id)) return current;
            const restored = [...current];
            restored.splice(originalIndex, 0, item);
            return restored;
          });
          toast.errorFrom(
            error,
            wasFavorite ? `No se pudo quitar ${NOUN[kind]} de tu lista.` : `No se pudo añadir ${NOUN[kind]} a tu lista.`,
          );
        }
      } finally {
        setPending(key, false);
      }
    },
    [ids, lists, setPending, toast, updateList],
  );

  const toggle = useCallback((movie: Movie) => toggleItem('movie', movie), [toggleItem]);
  const toggleSeries = useCallback((series: Series) => toggleItem('series', series), [toggleItem]);

  /**
   * Vacía las dos listas con dos peticiones en paralelo (el servidor tiene un
   * `DELETE` por lista). Se envían SIEMPRE las dos, aunque aquí una parezca
   * vacía: la copia local puede estar desfasada (otra pestaña pudo añadir algo)
   * y "vaciar" significa vaciar en el servidor.
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
    setLists((current) => ({
      movie: moviesCleared ? [] : current.movie,
      series: seriesCleared ? [] : current.series,
    }));

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
  }, [toast]);

  const reload = useCallback(() => {
    setStatus('loading');
    setReloadKey((key) => key + 1);
  }, []);

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
