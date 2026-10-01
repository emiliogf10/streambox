import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import type { Movie } from '../lib/types';
import { useToast } from './ToastContext';

/** Estado de la carga inicial de la lista. */
type FavoritesStatus = 'loading' | 'ready' | 'error';

/** Valor que expone {@link useFavorites}. */
interface FavoritesContextValue {
  /** Películas de la lista, la más recién añadida primero. */
  movies: Movie[];
  /** Estado de la carga inicial. */
  status: FavoritesStatus;
  /** Mensaje del último fallo de carga (solo con `status === 'error'`). */
  errorMessage: string;
  /** `true` si la película está en la lista. */
  isFavorite: (movieId: number) => boolean;
  /** `true` mientras hay una petición en curso para esa película (para bloquear su botón). */
  isPending: (movieId: number) => boolean;
  /** Añade o quita la película según su estado actual (actualización optimista). */
  toggle: (movie: Movie) => Promise<void>;
  /** Vacía la lista en el servidor. Devuelve `true` si se completó. No es optimista: es destructivo. */
  clear: () => Promise<boolean>;
  /** Vuelve a cargar la lista desde el servidor (botón "Reintentar"). */
  reload: () => void;
}

const FavoritesContext = createContext<FavoritesContextValue | null>(null);

/**
 * Proveedor de la lista "Mi lista": UNA copia compartida por todas las pantallas.
 *
 * Antes cada pantalla (portada, modal, buscador, "Mi lista") pedía y guardaba
 * su propia copia, que se desincronizaba. Ahora el modal abierto desde el
 * buscador, el banner y la página "Mi lista" ven siempre el mismo estado.
 *
 * Se coloca dentro de la zona autenticada: al cerrar sesión se desmonta y la
 * lista de un usuario no puede filtrarse al siguiente.
 *
 * **Actualización optimista**: al pulsar, la interfaz cambia al instante y la
 * petición va detrás; si falla, se vuelve atrás y se avisa. Dos respuestas del
 * servidor se consideran "estado ya correcto" y NO son error: 409 al añadir (ya
 * estaba) y 404 al quitar (ya no estaba).
 */
export function FavoritesProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [movies, setMovies] = useState<Movie[]>([]);
  const [status, setStatus] = useState<FavoritesStatus>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [pendingIds, setPendingIds] = useState<ReadonlySet<number>>(new Set());
  // Copia síncrona de `pendingIds` para bloquear dobles clics antes del siguiente render.
  const inFlight = useRef(new Set<number>());
  // Contador que cambia con cada "reload" para relanzar el efecto de carga.
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    apiFetch<Movie[]>('/users/me/favorites', { signal: controller.signal })
      .then((list) => {
        setMovies(list);
        setStatus('ready');
      })
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setErrorMessage(getErrorMessage(error));
        setStatus('error');
        toast.errorFrom(error, 'No se pudo cargar tu lista.');
      });
    return () => controller.abort();
  }, [reloadKey, toast]);

  const ids = useMemo(() => new Set(movies.map((m) => m.id)), [movies]);

  const isFavorite = useCallback((movieId: number) => ids.has(movieId), [ids]);
  const isPending = useCallback((movieId: number) => pendingIds.has(movieId), [pendingIds]);

  const setPending = useCallback((movieId: number, pending: boolean) => {
    if (pending) inFlight.current.add(movieId);
    else inFlight.current.delete(movieId);
    setPendingIds(new Set(inFlight.current));
  }, []);

  const toggle = useCallback(
    async (movie: Movie) => {
      if (inFlight.current.has(movie.id)) return; // ya hay una petición para esta película
      setPending(movie.id, true);

      const wasFavorite = ids.has(movie.id);
      const originalIndex = movies.findIndex((m) => m.id === movie.id);
      // Cambio optimista: la interfaz responde sin esperar al servidor.
      setMovies((current) =>
        wasFavorite ? current.filter((m) => m.id !== movie.id) : [movie, ...current],
      );

      try {
        await apiFetch(`/users/me/favorites/${movie.id}`, { method: wasFavorite ? 'DELETE' : 'POST' });
        toast.success(wasFavorite ? `«${movie.title}» se ha quitado de tu lista.` : `«${movie.title}» se ha añadido a tu lista.`);
      } catch (error) {
        const alreadyInDesiredState =
          error instanceof ApiError &&
          ((!wasFavorite && error.status === 409) || (wasFavorite && error.status === 404));
        if (alreadyInDesiredState) {
          // El servidor ya tenía ese estado (p. ej. otra pestaña lo cambió): no hay nada que revertir.
          toast.info(
            wasFavorite
              ? `«${movie.title}» ya no estaba en tu lista.`
              : `«${movie.title}» ya estaba en tu lista.`,
          );
        } else {
          // Vuelta atrás SOLO de esta película (y no de una copia antigua de toda la
          // lista), para no deshacer por error cambios de otras películas en curso.
          setMovies((current) => {
            if (!wasFavorite) return current.filter((m) => m.id !== movie.id);
            if (current.some((m) => m.id === movie.id)) return current;
            const restored = [...current];
            restored.splice(originalIndex, 0, movie);
            return restored;
          });
          toast.errorFrom(
            error,
            wasFavorite ? 'No se pudo quitar la película de tu lista.' : 'No se pudo añadir la película a tu lista.',
          );
        }
      } finally {
        setPending(movie.id, false);
      }
    },
    [ids, movies, setPending, toast],
  );

  const clear = useCallback(async () => {
    try {
      await apiFetch('/users/me/favorites', { method: 'DELETE' });
      setMovies([]);
      toast.success('Tu lista se ha vaciado.');
      return true;
    } catch (error) {
      toast.errorFrom(error, 'No se pudo vaciar tu lista.');
      return false;
    }
  }, [toast]);

  const reload = useCallback(() => {
    setStatus('loading');
    setReloadKey((key) => key + 1);
  }, []);

  const value = useMemo<FavoritesContextValue>(
    () => ({ movies, status, errorMessage, isFavorite, isPending, toggle, clear, reload }),
    [movies, status, errorMessage, isFavorite, isPending, toggle, clear, reload],
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
