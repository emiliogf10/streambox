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
 * A diferencia de la de película, es un ENLACE a `/series/:id`: el detalle de
 * una serie es una página propia (tiene temporadas y episodios, demasiado para
 * un diálogo), así que pulsar cambia de URL y se puede abrir en otra pestaña.
 */
export function SeriesCard({ series, className }: Props) {
  return (
    <PosterCard
      title={series.title}
      imageUrl={series.imageUrl}
      meta={seriesCardMeta(series)}
      to={`/series/${series.id}`}
      className={className}
    />
  );
}
