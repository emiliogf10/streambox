import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useCatalogPresence } from '../hooks/useCatalogPresence';

/** Clases por defecto del enlace: texto de acento y objetivo táctil de 44 px (para un hueco dentro de una frase). */
const DEFAULT_CLASS =
  'focus-ring inline-flex min-h-11 items-center rounded-md text-sm font-semibold text-accent hover:underline';

/** Propiedades de {@link ExploreLink}. */
interface ExploreLinkProps {
  /** Sección del catálogo a la que lleva (`/peliculas` muestra `/movies`; `/series`, `/series`). */
  catalog: '/movies' | '/series';
  to: string;
  children: ReactNode;
  /** Sustituye a las clases por defecto (p. ej. `buttonClasses('outline')` para verlo como botón). */
  className?: string;
}

/**
 * Enlace «Explorar películas/series» de un estado vacío, que solo aparece si al
 * otro lado hay algo que explorar.
 *
 * Sin esta comprobación, «Explorar series» con ninguna serie visible (todas sin
 * episodios, por ejemplo) llevaba a otra página vacía: un callejón sin salida.
 * Así que pregunta antes ({@link useCatalogPresence}, una petición mínima que
 * solo se hace si el componente llega a montarse, es decir, si la sección está vacía):
 * - Mientras pregunta no pinta nada, para no enseñar un enlace que quizá se
 *   retire al momento.
 * - Si no se pudo saber (error), lo enseña: es mejor ofrecer el camino y que la
 *   página de destino explique su propio error que esconderlo por un fallo pasajero.
 * - Si no hay nada, no lo enseña: el texto que lo acompaña debe seguir siendo
 *   verdad sin él y no prometer nada.
 *
 * Lo comparten «Mi lista» y el perfil.
 */
export function ExploreLink({ catalog, to, children, className = DEFAULT_CLASS }: ExploreLinkProps) {
  const presence = useCatalogPresence(catalog);
  if (presence === 'checking' || presence === 'none') return null;
  return (
    <Link to={to} className={className}>
      {children}
    </Link>
  );
}
