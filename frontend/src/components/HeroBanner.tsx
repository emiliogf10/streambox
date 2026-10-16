import { Info } from 'lucide-react';
import { Link } from 'react-router-dom';
import { moviePath } from '../lib/catalog';
import type { Movie } from '../lib/types';
import { getSafeVideoUrl } from '../lib/utils';
import { getTrailer } from '../lib/youtube';
import { buttonClasses } from './buttonStyles';
import { FavoriteButton } from './FavoriteButton';
import { FeaturedBanner } from './FeaturedBanner';
import { MovieMetaTags } from './MovieMetaTags';
import { NothingToWatchNote } from './NothingToWatchNote';
import { TrailerButton } from './TrailerButton';
import { WatchButton } from './WatchButton';

/**
 * Aviso para un administrador cuando la película del banner no tiene vídeo ni
 * tráiler. El banner no tiene «Editar»: está en la página de la película, a la
 * que lleva «Más información».
 */
const ADMIN_NOTHING_TO_WATCH = 'No tiene vídeo ni tráiler: puedes añadirlos desde su página, con «Editar».';

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
 * "Estreno reciente", año y duración ({@link MovieMetaTags}) y las acciones,
 * con UNA sola principal (blanca), que depende de lo que tenga la película:
 *
 * - **Con vídeo**: «Ver ahora» (principal; {@link WatchButton}), «Ver tráiler»
 *   si lo hay (secundaria, con borde; {@link TrailerButton}), «Mi lista»
 *   (secundaria; {@link FavoriteButton}) y «Más información» (terciaria, sin
 *   borde): un ENLACE a la página de la película (`/peliculas/:id`).
 * - **Sin vídeo, con tráiler**: «Ver tráiler» pasa a ser la principal: es lo
 *   único que se puede ver.
 * - **Sin vídeo ni tráiler**: no hay nada que ver aquí, así que la principal es
 *   «Más información» y va primero; detrás, «Mi lista». Debajo, un aviso
 *   breve ({@link NothingToWatchNote}) explica por qué no hay nada que ver.
 *
 * Nunca hay un «Ver ahora» sin vídeo: el botón no prometería algo que no existe.
 *
 * El título es un `<h2>`: el `<h1>` de la página lo pone quien usa el banner.
 *
 * @param props la película a destacar
 */
export function HeroBanner({ movie }: Props) {
  const hasVideo = getSafeVideoUrl(movie.videoUrl) !== null;
  const hasTrailer = getTrailer(movie.trailerUrl) !== null;
  const nothingToWatch = !hasVideo && !hasTrailer;

  const moreInfo = (
    <Link to={moviePath(movie.id)} className={buttonClasses(nothingToWatch ? 'light' : 'ghost')}>
      <Info aria-hidden="true" className="size-4" />
      Más información
      <span className="sr-only"> sobre {movie.title}</span>
    </Link>
  );

  return (
    <FeaturedBanner
      title={movie.title}
      imageUrl={movie.imageUrl}
      meta={<MovieMetaTags movie={movie} badge="Estreno reciente" />}
      description={movie.description}
      actionsNote={nothingToWatch && <NothingToWatchNote adminText={ADMIN_NOTHING_TO_WATCH} />}
      actions={
        nothingToWatch ? (
          <>
            {moreInfo}
            <FavoriteButton movie={movie} />
          </>
        ) : (
          <>
            <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
            <TrailerButton trailerUrl={movie.trailerUrl} title={movie.title} variant={hasVideo ? 'outline' : 'light'} />
            <FavoriteButton movie={movie} />
            {moreInfo}
          </>
        )
      }
    />
  );
}
