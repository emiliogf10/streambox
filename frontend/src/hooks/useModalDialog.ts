import { useEffect } from 'react';
import type { RefObject } from 'react';

/**
 * Cuántos diálogos modales hay abiertos a la vez. El bloqueo del scroll se
 * quita solo cuando se cierra el último (por si algún día se anidan dos).
 */
let openDialogs = 0;

/** Clase que `index.css` convierte en `overflow: hidden` sobre `<html>`. */
const SCROLL_LOCK_CLASS = 'scroll-locked';

function lockScroll() {
  openDialogs += 1;
  document.documentElement.classList.add(SCROLL_LOCK_CLASS);
}

function unlockScroll() {
  openDialogs = Math.max(0, openDialogs - 1);
  if (openDialogs === 0) document.documentElement.classList.remove(SCROLL_LOCK_CLASS);
}

/**
 * Gestiona el ciclo de vida de un `<dialog>` modal nativo.
 *
 * `showModal()` ya resuelve lo más difícil de un modal accesible: lo pinta en
 * la capa superior, vuelve inerte el resto de la página, atrapa el foco y
 * cierra con Escape. Lo que NO hace el navegador y completa este hook:
 * - **Bloquear el scroll del fondo** mientras está abierto.
 * - **Devolver el foco a quien lo abrió**. El `close()` nativo lo hace, pero
 *   cuando React *desmonta* el diálogo sin llamarlo se perdería; se guarda el
 *   elemento activo al abrir y se restaura al limpiar (si sigue en la página).
 * - **Foco inicial** en un elemento concreto (p. ej. el botón seguro "Cancelar").
 *
 * @param dialogRef referencia al `<dialog>`
 * @param open si debe estar abierto
 * @param initialFocusRef elemento que recibe el foco al abrir (por defecto, el primer enfocable)
 */
export function useModalDialog(
  dialogRef: RefObject<HTMLDialogElement | null>,
  open: boolean,
  initialFocusRef?: RefObject<HTMLElement | null>,
) {
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog || !open) return;

    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (!dialog.open) dialog.showModal();
    lockScroll();
    initialFocusRef?.current?.focus();

    return () => {
      unlockScroll();
      if (dialog.open) dialog.close();
      if (opener?.isConnected) opener.focus();
    };
  }, [dialogRef, open, initialFocusRef]);
}
