/**
 * Tests de `RequireAuth`, `RequireAdmin` y `RedirectIfAuthenticated`.
 *
 * Son el mecanismo por el que el cierre de sesión (401, otra pestaña, logout)
 * lleva al login sin que cada pantalla lo gestione, y por el que un usuario con
 * sesión no ve el formulario de login. `RequireAdmin` (preparada para el futuro
 * `/admin`) debe esperar al rol antes de decidir y fallar cerrado.
 *
 * `fetch` está simulado: `AuthProvider` pide `/users/me` al arrancar para
 * descubrir la sesión (`routeFetch` responde por defecto con un usuario normal si
 * se renderiza con `session: true` y con 401 si no). Mientras ese chequeo está
 * pendiente las rutas esperan: redirigir ya echaría al login a quien recarga con
 * la sesión abierta.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route, Routes } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { apiFetch } from '../lib/api';
import { RedirectIfAuthenticated, RequireAdmin, RequireAuth } from './RouteGuards';
import { MAIN_CONTENT_ID, SkipLink } from './SkipLink';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, {});
});

/** Mini aplicación con una ruta privada (`/`), una de administración (`/admin`) y una pública (`/login`). */
function Routing() {
  return (
    <Routes>
      <Route
        path="/"
        element={
          <RequireAuth>
            <h1>Portada privada</h1>
          </RequireAuth>
        }
      />
      <Route
        path="/admin"
        element={
          <RequireAuth>
            <RequireAdmin>
              <h1>Panel de administración</h1>
            </RequireAdmin>
          </RequireAuth>
        }
      />
      <Route
        path="/login"
        element={
          <RedirectIfAuthenticated>
            <h1>Formulario de login</h1>
          </RedirectIfAuthenticated>
        }
      />
    </Routes>
  );
}

/** Botón de prueba que hace una petición autenticada (para provocar un 401 con la app abierta). */
function RequestButton() {
  return (
    <button type="button" onClick={() => void apiFetch('/movies').catch(() => undefined)}>
      Pedir catálogo
    </button>
  );
}

/** Botón de prueba: inicia sesión con el contexto real (`login`). */
function LoginButton() {
  const { login } = useAuth();
  return (
    <button type="button" onClick={() => void login('ana@example.com', 'x').catch(() => undefined)}>
      Entrar
    </button>
  );
}

describe('RequireAuth', () => {
  it('sin sesión (401 en el chequeo inicial) redirige a /login y no enseña el contenido privado', async () => {
    renderWithProviders(<Routing />, { route: '/' });

    expect(await screen.findByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Portada privada' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });

  it('mientras se comprueba la sesión espera: ni contenido privado ni redirección al login', () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => new Promise<Response>(() => {}) });

    renderWithProviders(<Routing />, { route: '/', session: true });

    expect(screen.getByText('Comprobando tu sesión...').closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Portada privada' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
  });

  it('con sesión muestra el contenido', async () => {
    renderWithProviders(<Routing />, { route: '/', session: true });

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
  });

  it('si el chequeo inicial falla (500) no manda al login: avisa y «Reintentar» recupera la sesión', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(<Routing />, { route: '/', session: true });

    const title = await screen.findByRole('heading', { name: 'No se pudo comprobar tu sesión' });
    expect(title.closest('[role="alert"]')).not.toBeNull();
    // No culpa a la conexión: con un 500 (o un 429 del refresh) el servidor sí respondió.
    expect(
      screen.getByText('No hemos podido confirmar si tu sesión sigue abierta. Inténtalo de nuevo en unos instantes.'),
    ).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();

    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser()) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
  });

  it('la pantalla «No se pudo comprobar tu sesión» es una página completa: <main> destino del salto y un <h1> dentro de la alerta', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <SkipLink />
        <Routing />
      </>,
      { route: '/', session: true },
    );

    const title = await screen.findByRole('heading', { level: 1, name: 'No se pudo comprobar tu sesión' });
    const main = screen.getByRole('main');
    expect(main).toHaveAttribute('id', MAIN_CONTENT_ID);
    expect(main).toContainElement(title);
    // Un solo encabezado y una sola región que se anuncia: el título va DENTRO de la alerta, no repetido fuera.
    // (Fuera del <main> solo queda la zona de avisos, que está vacía.)
    expect(screen.getAllByRole('heading')).toHaveLength(1);
    expect(within(main).getAllByRole('alert')).toHaveLength(1);
    expect(within(main).getByRole('alert')).toContainElement(title);

    // «Saltar al contenido» tiene destino: lleva el foco al <main>.
    await user.click(screen.getByRole('link', { name: 'Saltar al contenido' }));
    expect(main).toHaveFocus();
  });

  it('mientras se comprueba la sesión también hay un <main> al que saltar', () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => new Promise<Response>(() => {}) });

    renderWithProviders(<Routing />, { route: '/', session: true });

    expect(screen.getByRole('main')).toHaveAttribute('id', MAIN_CONTENT_ID);
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('Comprobando tu sesión...');
  });

  it('si la sesión caduca mientras se está dentro (401 en una petición), expulsa al login', async () => {
    renderWithProviders(
      <>
        <Routing />
        <RequestButton />
      </>,
      { route: '/', session: true },
    );
    await screen.findByRole('heading', { name: 'Portada privada' });
    routeFetch(fetchMock, {
      'GET /api/movies': () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
      'POST /api/auth/logout': () => noContentResponse(),
    });

    await userEvent.setup().click(screen.getByRole('button', { name: 'Pedir catálogo' }));

    expect(await screen.findByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });
});

describe('RequireAdmin', () => {
  it('sin sesión, RequireAuth manda al login antes de mirar el rol', async () => {
    renderWithProviders(<Routing />, { route: '/admin' });

    expect(await screen.findByText('ruta:/login')).toBeInTheDocument();
    // Solo se hizo el chequeo inicial de la sesión (y, tras su 401, el intento de
    // renovarla con el refresh token): nada del panel.
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/users/me', '/api/auth/refresh']);
  });

  it('a un administrador le muestra el contenido cuando el servidor lo confirma', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) });

    renderWithProviders(<Routing />, { route: '/admin', session: true });

    expect(await screen.findByRole('heading', { name: 'Panel de administración' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/admin')).toBeInTheDocument();
  });

  it('a un usuario normal lo redirige a la portada sin enseñarle el panel', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'USER' })) });

    renderWithProviders(<Routing />, { route: '/admin', session: true });

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Panel de administración' })).not.toBeInTheDocument();
  });

  it('si tras iniciar sesión no se pudo cargar el usuario, falla cerrado (sin panel ni expulsión) y «Reintentar» lo resuelve', async () => {
    // Arranque sin sesión; el login sale bien pero `/users/me` falla: sesión abierta sin rol confirmado.
    let loggedIn = false;
    routeFetch(fetchMock, {
      [CURRENT_USER]: () =>
        loggedIn ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
    });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Routing />
        <><LoginButton /><Link to="/admin">Ir al panel</Link></>
      </>,
      { route: '/login' },
    );
    await user.click(await screen.findByRole('button', { name: 'Entrar' }));
    await screen.findByRole('heading', { name: 'Portada privada' });
    await user.click(screen.getByRole('link', { name: 'Ir al panel' }));

    const title = await screen.findByRole('heading', { name: 'No se pudo comprobar tu cuenta' });
    expect(title.closest('[role="alert"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Panel de administración' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/admin')).toBeInTheDocument();

    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { name: 'Panel de administración' })).toBeInTheDocument();
  });
});

describe('RedirectIfAuthenticated', () => {
  it('sin sesión muestra la pantalla pública', async () => {
    renderWithProviders(<Routing />, { route: '/login' });

    expect(await screen.findByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
  });

  it('mientras se comprueba la sesión no parpadea el formulario de login', () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => new Promise<Response>(() => {}) });

    renderWithProviders(<Routing />, { route: '/login', session: true });

    expect(screen.queryByRole('heading', { name: 'Formulario de login' })).not.toBeInTheDocument();
    expect(screen.getByText('Comprobando tu sesión...')).toBeInTheDocument();
  });

  it('con sesión en /login redirige a la portada', async () => {
    renderWithProviders(<Routing />, { route: '/login', session: true });

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Formulario de login' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
  });

  it('al iniciar sesión estando en /login pasa solo a la portada (como hace LoginPage con login())', async () => {
    routeFetch(fetchMock, { 'POST /api/auth/login': () => noContentResponse() });
    renderWithProviders(
      <>
        <Routing />
        <LoginButton />
      </>,
      { route: '/login' },
    );
    await screen.findByRole('heading', { name: 'Formulario de login' });
    // `/users/me` ya existe en el servidor simulado para el usuario que se acaba de identificar.
    routeFetch(fetchMock, {
      'POST /api/auth/login': () => noContentResponse(),
      [CURRENT_USER]: () => jsonResponse(makeUser()),
    });

    await userEvent.setup().click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
  });
});
