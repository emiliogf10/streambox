import { useRecommendations } from '../hooks/useRecommendations';
import { RowSkeleton } from './CatalogSkeleton';
import { ErrorState } from './ErrorState';
import { MovieRow } from './MovieRow';
import { SeriesRow } from './SeriesRow';

/** Título de la fila de películas recomendadas (su `<h2>` y el nombre de su región). */
export const RECOMMENDED_MOVIES_TITLE = 'Películas recomendadas para ti';

/** Título de la fila de series recomendadas. */
export const RECOMMENDED_SERIES_TITLE = 'Series recomendadas para ti';

/**
 * Filas «Películas recomendadas para ti» y «Series recomendadas para ti» de la
 * portada, a partir de lo que le gusta al usuario (`useRecommendations`).
 *
 * **Dos filas y no una mezclada.** El servidor ordena cada lista por afinidad
 * por separado y no da una puntuación común: intercalarlas sería inventar un
 * orden. Además, cada fila es de un solo tipo de tarjeta, como el resto de la
 * portada («Novedades» de películas, «Series»), y el título dice qué hay en
 * ella. Cada una aparece solo si tiene algo (una fila vacía no se pinta).
 *
 * Estados, pensados para que nunca estropeen la portada:
 * - **Cargando**: por defecto, **nada** (ni hueco ni `role="status"`). Antes se
 *   pintaba siempre la silueta de una fila con «Cargando recomendaciones...», y
 *   a quien no tiene «Me gusta» (todo usuario nuevo, casi todo ADMIN) se le
 *   anunciaba una carga que no llevaba a nada y, al desaparecer, las filas de
 *   debajo subían de golpe. La silueta solo se pinta cuando hay motivo para
 *   esperar filas (`reserveSpace` de `useRecommendations`: la caché ya sabe que
 *   tiene «Me gusta», o se está reintentando tras un error). En la primera carga
 *   la petición sale a la vez que la del catálogo (`usePrefetchRecommendations`
 *   en `HomePage`), así que lo normal es que las filas lleguen con él.
 * - **Sin «Me gusta»** (`basedOnLikes: false`) o sin nada que recomendar: no
 *   se pinta nada. Las novedades ya están justo encima; una fila que dijera
 *   «aún no hay recomendaciones» sería ruido en la portada (cómo conseguirlas lo
 *   explica la sección «Te gusta» del perfil).
 * - **Error** (red, 5xx...): un aviso compacto con «Reintentar» que solo
 *   vuelve a pedir las recomendaciones; el resto de la portada sigue siendo
 *   útil. El texto es verdad para cualquier rol: no se pudieron cargar. El foco
 *   tras «Reintentar» va al contenedor de la portada (ver `HomePage`).
 *
 * Mismo texto para los dos roles: las recomendaciones dependen de los «Me
 * gusta» de la cuenta, y un administrador también tiene los suyos.
 */
export function RecommendedRows() {
  const { status, recommendations, errorMessage, reserveSpace, reload } = useRecommendations();

  if (status === 'loading') return reserveSpace ? <RowSkeleton label="Cargando recomendaciones..." /> : null;
  if (status === 'error') {
    return (
      <div className="mb-9 px-4 sm:mb-10 sm:px-6">
        <ErrorState compact title="No se pudieron cargar tus recomendaciones" message={errorMessage} onRetry={reload} />
      </div>
    );
  }
  if (!recommendations.basedOnLikes) return null;
  // `MovieRow`/`SeriesRow` no pintan nada sin elementos: cada fila sale solo si tiene títulos.
  return (
    <>
      <MovieRow title={RECOMMENDED_MOVIES_TITLE} movies={recommendations.movies} />
      <SeriesRow title={RECOMMENDED_SERIES_TITLE} series={recommendations.series} />
    </>
  );
}
