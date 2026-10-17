import { Info } from 'lucide-react';
import { Link } from 'react-router-dom';
import { seriesPath } from '../lib/catalog';
import type { Series } from '../lib/types';
import { buttonClasses } from './buttonStyles';
import { FavoriteButton } from './FavoriteButton';
import { FeaturedBanner } from './FeaturedBanner';
import { SeriesMetaTags } from './SeriesMetaTags';
import { TrailerButton } from './TrailerButton';

/**
 * Banner de la página `/series`: la serie más reciente del catálogo.
 *
 * Mismo diseño que el de la portada ({@link FeaturedBanner}). Cambian la marca
 * ("Novedad": es la última serie añadida, no necesariamente un estreno), los
 * datos ({@link SeriesMetaTags}) y las acciones:
 * - **«Más información»** (principal) es un ENLACE a la página de la serie
 *   (`/series/:id`), con sus temporadas y episodios. Antes decía «Ver
 *   episodios», que se lee como «reproducir»; pero el banner no sabe si hay
 *   algo que ver (el listado no trae los episodios y, desde la V6, ningún
 *   episodio de ejemplo tiene vídeo propio). «Más información» siempre es
 *   verdad y es el mismo texto (y el mismo nombre accesible, «Más información
 *   sobre <título>») que el banner de películas (`HeroBanner`).
 * - **«Ver tráiler»** ({@link TrailerButton}), secundaria y solo si la serie
 *   tiene tráiler: abre el tráiler de YouTube en un diálogo, sin salir de aquí.
 *   «Más información» sigue siendo la principal: lleva a la serie entera.
 * - **«Mi lista»** ({@link FavoriteButton}) guarda la serie en su sección de la lista.
 */
export function SeriesHeroBanner({ series }: { series: Series }) {
  return (
    <FeaturedBanner
      title={series.title}
      imageUrl={series.imageUrl}
      meta={<SeriesMetaTags series={series} badge="Novedad" />}
      description={series.description}
      actions={
        <>
          <Link to={seriesPath(series.id)} className={buttonClasses('light')}>
            <Info aria-hidden="true" className="size-4" />
            Más información
            <span className="sr-only"> sobre {series.title}</span>
          </Link>
          <TrailerButton trailerUrl={series.trailerUrl} title={series.title} />
          <FavoriteButton series={series} />
        </>
      }
    />
  );
}
