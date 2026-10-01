import { useEffect, useId, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { Movie } from '../lib/types';
import { MovieCard } from './MovieCard';

/**
 * Propiedades para el componente MovieRow.
 */
interface Props {
  title: string;
  movies: Movie[];
  onSelectMovie: (movie: Movie) => void;
}

/** Clases de los botones "anterior/siguiente" (solo se ven desde `md`, donde no hay gesto táctil). */
const arrowClass =
  'focus-ring hidden size-10 items-center justify-center rounded-full border border-line bg-surface-raised ' +
  'text-white transition-colors hover:bg-white/15 aria-disabled:cursor-not-allowed aria-disabled:opacity-40 md:inline-flex';

/**
 * Fila de tarjetas de películas con scroll horizontal propio y un título.
 *
 * **Navegación.**
 * - Táctil y ratón/trackpad: arrastrar o rueda, con *scroll snap*
 *   (`snap-x snap-proximity`): las tarjetas se alinean al borde sin forzarlo.
 * - Teclado: cada tarjeta es un botón enfocable; al tabular, el navegador
 *   desplaza la fila para mostrar la que tiene el foco, así que no hace falta
 *   convertir el contenedor en enfocable (sería una parada de tabulador extra).
 * - Ratón sin gesto de desplazamiento horizontal: botones anterior/siguiente
 *   (visibles desde `md`), que se marcan como desactivados en los extremos
 *   con `aria-disabled` (y no `disabled`, para no perder el foco al llegar al final). El desplazamiento
 *   suave se sustituye por salto directo con `prefers-reduced-motion`.
 *
 * Es una sección con su título (`<h2>`) como nombre y una lista de tarjetas.
 *
 * @param props Las propiedades del componente que contienen el título, la lista de películas y el manejador de clics.
 */
export function MovieRow({ title, movies, onSelectMovie }: Props) {
  const headingId = useId();
  const scrollerRef = useRef<HTMLUListElement>(null);
  const [edges, setEdges] = useState({ atStart: true, atEnd: true });

  // Recalcula si hay más contenido a cada lado al desplazar y al cambiar el tamaño o las tarjetas.
  useEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const update = () =>
      setEdges({
        atStart: scroller.scrollLeft <= 1,
        atEnd: scroller.scrollLeft + scroller.clientWidth >= scroller.scrollWidth - 1,
      });
    scroller.addEventListener('scroll', update, { passive: true });
    // Observar el tamaño dispara también una medición inicial.
    const observer = new ResizeObserver(update);
    observer.observe(scroller);
    return () => {
      scroller.removeEventListener('scroll', update);
      observer.disconnect();
    };
  }, [movies.length]);

  if (movies.length === 0) return null;

  /** Desplaza la fila cerca de una "pantalla" (80 % del ancho visible) hacia un lado. */
  const scrollByPage = (direction: -1 | 1) => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    if (direction === -1 ? edges.atStart : edges.atEnd) return;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    scroller.scrollBy({ left: direction * scroller.clientWidth * 0.8, behavior: reduceMotion ? 'auto' : 'smooth' });
  };

  return (
    <section aria-labelledby={headingId} className="mb-8 px-4 sm:px-6">
      <div className="mb-3 flex items-center justify-between gap-4">
        <h2 id={headingId} className="text-base font-bold text-white">
          {title}
        </h2>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => scrollByPage(-1)}
            aria-disabled={edges.atStart}
            aria-label={`Ver anteriores en ${title}`}
            className={arrowClass}
          >
            <ChevronLeft aria-hidden="true" className="size-5" />
          </button>
          <button
            type="button"
            onClick={() => scrollByPage(1)}
            aria-disabled={edges.atEnd}
            aria-label={`Ver siguientes en ${title}`}
            className={arrowClass}
          >
            <ChevronRight aria-hidden="true" className="size-5" />
          </button>
        </div>
      </div>

      {/* Márgenes negativos: el scroll llega hasta el borde de la pantalla, pero las tarjetas arrancan alineadas con el título. */}
      <ul
        ref={scrollerRef}
        role="list"
        className="scrollbar-hide -mx-4 flex snap-x snap-proximity scroll-px-4 gap-4 overflow-x-auto px-4 pb-2 sm:-mx-6 sm:scroll-px-6 sm:px-6"
      >
        {movies.map((movie) => (
          <li key={movie.id} className="shrink-0 snap-start">
            <MovieCard movie={movie} onClick={onSelectMovie} />
          </li>
        ))}
      </ul>
    </section>
  );
}
