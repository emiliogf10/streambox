import { useFavoriteGenres, useGenreOptions } from '../hooks/useFavoriteGenres';
import { useRecommendations } from '../hooks/useRecommendations';
import { hasEnoughGenreOptions, recommendationsReason } from '../lib/favoriteGenres';
import { RowSkeleton } from './CatalogSkeleton';
import { ErrorState } from './ErrorState';
import { GenresInviteCard } from './GenresInviteCard';
import { MovieRow } from './MovieRow';
import { SeriesRow } from './SeriesRow';

/** Título de la fila de películas recomendadas (su `<h2>` y el nombre de su región). */
export const RECOMMENDED_MOVIES_TITLE = 'Películas recomendadas para ti';

/** Título de la fila de series recomendadas. */
export const RECOMMENDED_SERIES_TITLE = 'Series recomendadas para ti';

/** Propiedades de {@link RecommendedRows}. */
interface RecommendedRowsProps {
  /**
   * `true` si, sin nada en qué basar las recomendaciones (`basis: 'NONE'`), hay
   * que pintar la tarjeta «Dinos qué géneros te gustan…» ({@link GenresInviteCard}).
   * La portada lo pide solo cuando tiene películas que enseñar: con el catálogo
   * vacío no habría géneros que elegir y la tarjeta llevaría a una sección sin
   * opciones, encima del aviso «El catálogo está vacío».
   */
  inviteToChooseGenres?: boolean;
}

/**
 * Filas «Películas recomendadas para ti» y «Series recomendadas para ti» de la
 * portada, a partir de los géneros favoritos y de lo que le gusta al usuario
 * (`useRecommendations`), o la invitación a elegir sus géneros si aún no hay
 * nada en qué basarlas.
 *
 * **Dos filas y no una mezclada.** El servidor ordena cada lista por afinidad
 * por separado y no da una puntuación común: intercalarlas sería inventar un
 * orden. Además, cada fila es de un solo tipo de tarjeta, como el resto de la
 * portada («Novedades» de películas, «Series»), y el título dice qué hay en
 * ella. Cada una aparece solo si tiene algo (una fila vacía no se pinta).
 *
 * **El porqué, en cada fila.** Bajo el título, una frase que dice la verdad
 * según `basis` ({@link recommendationsReason}): con géneros favoritos los
 * nombra («Porque te gustan Acción, Drama y Comedia.», de `useFavoriteGenres`,
 * que solo se pide en ese caso); con «Me gusta», lo dice; con los dos, los
 * combina. Mientras llegan los nombres, una frase igual de cierta sin ellos. Va
 * en las dos filas porque cada una puede salir sola.
 *
 * Estados, pensados para que nunca estropeen la portada:
 * - **Cargando**: por defecto, **nada** (ni hueco ni `role="status"`). Antes se
 *   pintaba siempre la silueta de una fila con «Cargando recomendaciones...», y
 *   a quien no tiene en qué basarlas (todo usuario nuevo, casi todo ADMIN) se le
 *   anunciaba una carga que no llevaba a nada y, al desaparecer, las filas de
 *   debajo subían de golpe. La silueta solo se pinta cuando hay motivo para
 *   esperar filas (`reserveSpace` de `useRecommendations`: la caché ya sabe que
 *   tiene «Me gusta» o géneros favoritos, o se está reintentando tras un error).
 *   En la primera carga la petición sale a la vez que la del catálogo
 *   (`usePrefetchRecommendations` en `HomePage`), así que lo normal es que las
 *   filas lleguen con él.
 * - **Sin nada en qué basarlas** (`basis: 'NONE'`): la tarjeta «Dinos qué
 *   géneros te gustan…» si la portada lo permite (`inviteToChooseGenres`) Y hay
 *   al menos 3 géneros elegibles (`useGenreOptions`, que solo se pide en este
 *   caso); si no, nada. Con menos de 3 la tarjeta llevaría a un selector
 *   imposible (revisión de `qa`), y si las opciones fallan tampoco se pinta: una
 *   invitación que podría ser falsa es peor que ninguna, y «Tus gustos» sigue en
 *   el perfil. **Mientras llegan las opciones** se reserva su hueco con la propia
 *   tarjeta, invisible (`visibility: hidden`: ni se ve, ni se lee, ni se enfoca):
 *   esa petición sale después de la de las recomendaciones y, sin reserva, la
 *   tarjeta aparecía tarde y empujaba 160 px la fila «Series» (revisión visual).
 *   Con la tarjeta misma, el hueco mide lo mismo a cualquier ancho sin fijar
 *   ninguna altura a mano. Si al final no se pinta (caso raro: catálogo casi
 *   vacío o fallo), el hueco se cierra.
 * - **Con base pero sin nada que recomendar** (p. ej. ya le gusta todo lo de sus
 *   géneros): nada. Las novedades ya están justo encima.
 * - **Error** (red, 5xx...): un aviso compacto con «Reintentar» que solo
 *   vuelve a pedir las recomendaciones; el resto de la portada sigue siendo
 *   útil. El texto es verdad para cualquier rol: no se pudieron cargar. El foco
 *   tras «Reintentar» va al contenedor de la portada (ver `HomePage`).
 *
 * Mismo texto para los dos roles: las recomendaciones dependen de los gustos y
 * los «Me gusta» de la cuenta, y un administrador también tiene los suyos.
 */
export function RecommendedRows({ inviteToChooseGenres = false }: RecommendedRowsProps) {
  const { status, recommendations, errorMessage, reserveSpace, reload } = useRecommendations();
  const { basis } = recommendations;
  const usesGenres = status === 'ready' && (basis === 'GENRES' || basis === 'GENRES_AND_LIKES');
  const favoriteGenres = useFavoriteGenres({ enabled: usesGenres });
  // La tarjeta solo invita si se pueden elegir de verdad 3 géneros: con menos, «Tus gustos» no tendría selector.
  const mayInvite = inviteToChooseGenres && status === 'ready' && basis === 'NONE';
  const genreOptions = useGenreOptions({ enabled: mayInvite });

  if (status === 'loading') return reserveSpace ? <RowSkeleton label="Cargando recomendaciones..." /> : null;
  if (status === 'error') {
    return (
      <div className="mb-9 px-4 sm:mb-10 sm:px-6">
        <ErrorState compact title="No se pudieron cargar tus recomendaciones" message={errorMessage} onRetry={reload} />
      </div>
    );
  }
  if (basis === 'NONE') {
    if (!mayInvite) return null;
    if (genreOptions.status === 'loading') {
      return (
        <div aria-hidden="true" className="invisible">
          <GenresInviteCard />
        </div>
      );
    }
    const canChoose = genreOptions.status === 'ready' && hasEnoughGenreOptions(genreOptions.genres.length);
    return canChoose ? <GenresInviteCard /> : null;
  }

  const names = favoriteGenres.status === 'ready' ? favoriteGenres.genres.map((genre) => genre.name) : undefined;
  const reason = recommendationsReason(basis, names) ?? undefined;
  // `MovieRow`/`SeriesRow` no pintan nada sin elementos: cada fila sale solo si tiene títulos.
  return (
    <>
      <MovieRow title={RECOMMENDED_MOVIES_TITLE} movies={recommendations.movies} description={reason} />
      <SeriesRow title={RECOMMENDED_SERIES_TITLE} series={recommendations.series} description={reason} />
    </>
  );
}
