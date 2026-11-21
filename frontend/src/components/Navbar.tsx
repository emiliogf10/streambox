import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { flushSync } from 'react-dom';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { LogOut, Search, ShieldCheck, User, UserCircle } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useHeightCssVariable } from '../hooks/useHeightCssVariable';
import { useHideOnScroll } from '../hooks/useHideOnScroll';
import { SearchBar } from './SearchBar';
import { UserAvatar } from './UserAvatar';

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
 * Marca de «estás aquí» del enlace «Administrar» cuando solo se ve su icono
 * (por debajo de `lg`, 1024 px; ver «Enlace de administración» en {@link Navbar}).
 *
 * **El problema (WCAG 1.4.1, revisión de `qa`).** El subrayado de
 * {@link navLinkClass} va en el TEXTO, y ahí el texto es `sr-only`: el enlace
 * activo solo se distinguía del resto por el color del icono (blanco frente a
 * blanco al 70 %). La negrita tampoco se ve en un icono.
 *
 * **La marca.** Una raya de 2 px del color de acento bajo el escudo, a la altura
 * del subrayado de las demás pestañas (`after:`, sin cambiar el tamaño de 44×44).
 * Es un BORDE y no un fondo a propósito: con colores forzados (modo de alto
 * contraste de Windows) el navegador borra los fondos pero pinta los bordes con
 * el color del texto, así que la marca no desaparece. El acento sobre `canvas`
 * da 8.53:1 (≥ 3:1 de WCAG 1.4.11). Desde `lg` se ve el texto con su subrayado
 * y la raya sobraría (serían dos).
 */
const adminIconOnlyMark = ({ isActive }: { isActive: boolean }) =>
  isActive
    ? 'max-lg:after:absolute max-lg:after:inset-x-2 max-lg:after:bottom-1 max-lg:after:border-b-2 max-lg:after:border-accent'
    : '';

/**
 * Barra superior de la zona autenticada: logo, navegación principal, buscador y menú de usuario.
 *
 * **Responsive.** Se queda pegada arriba (`sticky`, no `fixed`: ocupa su sitio
 * en el flujo, así que el contenido no necesita un margen superior que la
 * compense). Desde `md` (768 px) todo cabe en una fila de 57 px: logo,
 * navegación, buscador y menú. Por debajo de `md`:
 * - **El buscador se pliega tras una lupa** (botón «Buscar películas y series»,
 *   `aria-expanded` + `aria-controls`). Al pulsarla, el campo aparece en su
 *   propia fila con el foco dentro (y en móvil, el teclado); la lupa lo vuelve a
 *   plegar, y también Escape cuando ya no queda nada que cerrar ni que borrar en
 *   el buscador (sus dos primeros Escape siguen siendo suyos: cerrar la lista y
 *   vaciar el campo), devolviendo el foco a la lupa. Al cambiar de página se
 *   pliega solo. Es UN solo `SearchBar` para todos los tamaños (el combobox, sus
 *   `id` y sus 44 px y 16 px no se duplican): CSS solo decide si su fila se ve.
 * - **Entre 640 y 767 px** (`sm`) la navegación cabe junto al logo: una fila.
 *   **Por debajo de 640 px**, dos filas: logo, lupa y menú arriba, y las
 *   pestañas debajo (se mantienen a la vista: son la navegación principal). No
 *   se usa menú «hamburguesa»: cuatro entradas cortas caben en 320 px, y
 *   esconderlas tras un botón añadiría un clic sin ganar espacio real.
 * - **Se aparta al bajar y vuelve al subir** ({@link useHideOnScroll}): mientras
 *   se lee, la página entera es contenido; cualquier gesto hacia arriba la
 *   trae de vuelta. Se desliza con `transform` (no cambia la maquetación ni
 *   mueve el contenido), en 200 ms con la curva de salida del proyecto, y sin
 *   transición si se ha pedido reducir el movimiento (`motion-safe:`): entonces
 *   aparece y desaparece sin deslizarse. NUNCA se aparta si tiene el foco
 *   (`:focus-within`: quien navega con el teclado o escribe en el buscador la
 *   sigue viendo) ni con el menú de usuario abierto. En escritorio no se mueve:
 *   una fila de 57 px no estorba. Mientras está apartada lleva `data-navbar-away`:
 *   la zona de avisos de móvil, anclada justo debajo de la barra, sube con ella
 *   (variante `navbar-away` de `index.css`; ver `ToastViewport`).
 *
 * Por qué así (revisión de 2026-11, D-1): a 375 px la barra medía 165 px, el
 * 20-25 % de la pantalla, siempre. La guía de `modern-web-guidance` pide barras
 * fijas por debajo del 12 % de la altura y apartarlas al bajar; plegar el
 * buscador la deja en dos filas (≈ 105 px, el 13 % de 812 px) arriba del todo
 * y apartarla al bajar deja la pantalla entera para el contenido mientras se lee.
 *
 * **Accesibilidad.** `<header>` + `<nav aria-label>`; el menú de usuario es un
 * botón de despliegue (`aria-expanded` + `aria-controls`) que se cierra con
 * Escape (devolviendo el foco al botón), al pulsar fuera o al sacar el foco.
 * Todos los objetivos táctiles miden al menos 44 px.
 *
 * **Usuario y rol.** Cuando `AuthContext` ya ha cargado el usuario
 * (`GET /api/users/me`), el botón del menú enseña su avatar ({@link UserAvatar}:
 * el icono que eligió en «Mi perfil» o su inicial) y el menú muestra su nombre y, solo a los
 * administradores, la etiqueta «Administrador» (texto con icono, no solo un
 * color). Es contenido normal del desplegable, no un `role="menu"`, así que el
 * lector de pantalla lo lee en orden al recorrerlo. Es informativo: el rol en el
 * cliente solo decide qué se pinta; quien protege los datos es el backend (403).
 * Tras los datos del usuario van dos acciones, ambas de 44 px: «Mi perfil»
 * (enlace a `/perfil`, que cierra el menú al navegar) y «Cerrar sesión». El perfil
 * vive aquí y no en la fila de navegación principal, que ya está al límite de ancho.
 * «Cerrar sesión» espera la confirmación del servidor (ver `logout` en
 * `AuthContext`): mientras tanto dice «Cerrando sesión...» y no se puede repetir;
 * si falla, el menú sigue abierto con el botón listo para reintentar y un aviso.
 *
 * **Navegación.** «Inicio» (`/`), «Películas» (`/peliculas`), «Series»
 * (`/series`) y «Mi lista» (`/favorites`). Ya no hay secciones reservadas:
 * «Películas» y «Series» fueron texto atenuado «(próximamente)» hasta tener su
 * página, y al pasar a enlace ocupan el mismo hueco (mismo `px-2` y texto), así
 * que la barra no se ensanchó.
 *
 * **Enlace de administración.** Solo los administradores ven «Administrar»
 * (a `/admin`), tras «Mi lista». Con él la navegación pasa de cuatro a cinco
 * entradas y deja de caber por debajo de 1024 px: en la fila propia del móvil,
 * junto al logo entre 640 y 767 px y en la fila única de 768–1023 px, donde
 * comparte espacio con el logo, el buscador y el menú. Ahí el enlace se queda en
 * su icono (el escudo, el mismo de la etiqueta «Administrador» del menú) y el texto pasa a `sr-only`: el
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
 * Apartarla no cambia nada de esto: `transform` no altera su altura medida, así
 * que el margen reserva siempre la barra entera, se vea o no, y al subir (que es
 * cuando vuelve) lo enfocado queda por debajo de ella.
 */
export function Navbar() {
  const [menuOpen, setMenuOpen] = useState(false);
  const { isAuthenticated, isCheckingSession, user, isAdmin, logout, isLoggingOut } = useAuth();
  const headerRef = useRef<HTMLElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const searchAreaId = useId();
  const searchToggleRef = useRef<HTMLButtonElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const { pathname } = useLocation();
  /**
   * Ruta en la que se desplegó el buscador de móvil. Guardar la RUTA (y no un
   * `true`) hace que se pliegue solo al cambiar de página, sin un efecto que lo cierre.
   */
  const [searchOpenAt, setSearchOpenAt] = useState<string | null>(null);
  const searchOpen = searchOpenAt === pathname;
  useHeightCssVariable(headerRef, NAVBAR_HEIGHT_VARIABLE);
  // Con el menú abierto no se aparta (en un móvil tocar un botón no siempre le da el foco).
  const barAway = useHideOnScroll(headerRef, isAuthenticated) && !menuOpen;

  useEffect(() => {
    const handleOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, []);

  /**
   * La lupa despliega o pliega el buscador (solo existe por debajo de `md`).
   *
   * Al desplegar, el foco va al campo DENTRO del propio clic: `flushSync` pinta la
   * fila antes de enfocar (un campo oculto no se puede enfocar) y Safari de iOS
   * solo saca el teclado si el `focus()` ocurre durante el gesto del usuario.
   */
  const toggleSearch = () => {
    if (searchOpen) {
      setSearchOpenAt(null);
      return;
    }
    flushSync(() => setSearchOpenAt(pathname));
    searchInputRef.current?.focus();
  };

  /**
   * Escape dentro del buscador desplegado: cuando el propio buscador ya no lo
   * usa (no hay lista que cerrar ni texto que borrar, `defaultPrevented` en
   * falso), lo pliega y devuelve el foco a la lupa. En escritorio no hay lupa
   * ni nada que plegar: el estado no se ve.
   */
  const handleSearchKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape' || event.defaultPrevented || !searchOpen) return;
    event.preventDefault();
    setSearchOpenAt(null);
    searchToggleRef.current?.focus();
  };

  /** Escape cierra el menú y devuelve el foco al botón que lo abrió. */
  const handleMenuKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && menuOpen) {
      event.stopPropagation();
      setMenuOpen(false);
      toggleRef.current?.focus();
    }
  };

  return (
    <header
      ref={headerRef}
      // Marca «apartada» para otras piezas que dependen de dónde está la barra: la zona de avisos de móvil
      // (`ToastViewport`) sube con ella a través de la variante `navbar-away` de `index.css`.
      data-navbar-away={barAway || undefined}
      // Por debajo de `md`, se aparta al bajar (salvo con el foco dentro) y se desliza solo si no se ha pedido
      // reducir el movimiento. Al entrar el foco aparece SIN transición (`focus-within:transition-none`): una acción
      // de teclado no se anima y, a mitad de deslizamiento, el navegador vería el control enfocado fuera de la
      // pantalla y desplazaría la página para enseñarlo. Ver «Responsive» arriba.
      className={`sticky top-0 z-50 border-b border-line bg-canvas max-md:motion-safe:transition-transform max-md:motion-safe:duration-200 max-md:motion-safe:ease-out-strong max-md:focus-within:transition-none ${
        barAway ? 'max-md:not-focus-within:-translate-y-full' : ''
      }`}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-1.5 sm:px-6 md:min-h-14 md:flex-nowrap md:gap-x-4 md:py-0 lg:gap-x-6">
        <Link to="/" className="focus-ring flex min-h-11 items-center gap-2 rounded-md pr-1">
          <span aria-hidden="true" className="size-2.5 rounded-full bg-accent" />
          <span className="text-base font-bold tracking-tight text-white">streambox</span>
        </Link>

        {/* Por debajo de 640 px la navegación baja a su propia fila (order-last + w-full), antes de la del buscador
            por orden del DOM; desde `sm` cabe junto al logo. */}
        {isAuthenticated && (
          <nav
            aria-label="Principal"
            className="order-last flex w-full flex-wrap items-center gap-1 sm:order-none sm:w-auto sm:flex-1 lg:gap-3"
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
              // `min-w-11 justify-center`: donde solo se ve el escudo, el enlace mide 44×44 (zona táctil, WCAG 2.5.5).
              <NavLink
                to="/admin"
                title="Administrar"
                className={(state) => `${navLinkClass(state)} relative min-w-11 justify-center gap-1.5 ${adminIconOnlyMark(state)}`}
              >
                <ShieldCheck aria-hidden="true" className="size-4 shrink-0" />
                {/* El texto se oculta (solo a la vista) donde no cabe: ver «Enlace de administración» arriba. */}
                <span className="max-lg:sr-only">Administrar</span>
              </NavLink>
            )}
          </nav>
        )}

        {/* Lupa del buscador plegado: solo por debajo de `md` (desde ahí el campo está siempre en la barra). */}
        {isAuthenticated && (
          <button
            ref={searchToggleRef}
            type="button"
            onClick={toggleSearch}
            aria-label="Buscar películas y series"
            title="Buscar"
            aria-expanded={searchOpen}
            aria-controls={searchAreaId}
            className={`focus-ring ml-auto inline-flex size-11 shrink-0 items-center justify-center rounded-full transition-colors md:hidden ${
              searchOpen ? 'bg-surface-raised text-white' : 'text-gray-300 hover:text-white'
            }`}
          >
            <Search aria-hidden="true" className="size-5" />
          </button>
        )}

        {/* Por debajo de `md` el buscador va en su propia fila (order-last + w-full) y solo se ve desplegado. */}
        {isAuthenticated && (
          <div
            id={searchAreaId}
            onKeyDown={handleSearchKeyDown}
            className={`order-last w-full md:order-none md:w-auto ${searchOpen ? '' : 'max-md:hidden'}`}
          >
            <SearchBar inputRef={searchInputRef} />
          </div>
        )}

        {isAuthenticated ? (
          <div
            ref={menuRef}
            className="relative"
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
              {/* Con el usuario cargado, su avatar (decorativo: el botón ya se llama «Menú de usuario»);
                  mientras carga o si falla, el icono genérico de siempre. */}
              {user ? (
                <UserAvatar username={user.username} avatar={user.avatar} size="sm" />
              ) : (
                <UserCircle aria-hidden="true" className="size-6" />
              )}
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
                {/* Mientras el servidor confirma el cierre: `aria-disabled` (no `disabled`) para que el foco
                    no salte fuera del menú —lo cerraría— y siga ahí si falla y hay que reintentar. */}
                <button
                  type="button"
                  onClick={() => void logout()}
                  aria-disabled={isLoggingOut || undefined}
                  className={`focus-ring flex min-h-11 w-full items-center gap-2 px-4 text-left text-sm text-gray-300 transition-colors ${
                    isLoggingOut ? 'cursor-wait' : 'hover:text-white'
                  }`}
                >
                  <LogOut aria-hidden="true" className="size-4" />
                  {isLoggingOut ? 'Cerrando sesión...' : 'Cerrar sesión'}
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
