import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

/** Propiedades de {@link BackLink}. */
interface BackLinkProps {
  /** Sección a la que lleva (`/peliculas`, `/series`). */
  to: string;
  /** Texto visible, que es también su nombre accesible («Volver a series»). */
  children: string;
}

/**
 * Enlace discreto de vuelta a la sección de la que cuelga una página
 * («Volver a películas» en `/peliculas/:id`, «Volver a series» en `/series/:id`).
 *
 * **Por qué un enlace y no «Atrás» del historial (`navigate(-1)`).** El botón
 * «Atrás» del navegador ya hace eso; este enlace sirve justo cuando el historial
 * no ayuda: se ha entrado con un enlace compartido o desde un marcador (no hay
 * página anterior, o es de otra web) o se viene de la portada o de «Mi lista»
 * y se quiere seguir explorando la sección. Un enlace de verdad, además, dice
 * adónde lleva antes de pulsarlo, se puede abrir en otra pestaña y no depende
 * de lo que haya pasado antes. Lleva a la sección sin filtros: para volver a
 * una búsqueda filtrada, «Atrás» la conserva.
 *
 * **Aspecto.** Texto pequeño con una flecha, sin caja de botón: es una salida,
 * no una acción del título, y no debe competir con «Ver ahora». El color es
 * `text-white/85` y no `text-muted` porque va sobre el fondo desenfocado del
 * banner: ese blanco al 85 % ya tiene calculado su contraste en el peor caso de
 * `FeaturedBanner` (6,0:1); el gris `muted` no está calculado sobre una portada
 * clara. La flecha es decorativa (el texto ya dice «Volver»). El área táctil
 * es de 44 px de alto (`min-h-11`) aunque el texto sea pequeño (WCAG 2.5.5).
 */
export function BackLink({ to, children }: BackLinkProps) {
  return (
    <Link
      to={to}
      className="focus-ring -ml-1 inline-flex min-h-11 items-center gap-1.5 rounded-md px-1 text-sm font-medium text-white/85 transition-colors hover:text-white"
    >
      <ArrowLeft aria-hidden="true" className="size-4" />
      {children}
    </Link>
  );
}
