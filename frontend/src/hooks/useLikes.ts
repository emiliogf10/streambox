import { useCallback, useMemo } from 'react';
import { useMutation, useMutationState, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Mutation } from '@tanstack/react-query';
import { useToast } from '../context/ToastContext';
import { ApiError, apiFetch, currentSessionKey, getErrorMessage } from '../lib/api';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import { fetchTitleLists } from '../lib/titleLists';
import type { TitleKind, TitleLists } from '../lib/titleLists';
import type { CatalogItem, Movie, Series } from '../lib/types';

/** Tipo de título de un «Me gusta» (películas y series son tablas distintas y sus ids pueden coincidir). */
export type LikeKind = TitleKind;

/** Endpoint de cada lista: `GET` la lee y `/{id}` da (`PUT`) o quita (`DELETE`) un «Me gusta». */
const ENDPOINTS: Record<LikeKind, string> = {
  movie: '/users/me/likes/movies',
  series: '/users/me/likes/series',
};

/** Listas vacías compartidas mientras no han llegado las de verdad (misma identidad en cada render). */
const NO_LISTS: TitleLists = { movie: [], series: [] };

/** Clave de la caché de los «Me gusta» (las dos listas en UNA entrada). */
const LIKES_KEY = queryKeys.likes.all;

/**
 * Clave de las mutaciones de dar/quitar «Me gusta». Sirve para dos cosas: saber
 * cuántas quedan en vuelo antes de marcar la lista como anticuada, y saber QUÉ
 * títulos tienen una en vuelo desde cualquier componente (ver `isPending`).
 */
const TOGGLE_MUTATION_KEY = ['likes', 'toggle'] as const;

/** Lo que recibe la mutación de dar/quitar un «Me gusta». */
interface ToggleVariables {
  kind: LikeKind;
  item: CatalogItem;
  /** Si le gustaba al pulsar: decide entre `DELETE` (quitar) y `PUT` (dar). */
  wasLiked: boolean;
}

/** Lo que `onMutate` deja preparado para `onError` y `onSettled`. */
interface ToggleContext {
  /** Posición que tenía el título en su lista (para devolverlo a su sitio si hay que deshacer); -1 si no estaba. */
  originalIndex: number;
  /** `true` si al pulsar aún no había lista en la caché (cargando o con error): no hubo cambio optimista. */
  withoutList: boolean;
  /** Sesión con la que se pulsó: si al responder ya es otra, la respuesta no es de quien está ahora. */
  sessionKey: number;
}

/** Textos que cambian según el tipo. */
const NOUN: Record<LikeKind, string> = { movie: 'la película', series: 'la serie' };

/**
 * Aviso cuando el servidor responde 404 al dar «Me gusta»: el título ya no
 * existe (o, en una serie, ya no tiene episodios, que para un usuario es lo
 * mismo: la API no los distingue). Una película solo puede haberse borrado; una
 * serie puede haberse borrado u ocultado, de ahí «ya no está disponible».
 */
export function likeGoneMessage(kind: LikeKind, title: string): string {
  return kind === 'movie'
    ? `«${title}» ya no está en el catálogo, así que no se ha guardado tu «Me gusta».`
    : `«${title}» ya no está disponible, así que no se ha guardado tu «Me gusta».`;
}

/** Aviso al dar «Me gusta»: dice también para qué sirve (las recomendaciones del inicio). */
export function likedMessage(title: string): string {
  return `Te gusta «${title}». Lo tendremos en cuenta para recomendarte títulos parecidos.`;
}

/** Aviso al quitar un «Me gusta». */
export function unlikedMessage(title: string): string {
  return `Ya no te gusta «${title}».`;
}

/** ¿La respuesta de una mutación es aún de la sesión actual? Sin contexto se da por buena. */
function isSameSession(context: ToggleContext | undefined): boolean {
  return context === undefined || context.sessionKey === currentSessionKey();
}

/** Deja la lista de un tipo en el estado que el servidor acaba de CONFIRMAR para un título. */
function applyConfirmed(list: CatalogItem[], item: CatalogItem, liked: boolean): CatalogItem[] {
  if (!liked) return list.some((m) => m.id === item.id) ? list.filter((m) => m.id !== item.id) : list;
  return list.some((m) => m.id === item.id) ? list : [item, ...list];
}

/**
 * Aplica un cambio a la lista de UN tipo. Los elementos de cada lista solo
 * entran por la carga de su endpoint o por `toggle` con su tipo, así que el
 * cambio de tipos (`CatalogItem` ↔ `Movie`/`Series`) es seguro.
 */
function changeList(lists: TitleLists, kind: LikeKind, change: (list: CatalogItem[]) => CatalogItem[]): TitleLists {
  return { ...lists, [kind]: change(lists[kind]) } as TitleLists;
}

/** Clave única de un título entre los dos tipos (`movie:3` ≠ `series:3`). */
function pendingKey(kind: LikeKind, id: number): string {
  return `${kind}:${id}`;
}

/** Filtro de las mutaciones de «Me gusta» aún en vuelo (de cualquier componente). */
const PENDING_FILTER = { mutationKey: TOGGLE_MUTATION_KEY, status: 'pending' as const };

/** De una mutación en vuelo, la clave del título que cambia. */
function pendingKeyOf(mutation: Pick<Mutation, 'state'>): string | null {
  const variables = mutation.state.variables as ToggleVariables | undefined;
  return variables ? pendingKey(variables.kind, variables.item.id) : null;
}

/** Lo que devuelve {@link useLikes}. */
export interface LikesValue {
  /** Películas que le gustan, la más reciente primero (orden del servidor; las de esta sesión, delante). */
  movies: Movie[];
  /** Series que le gustan (solo con episodios), en el mismo orden. */
  series: Series[];
  /** Estado de la carga (de las DOS listas: está lista cuando lo están ambas). */
  status: 'loading' | 'ready' | 'error';
  /** Mensaje del último fallo de carga (solo con `status === 'error'`). */
  errorMessage: string;
  /** `true` si el título le gusta. */
  isLiked: (id: number, kind: LikeKind) => boolean;
  /** `true` mientras hay una petición en curso para ese título (para bloquear su botón). */
  isPending: (id: number, kind: LikeKind) => boolean;
  /** Da o quita el «Me gusta» según su estado actual (optimista). Nunca lanza: avisa con un toast. */
  toggle: (kind: LikeKind, item: CatalogItem) => Promise<void>;
  /** Vuelve a pedir las listas (botón «Reintentar»). */
  reload: () => void;
}

/**
 * «Me gusta» del usuario de la sesión: películas y series que vio y le
 * gustaron. Es distinto de «Mi lista» (lo que quiere ver): aquel es
 * `FavoritesContext`; este alimenta las recomendaciones del inicio.
 *
 * **Por qué un hook y no un proveedor como `FavoritesContext`.** «Mi lista»
 * hace falta en casi todas las pantallas (banner, buscador...), así que se
 * carga una vez al entrar. Los «Me gusta» solo los usan la página de un título
 * (el botón) y el perfil (la sección «Te gusta»): con un proveedor en
 * `AppShell`, cada visita a la portada costaría dos peticiones que no pinta
 * nadie. La caché de TanStack (`queryKeys.likes.all`) ya hace de «estado
 * compartido»: todos los componentes que usan el hook ven la misma entrada, y
 * lo que está en vuelo se lee de la caché de mutaciones (`useMutationState`),
 * no de un estado de este componente, así que salir de la página y volver con
 * un cambio a medias no deja pulsar dos veces.
 *
 * **Datos.** `GET /users/me/likes/movies` y `/users/me/likes/series`, juntas
 * (`fetchTitleLists`: si falla una, error con «Reintentar» para las dos). Es un
 * dato PERSONAL: `AuthProvider` vacía la caché al cambiar de sesión.
 *
 * **Cambio optimista** (`PUT`/`DELETE /users/me/likes/{movies|series}/{id}`,
 * los dos 204 e idempotentes):
 * - `onMutate`: corta un refresco en vuelo (traería la lista de antes del clic)
 *   y cambia la lista en la caché al instante.
 * - `onSuccess`: reafirma el estado confirmado (un refresco que empezó durante
 *   la petición pudo pisarlo) y avisa, diciendo para qué sirve.
 * - `onError`: deshace SOLO ese título. Un 404 al dar «Me gusta» significa que
 *   el título ya no existe (o la serie ya no tiene episodios): se dice así y se
 *   invalida su página, que pasa a «no encontrada». Quitar nunca da 404 (es
 *   idempotente). Si la sesión ya es otra, no se toca nada.
 * - `onSettled`: descarta las **recomendaciones** (dependen de lo que te gusta:
 *   la portada las pedirá de nuevo al volver, con su esqueleto si aún te gusta algo) y, cuando no queda ningún cambio
 *   en vuelo, marca la lista como anticuada sin pedirla. Si se pulsó sin lista
 *   (cargando o con error), la pide de nuevo: no hubo cambio optimista.
 *
 * Pulsar mientras la lista carga es seguro: el botón dice «Me gusta», pulsar
 * significa «me gusta» y el `PUT` es idempotente (si ya le gustaba, no cambia nada).
 */
export function useLikes(): LikesValue {
  const toast = useToast();
  const queryClient = useQueryClient();
  const likes = useQuery({ queryKey: LIKES_KEY, queryFn: ({ signal }) => fetchTitleLists(ENDPOINTS, signal) });
  const lists = likes.data ?? NO_LISTS;
  const status = loadStatusOf(likes);
  const errorMessage = status === 'error' ? getErrorMessage(likes.error) : '';
  const { refetch } = likes;

  // Títulos con un cambio en vuelo, leídos de la caché de mutaciones (compartida por todos los componentes).
  const pendingKeys = useMutationState({ filters: PENDING_FILTER, select: pendingKeyOf });

  const ids = useMemo(
    () => ({ movie: new Set(lists.movie.map((m) => m.id)), series: new Set(lists.series.map((s) => s.id)) }),
    [lists],
  );
  const isLiked = useCallback((id: number, kind: LikeKind) => ids[kind].has(id), [ids]);
  const isPending = useCallback(
    (id: number, kind: LikeKind) => pendingKeys.includes(pendingKey(kind, id)),
    [pendingKeys],
  );

  /** Cambia la lista de un tipo en la caché (si aún no hay lista, no hay nada que cambiar). */
  const updateCachedList = useCallback(
    (kind: LikeKind, change: (list: CatalogItem[]) => CatalogItem[]) => {
      queryClient.setQueryData<TitleLists>(LIKES_KEY, (current) => (current ? changeList(current, kind, change) : current));
    },
    [queryClient],
  );

  const { mutateAsync } = useMutation<void, unknown, ToggleVariables, ToggleContext>({
    mutationKey: TOGGLE_MUTATION_KEY,
    mutationFn: ({ kind, item, wasLiked }) =>
      apiFetch(`${ENDPOINTS[kind]}/${item.id}`, { method: wasLiked ? 'DELETE' : 'PUT' }),
    onMutate: async ({ kind, item, wasLiked }) => {
      const sessionKey = currentSessionKey();
      const before = queryClient.getQueryData<TitleLists>(LIKES_KEY);
      if (!before) return { originalIndex: -1, withoutList: true, sessionKey };
      await queryClient.cancelQueries({ queryKey: LIKES_KEY });
      updateCachedList(kind, (current) => (wasLiked ? current.filter((m) => m.id !== item.id) : [item, ...current]));
      return { originalIndex: before[kind].findIndex((m) => m.id === item.id), withoutList: false, sessionKey };
    },
    onSuccess: async (_data, { kind, item, wasLiked }, context) => {
      if (!isSameSession(context)) return;
      if (!context?.withoutList) {
        await queryClient.cancelQueries({ queryKey: LIKES_KEY });
        updateCachedList(kind, (current) => applyConfirmed(current, item, !wasLiked));
      }
      toast.success(wasLiked ? unlikedMessage(item.title) : likedMessage(item.title));
    },
    onError: (error, { kind, item, wasLiked }, context) => {
      if (!isSameSession(context)) return;
      // Vuelta atrás SOLO de este título, para no deshacer cambios de otros títulos en curso.
      updateCachedList(kind, (current) => {
        if (!wasLiked) return current.filter((m) => m.id !== item.id);
        if (current.some((m) => m.id === item.id)) return current;
        const restored = [...current];
        restored.splice(Math.max(context?.originalIndex ?? 0, 0), 0, item);
        return restored;
      });
      if (!wasLiked && error instanceof ApiError && error.status === 404) {
        toast.error(likeGoneMessage(kind, item.title));
        // Su página (si está abierta) vuelve a preguntar y pasa a «no encontrada»: no sigue ofreciendo un título que no existe.
        const detail = kind === 'movie' ? queryKeys.movies.detail(item.id) : queryKeys.series.detail(item.id);
        void queryClient.invalidateQueries({ queryKey: detail });
        return;
      }
      toast.errorFrom(
        error,
        wasLiked ? `No se pudo quitar el «Me gusta» de ${NOUN[kind]}.` : `No se pudo guardar el «Me gusta» de ${NOUN[kind]}.`,
      );
    },
    onSettled: async (_data, _error, _variables, context) => {
      if (!isSameSession(context)) return;
      // Las recomendaciones salen de lo que te gusta: se descartan (no solo se invalidan) para que la portada
      // no enseñe ni un instante las de antes, que podrían «recomendar» el título al que se acaba de dar «Me gusta».
      void queryClient.resetQueries({ queryKey: queryKeys.recommendations.all });
      if (context?.withoutList) {
        // Se pulsó sin lista: la carga en curso pudo salir antes del cambio. Se corta y se pide de nuevo.
        await queryClient.cancelQueries({ queryKey: LIKES_KEY });
        void queryClient.invalidateQueries({ queryKey: LIKES_KEY });
      } else if (queryClient.isMutating({ mutationKey: TOGGLE_MUTATION_KEY }) <= 1) {
        // Último cambio en vuelo (él mismo aún cuenta): la lista queda «anticuada» sin pedirla ya.
        void queryClient.invalidateQueries({ queryKey: LIKES_KEY, refetchType: 'none' });
      }
    },
  });

  const toggle = useCallback(
    async (kind: LikeKind, item: CatalogItem) => {
      const key = pendingKey(kind, item.id);
      // Se mira la caché de mutaciones (síncrona y común a todos los componentes): bloquea el doble clic
      // antes del siguiente render, y también si el cambio en vuelo lo empezó otra pantalla.
      const inFlight = queryClient.getMutationCache().findAll(PENDING_FILTER).some((m) => pendingKeyOf(m) === key);
      if (inFlight) return;
      // Se lee la caché (y no `ids`, de este render): tras varios clics seguidos es lo más reciente.
      const wasLiked = queryClient.getQueryData<TitleLists>(LIKES_KEY)?.[kind].some((m) => m.id === item.id) ?? false;
      await mutateAsync({ kind, item, wasLiked }).catch(() => undefined);
    },
    [queryClient, mutateAsync],
  );

  /** Vuelve a pedir las dos listas («Reintentar»): mientras tanto, `status` es `loading`. */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  return useMemo<LikesValue>(
    () => ({ movies: lists.movie, series: lists.series, status, errorMessage, isLiked, isPending, toggle, reload }),
    [lists, status, errorMessage, isLiked, isPending, toggle, reload],
  );
}
