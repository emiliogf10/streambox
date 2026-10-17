import { TriangleAlert } from 'lucide-react';
import type { RefObject } from 'react';
import { useRetryFocus } from '../hooks/useRetryFocus';
import { Button } from './Button';

/** Propiedades de {@link ErrorState}. */
interface ErrorStateProps {
  /** Título breve del problema. */
  title?: string;
  /** Mensaje ya legible para el usuario (el de un `ApiError`). */
  message: string;
  /** Si se indica, se muestra el botón "Reintentar" que lo invoca. */
  onRetry?: () => void;
  /**
   * `true` cuando el error afecta a UNA zona de la página (una tarjeta, una
   * sección) y no a la página entera: no reserva media pantalla de alto.
   */
  compact?: boolean;
  /**
   * Nivel del encabezado del título: 2 (por defecto) cuando el error vive dentro
   * de una página que ya tiene su `<h1>`; 1 cuando el error ES la página (p. ej.
   * «No se pudo comprobar tu sesión», que se pinta sin barra ni contenido).
   */
  headingLevel?: 1 | 2;
  /**
   * Elemento estable de la zona (p. ej. su encabezado, con `tabIndex={-1}`) que
   * recibe el foco cuando «Reintentar» desaparece al pulsarlo. Sin él, va al
   * antecesor enfocable por código más cercano que siga en la página (en
   * último término, el `<main>`). Ver `useRetryFocus`.
   */
  retryFocusRef?: RefObject<HTMLElement | null>;
}

/** Reintento vacío sin `onRetry`: el hook se llama siempre (reglas de los hooks), pero sin botón no se usa. */
const NO_RETRY = () => {};

/**
 * Estado de error a pantalla (casi) completa (o de una sola zona, con `compact`), con botón de reintento.
 *
 * `role="alert"` anuncia el error al instante. El mensaje no se redacta aquí:
 * viene de `ApiError`, que ya distingue red caída, 403, 429, 5xx...
 *
 * **El foco tras «Reintentar».** Al pulsarlo, este aviso se sustituye por la
 * silueta de carga y el botón desaparece: sin hacer nada, el foco caería en
 * `<body>`. {@link useRetryFocus} lo lleva a un elemento estable de la misma
 * zona (`retryFocusRef` o el antecesor enfocable más cercano) y, si el
 * reintento vuelve a fallar, al nuevo «Reintentar».
 */
export function ErrorState({
  title = 'No se pudo cargar el contenido',
  message,
  onRetry,
  compact = false,
  headingLevel = 2,
  retryFocusRef,
}: ErrorStateProps) {
  const { retryRef, handleRetry } = useRetryFocus(onRetry ?? NO_RETRY, retryFocusRef && (() => retryFocusRef.current));
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  return (
    <div className={`flex items-center justify-center ${compact ? '' : 'min-h-[50vh] px-4'}`}>
      <div
        role="alert"
        className="flex w-full max-w-md flex-col items-center gap-3 rounded-xl border border-red-500/30 bg-red-950/30 px-6 py-8 text-center"
      >
        <TriangleAlert aria-hidden="true" className="size-8 text-danger" />
        <Heading className="font-semibold text-danger">{title}</Heading>
        <p className="text-sm leading-relaxed text-muted">{message}</p>
        {onRetry && (
          <Button ref={retryRef} variant="outline" onClick={handleRetry} className="mt-2">
            Reintentar
          </Button>
        )}
      </div>
    </div>
  );
}
