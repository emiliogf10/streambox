import { useEffect, useRef } from 'react';
import type { ReactNode, RefObject } from 'react';
import { useToast } from '../context/ToastContext';
import { useModalDialog } from '../hooks/useModalDialog';

/** Propiedades de {@link Modal}. */
interface ModalProps {
  /** Si está abierto. Por defecto `true`: basta con montarlo/desmontarlo. */
  open?: boolean;
  /** Se invoca al pedir el cierre (Escape, clic en el fondo...). El padre decide si cerrar. */
  onClose: () => void;
  /** Si es `false` (p. ej. mientras hay una operación en curso) Escape y el fondo no cierran. */
  dismissible?: boolean;
  /** `alertdialog` para preguntas que exigen respuesta (confirmaciones); `dialog` para el resto. */
  role?: 'dialog' | 'alertdialog';
  /** `id` del elemento que da nombre al diálogo (normalmente su título). */
  labelledBy: string;
  /** `id` del elemento que lo describe (opcional). */
  describedBy?: string;
  /** Elemento que recibe el foco al abrir; por defecto el primer enfocable. */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** Clases adicionales para el cuadro (normalmente `max-w-*`). */
  className?: string;
  children: ReactNode;
}

/**
 * Diálogo modal accesible, base de {@link MovieDetailsModal} y {@link ConfirmDialog}.
 *
 * Envuelve el elemento nativo `<dialog>` abierto con `showModal()` (ver
 * {@link useModalDialog}), que aporta capa superior, fondo inerte y foco
 * atrapado. Este componente añade:
 * - Semántica explícita: `role`, `aria-modal`, `aria-labelledby`, `aria-describedby`.
 * - **Escape** (evento `cancel`) y **clic en el fondo** llaman a `onClose`; el
 *   estado lo gobierna React, no el navegador (por eso se evita el cierre
 *   automático con `preventDefault`).
 * - El clic en el fondo solo cierra si el clic EMPEZÓ también en el fondo: si
 *   alguien selecciona texto y suelta el ratón fuera, no se cierra por accidente.
 * - Una zona para los avisos (toasts): el `<dialog>` modal tapa todo lo que hay
 *   fuera de él, y un error mostrado detrás del fondo pasaría desapercibido.
 * - En móvil ocupa casi toda la pantalla; el contenido largo se desplaza dentro.
 */
export function Modal({
  open = true,
  onClose,
  dismissible = true,
  role = 'dialog',
  labelledBy,
  describedBy,
  initialFocusRef,
  className = '',
  children,
}: ModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const toastSlotRef = useRef<HTMLDivElement>(null);
  const pressStartedOnBackdrop = useRef(false);
  const { registerHost } = useToast();

  useModalDialog(dialogRef, open, initialFocusRef);

  // Mientras está abierto, los avisos se pintan dentro del diálogo.
  useEffect(() => {
    if (!open) return;
    registerHost(toastSlotRef.current);
    return () => registerHost(null);
  }, [open, registerHost]);

  return (
    <dialog
      ref={dialogRef}
      role={role}
      aria-modal="true"
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      onCancel={(event) => {
        // Escape: el navegador cerraría el <dialog> por su cuenta, dejando a React con un estado desfasado.
        event.preventDefault();
        if (dismissible) onClose();
      }}
      // Respaldo: el navegador puede cerrar el diálogo sin pasar por `cancel`
      // (p. ej. al pulsar Escape dos veces seguidas sin otra interacción).
      // El evento `close` llega en una tarea posterior: si para entonces el
      // diálogo ya se ha vuelto a abrir (React StrictMode monta, desmonta y
      // vuelve a montar en desarrollo) es un cierre antiguo y se ignora.
      onClose={() => {
        if (!dialogRef.current?.open) onClose();
      }}
      onMouseDown={(event) => {
        pressStartedOnBackdrop.current = event.target === dialogRef.current;
      }}
      onClick={(event) => {
        // Un clic cuyo destino es el propio <dialog> (no su contenido) es un clic en el fondo.
        if (event.target === dialogRef.current && pressStartedOnBackdrop.current && dismissible) onClose();
      }}
      className={
        'm-auto max-h-[calc(100dvh-1rem)] w-[calc(100%-1rem)] overflow-y-auto overscroll-contain rounded-2xl ' +
        'border border-line bg-surface p-0 text-white shadow-2xl backdrop:bg-black/80 ' +
        'sm:max-h-[calc(100dvh-4rem)] sm:w-[calc(100%-2rem)] ' +
        className
      }
    >
      {children}
      <div ref={toastSlotRef} />
    </dialog>
  );
}
