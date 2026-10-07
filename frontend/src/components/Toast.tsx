import { useEffect, useState } from 'react';
import { CircleAlert, CircleCheck, Info, X } from 'lucide-react';
import type { ToastItem, ToastType } from '../context/ToastContext';

/** Milisegundos que permanece visible cada tipo de aviso (los errores, más: hay que leerlos). */
const DURATION_MS: Record<ToastType, number> = {
  success: 5000,
  info: 5000,
  error: 9000,
};

/** Icono y colores de cada tipo. El icono evita depender solo del color. */
const STYLES: Record<ToastType, { icon: typeof Info; iconClass: string; label: string }> = {
  success: { icon: CircleCheck, iconClass: 'text-success', label: 'Correcto' },
  error: { icon: CircleAlert, iconClass: 'text-danger', label: 'Error' },
  info: { icon: Info, iconClass: 'text-accent', label: 'Información' },
};

/** Propiedades de {@link Toast}. */
interface ToastProps {
  toast: ToastItem;
  /** Se invoca al cerrarse, de forma manual o automática. */
  onDismiss: (id: number) => void;
}

/**
 * Un aviso individual.
 *
 * Se cierra solo tras unos segundos, o a mano con el botón. El temporizador se
 * pausa mientras el puntero o el foco están encima, para que quien lee despacio
 * o usa teclado no pierda el mensaje.
 *
 * Entra con un fundido y un desplazamiento corto desde abajo (8 px, 250 ms),
 * la misma dirección de la que viene la zona de avisos: así se percibe como
 * algo que llega, no como un parpadeo. Se hace con `@starting-style`
 * (variante `starting:` de Tailwind) y una TRANSICIÓN, no con `@keyframes`:
 * si llegan varios avisos seguidos, una transición se puede interrumpir sin
 * saltos. Con `prefers-reduced-motion` se conserva el fundido (no marea) y se quita
 * el desplazamiento (`motion-reduce:starting:translate-y-0`).
 */
export function Toast({ toast, onDismiss }: ToastProps) {
  const [paused, setPaused] = useState(false);
  const { icon: Icon, iconClass, label } = STYLES[toast.type];

  useEffect(() => {
    if (paused) return;
    const timer = setTimeout(() => onDismiss(toast.id), DURATION_MS[toast.type]);
    return () => clearTimeout(timer);
  }, [paused, toast.id, toast.type, onDismiss]);

  return (
    <div
      className="pointer-events-auto flex items-start gap-3 rounded-xl border border-line bg-surface-raised p-4 shadow-2xl transition-[opacity,translate] duration-250 ease-out-strong starting:translate-y-2 starting:opacity-0 motion-reduce:starting:translate-y-0"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <Icon aria-hidden="true" className={`mt-0.5 size-5 shrink-0 ${iconClass}`} />
      <p className="flex-1 text-sm leading-snug text-white">
        <span className="sr-only">{label}: </span>
        {toast.message}
      </p>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        aria-label="Cerrar aviso"
        className="focus-ring -m-1 rounded-md p-1 text-muted hover:text-white"
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}
