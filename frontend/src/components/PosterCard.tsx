import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { MoviePoster } from './MoviePoster';

/** Lo común a las dos formas de {@link PosterCard}. */
interface PosterCardBaseProps {
  /** Título del elemento (película o serie): pie de la tarjeta y texto del hueco si no hay imagen. */
  title: string;
  /** `imageUrl` de la API; {@link MoviePoster} se encarga del respaldo si falla. */
  imageUrl: string;
  /** Dato secundario bajo el título: "2014 · 2h 49m" en películas, "2019–2022 · 3 temporadas" en series. */
  meta: string;
  /**
   * Tamaño dentro de su contenedor: ancho fijo en una fila, `w-full` en una rejilla.
   * En una fila a 375 px el ancho por defecto (`w-36`, 144 px) deja ver dos
   * tarjetas enteras y ASOMAR la tercera: con 160 px cabían dos exactas y no se
   * notaba que la fila se puede desplazar.
   */
  className?: string;
}

/**
 * Propiedades de {@link PosterCard}: o abre algo en la misma página (`onClick`,
 * p. ej. el diálogo de detalles de una película) o lleva a otra (`to`, p. ej. la
 * página de una serie). Son excluyentes: el compilador no deja pasar las dos.
 */
type PosterCardProps = PosterCardBaseProps &
  ({ onClick: () => void; to?: never } | { to: string; onClick?: never });

/** Clases del elemento pulsable (botón o enlace): las mismas en los dos casos. */
const cardClass =
  'focus-ring group block rounded-xl text-left transition-[scale] duration-150 ease-out-strong active:scale-98 motion-reduce:active:scale-100';

/**
 * Tarjeta de póster de cualquier título del catálogo: imagen, título y un dato
 * secundario. Es la base de `MovieCard` y `SeriesCard`.
 *
 * **Por qué un componente común.** Las películas y las series se muestran igual
 * (mismo póster 2:3, mismos efectos, mismo pie) y solo cambian el dato
 * secundario y QUÉ pasa al pulsar. Duplicar la tarjeta habría hecho que, al
 * retocar una, la otra se quedara atrás.
 *
 * **Botón o enlace, según lo que hace.** Con `onClick` es un `<button>` con
 * `aria-haspopup="dialog"` (abre un diálogo sin cambiar de página); con `to` es
 * un enlace (`<Link>`) de verdad, que cambia la URL y admite abrirse en otra
 * pestaña o copiarse. Usar un botón para navegar rompería esas dos cosas.
 * En ambos casos recibe foco, se activa con el teclado y tiene anillo de foco.
 * Su nombre accesible sale del texto que contiene (título + dato secundario);
 * el póster es decorativo (`alt` vacío) porque el título ya está escrito debajo.
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
 *   toque antes de que se abra el diálogo o cambie la página.
 */
export function PosterCard({ title, imageUrl, meta, className = 'w-36 shrink-0 sm:w-44', ...action }: PosterCardProps) {
  const content: ReactNode = (
    <>
      <div className="relative mb-2 overflow-hidden rounded-xl">
        <MoviePoster
          title={title}
          src={imageUrl}
          className="aspect-2/3 w-full"
          imgClassName="transition-[scale] duration-500 ease-out-strong group-hover:scale-105 motion-reduce:group-hover:scale-100"
        />
        <div
          aria-hidden="true"
          className="absolute inset-0 rounded-xl ring-1 ring-white/10 transition-[box-shadow] duration-200 ring-inset group-hover:ring-white/30"
        />
      </div>
      <div className="px-0.5">
        <p className="truncate text-sm font-medium text-white/90 transition-colors group-hover:text-white">{title}</p>
        <p className="text-xs text-muted tabular-nums">{meta}</p>
      </div>
    </>
  );

  if (action.to !== undefined) {
    return (
      <Link to={action.to} className={`${cardClass} ${className}`}>
        {content}
      </Link>
    );
  }

  return (
    <button type="button" onClick={action.onClick} aria-haspopup="dialog" className={`${cardClass} ${className}`}>
      {content}
    </button>
  );
}
