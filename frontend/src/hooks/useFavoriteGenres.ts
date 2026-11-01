import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { useAuth } from '../context/AuthContext';
import { apiFetch, currentSessionKey, getErrorMessage } from '../lib/api';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import type { FavoriteGenresRequest, Genre } from '../lib/types';

/** Lista vacía estable (misma identidad en cada render) mientras no hay datos. */
const NO_GENRES: Genre[] = [];

/**
 * Las dos listas de géneros de «Tus gustos»: su clave en la caché y su endpoint.
 * - `mine`: los favoritos del usuario (`GET /api/users/me/genres`);
 * - `options`: los que se pueden elegir (`GET /api/users/me/genres/options`).
 */
const GENRE_LISTS = {
  mine: { queryKey: queryKeys.favoriteGenres.mine, path: '/users/me/genres' },
  options: { queryKey: queryKeys.favoriteGenres.options, path: '/users/me/genres/options' },
} as const;

/** Opciones de {@link useFavoriteGenres} y {@link useGenreOptions}. */
interface GenreQueryOptions {
  /**
   * `false` para no pedir nada todavía (p. ej. la portada solo necesita los
   * nombres de los favoritos cuando las recomendaciones se basan en ellos).
   */
  enabled?: boolean;
}

/**
 * Los tres estados de una consulta de géneros, como los pintan las pantallas:
 * `status` (cargando/listo/error), la lista (vacía mientras no hay datos), el
 * mensaje del error y `reload` para el botón «Reintentar».
 */
function useGenreList(list: keyof typeof GENRE_LISTS, enabled: boolean) {
  const { queryKey, path } = GENRE_LISTS[list];
  const query = useQuery<Genre[], unknown, Genre[], QueryKey>({
    queryKey,
    queryFn: ({ signal }) => apiFetch<Genre[]>(path, { signal }),
    enabled,
  });
  const status = loadStatusOf(query);
  const { refetch } = query;
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);
  return {
    status,
    genres: query.data ?? NO_GENRES,
    errorMessage: status === 'error' ? getErrorMessage(query.error) : '',
    reload,
  };
}

/**
 * Géneros favoritos del usuario de la sesión («Tus gustos»), ordenados por nombre
 * por el servidor. Dato PERSONAL: la caché se vacía en cada cambio de sesión.
 * Reintentos: los de la aplicación (uno, solo en red/5xx).
 */
export function useFavoriteGenres({ enabled = true }: GenreQueryOptions = {}) {
  return useGenreList('mine', enabled);
}

/**
 * Géneros que se pueden elegir como favoritos: los que tienen algún título
 * visible (película, o serie con episodios), por nombre. Elegir uno sin títulos
 * no daría ninguna recomendación, por eso el servidor ni lo ofrece (y lo
 * rechaza con 400 si llega en el `PUT`).
 */
export function useGenreOptions({ enabled = true }: GenreQueryOptions = {}) {
  return useGenreList('options', enabled);
}

/** Contexto de la mutación: a qué sesión pertenecía la petición. */
interface SaveContext {
  sessionKey: number;
}

/**
 * Guarda los géneros favoritos (`PUT /api/users/me/genres`): de 3 a 5 ids, o la
 * lista vacía («Ahora no» en la bienvenida, «Quitar mis gustos» en el perfil).
 *
 * `save` devuelve la lista que guardó el servidor y lanza su error (400
 * `VALIDATION_ERROR` con `validationErrors.genreIds`, red, 5xx...) para que cada
 * pantalla lo enseñe donde corresponde. Si la respuesta es de otra sesión (se
 * cerró o se cambió de cuenta mientras tanto), no toca nada.
 *
 * Tras guardar:
 * - la respuesta pasa a ser la entrada `favoriteGenres.mine` (no se vuelve a pedir);
 * - las **recomendaciones se descartan** (`resetQueries`, no solo se invalidan),
 *   igual que tras un «Me gusta»: dependen de los gustos y la portada no debe
 *   enseñar ni un instante las de antes (p. ej. la tarjeta «Dinos qué géneros…»
 *   justo después de elegirlos);
 * - el usuario de la sesión deja de tener la pregunta pendiente
 *   (`genresOnboardingPending: false`): el servidor la cierra con CUALQUIER
 *   `PUT`, así que se refleja con `updateUser` sin volver a pedir `/users/me`
 *   (eso pasaría por «cargando» y haría parpadear las guardas). Se cambia solo
 *   ese campo sobre el usuario más reciente (actualizador funcional), no sobre
 *   el de cuando se pintó la pantalla.
 *
 * Las opciones (`favoriteGenres.options`) no se tocan: no dependen de lo elegido.
 */
export function useSaveFavoriteGenres() {
  const queryClient = useQueryClient();
  const { updateUser } = useAuth();
  const { mutateAsync, isPending } = useMutation<Genre[], unknown, number[], SaveContext>({
    mutationFn: (genreIds) => {
      const body: FavoriteGenresRequest = { genreIds };
      return apiFetch<Genre[]>('/users/me/genres', { method: 'PUT', body });
    },
    onMutate: () => ({ sessionKey: currentSessionKey() }),
    onSuccess: (saved, _genreIds, context) => {
      if (context?.sessionKey !== currentSessionKey()) return;
      queryClient.setQueryData(queryKeys.favoriteGenres.mine, saved);
      void queryClient.resetQueries({ queryKey: queryKeys.recommendations.all });
      // Con el actualizador funcional: el `PATCH` del nombre o del avatar pudo terminar mientras tanto, y
      // `{ ...user }` (el del render) los devolvería a su valor anterior.
      updateUser((latest) => (latest.genresOnboardingPending ? { ...latest, genresOnboardingPending: false } : latest));
    },
  });

  const save = useCallback((genreIds: number[]) => mutateAsync(genreIds), [mutateAsync]);
  return { save, saving: isPending };
}
