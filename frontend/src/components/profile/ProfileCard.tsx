import { useId } from 'react';
import type { ReactNode } from 'react';

/** Propiedades de {@link ProfileCard}. */
interface ProfileCardProps {
  title: string;
  children: ReactNode;
}

/**
 * Tarjeta con título (`<h2>`) para agrupar datos del perfil. Es una `section`
 * con nombre accesible, así que aparece como región en la lista de puntos de
 * referencia del lector de pantalla. Misma superficie que el menú de usuario y
 * las tarjetas del panel.
 */
export function ProfileCard({ title, children }: ProfileCardProps) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="rounded-xl border border-line bg-surface p-5">
      <h2 id={headingId} className="mb-4 text-lg font-bold tracking-tight text-white">
        {title}
      </h2>
      {children}
    </section>
  );
}
