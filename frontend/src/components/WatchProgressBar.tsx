import { useId } from 'react';
import { formatRemaining } from '../lib/playback';

/** Propiedades de {@link WatchProgressBar}. */
interface WatchProgressBarProps {
  positionSeconds: number;
  durationSeconds: number;
  /** `true` si la posición actual llega al final (≥ 95 %): la barra va llena y el texto dice «Visto». */
  finished?: boolean;
  /**
   * `true` si lo terminó alguna vez (`watched` de la API). Sin `finished`, es que
   * lo está volviendo a ver: la barra marca la posición actual y el texto dice
   * las dos cosas, «Visto · Quedan 12 min».
   */
  watched?: boolean;
  /**
   * `false` = sin texto: solo la barra, oculta a los lectores de pantalla. Para
   * cuando el texto ya está escrito al lado (la tarjeta de «Seguir viendo» lo
   * lleva en su línea de datos) o cuando la barra va DENTRO de un enlace (un
   * `progressbar` dentro de un enlace se colaría en su nombre accesible).
   */
  withText?: boolean;
  /** Clases del contenedor (ancho, márgenes). */
  className?: string;
  /** Clases del texto (su color depende del fondo: banner o lista). */
  textClassName?: string;
}

/**
 * Cuánto se ha visto de un título: una barra fina y, debajo, lo que queda
 * («Quedan 34 min») o «Visto».
 *
 * **Volver a ver.** Un título terminado alguna vez (`watched`) que se ha vuelto a
 * empezar no es «a medias» sin más, ni «Visto» con la barra llena (el botón de
 * al lado dice «Reanudar»: la barra llena lo contradiría). Muestra la verdad
 * de las dos cosas: la barra en la posición actual y «Visto · Quedan 12 min».
 * `finished` manda sobre `watched`: con la posición al final es «Visto» sin más.
 *
 * **Accesible sin depender del dibujo.** Es un `<progress>` nativo (rol
 * `progressbar`, con su valor) que toma su nombre del texto de al lado
 * (`aria-labelledby`): un lector de pantalla dice «Quedan 34 min, barra de
 * progreso, 40 %». Quien no distingue el color de la barra lee lo mismo en el
 * texto. El aspecto (pista y relleno `accent`) está en la utilidad
 * `watch-progress` de `index.css`.
 *
 * Sin animación: aparece ya con su valor. Se ve en muchas tarjetas y filas a la
 * vez, y una barra que «se llena» al cargar solo distraería.
 */
export function WatchProgressBar({
  positionSeconds,
  durationSeconds,
  finished = false,
  watched = false,
  withText = true,
  className = '',
  textClassName = 'text-muted',
}: WatchProgressBarProps) {
  const textId = useId();
  const max = Math.max(durationSeconds, 1);
  const value = finished ? max : Math.min(Math.max(positionSeconds, 0), max);
  const remaining = formatRemaining(positionSeconds, durationSeconds);
  const text = finished ? 'Visto' : watched ? `Visto · ${remaining}` : remaining;

  if (!withText) {
    return <progress aria-hidden="true" max={max} value={value} className={`watch-progress h-1 w-full ${className}`} />;
  }
  return (
    <div className={`flex flex-col gap-1.5 ${className}`}>
      <progress aria-labelledby={textId} max={max} value={value} className="watch-progress h-1 w-full" />
      <span id={textId} className={`text-xs tabular-nums ${textClassName}`}>
        {text}
      </span>
    </div>
  );
}
