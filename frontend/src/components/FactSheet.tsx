import { useId } from 'react';
import type { ReactNode } from 'react';

/** Un dato de la ficha: su nombre («Duración») y su valor («2h 49m»). */
export interface Fact {
  term: string;
  detail: ReactNode;
  /**
   * `true` para los datos que necesitan más sitio (los géneros): ocupan dos
   * columnas de la rejilla en lugar de una (sin efecto si la lista es `flex`).
   */
  wide?: boolean;
}

/** Propiedades de {@link FactSheet}. */
interface Props {
  facts: Fact[];
  /** Clases extra de la sección (bordes, fondo de tarjeta en la columna lateral...). */
  className?: string;
  /**
   * Disposición de la lista según el ancho. Por defecto, una rejilla de dos
   * columnas; quien la usa la adapta a su sitio (una banda en línea bajo el
   * banner de una película, una columna lateral junto a los episodios).
   */
  listClassName?: string;
}

/**
 * Ficha de un título (película o serie): sus datos con nombre, en una lista de
 * descripción (`<dl>`), bajo un `<h2>` «Ficha».
 *
 * **Por qué una ficha, si el banner ya enseña el año y la duración.** En el
 * banner esos datos van sin nombre, en una línea, para leerse de un vistazo;
 * aquí cada dato lleva su nombre («Estreno», «Duración»), como en la ficha
 * técnica de cualquier catálogo. Y añade lo que el banner no tiene: los géneros
 * como enlaces para seguir explorando (en películas) y desde cuándo está el
 * título en el catálogo. Solo datos que da la API: no hay reparto, director ni
 * valoración, así que no se reserva sitio para ellos.
 *
 * **Accesibilidad.** `<dl>` hace que un lector de pantalla anuncie cada par
 * nombre-valor; la sección tiene nombre («Ficha») y su título se puede
 * alcanzar con la navegación por encabezados. Los nombres van en `muted` (no
 * solo en otro color: también en mayúsculas pequeñas) y los valores en blanco.
 */
export function FactSheet({ facts, className = '', listClassName = 'grid grid-cols-2 gap-x-6 gap-y-5' }: Props) {
  const headingId = useId();

  return (
    <section aria-labelledby={headingId} className={className}>
      <h2 id={headingId} className="mb-4 text-lg font-bold tracking-tight text-white">
        Ficha
      </h2>
      <dl className={listClassName}>
        {facts.map((fact) => (
          <div key={fact.term} className={`min-w-0 ${fact.wide ? 'col-span-2' : ''}`}>
            <dt className="text-xs font-semibold tracking-wider text-muted uppercase">{fact.term}</dt>
            <dd className="mt-1.5 text-sm text-pretty break-words text-white tabular-nums sm:text-base">{fact.detail}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
