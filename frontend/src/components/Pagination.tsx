import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from './Button';

/** Propiedades de {@link Pagination}. */
interface PaginationProps {
  /** Página actual, empezando en 1 (la que se muestra; la API cuenta desde 0). */
  page: number;
  totalPages: number;
  totalElements: number;
  /** Nombre de los elementos en singular y plural, p. ej. `['película', 'películas']`. */
  itemNames: readonly [string, string];
  /** Nombre accesible del bloque (`<nav aria-label>`), p. ej. "Paginación de películas". */
  label: string;
  onPageChange: (page: number) => void;
}

/**
 * Paginación "Anterior / Página X de Y / Siguiente" con el total de elementos.
 *
 * - **`aria-disabled` en lugar de `disabled`** en el primer y último extremo: si
 *   alguien pulsa "Siguiente" con el teclado y llega a la última página, un
 *   botón `disabled` perdería el foco (el navegador lo manda a `<body>`) y quien
 *   usa teclado o lector de pantalla tendría que volver a buscarlo. Con
 *   `aria-disabled` el foco se queda, se anuncia "no disponible" y el clic no
 *   hace nada.
 * - "Página X de Y" está en una región `role="status"`: al cambiar de página se
 *   anuncia sin mover el foco.
 * - En móvil el texto va arriba a todo el ancho y los dos botones debajo; desde
 *   `sm` todo cabe en una fila. El orden del DOM (texto, Anterior, Siguiente)
 *   es el de lectura y el de tabulación en todos los tamaños.
 */
export function Pagination({ page, totalPages, totalElements, itemNames, label, onPageChange }: PaginationProps) {
  const pages = Math.max(1, totalPages);
  const hasPrevious = page > 1;
  const hasNext = page < pages;
  const [singular, plural] = itemNames;

  return (
    <nav aria-label={label} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
      <p role="status" className="w-full text-center text-sm text-muted tabular-nums sm:order-2 sm:w-auto">
        <span>{`Página ${page} de ${pages}`}</span>
        {/* El punto medio es solo visual; el lector de pantalla oye una pausa (la coma). */}
        <span aria-hidden="true"> · </span>
        <span className="sr-only">, </span>
        <span>{`${totalElements} ${totalElements === 1 ? singular : plural}`}</span>
      </p>
      <Button
        variant="outline"
        className="font-medium sm:order-1"
        aria-disabled={hasPrevious ? undefined : true}
        onClick={() => {
          if (hasPrevious) onPageChange(page - 1);
        }}
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        Anterior
      </Button>
      <Button
        variant="outline"
        className="font-medium sm:order-3"
        aria-disabled={hasNext ? undefined : true}
        onClick={() => {
          if (hasNext) onPageChange(page + 1);
        }}
      >
        Siguiente
        <ChevronRight aria-hidden="true" className="size-4" />
      </Button>
    </nav>
  );
}
