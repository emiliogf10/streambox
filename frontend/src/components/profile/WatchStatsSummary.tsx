import { useId } from 'react';
import type { ReactNode } from 'react';
import { TriangleAlert } from 'lucide-react';
import { useRetryFocus } from '../../hooks/useRetryFocus';
import { useWatchStats } from '../../hooks/useWatchProgress';
import { formatWatchedTime } from '../../lib/watchHistory';
import { Button } from '../Button';
import { STAT_CELL_CLASS, STAT_STRIP_CLASS, Stat } from './Stat';

/** Propiedades de {@link WatchStatsSummary}. */
interface WatchStatsSummaryProps {
  /** Clases del contenedor (su sitio en la rejilla de la página). */
  className?: string;
}

/**
 * Silueta de la franja mientras llegan las cifras: mismas celdas y alturas que
 * la real ({@link STAT_CELL_CLASS}), para que al llegar nada salte. Las formas
 * son decorativas; se anuncia el texto del `role="status"`.
 */
function StatsSkeleton() {
  const block = 'rounded-md bg-white/6';
  return (
    <div role="status">
      <span className="sr-only">Cargando las cifras de lo que has visto...</span>
      <div aria-hidden="true" className={`${STAT_STRIP_CLASS} animate-pulse motion-reduce:animate-none`}>
        {[0, 1, 2].map((index) => (
          <div key={index} className={STAT_CELL_CLASS}>
            <div className={`${block} h-3.5 w-4/5 sm:h-4`} />
            <div className={`${block} h-8 w-12 sm:h-10 sm:w-16`} />
          </div>
        ))}
      </div>
    </div>
  );
}

/** Propiedades de {@link StatsError}. */
interface StatsErrorProps {
  message: string;
  onRetry: () => void;
}

/**
 * Error de la franja: una línea con el motivo y «Reintentar», del alto de la
 * franja. No es un `ErrorState` (una caja de unos 200 px para tres números
 * sería desproporcionado), pero sigue sus reglas: `role="alert"` y el foco que
 * no se pierde al reintentar ({@link useRetryFocus}: el botón desaparece al
 * pulsarlo y el foco va al contenedor enfocable de la sección).
 */
function StatsError({ message, onRetry }: StatsErrorProps) {
  const { retryRef, handleRetry } = useRetryFocus(onRetry);
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3 rounded-xl border border-red-500/30 bg-red-950/30 px-4 py-3 sm:px-5">
      <div role="alert" className="flex min-w-0 items-start gap-3">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-danger" />
        <p className="text-sm leading-relaxed text-muted">
          <span className="font-semibold text-danger">No se pudieron cargar las cifras de lo que has visto.</span>{' '}
          {message}
        </p>
      </div>
      <Button ref={retryRef} variant="outline" onClick={handleRetry}>
        Reintentar
      </Button>
    </div>
  );
}

/**
 * Resumen de lo visto en el perfil: películas y episodios terminados y el tiempo
 * visto (`GET /api/users/me/watch-stats`), en la misma franja de tres cifras que
 * el resumen de «Mi lista» ({@link Stat}).
 *
 * **Que las cifras no mientan:**
 * - «Vistas»/«vistos» = terminadas (≥ 95 %), la misma regla que la marca
 *   «Visto» de las tarjetas de «Vistos recientemente».
 * - El tiempo es una estimación del servidor (solo guarda el último punto de cada
 *   título): la etiqueta dice «aprox.» y {@link formatWatchedTime} redondea hacia
 *   abajo («1,5 h», nunca los minutos exactos de 30 h).
 * - Mientras cargan, una silueta (nunca un «0» falso); si fallan, el motivo y
 *   «Reintentar». Los ceros solo aparecen cuando el servidor dice que son ceros.
 *
 * Los textos valen igual para USER y ADMIN: los dos ven títulos y tienen su
 * propio historial.
 */
export function WatchStatsSummary({ className = '' }: WatchStatsSummaryProps) {
  const { stats, status, errorMessage, reload } = useWatchStats();
  const headingId = useId();

  let body: ReactNode;
  if (status === 'error') {
    body = <StatsError message={errorMessage} onRetry={reload} />;
  } else if (status === 'loading' || !stats) {
    body = <StatsSkeleton />;
  } else {
    const time = formatWatchedTime(stats.watchedSeconds);
    body = (
      <dl className={STAT_STRIP_CLASS}>
        <Stat
          value={stats.finishedMovies}
          label={stats.finishedMovies === 1 ? 'Película vista' : 'Películas vistas'}
        />
        <Stat
          value={stats.finishedEpisodes}
          label={stats.finishedEpisodes === 1 ? 'Episodio visto' : 'Episodios vistos'}
        />
        <Stat value={time.short} spokenValue={time.spoken} label="Tiempo visto (aprox.)" />
      </dl>
    );
  }

  return (
    <section aria-labelledby={headingId} className={className}>
      <h2 id={headingId} className="sr-only">
        Resumen de lo que has visto
      </h2>
      {/* tabIndex -1: si «Reintentar» desaparece al pulsarlo, el foco se queda aquí (`useRetryFocus`). */}
      <div tabIndex={-1} className="focus-ring rounded-xl">
        {body}
      </div>
    </section>
  );
}
