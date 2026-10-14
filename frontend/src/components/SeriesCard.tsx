import { seriesPath } from '../lib/catalog';
import { seriesCardMeta } from '../lib/series';
import type { Series } from '../lib/types';
import { PosterCard } from './PosterCard';

/** Propiedades de {@link SeriesCard}. */
interface Props {
  series: Series;
  /** Tamaño dentro de su contenedor (ver {@link PosterCard}): ancho fijo en una fila, `w-full` en una rejilla. */
  className?: string;
}

/**
 * Tarjeta de serie: póster, título, años y número de temporadas
 * ("2019–2022 · 3 temporadas").
 *
 * Como la de película, es un ENLACE a la página del título (`/series/:id`, con
 * sus temporadas y episodios): pulsar cambia de URL y se puede abrir en otra pestaña.
 */
export function SeriesCard({ series, className }: Props) {
  return (
    <PosterCard
      title={series.title}
      imageUrl={series.imageUrl}
      meta={seriesCardMeta(series)}
      to={seriesPath(series.id)}
      className={className}
    />
  );
}
