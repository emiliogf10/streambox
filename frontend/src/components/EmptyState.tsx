import type { ReactNode } from 'react';

/** Propiedades de {@link EmptyState}. */
interface EmptyStateProps {
  /** Icono decorativo (un icono de `lucide-react`), dentro de un círculo. Se ignora si hay `visual`. */
  icon?: ReactNode;
  /**
   * Ilustración propia que sustituye al icono en círculo (decorativa: se oculta
   * a los lectores de pantalla). Útil cuando el estado vacío puede ENSEÑAR qué
   * aparecerá ahí, como los huecos de póster de "Mi lista".
   */
  visual?: ReactNode;
  title: string;
  description: string;
  /** Acción opcional (un enlace o botón), p. ej. "Explorar catálogo". */
  action?: ReactNode;
  /**
   * Nivel del título: `h2` (por defecto) cuando la página ya tiene su `<h1>`;
   * `h1` cuando el estado ES la página entera (p. ej. «Serie no encontrada»).
   */
  titleAs?: 'h1' | 'h2';
}

/**
 * Estado "vacío": explica por qué no hay nada que mostrar y, si procede, qué
 * hacer a continuación. Evita pantallas en blanco que parecen un fallo.
 */
export function EmptyState({ icon, visual, title, description, action, titleAs: Title = 'h2' }: EmptyStateProps) {
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-6 px-4 text-center">
      {visual ? (
        <div aria-hidden="true">{visual}</div>
      ) : (
        icon && (
          <div
            aria-hidden="true"
            className="flex size-16 items-center justify-center rounded-full border border-line bg-surface text-muted"
          >
            {icon}
          </div>
        )
      )}
      <div className="max-w-sm">
        <Title className="mb-1.5 text-lg font-semibold tracking-tight text-balance text-white">{title}</Title>
        <p className="text-sm leading-relaxed text-pretty text-muted">{description}</p>
      </div>
      {action}
    </div>
  );
}
