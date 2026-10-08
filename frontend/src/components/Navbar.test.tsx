/**
 * Tests de `Navbar`: los enlaces «Películas», «Series» y «Administrar» y el menú de usuario
 * (con su enlace «Mi perfil»).
 *
 * Protegen la muestra visible del rol: el enlace «Administrar» y la etiqueta
 * «Administrador» del menú aparecen SOLO a quien el servidor confirma como
 * `ADMIN`, y el menú enseña el nombre del usuario de la sesión. Mientras se comprueba la sesión la barra solo tiene el logo; si tras
 * iniciar sesión el usuario no carga, el menú es el de siempre (sin huecos) y cerrar sesión sigue funcionando. `fetch` está
 * simulado; la sesión y los avisos son los proveedores reales.
 *
 * Además comprueban que la barra publica su altura en `--navbar-height`, el dato
 * con el que `index.css` evita que el foco quede tapado por ella.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuth } from '../context/AuthContext';
import { FavoritesProvider } from '../context/FavoritesContext';
import { CURRENT_USER, errorResponse, jsonResponse, makeUser, noContentResponse, renderWithProviders, routeFetch } from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { NAVBAR_HEIGHT_VARIABLE, Navbar } from './Navbar';

const fetchMock = vi.fn<typeof fetch>();

/** Rutas que cualquier pantalla con sesión pide al montarse (la lista, para el buscador). */
const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/**
 * Monta la barra con sesión iniciada y espera a que el arranque la confirme
 * (`GET /users/me`); `currentUser` decide qué responde `/users/me`.
 */
async function renderNavbar(currentUser: () => Response | Promise<Response>) {
  routeFetch(fetchMock, { ...FAVORITES, [CURRENT_USER]: currentUser });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <Navbar />
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true },
  );
  await screen.findByRole('button', { name: 'Menú de usuario' });
  return user;
}

/** Botón de prueba que inicia sesión con el contexto real (`login`). */
function SignInButton() {
  const { login } = useAuth();
  return (
    <button type="button" onClick={() => void login('ana@example.com', 'x').catch(() => undefined)}>
      Entrar de prueba
    </button>
  );
}

/** Abre el desplegable con el botón «Menú de usuario». */
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Menú de usuario' }));
}

describe('Navbar: enlace «Administrar»', () => {
  /** La navegación principal (los enlaces de la barra). */
  const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

  it('un administrador ve «Administrar», que lleva a /admin, después de «Inicio», «Películas», «Series» y «Mi lista»', async () => {
    await renderNavbar(() => jsonResponse(makeUser({ role: 'ADMIN' })));

    const link = await within(mainNav()).findByRole('link', { name: 'Administrar' });
    expect(link).toHaveAttribute('href', '/admin');
    expect(within(mainNav()).getAllByRole('link').map((element) => element.textContent)).toEqual([
      'Inicio',
      'Películas',
      'Series',
      'Mi lista',
      'Administrar',
    ]);
    // El icono es decorativo: el nombre lo da el texto (visible u oculto según el ancho), no el dibujo.
    expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('un usuario normal NO lo ve: solo «Inicio», «Películas», «Series» y «Mi lista»', async () => {
    await renderNavbar(() => jsonResponse(makeUser({ role: 'USER' })));
    await screen.findByText('estado:ready');

    expect(within(mainNav()).queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
    expect(within(mainNav()).getAllByRole('link').map((element) => element.textContent)).toEqual([
      'Inicio',
      'Películas',
      'Series',
      'Mi lista',
    ]);
  });
});

describe('Navbar: enlace «Películas»', () => {
  const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

  it('«Películas» es un enlace a /peliculas: ya no hay secciones reservadas «(próximamente)»', async () => {
    await renderNavbar(() => jsonResponse(makeUser()));

    const link = within(mainNav()).getByRole('link', { name: 'Películas' });
    expect(link).toHaveAttribute('href', '/peliculas');
    expect(link).not.toHaveAttribute('aria-disabled');
    // Ningún elemento de la navegación queda desactivado ni anunciado como «próximamente».
    expect(mainNav().querySelector('[aria-disabled]')).toBeNull();
    expect(within(mainNav()).queryByText(/próximamente/)).not.toBeInTheDocument();
    await screen.findByText('estado:ready');
  });

  it('queda marcado como página actual en /peliculas, también con filtros en la URL', async () => {
    routeFetch(fetchMock, { ...FAVORITES });
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { session: true, route: '/peliculas?genero=4&orden=titulo-asc' },
    );
    await screen.findByRole('navigation', { name: 'Principal' });

    expect(within(mainNav()).getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).not.toHaveAttribute('aria-current');
    expect(within(mainNav()).getByRole('link', { name: 'Series' })).not.toHaveAttribute('aria-current');
  });

  it('en la portada no está marcado (solo «Inicio»)', async () => {
    routeFetch(fetchMock, { ...FAVORITES });
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { session: true, route: '/' },
    );
    await screen.findByRole('navigation', { name: 'Principal' });

    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Películas' })).not.toHaveAttribute('aria-current');
  });
});

describe('Navbar: enlace «Series»', () => {
  const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

  it('«Series» es un enlace a /series (ya no texto reservado «próximamente»)', async () => {
    await renderNavbar(() => jsonResponse(makeUser()));

    const link = within(mainNav()).getByRole('link', { name: 'Series' });
    expect(link).toHaveAttribute('href', '/series');
    expect(link).not.toHaveAttribute('aria-disabled');
    expect(within(mainNav()).queryByText(/Series \(próximamente\)/)).not.toBeInTheDocument();
    await screen.findByText('estado:ready');
  });

  it('queda marcado como página actual en /series y también en la página de una serie', async () => {
    routeFetch(fetchMock, { ...FAVORITES });
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { session: true, route: '/series/7?temporada=2' },
    );
    await screen.findByRole('navigation', { name: 'Principal' });

    expect(within(mainNav()).getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).not.toHaveAttribute('aria-current');
  });

  it('mientras se comprueba la sesión la barra solo tiene el logo: ni enlaces, ni menú, ni «Iniciar sesión»', () => {
    routeFetch(fetchMock, { ...FAVORITES, [CURRENT_USER]: () => new Promise<Response>(() => {}) });
    renderWithProviders(
      <>
        <Navbar />
        <UserStatusProbe />
      </>,
      { session: true },
    );

    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Principal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Menú de usuario' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Iniciar sesión' })).not.toBeInTheDocument();
  });

  it('si el chequeo inicial falla (500) no se sabe el rol: no hay enlaces de sesión ni «Administrar»', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [CURRENT_USER]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    renderWithProviders(
      <>
        <Navbar />
        <UserStatusProbe />
      </>,
      { session: true },
    );
    await screen.findByText('estado:error');

    expect(screen.queryByRole('navigation', { name: 'Principal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
  });
});

describe('Navbar: altura publicada para que el foco no quede debajo de la barra', () => {
  it('publica la altura de la barra en --navbar-height (la que usa index.css) y la borra al desmontarse', async () => {
    // jsdom no maqueta: la cabecera "mide" 165 px, como la barra de tres filas del móvil.
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      return { height: this.tagName === 'HEADER' ? 165 : 0 } as DOMRect;
    });
    routeFetch(fetchMock, { ...FAVORITES, [CURRENT_USER]: () => jsonResponse(makeUser()) });
    const { unmount } = renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { session: true },
    );
    await screen.findByRole('navigation', { name: 'Principal' });

    expect(NAVBAR_HEIGHT_VARIABLE).toBe('--navbar-height');
    expect(document.documentElement.style.getPropertyValue('--navbar-height')).toBe('165px');

    unmount();
    expect(document.documentElement.style.getPropertyValue('--navbar-height')).toBe('');
  });
});

describe('Navbar: menú de usuario', () => {
  it('a un usuario normal le muestra su nombre y NO la etiqueta «Administrador»', async () => {
    const user = await renderNavbar(() => jsonResponse(makeUser({ username: 'ana', role: 'USER' })));

    await openMenu(user);

    expect(await screen.findByText('ana')).toBeInTheDocument();
    expect(screen.getByText('Sesión iniciada como')).toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
  });

  it('a un administrador le muestra su nombre y la etiqueta «Administrador» como texto', async () => {
    const user = await renderNavbar(() => jsonResponse(makeUser({ username: 'jefa', role: 'ADMIN' })));

    await openMenu(user);

    expect(await screen.findByText('Administrador')).toBeInTheDocument();
    expect(screen.getByText('jefa')).toBeInTheDocument();
    // El icono es decorativo: el rol se comunica con el texto, no con el color ni con el dibujo.
    expect(screen.getByText('Administrador').querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it.each(['USER', 'ADMIN'] as const)(
    'ofrece «Mi perfil» (enlace a /perfil) encima de «Cerrar sesión» al rol %s',
    async (role) => {
      const user = await renderNavbar(() => jsonResponse(makeUser({ role })));
      await screen.findByText('estado:ready');

      await openMenu(user);

      const profile = screen.getByRole('link', { name: 'Mi perfil' });
      expect(profile).toHaveAttribute('href', '/perfil');
      // El icono es decorativo: el nombre lo da el texto.
      expect(profile.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
      const logout = screen.getByRole('button', { name: 'Cerrar sesión' });
      // «Mi perfil» va antes en el orden del documento (y del tabulador) que «Cerrar sesión».
      expect(profile.compareDocumentPosition(logout) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      // No se añade a la fila de navegación principal (ya está al límite de ancho).
      expect(within(screen.getByRole('navigation', { name: 'Principal' })).queryByRole('link', { name: 'Mi perfil' })).toBeNull();
    },
  );

  it('pulsar «Mi perfil» navega a /perfil y cierra el menú', async () => {
    const user = await renderNavbar(() => jsonResponse(makeUser()));
    await openMenu(user);

    await user.click(screen.getByRole('link', { name: 'Mi perfil' }));

    expect(screen.getByText('ruta:/perfil')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Menú de usuario' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link', { name: 'Mi perfil' })).not.toBeInTheDocument();
  });

  it('en /perfil, «Mi perfil» queda marcado como la página actual', async () => {
    routeFetch(fetchMock, { ...FAVORITES });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { session: true, route: '/perfil' },
    );
    await screen.findByRole('button', { name: 'Menú de usuario' });

    await openMenu(user);

    expect(screen.getByRole('link', { name: 'Mi perfil' })).toHaveAttribute('aria-current', 'page');
  });

  it('si tras iniciar sesión /users/me falla, el menú sigue funcionando y cerrar sesión cierra la sesión', async () => {
    // Arranque sin sesión (401); el login sale bien pero cargar el usuario falla: sesión abierta sin rol.
    let loggedIn = false;
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () =>
        loggedIn ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
      'POST /api/auth/logout': () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
        <SignInButton />
        <UserStatusProbe />
      </FavoritesProvider>,
    );
    await user.click(await screen.findByRole('button', { name: 'Entrar de prueba' }));
    await screen.findByText('estado:error');

    await openMenu(user);

    expect(screen.queryByText('Sesión iniciada como')).not.toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mi perfil' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    expect(await screen.findByRole('link', { name: 'Iniciar sesión' })).toBeInTheDocument();
    expect(localStorage.getItem('token')).toBeNull();
  });
});
