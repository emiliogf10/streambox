import { Link } from 'react-router-dom';
import { SEASON_PARAM } from '../lib/series';
import type { Season } from '../lib/types';

/** Propiedades de {@link SeasonPicker}. */
interface Props {
  seasons: Season[];
  /** Número de la temporada que se está mostrando (ya validado, ver `resolveSeasonNumber`). */
  current: number;
}

/**
 * Selector de temporada de la página de una serie.
 *
 * **Enlaces, no pestañas ARIA ni un `<select>`.** La temporada vive en la URL
 * (`?temporada=2`) para poder compartirla y recargar sin perderla, así que cada
 * opción es un enlace de verdad: se puede abrir en otra pestaña, copiar, y se
 * maneja con Tab + Intro como cualquier enlace. Es el mismo criterio que las
 * secciones del panel de administración (`AdminLayout`): el patrón `tablist` es
 * para paneles que cambian SIN cambiar de URL. Un `<select>` habría obligado a
 * navegar en `onChange`, y en Windows las flechas cambian el valor (y la URL) a
 * cada pulsación.
 *
 * - La temporada actual lleva `aria-current="true"` (un lector de pantalla dice
 *   "actual") y se distingue por el relleno blanco y la negrita, no solo por el color.
 * - Navega con `replace`: cambiar de temporada no llena el historial, así que
 *   «Atrás» vuelve a la página anterior y no recorre las temporadas vistas.
 *   El foco se queda en el enlace pulsado (la lista no se vuelve a montar).
 * - Con muchas temporadas la fila se desplaza en horizontal DENTRO de sí misma
 *   (nunca la página); al tabular, el navegador lleva a la vista el enlace enfocado.
 */
export function SeasonPicker({ seasons, current }: Props) {
  return (
    <nav aria-label="Temporadas" className="min-w-0">
      <ul role="list" className="scrollbar-hide -mx-4 flex gap-2 overflow-x-auto px-4 py-1 sm:-mx-6 sm:px-6">
        {seasons.map(({ seasonNumber }) => {
          const active = seasonNumber === current;
          return (
            <li key={seasonNumber} className="shrink-0">
              <Link
                to={`?${SEASON_PARAM}=${seasonNumber}`}
                replace
                aria-current={active ? 'true' : undefined}
                className={`focus-ring inline-flex min-h-11 items-center rounded-full px-4 text-sm whitespace-nowrap transition-[color,background-color,scale] duration-150 ease-out-strong active:scale-97 motion-reduce:active:scale-100 ${
                  active
                    ? 'bg-white font-semibold text-black'
                    : 'border border-white/30 text-white/85 hover:bg-white/10 hover:text-white'
                }`}
              >
                Temporada {seasonNumber}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
