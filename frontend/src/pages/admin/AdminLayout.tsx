import { NavLink, Outlet } from 'react-router-dom';
import { Clapperboard, Tags } from 'lucide-react';
import { ADMIN_GENRES_PATH, ADMIN_MOVIES_PATH } from './adminPaths';

/**
 * Clases de una pestaña del panel. La activa se marca con negrita, texto
 * blanco y una barra de acento debajo (forma + peso, no solo color); el resto
 * va en `muted` (6,0:1 sobre el fondo).
 */
const tabClass = ({ isActive }: { isActive: boolean }) =>
  'focus-ring relative -mb-px inline-flex min-h-11 items-center gap-2 rounded-t-md px-1 text-sm transition-colors ' +
  (isActive
    ? 'font-semibold text-white after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:rounded-full after:bg-accent'
    : 'text-muted hover:text-white');

/**
 * Estructura común del panel de administración (`/admin/*`): el único `<h1>`
 * de la página («Administración»), las dos secciones y, debajo, la pantalla de
 * cada ruta (`<Outlet />`), que usa `<h2>` para su propio título.
 *
 * **Por qué enlaces y no el patrón ARIA de pestañas (`tablist`).** Aunque se
 * vean como pestañas, «Películas» y «Géneros» son PÁGINAS distintas, cada una
 * con su URL: se pueden abrir en otra pestaña, compartir, recargar y recorrer
 * con «Atrás». El patrón `role="tablist"` describe otra cosa (paneles dentro de
 * una misma página que se alternan con las flechas del teclado, sin cambiar de
 * URL), y anunciar "pestaña 1 de 2" para algo que navega confundiría a quien usa
 * un lector de pantalla. Por eso es un `<nav>` con nombre propio y `NavLink`, que
 * marca la sección actual con `aria-current="page"` (y la de películas sigue
 * activa en el alta y la edición, que cuelgan de su ruta).
 *
 * Se monta dentro de `RequireAdmin`: no decide nada sobre permisos.
 */
export function AdminLayout() {
  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:py-8">
      <header className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Administración</h1>
        <p className="mt-1 text-sm text-muted">Gestiona el catálogo: películas y géneros.</p>
        <nav aria-label="Secciones de administración" className="mt-5 flex gap-6 border-b border-line">
          <NavLink to={ADMIN_MOVIES_PATH} className={tabClass}>
            <Clapperboard aria-hidden="true" className="size-4" />
            Películas
          </NavLink>
          <NavLink to={ADMIN_GENRES_PATH} className={tabClass}>
            <Tags aria-hidden="true" className="size-4" />
            Géneros
          </NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  );
}
