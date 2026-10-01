import { useId, useRef } from 'react';
import { Button } from './Button';
import { Modal } from './Modal';

/** Propiedades de {@link ConfirmDialog}. */
interface ConfirmDialogProps {
  /** Si el diálogo está abierto. El padre es quien controla este estado. */
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  /** Mientras es `true` (operación en curso) no se puede cancelar y el botón de confirmar se bloquea. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Diálogo de confirmación para acciones destructivas (p. ej. vaciar la lista).
 *
 * Se apoya en {@link Modal} (`<dialog>` nativo: capa superior, fondo inerte,
 * foco atrapado, Escape, bloqueo de scroll y devolución del foco) y añade:
 * - `role="alertdialog"`: se anuncia como una pregunta que requiere respuesta.
 * - El foco inicial va al botón seguro "Cancelar", para que pulsar Intro sin
 *   leer no destruya nada.
 * - Escape y clic en el fondo cancelan, salvo mientras hay una operación en curso.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = 'Cancelar',
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  return (
    <Modal
      open={open}
      role="alertdialog"
      labelledBy={titleId}
      describedBy={descriptionId}
      initialFocusRef={cancelRef}
      dismissible={!busy}
      onClose={onCancel}
      className="max-w-md"
    >
      <div className="p-6">
        <h2 id={titleId} className="mb-2 text-lg font-bold">
          {title}
        </h2>
        <p id={descriptionId} className="mb-6 text-sm leading-relaxed text-muted">
          {description}
        </p>
        <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
          <Button ref={cancelRef} variant="outline" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button variant="danger" onClick={onConfirm} disabled={busy}>
            {busy ? 'Procesando...' : confirmLabel}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
