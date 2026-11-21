/** Propiedades de {@link StableLabel}. */
interface StableLabelProps {
  /** Texto que se ve ahora. */
  current: string;
  /** Todos los textos que puede llegar a mostrar (el actual incluido): el más ancho fija el ancho. */
  options: readonly string[];
}

/**
 * Texto de un botón que cambia de etiqueta («Mi lista» ↔ «En mi lista») sin
 * cambiar de ancho (D-11).
 *
 * Todas las etiquetas se apilan en la misma celda de una rejilla y solo la
 * actual es visible; las demás ocupan su sitio con `invisible`
 * (`visibility: hidden`) y `aria-hidden`. La celda mide lo que la más ancha,
 * así que al pulsar el botón no se ensancha ni empuja lo que tiene al lado (antes
 * «Más información» se desplazaba justo bajo el puntero). Sin JavaScript ni
 * medidas a mano: funciona con cualquier tamaño de letra y con el zoom.
 *
 * `aria-hidden` es redundante en un navegador (lo invisible ya no se lee), pero
 * deja el nombre accesible correcto también donde no hay CSS (jsdom).
 */
export function StableLabel({ current, options }: StableLabelProps) {
  return (
    <span className="inline-grid">
      {options.map((option) => (
        <span
          key={option}
          aria-hidden={option === current ? undefined : true}
          className={`col-start-1 row-start-1 ${option === current ? '' : 'invisible'}`}
        >
          {option}
        </span>
      ))}
    </span>
  );
}
