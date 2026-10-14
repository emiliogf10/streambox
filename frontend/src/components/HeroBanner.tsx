import { Info } from 'lucide-react';
import { Link } from 'react-router-dom';
import { moviePath } from '../lib/catalog';
import type { Movie } from '../lib/types';
import { buttonClasses } from './buttonStyles';
import { FavoriteButton } from './FavoriteButton';
import { FeaturedBanner } from './FeaturedBanner';
import { MovieMetaTags } from './MovieMetaTags';
import { WatchButton } from './WatchButton';

/**
 * Propiedades del componente HeroBanner.
 */
interface Props {
  movie: Movie;
}

/**
 * Banner principal de la portada y de `/peliculas`: la película más reciente del catálogo.
 *
 * El diseño (fondo desenfocado + póster nítido junto al título, móvil en
 * rejilla, imágenes con prioridad) es el de {@link FeaturedBanner}, compartido
 * con las series. Aquí se decide lo propio de una película: la marca
 * "Estreno reciente", año y duración ({@link MovieMetaTags}) y tres acciones con
 * jerarquía: "Ver ahora" (principal, blanco; enlace al vídeo, ver
 * {@link WatchButton}), "Mi lista" (secundaria, con borde; {@link FavoriteButton})
 * y "Más información" (terciaria, sin borde): un ENLACE a la página de la
 * película (`/peliculas/:id`), con la sinopsis completa. En móvil las dos
 * primeras comparten fila y la tercera va debajo.
 *
 * El título es un `<h2>`: el `<h1>` de la página lo pone quien usa el banner.
 *
 * @param props la película a destacar
 */
export function HeroBanner({ movie }: Props) {
  return (
    <FeaturedBanner
      title={movie.title}
      imageUrl={movie.imageUrl}
      meta={<MovieMetaTags movie={movie} badge="Estreno reciente" />}
      description={movie.description}
      actions={
        <>
          <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
          <FavoriteButton movie={movie} />
          <Link to={moviePath(movie.id)} className={buttonClasses('ghost', 'col-span-2 sm:col-span-1')}>
            <Info aria-hidden="true" className="size-4" />
            Más información
            <span className="sr-only"> sobre {movie.title}</span>
          </Link>
        </>
      }
    />
  );
}
