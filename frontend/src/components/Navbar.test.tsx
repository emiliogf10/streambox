/**
 * Tests de `Navbar`: los enlaces «Películas», «Series» y «Administrar» y el menú de usuario.
 *
 * Protegen la muestra visible del rol: el enlace «Administrar» y la etiqueta
 * «Administrador» del menú aparecen SOLO a quien el servidor confirma como
 * `ADMIN`, y el menú enseña el nombre del usuario de la sesión. Mientras `/users/me` carga o si falla, el menú es el de siempre (sin
 * huecos ni textos «undefined») y cerrar sesión sigue funcionando. `fetch` está
 * simulado; la sesión y los avisos son los proveedores reales.
 *
 * Además comprueban que la barra publica su altura en `--navbar-height`, el dato
 * con el que `index.css` evita que el foco quede tapado por ella.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { CURRENT_USER, errorResponse, jsonResponse, makeUser, renderWithProviders, routeFetch } from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { NAVBAR_HEIGHT_VARIABLE, Navbar } from './Navbar';

const fetchMock = vi.fn<typeof fetch>();

/** Rutas que cualquier pantalla con sesión pide al montarse (la lista, para el buscador). */
const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Monta la barra con sesión iniciada; `currentUser` decide qué responde `/users/me`. */
function renderNavbar(currentUser: () => Response | Promise<Response>) {
  routeFetch(fetchMock, { ...FAVORITES, [CURRENT_USER]: currentUser });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <Navbar />
      <UserStatusProbe />
    </FavoritesProvider>,
    { token: 'jwt' },
  );
  return user;
}

/** Abre el desplegable con el botón «Menú de usuario». */
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Menú de usuario' }));
}

describe('Navbar: enlace «Administrar»', () => {
  /** La navegación principal (los enlaces de la barra). */
  const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

  it('un administrador ve «Administrar», que lleva a /admin, después de «Inicio», «Películas», «Series» y «Mi lista»', async () => {
    renderNavbar(() => jsonResponse(makeUser({ role: 'ADMIN' })));

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
    renderNavbar(() => jsonResponse(makeUser({ role: 'USER' })));
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
    renderNavbar(() => jsonResponse(makeUser()));

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
      { token: 'jwt', route: '/peliculas?genero=4&orden=titulo-asc' },
    );

    expect(within(mainNav()).getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).not.toHaveAttribute('aria-current');
    expect(within(mainNav()).getByRole('link', { name: 'Series' })).not.toHaveAttribute('aria-current');
    await screen.findByRole('navigation', { name: 'Principal' });
  });

  it('en la portada no está marcado (solo «Inicio»)', async () => {
    routeFetch(fetchMock, { ...FAVORITES });
    renderWithProviders(
      <FavoritesProvider>
        <Navbar />
      </FavoritesProvider>,
      { token: 'jwt', route: '/' },
    );

    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Películas' })).not.toHaveAttribute('aria-current');
    await screen.findByRole('navigation', { name: 'Principal' });
  });
});

describe('Navbar: enlace «Series»', () => {
  const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

  it('«Series» es un enlace a /series (ya no texto reservado «próximamente»)', async () => {
    renderNavbar(() => jsonResponse(makeUser()));

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
      { token: 'jwt', route: '/series/7?temporada=2' },
    );

    expect(within(mainNav()).getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
    expect(within(mainNav()).getByRole('link', { name: 'Inicio' })).not.toHaveAttribute('aria-current');
    await screen.findByRole('navigation', { name: 'Principal' });
  });

  it('mientras se carga el usuario no aparece: solo se enseña con el rol ya confirmado', () => {
    renderNavbar(() => new Promise<Response>(() => {}));

    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(within(mainNav()).queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
  });

  it('si /users/me falla tampoco aparece (falla cerrado)', async () => {
    renderNavbar(() => errorResponse(500, 'INTERNAL_ERROR', 'boom'));
    await screen.findByText('estado:error');

    expect(within(mainNav()).queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
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
      { token: 'jwt' },
    );

    expect(NAVBAR_HEIGHT_VARIABLE).toBe('--navbar-height');
    expect(document.documentElement.style.getPropertyValue('--navbar-height')).toBe('165px');
    await screen.findByRole('navigation', { name: 'Principal' });

    unmount();
    expect(document.documentElement.style.getPropertyValue('--navbar-height')).toBe('');
  });
});

describe('Navbar: menú de usuario', () => {
  it('a un usuario normal le muestra su nombre y NO la etiqueta «Administrador»', async () => {
    const user = renderNavbar(() => jsonResponse(makeUser({ username: 'ana', role: 'USER' })));

    await openMenu(user);

    expect(await screen.findByText('ana')).toBeInTheDocument();
    expect(screen.getByText('Sesión iniciada como')).toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
  });

  it('a un administrador le muestra su nombre y la etiqueta «Administrador» como texto', async () => {
    const user = renderNavbar(() => jsonResponse(makeUser({ username: 'jefa', role: 'ADMIN' })));

    await openMenu(user);

    expect(await screen.findByText('Administrador')).toBeInTheDocument();
    expect(screen.getByText('jefa')).toBeInTheDocument();
    // El icono es decorativo: el rol se comunica con el texto, no con el color ni con el dibujo.
    expect(screen.getByText('Administrador').querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('mientras /users/me carga, el menú es el de siempre: solo «Cerrar sesión», sin huecos ni «undefined»', async () => {
    const user = renderNavbar(() => new Promise<Response>(() => {}));

    await openMenu(user);

    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
    expect(screen.queryByText('Sesión iniciada como')).not.toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('undefined');
  });

  it('si /users/me falla, el menú sigue funcionando y cerrar sesión cierra la sesión', async () => {
    const user = renderNavbar(() => errorResponse(500, 'INTERNAL_ERROR', 'boom'));
    await screen.findByText('estado:error');

    await openMenu(user);

    expect(screen.queryByText('Sesión iniciada como')).not.toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    expect(localStorage.getItem('token')).toBeNull();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toBeInTheDocument();
  });
});
