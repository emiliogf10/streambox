import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';

/** Muestra un aviso (p. ej. `() => toast.success('Borrado.')`). */
export type ShowToast = () => void;

/**
 * Aplaza un aviso hasta que se cierre el diálogo que lo provocó.
 *
 * **El problema.** Mientras un `Modal` está abierto, los avisos se pintan DENTRO
 * de él (el resto de la página es inerte y queda tapada: ver `registerHost` en
 * `ToastContext`). Si una acción del diálogo («Sí, borrar») lanza su aviso y
 * acto seguido cierra el diálogo, el aviso nace dentro del diálogo y, un
 * instante después, al devolverse la zona de avisos a la página, se vuelve a
 * crear allí: un lector de pantalla puede leerlo dos veces (o perderlo) y su
 * cuenta atrás empieza otra vez. La guía «persistent-toast-notifications» lo
 * resume: no hay que lanzar avisos dentro de un diálogo modal, sino esperar a
 * que se cierre.
 *
 * **Cómo.** Con el diálogo abierto, el aviso se guarda y se lanza en el efecto
 * que corre cuando `dialogOpen` pasa a `false`. Ese efecto va DESPUÉS de las
 * limpiezas del `Modal` (React ejecuta todas las limpiezas de efectos antes que
 * los efectos nuevos), así que para entonces la zona de avisos ya ha vuelto a la
 * página y el aviso nace directamente allí, una sola vez. Con el diálogo cerrado
 * se lanza en el acto. Es el mismo patrón que `pendingToast` de
 * `SeriesEpisodesSection`, que además coordina el foco con él.
 *
 * Solo se guarda el último: cada acción de un diálogo termina con un único aviso.
 *
 * @param dialogOpen si el diálogo está abierto en este render
 * @returns función que lanza el aviso ahora (diálogo cerrado) o al cerrarse el diálogo
 */
export function useToastAfterDialog(dialogOpen: boolean): (show: ShowToast) => void {
  const pending = useRef<ShowToast | null>(null);
  // Se lee desde manejadores asíncronos (tras un `await`): necesitan el valor del último render pintado.
  const openRef = useRef(dialogOpen);
  useLayoutEffect(() => {
    openRef.current = dialogOpen;
  });

  useEffect(() => {
    if (dialogOpen) return;
    const show = pending.current;
    pending.current = null;
    show?.();
  }, [dialogOpen]);

  return useCallback((show: ShowToast) => {
    if (openRef.current) pending.current = show;
    else show();
  }, []);
}
