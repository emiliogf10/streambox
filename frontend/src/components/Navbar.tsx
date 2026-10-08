import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { LogOut, ShieldCheck, User, UserCircle } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useHeightCssVariable } from '../hooks/useHeightCssVariable';
import { SearchBar } from './SearchBar';

/**
 * Variable CSS con la altura real de la barra (la publica {@link useHeightCssVariable}).
 * La usa `index.css` para que el foco nunca quede debajo de la barra.
 */
export const NAVBAR_HEIGHT_VARIABLE = '--navbar-height';

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
 * Barra superior de la zona autenticada: logo, navegación principal, buscador y menú de usuario.
 *
 * **Responsive.** Se queda pegada arriba (`sticky`, no `fixed`: ocupa su sitio
 * en el flujo, así que el contenido no necesita un margen superior que la
 * compense). En pantallas estrechas se apila en tres filas (`flex-wrap`):
 * logo + menú de usuario, navegación a todo el ancho y buscador a todo el
 * ancho; desde `md` todo cabe en una fila. No se
 * usa menú "hamburguesa": con cuatro entradas cortas caben en una fila de
 * 320 px, y esconderlas tras un botón añadiría un clic sin ganar espacio real.
 *
 * **Accesibilidad.** `<header>` + `<nav aria-label>`; el menú de usuario es un
 * botón de despliegue (`aria-expanded` + `aria-controls`) que se cierra con
 * Escape (devolviendo el foco al botón), al pulsar fuera o al sacar el foco.
 * Todos los objetivos táctiles miden al menos 44 px.
 *
 * **Usuario y rol.** Cuando `AuthContext` ya ha cargado el usuario
 * (`GET /api/users/me`), el menú muestra su nombre y, solo a los
 * administradores, la etiqueta «Administrador» (texto con icono, no solo un
 * color). Es contenido normal del desplegable, no un `role="menu"`, así que el
 * lector de pantalla lo lee en orden al recorrerlo. Es informativo: el rol en el
 * cliente solo decide qué se pinta; quien protege los datos es el backend (403).
 * Tras los datos del usuario van dos acciones, ambas de 44 px: «Mi perfil»
 * (enlace a `/perfil`, que cierra el menú al navegar) y «Cerrar sesión». El perfil
 * vive aquí y no en la fila de navegación principal, que ya está al límite de ancho.
 *
 * **Navegación.** «Inicio» (`/`), «Películas» (`/peliculas`), «Series»
 * (`/series`) y «Mi lista» (`/favorites`). Ya no hay secciones reservadas:
 * «Películas» y «Series» fueron texto atenuado «(próximamente)» hasta tener su
 * página, y al pasar a enlace ocupan el mismo hueco (mismo `px-2` y texto), así
 * que la barra no se ensanchó.
 *
 * **Enlace de administración.** Solo los administradores ven «Administrar»
 * (a `/admin`), tras «Mi lista». Con él la navegación pasa de cuatro a cinco
 * entradas y deja de caber en dos sitios: la fila propia del móvil (por debajo
 * de 640 px) y la fila única de 768–1023 px, donde comparte espacio con el logo,
 * el buscador y el menú. Ahí el enlace se queda en su icono (el escudo, el mismo
 * de la etiqueta «Administrador» del menú) y el texto pasa a `sr-only`: el
 * nombre accesible sigue siendo «Administrar» y el `title` lo muestra al pasar
 * el ratón. Además la navegación admite `flex-wrap` como red de seguridad: en
 * una pantalla aún más estrecha (320 px) baja a otra línea antes que provocar
 * scroll horizontal. Para ganar margen a 768 px, el buscador mide 12rem en
 * `md` y vuelve a 15rem desde `lg`.
 *
 * **El foco no queda debajo de la barra (WCAG 2.2 · 2.4.11).** Que sea
 * `sticky` evita compensarla en la maquetación, pero NO al desplazar: cuando el
 * navegador lleva a la vista el elemento enfocado (Tab, `focus()` de un
 * formulario con errores, `scrollIntoView`), solo comprueba que quepa en la
 * ventana, y la franja de arriba la tapa la barra. Por eso la barra publica su
 * altura real en `--navbar-height` ({@link useHeightCssVariable}) e `index.css`
 * da a todo lo que hay en `<main>` un `scroll-margin-top` de esa altura más un
 * respiro (ver allí por qué así y no con `scroll-padding-top` en `<html>`).
 */
export function Navbar() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { isAuthenticated, isCheckingSession, user, isAdmin, logout } = useAuth();
  const headerRef = useRef<HTMLElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  useHeightCssVariable(headerRef, NAVBAR_HEIGHT_VARIABLE);

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
    <header ref={headerRef} className="sticky top-0 z-50 border-b border-line bg-canvas">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2 sm:px-6 md:min-h-14 md:flex-nowrap md:gap-x-4 md:py-0 lg:gap-x-6">
        <Link to="/" className="focus-ring flex min-h-11 items-center gap-2 rounded-md pr-1">
          <span aria-hidden="true" className="size-2.5 rounded-full bg-accent" />
          <span className="text-base font-bold tracking-tight text-white">streambox</span>
        </Link>

        {/* En móvil la navegación baja a su propia fila (order-last + w-full), antes del buscador por orden del DOM. */}
        {isAuthenticated && (
          <nav
            aria-label="Principal"
            className="order-last flex w-full flex-wrap items-center gap-1 md:order-none md:w-auto md:flex-1 lg:gap-3"
          >
            <NavLink to="/" end className={navLinkClass}>
              Inicio
            </NavLink>
            {/* Sin `end`: también queda marcado en cualquier subruta de la sección (y con filtros, `?genero=4`). */}
            <NavLink to="/peliculas" className={navLinkClass}>
              Películas
            </NavLink>
            {/* Sin `end`: también queda marcado en la página de una serie (`/series/7`), que es parte de la sección. */}
            <NavLink to="/series" className={navLinkClass}>
              Series
            </NavLink>
            <NavLink to="/favorites" className={navLinkClass}>
              Mi lista
            </NavLink>
            {/* Solo con el rol ya confirmado por el servidor: mientras carga `isAdmin` es false y no aparece. */}
            {isAdmin && (
              <NavLink to="/admin" title="Administrar" className={(state) => `${navLinkClass(state)} gap-1.5`}>
                <ShieldCheck aria-hidden="true" className="size-4 shrink-0" />
                {/* El texto se oculta (solo a la vista) donde no cabe: ver «Enlace de administración» arriba. */}
                <span className="max-sm:sr-only md:max-lg:sr-only">Administrar</span>
              </NavLink>
            )}
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
                className="absolute top-full right-0 z-60 mt-1 max-w-[min(18rem,calc(100vw-2rem))] min-w-44 rounded-xl border border-line bg-surface py-1 shadow-2xl"
              >
                {/* Solo con el usuario ya cargado: mientras carga o si falla, el menú queda como siempre (sin huecos). */}
                {user && (
                  <div className="mb-1 border-b border-line px-4 pt-2 pb-3">
                    <p className="text-xs text-muted">Sesión iniciada como</p>
                    <p className="truncate text-sm font-semibold text-white" title={user.username}>
                      {user.username}
                    </p>
                    {isAdmin && (
                      <p className="mt-2 inline-flex items-center gap-1 rounded-full border border-accent/40 bg-accent/10 px-2 py-0.5 text-xs font-medium text-accent">
                        <ShieldCheck aria-hidden="true" className="size-3.5" />
                        Administrador
                      </p>
                    )}
                  </div>
                )}
                {/* NavLink: marca `aria-current="page"` cuando ya estás en el perfil. Cierra el menú al navegar. */}
                <NavLink
                  to="/perfil"
                  onClick={() => setMenuOpen(false)}
                  className={({ isActive }) =>
                    `focus-ring flex min-h-11 w-full items-center gap-2 px-4 text-left text-sm transition-colors hover:text-white ${
                      isActive ? 'font-semibold text-white' : 'text-gray-300'
                    }`
                  }
                >
                  <User aria-hidden="true" className="size-4" />
                  Mi perfil
                </NavLink>
                <button
                  type="button"
                  onClick={() => void logout()}
                  className="focus-ring flex min-h-11 w-full items-center gap-2 px-4 text-left text-sm text-gray-300 transition-colors hover:text-white"
                >
                  <LogOut aria-hidden="true" className="size-4" />
                  Cerrar sesión
                </button>
              </div>
            )}
          </div>
        ) : (
          // Mientras el arranque averigua si hay sesión no se ofrece «Iniciar sesión»:
          // a quien ya la tiene le parpadearía un botón falso.
          !isCheckingSession && (
            <Link
              to="/login"
              className="focus-ring inline-flex min-h-11 items-center rounded-lg bg-accent px-4 text-sm font-semibold text-black"
            >
              Iniciar sesión
            </Link>
          )
        )}
      </div>
    </header>
  );
}
