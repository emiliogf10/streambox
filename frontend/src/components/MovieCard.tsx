import { moviePath } from '../lib/catalog';
import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { PosterCard } from './PosterCard';

/**
 * Propiedades para el componente MovieCard.
 */
interface Props {
  movie: Movie;
  /** Tamaño dentro de su contenedor (ver {@link PosterCard}): ancho fijo en una fila, `w-full` en una rejilla. */
  className?: string;
}

/**
 * Tarjeta de película: póster, título, año y duración; es un ENLACE a la
 * página de la película (`/peliculas/:id`), igual que la de serie lleva a la suya.
 *
 * El aspecto, la accesibilidad y los efectos son los de {@link PosterCard}
 * (compartida con las series); aquí solo se decide el dato secundario
 * ("2014 · 2h 49m") y el destino.
 *
 * @param props la película y, opcionalmente, las clases de tamaño
 */
export function MovieCard({ movie, className }: Props) {
  return (
    <PosterCard
      title={movie.title}
      imageUrl={movie.imageUrl}
      meta={`${movie.releaseYear} · ${formatDuration(movie.duration)}`}
      to={moviePath(movie.id)}
      className={className}
    />
  );
}
