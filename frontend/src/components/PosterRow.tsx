import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

/** Propiedades de {@link PosterRow}. */
interface Props<T extends { id: number }> {
  title: string;
  items: T[];
  /** Pinta la tarjeta de un elemento (`MovieCard`, `SeriesCard`...). */
  renderItem: (item: T) => ReactNode;
  /**
   * Enlace opcional junto al título (p. ej. «Ver todas» en la fila «Series» de
   * la portada). Va en la cabecera, no al final: así se ve sin recorrer la fila.
   */
  action?: ReactNode;
}

/** Clases de los botones "anterior/siguiente" (solo se ven desde `md`, donde no hay gesto táctil). */
const arrowClass =
  'focus-ring hidden size-10 items-center justify-center rounded-full border border-line bg-surface-raised ' +
  'text-white transition-[background-color,scale] duration-150 ease-out-strong hover:bg-white/15 active:scale-95 ' +
  'motion-reduce:active:scale-100 aria-disabled:cursor-not-allowed aria-disabled:opacity-40 aria-disabled:active:scale-100 md:inline-flex';

/**
 * Fila de tarjetas con scroll horizontal propio y un título. Sirve para
 * películas y series: no sabe qué pinta, recibe los elementos y cómo pintar
 * cada uno (`renderItem`). `MovieRow` es esta fila con tarjetas de película.
 *
 * **Navegación.**
 * - Táctil y ratón/trackpad: arrastrar o rueda, con *scroll snap*
 *   (`snap-x snap-proximity`): las tarjetas se alinean al borde sin forzarlo.
 * - Teclado: cada tarjeta es un botón o enlace enfocable; al tabular, el
 *   navegador desplaza la fila para mostrar la que tiene el foco, así que no hace
 *   falta convertir el contenedor en enfocable (sería una parada de tabulador extra).
 * - Ratón sin gesto de desplazamiento horizontal: botones anterior/siguiente
 *   (visibles desde `md`), que se marcan como desactivados en los extremos
 *   con `aria-disabled` (y no `disabled`, para no perder el foco al llegar al final). El desplazamiento
 *   suave se sustituye por salto directo con `prefers-reduced-motion`.
 *
 * Es una sección con su título (`<h2>`) como nombre y una lista de tarjetas.
 * Sin elementos no pinta nada.
 */
export function PosterRow<T extends { id: number }>({ title, items, renderItem, action }: Props<T>) {
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
  }, [items.length]);

  if (items.length === 0) return null;

  /** Desplaza la fila cerca de una "pantalla" (80 % del ancho visible) hacia un lado. */
  const scrollByPage = (direction: -1 | 1) => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    if (direction === -1 ? edges.atStart : edges.atEnd) return;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    scroller.scrollBy({ left: direction * scroller.clientWidth * 0.8, behavior: reduceMotion ? 'auto' : 'smooth' });
  };

  return (
    // Título de fila un paso por encima del texto de las tarjetas (text-lg frente a text-sm) y separación entre
    // filas mayor que la del título a sus tarjetas: así cada fila se lee como un grupo (ley de proximidad).
    <section aria-labelledby={headingId} className="mb-9 px-4 sm:mb-10 sm:px-6">
      <div className="mb-3 flex items-center justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <h2 id={headingId} className="text-lg font-bold tracking-tight text-white">
            {title}
          </h2>
          {action}
        </div>
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
        className="scrollbar-hide -mx-4 flex snap-x snap-proximity scroll-px-4 gap-3 overflow-x-auto px-4 pb-2 sm:-mx-6 sm:scroll-px-6 sm:gap-4 sm:px-6"
      >
        {items.map((item) => (
          <li key={item.id} className="shrink-0 snap-start">
            {renderItem(item)}
          </li>
        ))}
      </ul>
    </section>
  );
}
