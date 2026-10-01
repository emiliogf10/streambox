import type { ReactNode } from 'react';
import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';

/**
 * Propiedades para el componente HeroInfoPanels.
 */
interface Props {
  movie: Movie;
}

/** Panel con título (`<h3>`, subordinado al `<h2>` del banner) y contenido. */
function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-line bg-surface p-5">
      <h3 className="mb-3 text-[11px] font-bold tracking-widest text-muted uppercase">{title}</h3>
      {children}
    </section>
  );
}

/**
 * Paneles informativos debajo del banner principal: sinopsis, reparto y ficha técnica.
 *
 * Una columna en móvil y tres desde `md`. El reparto no existe en la API, así
 * que se muestra un estado vacío honesto en lugar de inventar datos. La ficha
 * es una lista de definiciones (`<dl>`), el marcado correcto para pares
 * "etiqueta: valor".
 *
 * @param props Propiedades del componente que contienen los datos de la película.
 */
export function HeroInfoPanels({ movie }: Props) {
  const facts = [
    { label: 'Año', value: String(movie.releaseYear) },
    { label: 'Duración', value: formatDuration(movie.duration) },
  ];

  return (
    <div className="grid gap-3 px-4 pb-6 sm:px-6 md:grid-cols-3">
      <Panel title="Sinopsis">
        <p className="text-sm leading-relaxed text-gray-300">{movie.description || 'Sin descripción disponible.'}</p>
      </Panel>

      <Panel title="Reparto">
        <p className="text-sm text-muted italic">Información no disponible en esta versión.</p>
      </Panel>

      <Panel title="Ficha">
        <dl className="flex flex-col gap-1.5 text-sm">
          {facts.map(({ label, value }) => (
            <div key={label} className="flex justify-between gap-4">
              <dt className="text-muted">{label}</dt>
              <dd className="font-medium text-gray-300">{value}</dd>
            </div>
          ))}
        </dl>
      </Panel>
    </div>
  );
}
