import { Link } from 'react-router-dom';
import { Pencil, Trash } from 'lucide-react';
import { Button } from '../../components/Button';
import { buttonClasses } from '../../components/buttonStyles';
import type { ReturnToState } from './adminPaths';

/** Propiedades de {@link AdminRowActions}. */
interface AdminRowActionsProps {
  /** Título de la fila: forma parte del nombre accesible de cada acción. */
  title: string;
  /** Ruta del formulario de edición. */
  editTo: string;
  /** URL del listado (búsqueda y página) para que el formulario vuelva al mismo sitio. */
  returnState: ReturnToState;
  onDelete: () => void;
}

/**
 * Acciones «Editar» y «Borrar» de una fila de los listados del panel
 * (películas y series).
 *
 * - El nombre accesible incluye el título («Editar Interstellar»): en una tabla
 *   con veinte botones «Editar», un lector de pantalla que los liste no sabría
 *   cuál es cuál.
 * - Por debajo de `sm` se quedan en su icono, con 44 px de ancho (objetivo
 *   táctil) y el texto solo para lectores de pantalla, para que la tabla quepa
 *   en 375 px sin scroll horizontal.
 */
export function AdminRowActions({ title, editTo, returnState, onDelete }: AdminRowActionsProps) {
  return (
    <div className="flex justify-end gap-1 sm:gap-2">
      <Link
        to={editTo}
        state={returnState}
        aria-label={`Editar ${title}`}
        className={buttonClasses('ghost', 'font-medium max-sm:w-11 max-sm:px-0 sm:px-3')}
      >
        <Pencil aria-hidden="true" className="size-4 shrink-0" />
        <span className="max-sm:sr-only">Editar</span>
      </Link>
      <Button
        variant="ghost"
        aria-label={`Borrar ${title}`}
        onClick={onDelete}
        className="font-medium text-danger hover:bg-red-500/10 hover:text-danger max-sm:w-11 max-sm:px-0 sm:px-3"
      >
        <Trash aria-hidden="true" className="size-4 shrink-0" />
        <span className="max-sm:sr-only">Borrar</span>
      </Button>
    </div>
  );
}
