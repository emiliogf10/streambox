import { useEffect, useRef, useState } from 'react';
import { CircleAlert, CircleCheck, Info, X } from 'lucide-react';
import type { ToastItem, ToastType } from '../context/ToastContext';
import { usePageVisible } from '../hooks/usePageVisible';

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
 * Se cierra solo tras unos segundos, o a mano con el botón. La cuenta atrás se
 * pausa en dos casos:
 * - **Puntero o foco encima**, para que quien lee despacio o usa teclado no
 *   pierda el mensaje. Al salir vuelve a empezar con la duración COMPLETA (se
 *   entiende que ha dejado de leerlo en ese momento).
 * - **Pestaña oculta** (`usePageVisible`): si el usuario cambia de pestaña con el
 *   aviso contando, al volver sigue con el tiempo que le QUEDABA. Sin esto, el
 *   aviso que salta en una pestaña de fondo (p. ej. «Se ha cerrado la sesión en
 *   otra pestaña…», o una respuesta tardía) caducaba sin que nadie lo leyera. Los
 *   que NACEN con la pestaña oculta ni siquiera se montan hasta que se ve (lo
 *   decide `ToastProvider`), así que su cuenta atrás empieza al volver.
 *
 * El tiempo restante vive en un `ref` (no en estado): cambiarlo no tiene que
 * repintar nada, solo decide cuánto dura el siguiente `setTimeout`. Se mide con
 * `Date.now()` al pausar, porque el navegador puede retrasar los temporizadores de
 * una pestaña de fondo y no conviene fiarse de ellos para saber cuánto pasó.
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
  const pageVisible = usePageVisible();
  const remainingMs = useRef(DURATION_MS[toast.type]);
  const { icon: Icon, iconClass, label } = STYLES[toast.type];
  const counting = !paused && pageVisible;

  useEffect(() => {
    if (!counting) return;
    const startedAt = Date.now();
    const timer = setTimeout(() => onDismiss(toast.id), remainingMs.current);
    // Al pausar (o desmontar) se descuenta lo que llevaba corriendo: es lo que permite
    // que, al volver a la pestaña, siga con el tiempo restante y no desde cero.
    return () => {
      clearTimeout(timer);
      remainingMs.current = Math.max(0, remainingMs.current - (Date.now() - startedAt));
    };
  }, [counting, toast.id, onDismiss]);

  /** Fin de la pausa por puntero o foco: la cuenta atrás vuelve a empezar entera (como siempre). */
  const resume = () => {
    remainingMs.current = DURATION_MS[toast.type];
    setPaused(false);
  };

  return (
    <div
      className="pointer-events-auto flex items-start gap-3 rounded-xl border border-line bg-surface-raised p-4 shadow-2xl transition-[opacity,translate] duration-250 ease-out-strong starting:translate-y-2 starting:opacity-0 motion-reduce:starting:translate-y-0"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={resume}
      onFocus={() => setPaused(true)}
      onBlur={resume}
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
