import type { ReactNode } from 'react';

/** Propiedades de {@link EmptyState}. */
interface EmptyStateProps {
  /** Icono decorativo (un icono de `lucide-react`). */
  icon: ReactNode;
  title: string;
  description: string;
  /** Acción opcional (un enlace o botón), p. ej. "Explorar catálogo". */
  action?: ReactNode;
}

/**
 * Estado "vacío": explica por qué no hay nada que mostrar y, si procede, qué
 * hacer a continuación. Evita pantallas en blanco que parecen un fallo.
 */
export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-5 px-4 text-center">
      <div
        aria-hidden="true"
        className="flex size-16 items-center justify-center rounded-full border border-line bg-surface text-muted"
      >
        {icon}
      </div>
      <div className="max-w-sm">
        <h2 className="mb-1 text-base font-semibold text-white">{title}</h2>
        <p className="text-sm leading-relaxed text-muted">{description}</p>
      </div>
      {action}
    </div>
  );
}
