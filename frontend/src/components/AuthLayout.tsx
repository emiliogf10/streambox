import type { ReactNode } from 'react';
import { MAIN_CONTENT_ID } from './SkipLink';

/** Propiedades de {@link AuthLayout}. */
interface AuthLayoutProps {
  title: string;
  subtitle: string;
  children: ReactNode;
  /** Pie de la tarjeta, normalmente el enlace cruzado login/registro. */
  footer?: ReactNode;
}

/**
 * Estructura común de las pantallas públicas (login y registro): logo y
 * tarjeta centrada con título, formulario y pie. Así las dos pantallas son
 * idénticas por construcción y no duplican marcado. El `<main>` es el destino
 * del enlace "Saltar al contenido" (`id="contenido"`).
 */
export function AuthLayout({ title, subtitle, children, footer }: AuthLayoutProps) {
  return (
    <main
      id={MAIN_CONTENT_ID}
      tabIndex={-1}
      className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-8 text-white outline-hidden"
    >
      <div className="mb-10 flex items-center gap-2">
        <div aria-hidden="true" className="size-2.5 rounded-full bg-accent" />
        <span className="text-lg font-bold tracking-tight">streambox</span>
      </div>

      <div className="w-full max-w-md rounded-2xl border border-line bg-surface p-6 shadow-2xl sm:p-8">
        <h1 className="mb-1 text-2xl font-bold">{title}</h1>
        <p className="mb-7 text-sm text-muted">{subtitle}</p>
        {children}
        {footer && <div className="mt-6 text-center text-sm text-muted">{footer}</div>}
      </div>
    </main>
  );
}
