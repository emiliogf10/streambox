import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { useToast } from '../context/ToastContext';
import { ApiError, apiFetch } from '../lib/api';

/** Lo mínimo que necesita {@link useCatalogDelete} de un título del catálogo. */
interface Deletable {
  id: number;
  title: string;
}

/** Lo que devuelve {@link useCatalogDelete}. */
export interface CatalogDelete<T extends Deletable> {
  /** Título pendiente de confirmar (el diálogo está abierto si no es `null`). */
  pending: T | null;
  /** Abre la confirmación para `item`. */
  ask: (item: T) => void;
  /** Cierra la confirmación sin borrar. */
  cancel: () => void;
  /** Borra el título pendiente (el botón «Sí, borrar» del diálogo). */
  confirm: () => void;
  /** `true` mientras el servidor no ha respondido: el diálogo no se puede cerrar. */
  deleting: boolean;
  /**
   * Para el `<h2>` de la sección (con `tabIndex={-1}`): recibe el foco tras borrar, porque la
   * fila (y su botón, que lo tenía) desaparece y el foco se perdería en `<body>`.
   */
  headingRef: RefObject<HTMLHeadingElement | null>;
}

/**
 * Borrado con confirmación de un título del catálogo desde el panel (películas
 * y series comparten el flujo; solo cambia la ruta del `DELETE`).
 *
 * - **No es optimista**: es destructivo y afecta a las listas de todos los
 *   usuarios, así que se espera al servidor antes de quitar la fila.
 * - **404 = ya estaba borrado** (otra persona u otra pestaña): el estado
 *   deseado ya se cumple, se informa sin tono de error.
 * - Al terminar (bien o mal) se llama a `onSettled` para recargar el listado.
 * - Tras borrar, el foco va al título de la sección **al cerrarse el
 *   diálogo**: mientras está abierto el resto de la página es inerte.
 *
 * @param deletePath ruta de la API que borra `item` (p. ej. `` (s) => `/series/${s.id}` ``)
 * @param onSettled se llama al terminar, haya ido bien o mal (normalmente, recargar)
 */
export function useCatalogDelete<T extends Deletable>(
  deletePath: (item: T) => string,
  onSettled: () => void,
): CatalogDelete<T> {
  const toast = useToast();
  const [pending, setPending] = useState<T | null>(null);
  const [deleting, setDeleting] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusHeadingAfterClose = useRef(false);

  const confirm = async () => {
    if (!pending) return;
    const { title } = pending;
    setDeleting(true);
    try {
      await apiFetch(deletePath(pending), { method: 'DELETE' });
      toast.success(`«${title}» se ha borrado del catálogo.`);
      focusHeadingAfterClose.current = true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        toast.info(`«${title}» ya no estaba en el catálogo. Se ha actualizado el listado.`);
        focusHeadingAfterClose.current = true;
      } else {
        toast.errorFrom(error, `No se pudo borrar «${title}».`);
      }
    } finally {
      setDeleting(false);
      setPending(null);
      onSettled();
    }
  };

  useEffect(() => {
    if (!pending && focusHeadingAfterClose.current) {
      focusHeadingAfterClose.current = false;
      headingRef.current?.focus();
    }
  }, [pending]);

  return {
    pending,
    ask: setPending,
    cancel: () => setPending(null),
    confirm: () => void confirm(),
    deleting,
    headingRef,
  };
}
