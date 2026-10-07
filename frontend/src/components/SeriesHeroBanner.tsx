import { ListVideo } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { Series } from '../lib/types';
import { buttonClasses } from './buttonStyles';
import { FavoriteButton } from './FavoriteButton';
import { FeaturedBanner } from './FeaturedBanner';
import { SeriesMetaTags } from './SeriesMetaTags';

/**
 * Banner de la página `/series`: la serie más reciente del catálogo.
 *
 * Mismo diseño que el de la portada ({@link FeaturedBanner}). Cambian la marca
 * ("Novedad": es la última serie añadida, no necesariamente un estreno), los
 * datos ({@link SeriesMetaTags}) y las acciones:
 * - **«Ver episodios»** (principal) es un ENLACE a la página de la serie, no un
 *   botón que abre un vídeo: una serie no tiene un único vídeo, hay que elegir
 *   temporada y episodio.
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
          <Link to={`/series/${series.id}`} className={buttonClasses('light')}>
            <ListVideo aria-hidden="true" className="size-4" />
            Ver episodios
            <span className="sr-only"> de {series.title}</span>
          </Link>
          <FavoriteButton series={series} />
        </>
      }
    />
  );
}
