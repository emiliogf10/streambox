import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { MoviePoster } from './MoviePoster';

/**
 * Propiedades para el componente MovieCard.
 */
interface Props {
  movie: Movie;
  onClick: (movie: Movie) => void;
  /**
   * Tamaño dentro de su contenedor: ancho fijo en una fila, `w-full` en una rejilla.
   * En una fila a 375 px el ancho por defecto (`w-36`, 144 px) deja ver dos
   * tarjetas enteras y ASOMAR la tercera: con 160 px cabían dos exactas y no se
   * notaba que la fila se puede desplazar.
   */
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
 * Los efectos son solo CSS (`group-hover`, `active`) y se desactivan con
 * `prefers-reduced-motion`:
 * - **Borde interior** (`ring-inset` blanco al 10 %): muchos pósters son muy
 *   oscuros y, sin él, su borde se perdía contra el fondo de la página. Al
 *   pasar el ratón se aclara, en lugar del antiguo velo negro que apagaba
 *   justo la imagen que el usuario quería mirar.
 * - **Zoom suave** de la imagen al pasar el ratón. En Tailwind v4 `hover:` solo
 *   se aplica en dispositivos con puntero real, así que en móvil un toque no
 *   deja la tarjeta "pegada" en estado hover.
 * - **Al pulsar** la tarjeta se hunde un 2 % (`active:scale-98`): confirma el
 *   toque antes de que se abra el diálogo.
 *
 * @param props Propiedades del componente que contienen los datos de la película y el manejador de clics.
 */
export function MovieCard({ movie, onClick, className = 'w-36 shrink-0 sm:w-44' }: Props) {
  const meta = `${movie.releaseYear} · ${formatDuration(movie.duration)}`;
  return (
    <button
      type="button"
      onClick={() => onClick(movie)}
      aria-haspopup="dialog"
      className={`focus-ring group block rounded-xl text-left transition-[scale] duration-150 ease-out-strong active:scale-98 motion-reduce:active:scale-100 ${className}`}
    >
      <div className="relative mb-2 overflow-hidden rounded-xl">
        <MoviePoster
          title={movie.title}
          src={movie.imageUrl}
          className="aspect-2/3 w-full"
          imgClassName="transition-[scale] duration-500 ease-out-strong group-hover:scale-105"
        />
        <div
          aria-hidden="true"
          className="absolute inset-0 rounded-xl ring-1 ring-white/10 transition-[box-shadow] duration-200 ring-inset group-hover:ring-white/30"
        />
      </div>
      <div className="px-0.5">
        <p className="truncate text-sm font-medium text-white/90 transition-colors group-hover:text-white">
          {movie.title}
        </p>
        <p className="text-xs text-muted tabular-nums">{meta}</p>
      </div>
    </button>
  );
}
