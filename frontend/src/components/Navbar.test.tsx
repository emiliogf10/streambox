/**
 * Tests de `Navbar`: el enlace «Administrar» y el menú de usuario.
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
import { useAuth } from '../context/AuthContext';
import { FavoritesProvider } from '../context/FavoritesContext';
import { CURRENT_USER, errorResponse, jsonResponse, makeUser, renderWithProviders, routeFetch } from '../test/helpers';
import { NAVBAR_HEIGHT_VARIABLE, Navbar } from './Navbar';

const fetchMock = vi.fn<typeof fetch>();

/** Rutas que cualquier pantalla con sesión pide al montarse (la lista, para el buscador). */
const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/**
 * Escribe el `userStatus` de la sesión. Permite esperar a que `/users/me` haya
 * terminado (p. ej. en error, que no cambia nada visible en la barra) en lugar
 * de comprobar demasiado pronto y dar por bueno un estado que aún no ha llegado.
 */
function UserStatusProbe() {
  const { userStatus } = useAuth();
  return <p>{`estado:${userStatus}`}</p>;
}

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

  it('un administrador ve «Administrar», que lleva a /admin, después de «Inicio» y «Mi lista»', async () => {
    renderNavbar(() => jsonResponse(makeUser({ role: 'ADMIN' })));

    const link = await within(mainNav()).findByRole('link', { name: 'Administrar' });
    expect(link).toHaveAttribute('href', '/admin');
    expect(within(mainNav()).getAllByRole('link').map((element) => element.textContent)).toEqual([
      'Inicio',
      'Mi lista',
      'Administrar',
    ]);
    // El icono es decorativo: el nombre lo da el texto (visible u oculto según el ancho), no el dibujo.
    expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('un usuario normal NO lo ve (y «Películas» y «Series» siguen siendo texto, no enlaces)', async () => {
    renderNavbar(() => jsonResponse(makeUser({ role: 'USER' })));
    await screen.findByText('estado:ready');

    expect(within(mainNav()).queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
    expect(within(mainNav()).getAllByRole('link')).toHaveLength(2);
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
