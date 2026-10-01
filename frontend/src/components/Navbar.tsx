import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { LogOut, UserCircle } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { SearchBar } from './SearchBar';

/**
 * Clases de un enlace de la navegación principal. `NavLink` de react-router
 * pone `aria-current="page"` al enlace de la ruta actual y nos dice cuál es
 * (`isActive`); el estado activo se marca con negrita y subrayado, no solo con color.
 */
const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  'focus-ring inline-flex min-h-11 items-center rounded-md px-2 text-sm transition-colors ' +
  (isActive
    ? 'font-semibold text-white underline decoration-accent decoration-2 underline-offset-8'
    : 'text-white/70 hover:text-white');

/**
 * Secciones previstas de la navegación que todavía no tienen ruta.
 *
 * Se muestran para anunciar el rumbo de la plataforma, pero como no llevan a
 * ningún sitio se pintan como texto atenuado (`span aria-disabled`), no como
 * enlace: así no entran en el orden de tabulación ni parecen interactivas.
 * Cuando exista la ruta de una sección, basta con moverla a un `NavLink`
 * (con `to` y `navLinkClass`) y quitarla de esta lista.
 */
const PLANNED_SECTIONS = ['Películas', 'Series'] as const;

/**
 * Barra superior de la zona autenticada: logo, navegación principal, buscador y menú de usuario.
 *
 * **Responsive.** Se queda pegada arriba (`sticky`, no `fixed`: así no hace
 * falta compensar su altura en el contenido). En pantallas estrechas se
 * apila en tres filas (`flex-wrap`): logo + menú de usuario, navegación a todo
 * el ancho y buscador a todo el ancho; desde `md` todo cabe en una fila. No se
 * usa menú "hamburguesa": con cuatro entradas cortas caben en una fila de
 * 320 px, y esconderlas tras un botón añadiría un clic sin ganar espacio real.
 *
 * **Accesibilidad.** `<header>` + `<nav aria-label>`; el menú de usuario es un
 * botón de despliegue (`aria-expanded` + `aria-controls`) que se cierra con
 * Escape (devolviendo el foco al botón), al pulsar fuera o al sacar el foco.
 * Todos los objetivos táctiles miden al menos 44 px.
 */
export function Navbar() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { isAuthenticated, logout } = useAuth();
  const menuRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    const handleOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, []);

  /** Escape cierra el menú y devuelve el foco al botón que lo abrió. */
  const handleMenuKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && menuOpen) {
      event.stopPropagation();
      setMenuOpen(false);
      toggleRef.current?.focus();
    }
  };

  return (
    <header className="sticky top-0 z-50 border-b border-line bg-canvas">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2 sm:px-6 md:min-h-14 md:flex-nowrap md:gap-x-4 md:py-0 lg:gap-x-6">
        <Link to="/" className="focus-ring flex min-h-11 items-center gap-2 rounded-md pr-1">
          <span aria-hidden="true" className="size-2.5 rounded-full bg-accent" />
          <span className="text-base font-bold tracking-tight text-white">streambox</span>
        </Link>

        {/* En móvil la navegación baja a su propia fila (order-last + w-full), antes del buscador por orden del DOM. */}
        {isAuthenticated && (
          <nav
            aria-label="Principal"
            className="order-last flex w-full items-center gap-1 md:order-none md:w-auto md:flex-1 lg:gap-3"
          >
            <NavLink to="/" end className={navLinkClass}>
              Inicio
            </NavLink>
            {PLANNED_SECTIONS.map((name) => (
              <span
                key={name}
                aria-disabled="true"
                title="Próximamente"
                className="inline-flex min-h-11 cursor-default items-center rounded-md px-2 text-sm text-muted"
              >
                {name}
                <span className="sr-only"> (próximamente)</span>
              </span>
            ))}
            <NavLink to="/favorites" className={navLinkClass}>
              Mi lista
            </NavLink>
          </nav>
        )}

        {/* En móvil el buscador baja a su propia fila (order-last + w-full). */}
        {isAuthenticated && (
          <div className="order-last w-full md:order-none md:w-auto">
            <SearchBar />
          </div>
        )}

        {isAuthenticated ? (
          <div
            ref={menuRef}
            className="relative ml-auto md:ml-0"
            onKeyDown={handleMenuKeyDown}
            onBlur={(event) => {
              // Si el foco sale del menú (Tab hacia fuera), se cierra.
              if (!menuRef.current?.contains(event.relatedTarget as Node | null)) setMenuOpen(false);
            }}
          >
            <button
              ref={toggleRef}
              type="button"
              onClick={() => setMenuOpen((open) => !open)}
              aria-label="Menú de usuario"
              aria-expanded={menuOpen}
              aria-controls={menuOpen ? menuId : undefined}
              className="focus-ring inline-flex size-11 items-center justify-center rounded-full bg-surface-raised text-gray-300 transition-colors hover:text-white"
            >
              <UserCircle aria-hidden="true" className="size-6" />
            </button>

            {menuOpen && (
              <div
                id={menuId}
                className="absolute top-full right-0 z-60 mt-1 min-w-44 rounded-xl border border-line bg-surface py-1 shadow-2xl"
              >
                <button
                  type="button"
                  onClick={() => logout()}
                  className="focus-ring flex min-h-11 w-full items-center gap-2 px-4 text-left text-sm text-gray-300 transition-colors hover:text-white"
                >
                  <LogOut aria-hidden="true" className="size-4" />
                  Cerrar sesión
                </button>
              </div>
            )}
          </div>
        ) : (
          <Link
            to="/login"
            className="focus-ring inline-flex min-h-11 items-center rounded-lg bg-accent px-4 text-sm font-semibold text-black"
          >
            Iniciar sesión
          </Link>
        )}
      </div>
    </header>
  );
}
