import { TriangleAlert } from 'lucide-react';
import { Button } from './Button';

/** Propiedades de {@link ErrorState}. */
interface ErrorStateProps {
  /** Título breve del problema. */
  title?: string;
  /** Mensaje ya legible para el usuario (el de un `ApiError`). */
  message: string;
  /** Si se indica, se muestra el botón "Reintentar" que lo invoca. */
  onRetry?: () => void;
}

/**
 * Estado de error a pantalla (casi) completa, con botón de reintento.
 *
 * `role="alert"` anuncia el error al instante. El mensaje no se redacta aquí:
 * viene de `ApiError`, que ya distingue red caída, 403, 429, 5xx...
 */
export function ErrorState({ title = 'No se pudo cargar el contenido', message, onRetry }: ErrorStateProps) {
  return (
    <div className="flex min-h-[50vh] items-center justify-center px-4">
      <div
        role="alert"
        className="flex w-full max-w-md flex-col items-center gap-3 rounded-xl border border-red-500/30 bg-red-950/30 px-6 py-8 text-center"
      >
        <TriangleAlert aria-hidden="true" className="size-8 text-danger" />
        <h2 className="font-semibold text-danger">{title}</h2>
        <p className="text-sm leading-relaxed text-muted">{message}</p>
        {onRetry && (
          <Button variant="outline" onClick={onRetry} className="mt-2">
            Reintentar
          </Button>
        )}
      </div>
    </div>
  );
}
