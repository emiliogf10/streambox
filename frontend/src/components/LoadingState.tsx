/** Propiedades de {@link LoadingState}. */
interface LoadingStateProps {
  /** Texto visible (y anunciado) mientras se carga. */
  label?: string;
}

/**
 * Estado "cargando" a pantalla (casi) completa: spinner con texto.
 *
 * `role="status"` hace que los lectores de pantalla anuncien el texto sin
 * mover el foco. El giro se desactiva con `motion-reduce` para quien lo pide
 * en su sistema.
 */
export function LoadingState({ label = 'Cargando...' }: LoadingStateProps) {
  return (
    <div role="status" className="flex min-h-[50vh] flex-col items-center justify-center gap-4 px-4">
      <div
        aria-hidden="true"
        className="size-12 animate-spin rounded-full border-4 border-white/15 border-t-accent motion-reduce:animate-none"
      />
      <p className="text-sm text-muted">{label}</p>
    </div>
  );
}
