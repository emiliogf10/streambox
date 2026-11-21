import { useEffect, useRef, useState } from 'react';
import type { Ref } from 'react';
import { CircleAlert, CircleCheck, Info, X } from 'lucide-react';
import type { ToastItem, ToastType } from '../context/ToastContext';
import { usePageVisible } from '../hooks/usePageVisible';

/**
 * Milisegundos que permanece visible cada tipo de aviso, o `null` si no se
 * cierra solo.
 *
 * **Los errores no se cierran solos** (B5 de la revisión, guía
 * «persistent-toast-notifications»: «Prefer persistent toast notifications that
 * remain present until they are dismissed, except for frequent, low importance
 * events»). Antes duraban 9 s: un «No se pudo borrar…» desaparecía si el usuario
 * miraba a otro sitio, el botón volvía a su estado anterior y el motivo ya no se
 * podía releer. Se quedan hasta que se pulsa «Cerrar aviso» (y como mucho hay
 * `MAX_TOASTS` a la vez: el más antiguo deja sitio al nuevo). Los de éxito e
 * información sí se van solos: son frecuentes, de poca importancia, y lo que
 * cuentan ya se ve en la pantalla (el botón cambia a «En mi lista», la fila
 * desaparece...).
 */
const DURATION_MS: Record<ToastType, number | null> = {
  success: 5000,
  info: 5000,
  error: null,
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
  /** `ref` del botón «Cerrar aviso»: la zona de avisos la usa para mover el foco al cerrar otro (ver `ToastViewport`). */
  closeRef?: Ref<HTMLButtonElement>;
}

/**
 * Un aviso individual.
 *
 * Los de éxito e información se cierran solos tras unos segundos; los de error,
 * solo a mano (ver `DURATION_MS`). La cuenta atrás se pausa en dos casos:
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
 * saltos. En móvil la zona de avisos está ARRIBA (ver `ToastViewport`), así que
 * allí baja desde arriba: entra siempre desde el borde al que está anclada.
 * Con `prefers-reduced-motion` se conserva el fundido (no marea) y se quita
 * el desplazamiento (solo se aplica con `motion-safe:`).
 *
 * El botón «Cerrar aviso» mide 44 px de lado en móvil (zona táctil mínima) sin
 * agrandar el aviso: el margen negativo compensa el relleno.
 */
export function Toast({ toast, onDismiss, closeRef }: ToastProps) {
  const [paused, setPaused] = useState(false);
  const pageVisible = usePageVisible();
  const remainingMs = useRef(DURATION_MS[toast.type]);
  const { icon: Icon, iconClass, label } = STYLES[toast.type];
  const counting = !paused && pageVisible;

  useEffect(() => {
    if (!counting || remainingMs.current === null) return;
    const startedAt = Date.now();
    const timer = setTimeout(() => onDismiss(toast.id), remainingMs.current);
    // Al pausar (o desmontar) se descuenta lo que llevaba corriendo: es lo que permite
    // que, al volver a la pestaña, siga con el tiempo restante y no desde cero.
    return () => {
      clearTimeout(timer);
      if (remainingMs.current !== null) {
        remainingMs.current = Math.max(0, remainingMs.current - (Date.now() - startedAt));
      }
    };
  }, [counting, toast.id, onDismiss]);

  /** Fin de la pausa por puntero o foco: la cuenta atrás vuelve a empezar entera (como siempre). */
  const resume = () => {
    remainingMs.current = DURATION_MS[toast.type];
    setPaused(false);
  };

  return (
    <div
      className="pointer-events-auto flex items-start gap-3 rounded-xl border border-line bg-surface-raised p-4 shadow-2xl transition-[opacity,translate] duration-250 ease-out-strong starting:opacity-0 motion-safe:max-sm:starting:-translate-y-2 motion-safe:sm:starting:translate-y-2"
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
        ref={closeRef}
        type="button"
        onClick={() => onDismiss(toast.id)}
        aria-label="Cerrar aviso"
        className="focus-ring -m-3 rounded-md p-3.5 text-muted hover:text-white sm:-m-1 sm:p-1"
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}
