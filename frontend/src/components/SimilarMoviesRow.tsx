import { useSimilarMovies } from '../hooks/useSimilarMovies';
import { RowSkeleton } from './CatalogSkeleton';
import { ErrorState } from './ErrorState';
import { MovieRow } from './MovieRow';

/** Propiedades de {@link SimilarMoviesRow}. */
interface Props {
  /** Id de la película de la página. */
  movieId: number;
  /** `true` cuando el servidor ha confirmado la película (ver `useMovieDetail`). */
  enabled: boolean;
}

/** Título de la fila (su `<h2>`). */
export const SIMILAR_MOVIES_TITLE = 'Películas similares';

/**
 * Fila «Películas similares» al pie de `/peliculas/:id`: para seguir navegando
 * por títulos parecidos sin volver al catálogo. Es la misma {@link MovieRow} de
 * la portada (tarjetas que enlazan a su página, flechas solo si la fila
 * desborda), con el orden que da el servidor.
 *
 * Estados, pensados para que la fila nunca estropee la página que la contiene:
 * - **Cargando**: la silueta de una fila ({@link RowSkeleton}), con su hueco ya
 *   reservado, anunciada como «Cargando películas similares...».
 * - **Sin similares** (lista vacía) o **404**: no se pinta nada, ni siquiera el
 *   título: un encabezado sin tarjetas prometería algo que no hay. El 404 solo
 *   llega si la película se borra mientras se ve, y entonces la página entera
 *   pasa a «Película no encontrada».
 * - **Error** (red, 5xx...): un aviso compacto (`ErrorState compact`) con
 *   «Reintentar» que solo vuelve a pedir la fila; el resto de la página sigue
 *   siendo útil. El texto es verdad para cualquier rol: no se pudo cargar.
 *   Al pulsarlo, el foco pasa al contenedor de la fila en `MovieDetailPage`
 *   (enfocable por código y presente en todos los estados; ver `useRetryFocus`).
 *
 * Al pulsar una tarjeta cambia la película de la página: la clave de la caché
 * es otra, así que la fila pasa a cargando (nunca enseña las similares de la
 * anterior) y `RouteChangeFocus` sube el scroll y lleva el foco al contenido.
 */
export function SimilarMoviesRow({ movieId, enabled }: Props) {
  const { status, movies, errorMessage, reload } = useSimilarMovies(movieId, enabled);

  if (status === 'hidden' || (status === 'ready' && movies.length === 0)) return null;
  if (status === 'ready') return <MovieRow title={SIMILAR_MOVIES_TITLE} movies={movies} />;
  if (status === 'error') {
    return (
      <div className="mb-9 px-4 sm:mb-10 sm:px-6">
        <ErrorState compact title="No se pudieron cargar las películas similares" message={errorMessage} onRetry={reload} />
      </div>
    );
  }
  return <RowSkeleton label="Cargando películas similares..." />;
}
