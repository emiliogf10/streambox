import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { MoviePoster } from './MoviePoster';

/**
 * Propiedades para el componente MovieCard.
 */
interface Props {
  movie: Movie;
  onClick: (movie: Movie) => void;
  /** Tamaño dentro de su contenedor: ancho fijo en una fila (`w-40 sm:w-44`), `w-full` en una rejilla. */
  className?: string;
}

/**
 * Tarjeta de película: póster e información básica, pulsable para ver los detalles.
 *
 * Es un `<button>` (no un `div` con `onClick`): recibe foco, se activa con
 * Intro/Espacio y tiene anillo de foco visible. Su nombre accesible sale del
 * texto que contiene (título + año y duración); el póster es decorativo
 * (`alt` vacío) porque el título ya está escrito debajo. `aria-haspopup="dialog"`
 * avisa de que abre un diálogo.
 *
 * Los efectos al pasar el ratón (zoom y oscurecido del póster) son solo CSS
 * (`group-hover`) y se desactivan con `prefers-reduced-motion`.
 *
 * @param props Propiedades del componente que contienen los datos de la película y el manejador de clics.
 */
export function MovieCard({ movie, onClick, className = 'w-40 shrink-0 sm:w-44' }: Props) {
  const meta = `${movie.releaseYear} · ${formatDuration(movie.duration)}`;
  return (
    <button
      type="button"
      onClick={() => onClick(movie)}
      aria-haspopup="dialog"
      className={`focus-ring group block rounded-xl text-left ${className}`}
    >
      <div className="relative mb-2 overflow-hidden rounded-xl">
        <MoviePoster
          title={movie.title}
          src={movie.imageUrl}
          className="aspect-2/3 w-full"
          imgClassName="transition-transform duration-300 group-hover:scale-105"
        />
        <div
          aria-hidden="true"
          className="absolute inset-0 bg-black/0 transition-colors duration-300 group-hover:bg-black/25"
        />
      </div>
      <div className="px-0.5">
        <p className="truncate text-sm font-medium text-white">{movie.title}</p>
        <p className="text-xs text-muted">{meta}</p>
      </div>
    </button>
  );
}
