import type { ReactNode } from 'react';
import { MAIN_CONTENT_ID } from './SkipLink';

/** Propiedades de {@link AuthLayout}. */
interface AuthLayoutProps {
  title: string;
  subtitle: string;
  children: ReactNode;
  /** Pie del formulario, normalmente el enlace cruzado login/registro. */
  footer?: ReactNode;
}

/**
 * Estructura común de las pantallas públicas (login y registro): logotipo,
 * título, formulario y pie. Así las dos pantallas son idénticas por
 * construcción y no duplican marcado. El `<main>` es el destino del enlace
 * "Saltar al contenido" (`id="contenido"`).
 *
 * **Por qué ya no hay tarjeta.** Antes el formulario iba en una tarjeta con
 * borde y sombra centrada en un fondo plano: el patrón por defecto de cualquier
 * plantilla, y en 375 px el doble relleno (página + tarjeta) estrechaba los
 * campos. Ahora el formulario va directamente sobre el fondo, más estrecho
 * (`max-w-sm`, una sola columna de lectura) y con un título más grande.
 *
 * **La luz de arriba.** Un degradado radial ámbar muy tenue cae desde la parte
 * superior, como la luz de un proyector en una sala a oscuras. Retoma el punto
 * ámbar del logotipo y es lo único decorativo de la pantalla; es puro CSS (sin
 * imágenes ni peticiones) y no tiene animación. Contraste: en el punto de más
 * luz (accent al 12 % sobre canvas) el texto `muted` sigue por encima de 5:1.
 *
 * Se centra (y no se usa un diseño asimétrico) porque es una tarea de un solo
 * paso: el usuario viene a escribir dos campos y pulsar un botón, no a explorar.
 */
export function AuthLayout({ title, subtitle, children, footer }: AuthLayoutProps) {
  return (
    <main
      id={MAIN_CONTENT_ID}
      tabIndex={-1}
      className="relative isolate flex min-h-dvh flex-col items-center justify-center overflow-hidden bg-canvas px-5 py-12 text-white outline-hidden"
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-[36rem] bg-radial-[ellipse_at_top] from-accent/12 via-accent/4 via-40% to-transparent to-70%"
      />

      <div className="w-full max-w-sm">
        <div className="mb-12 flex items-center gap-2.5">
          <span aria-hidden="true" className="size-3 rounded-full bg-accent" />
          <span className="text-xl font-bold tracking-tight">streambox</span>
        </div>

        <h1 className="mb-2 text-3xl leading-tight font-bold tracking-tight text-balance">{title}</h1>
        <p className="mb-8 text-sm text-muted">{subtitle}</p>
        {children}
        {footer && <div className="mt-8 border-t border-line pt-6 text-sm text-muted">{footer}</div>}
      </div>
    </main>
  );
}
