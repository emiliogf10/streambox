/**
 * Clases de la franja de estadísticas del perfil (la `<dl>`): tres columnas a
 * cualquier ancho, separadas por una línea. La comparten el resumen de «Mi
 * lista» y el de lo visto, y el esqueleto de este último, para que midan igual.
 */
export const STAT_STRIP_CLASS = 'grid grid-cols-3 divide-x divide-line rounded-xl border border-line bg-surface';

/** Clases de cada celda de la franja (las de {@link Stat} y las de su esqueleto). */
export const STAT_CELL_CLASS = 'flex min-w-0 flex-col-reverse justify-end gap-1 px-3 py-3 sm:px-5 sm:py-4';

/** Propiedades de {@link Stat}. */
interface StatProps {
  /** Lo que se ve: un número o un texto corto («1,5 h»). */
  value: number | string;
  label: string;
  /**
   * Cómo se lee el valor si lo que se ve es una abreviatura («1,5 horas» por
   * «1,5 h», «menos de 1 minuto» por «<1 min»). Sin él, se lee lo que se ve.
   */
  spokenValue?: string;
}

/**
 * Una estadística: número y etiqueta. Es un grupo `dt`/`dd` de una lista de
 * descripción (la etiqueta nombra el dato); `flex-col-reverse` la pone DEBAJO
 * del número a la vista sin cambiar el orden de lectura (etiqueta y luego valor).
 *
 * Las tres van en UNA franja de tres columnas a cualquier ancho, separadas por
 * una línea (D-6). Antes eran tres tarjetas que en móvil se apilaban a todo el
 * ancho: unos 330 px de pantalla para tres números, empujando lo útil fuera de
 * la vista. En móvil el número baja a `text-2xl` y la etiqueta a `text-xs` (se
 * parte en dos líneas si hace falta); desde `sm`, tamaño de escritorio.
 *
 * **Valor abreviado.** Con `spokenValue`, lo visible va con `aria-hidden` y la
 * forma entera en un único `sr-only` (mismo patrón que las etiquetas de
 * «Géneros que más guardas»): un lector de pantalla diría «1,5 hache».
 */
export function Stat({ value, label, spokenValue }: StatProps) {
  return (
    <div className={STAT_CELL_CLASS}>
      <dt className="text-xs leading-snug text-muted sm:text-sm">{label}</dt>
      <dd className="text-2xl font-bold tracking-tight whitespace-nowrap text-white tabular-nums sm:text-4xl">
        {spokenValue === undefined ? (
          value
        ) : (
          <>
            <span aria-hidden="true">{value}</span>
            <span className="sr-only">{spokenValue}</span>
          </>
        )}
      </dd>
    </div>
  );
}
