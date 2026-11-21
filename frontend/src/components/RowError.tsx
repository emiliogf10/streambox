import { useId } from 'react';
import { useRetryFocus } from '../hooks/useRetryFocus';
import { Button } from './Button';

/** Propiedades de {@link RowError}. */
interface RowErrorProps {
  /** Título de la fila o bloque que ha fallado («Películas», «Series»): su `<h2>` y el nombre de su región. */
  title: string;
  /** Qué ha fallado, en una frase («No se pudieron cargar las películas.»). */
  summary: string;
  /** El motivo, ya legible (el mensaje de un `ApiError`: red caída, 5xx...). */
  message: string;
  onRetry: () => void;
  /**
   * Final del nombre accesible de «Reintentar» («la carga de películas»). Con
   * varios bloques en error a la vez, cinco «Reintentar» iguales no dicen cuál es
   * cuál en la lista de botones del lector de pantalla.
   */
  retryContext?: string;
}

/**
 * Error DENTRO de una fila o bloque de la portada, sin tapar el resto: su
 * título, una frase con qué falló y por qué, y «Reintentar» solo para él.
 *
 * Es el patrón de «error en el bloque que falló» (revisión visual D-10;
 * taste-skill, pantallas de aplicación: «inside the block that failed, never as
 * a full-screen banner»): si las películas fallan pero las series cargaron, la
 * portada sigue siendo útil. El rojo, solo en el borde y en la frase del fallo.
 *
 * El foco tras «Reintentar» lo resuelve `useRetryFocus`: el bloque se sustituye
 * por su silueta de carga y el foco va al antecesor enfocable más cercano (el
 * hueco del bloque en la portada), no al `<body>`.
 */
export function RowError({ title, summary, message, onRetry, retryContext }: RowErrorProps) {
  const headingId = useId();
  const { retryRef, handleRetry } = useRetryFocus(onRetry);
  return (
    <section aria-labelledby={headingId} className="mb-9 px-4 sm:mb-10 sm:px-6">
      <h2 id={headingId} className="mb-3 text-lg font-bold tracking-tight text-white">
        {title}
      </h2>
      <div
        role="alert"
        className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-red-500/30 bg-red-950/30 px-4 py-3"
      >
        <p className="text-sm text-muted">
          <span className="font-semibold text-danger">{summary}</span> {message}
        </p>
        <Button ref={retryRef} variant="outline" onClick={handleRetry}>
          Reintentar
          {retryContext && <span className="sr-only"> {retryContext}</span>}
        </Button>
      </div>
    </section>
  );
}
