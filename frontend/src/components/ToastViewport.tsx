import type { ToastItem } from '../context/ToastContext';
import { Toast } from './Toast';

/** Propiedades de {@link ToastViewport}. */
interface ToastViewportProps {
  toasts: ToastItem[];
  onDismiss: (id: number) => void;
}

/**
 * Zona fija de la pantalla donde se apilan los avisos.
 *
 * Hay DOS regiones `aria-live` permanentes (no se crean al aparecer el primer
 * aviso, porque los lectores de pantalla solo anuncian cambios en regiones que
 * ya existían): una `polite` para éxito/información, que espera a que el
 * lector termine de hablar, y una `assertive` para errores, que interrumpe.
 */
export function ToastViewport({ toasts, onDismiss }: ToastViewportProps) {
  const polite = toasts.filter((t) => t.type !== 'error');
  const assertive = toasts.filter((t) => t.type === 'error');

  return (
    <div className="pointer-events-none fixed inset-x-4 bottom-4 z-[200] flex flex-col items-stretch gap-2 sm:left-auto sm:w-96">
      <div role="status" aria-live="polite" className="flex flex-col gap-2">
        {polite.map((t) => (
          <Toast key={t.id} toast={t} onDismiss={onDismiss} />
        ))}
      </div>
      <div role="alert" aria-live="assertive" className="flex flex-col gap-2">
        {assertive.map((t) => (
          <Toast key={t.id} toast={t} onDismiss={onDismiss} />
        ))}
      </div>
    </div>
  );
}
