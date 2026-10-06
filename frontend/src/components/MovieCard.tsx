import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { PosterCard } from './PosterCard';

/**
 * Propiedades para el componente MovieCard.
 */
interface Props {
  movie: Movie;
  onClick: (movie: Movie) => void;
  /** Tamaño dentro de su contenedor (ver {@link PosterCard}): ancho fijo en una fila, `w-full` en una rejilla. */
  className?: string;
}

/**
 * Tarjeta de película: póster, título, año y duración; al pulsarla se abren
 * los detalles en un diálogo.
 *
 * El aspecto, la accesibilidad y los efectos son los de {@link PosterCard}
 * (compartida con las series); aquí solo se decide el dato secundario
 * ("2014 · 2h 49m") y que pulsar abre un diálogo (por eso es un `<button>` con
 * `aria-haspopup="dialog"`, no un enlace).
 *
 * @param props la película, el manejador de clics y, opcionalmente, las clases de tamaño
 */
export function MovieCard({ movie, onClick, className }: Props) {
  return (
    <PosterCard
      title={movie.title}
      imageUrl={movie.imageUrl}
      meta={`${movie.releaseYear} · ${formatDuration(movie.duration)}`}
      onClick={() => onClick(movie)}
      className={className}
    />
  );
}
