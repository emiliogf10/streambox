/**
 * Tests de `RequireAuth`, `RequireAdmin` y `RedirectIfAuthenticated`.
 *
 * Son el mecanismo por el que el cierre de sesión (401, otra pestaña, logout)
 * lleva al login sin que cada pantalla lo gestione, y por el que un usuario con
 * sesión no ve el formulario de login. `RequireAdmin` (preparada para el futuro
 * `/admin`) debe esperar al rol antes de decidir y fallar cerrado.
 *
 * `fetch` está simulado: `AuthProvider` pide `/users/me` en cuanto hay sesión
 * (`routeFetch` responde por defecto con un usuario normal).
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CURRENT_USER, errorResponse, jsonResponse, makeUser, renderWithProviders, routeFetch } from '../test/helpers';
import { RedirectIfAuthenticated, RequireAdmin, RequireAuth } from './RouteGuards';

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

describe('RequireAuth', () => {
  it('sin sesión redirige a /login y no enseña el contenido privado', () => {
    renderWithProviders(<Routing />, { route: '/' });

    expect(screen.getByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Portada privada' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });

  it('con sesión muestra el contenido', () => {
    renderWithProviders(<Routing />, { route: '/', token: 'jwt' });

    expect(screen.getByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
  });

  it('si la sesión se cierra mientras se está dentro (p. ej. en otra pestaña), expulsa al login', () => {
    renderWithProviders(<Routing />, { route: '/', token: 'jwt' });
    expect(screen.getByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'token', newValue: null }));
    });

    expect(screen.getByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });
});

describe('RequireAdmin', () => {
  it('sin sesión, RequireAuth manda al login antes de mirar el rol (no se pide /users/me)', () => {
    renderWithProviders(<Routing />, { route: '/admin' });

    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('mientras se carga el rol espera (sin redirigir ni enseñar el panel)', () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => new Promise<Response>(() => {}) });

    renderWithProviders(<Routing />, { route: '/admin', token: 'jwt' });

    // Redirigir aquí echaría a un administrador real solo por recargar la página en /admin.
    // Se busca por texto: la zona de avisos mantiene siempre otra región `role="status"` (vacía).
    expect(screen.getByText('Comprobando permisos...').closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Panel de administración' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/admin')).toBeInTheDocument();
  });

  it('a un administrador le muestra el contenido cuando el servidor lo confirma', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) });

    renderWithProviders(<Routing />, { route: '/admin', token: 'jwt' });

    expect(await screen.findByRole('heading', { name: 'Panel de administración' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/admin')).toBeInTheDocument();
  });

  it('a un usuario normal lo redirige a la portada sin enseñarle el panel', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'USER' })) });

    renderWithProviders(<Routing />, { route: '/admin', token: 'jwt' });

    expect(await screen.findByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Panel de administración' })).not.toBeInTheDocument();
  });

  it('si no se puede comprobar el rol, falla cerrado (sin panel ni expulsión) y «Reintentar» lo resuelve', async () => {
    routeFetch(fetchMock, { [CURRENT_USER]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(<Routing />, { route: '/admin', token: 'jwt' });

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
  it('sin sesión muestra la pantalla pública', () => {
    renderWithProviders(<Routing />, { route: '/login' });

    expect(screen.getByRole('heading', { name: 'Formulario de login' })).toBeInTheDocument();
  });

  it('con sesión en /login redirige a la portada', () => {
    renderWithProviders(<Routing />, { route: '/login', token: 'jwt' });

    expect(screen.getByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Formulario de login' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/')).toBeInTheDocument();
  });

  it('al iniciar sesión estando en /login pasa solo a la portada (como hace LoginPage con login())', () => {
    renderWithProviders(<Routing />, { route: '/login' });

    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'token', newValue: 'otro-token' }));
    });

    expect(screen.getByRole('heading', { name: 'Portada privada' })).toBeInTheDocument();
  });
});
