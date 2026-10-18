import { useCallback } from 'react';
import { queryOptions, usePrefetchQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, getErrorMessage } from '../lib/api';
import { loadStatusOf } from '../lib/queryClient';
import { queryKeys } from '../lib/queryKeys';
import type { TitleLists } from '../lib/titleLists';
import type { Recommendations } from '../lib/types';

/**
 * Cuántos títulos de cada tipo se piden. Doce, como «Películas similares»:
 * llenan la fila en una pantalla de 1920 px (con flechas para el resto en las
 * más estrechas) sin convertirla en otro catálogo. El servidor admite de 1 a 24.
 */
export const RECOMMENDATIONS_LIMIT = 12;

/** Respuesta vacía mientras no ha llegado la de verdad (misma identidad en cada render). */
const NONE: Recommendations = { basedOnLikes: false, movies: [], series: [] };

/**
 * Opciones de la consulta de recomendaciones, compartidas por
 * {@link useRecommendations} y {@link usePrefetchRecommendations}: así las dos
 * usan la misma clave y la misma petición, y la precarga no puede «desviarse».
 */
function recommendationsQuery() {
  return queryOptions({
    queryKey: queryKeys.recommendations.list(RECOMMENDATIONS_LIMIT),
    queryFn: ({ signal }) =>
      apiFetch<Recommendations>(`/users/me/recommendations?limit=${RECOMMENDATIONS_LIMIT}`, { signal }),
  });
}

/**
 * Lanza la petición de las recomendaciones YA, sin esperar a que se monten sus
 * filas. La usa la portada en su primer render.
 *
 * **Por qué.** Las filas (`RecommendedRows`) solo se montan cuando ha llegado
 * el catálogo, así que sin esto la petición salía DESPUÉS de la del catálogo
 * (una cascada) y, para quien tiene «Me gusta», las filas aparecían un momento
 * más tarde y empujaban hacia abajo las de debajo. En esa primera carga ya no
 * se reserva el hueco (para quien no tiene «Me gusta» eran un hueco y un aviso
 * que no llevaban a nada; ver `reserveSpace`), así que conviene que la
 * respuesta llegue a la vez que el catálogo. No es una petición nueva: es la
 * misma, antes.
 *
 * Si la consulta ya tiene estado en la caché (datos, en curso o error), no
 * hace nada (`usePrefetchQuery`).
 */
export function usePrefetchRecommendations(): void {
  usePrefetchQuery(recommendationsQuery());
}

/**
 * `true` si la caché YA sabe que al usuario le gusta al menos un título: la
 * entrada `queryKeys.likes.all` que llena `useLikes` en la página de un título
 * o en el perfil. La portada no pide los «Me gusta» (serían dos peticiones que
 * no pinta nadie): solo mira si ya están.
 *
 * Se lee con `getQueryData` y no con una suscripción porque solo importa en el
 * instante en que las recomendaciones empiezan a cargar; si la caché cambiara
 * durante la carga, lo peor sería no reservar el hueco, que es lo mismo que no
 * saberlo.
 */
function cacheKnowsUserHasLikes(lists: TitleLists | undefined): boolean {
  return lists !== undefined && (lists.movie.length > 0 || lists.series.length > 0);
}

/**
 * Recomendaciones del usuario de la sesión para la portada
 * (`GET /api/users/me/recommendations?limit=12`): títulos que comparten géneros
 * con lo que le gusta, sin lo que ya le gusta, en dos listas ya ordenadas por
 * el servidor.
 *
 * - Se guardan en `queryKeys.recommendations.list(12)`. `useLikes` las
 *   **descarta** tras cada «Me gusta» (`resetQueries`): al volver a la portada
 *   se piden de nuevo y nunca se ve un instante las de antes, que podrían
 *   «recomendar» el título al que se acaba de dar «Me gusta». Tras un cambio
 *   del catálogo solo se invalidan (`keysAffectedBy`): ahí unas un poco viejas
 *   mientras llegan las nuevas no dicen nada falso sobre el usuario.
 * - `reserveSpace`: mientras cargan, si hay motivo para esperar algo y por
 *   tanto para reservar el hueco con un esqueleto anunciado:
 *   - la caché ya sabe que el usuario tiene «Me gusta» (ver
 *     {@link cacheKnowsUserHasLikes}). Es justo el caso de volver a la portada
 *     tras dar un «Me gusta»: casi seguro llegan filas, y sin hueco empujarían
 *     hacia abajo la fila «Series» que ya se estaba viendo;
 *   - o se está reintentando tras un error («Reintentar»): el usuario ha pedido
 *     algo y debe ver y oír que está en marcha, y el foco que `useRetryFocus`
 *     deja en el contenedor necesita que este siga visible.
 *
 *   En cualquier otro caso (la primera carga de quien no tiene «Me gusta», o
 *   no se sabe), nada: ni hueco ni `role="status"`.
 * - Dato PERSONAL: la caché se vacía al cambiar de sesión, nunca se ven las del
 *   usuario anterior.
 * - Reintentos: los de la aplicación (uno, solo en red/5xx).
 */
export function useRecommendations() {
  const queryClient = useQueryClient();
  const query = useQuery(recommendationsQuery());
  const { refetch } = query;
  const status = loadStatusOf(query);

  /** Vuelve a pedirlas (botón «Reintentar» de su aviso de error). */
  const reload = useCallback(() => {
    void refetch();
  }, [refetch]);

  return {
    status,
    recommendations: query.data ?? NONE,
    errorMessage: status === 'error' ? getErrorMessage(query.error) : '',
    // Reintento tras un error: sin datos, TanStack vuelve a `pending` al repetir la petición (`isError` pasa a
    // `false`), pero `errorUpdateCount` recuerda que ya falló y se pintó el aviso.
    reserveSpace:
      status === 'loading' &&
      (query.errorUpdateCount > 0 ||
        cacheKnowsUserHasLikes(queryClient.getQueryData<TitleLists>(queryKeys.likes.all))),
    reload,
  };
}
