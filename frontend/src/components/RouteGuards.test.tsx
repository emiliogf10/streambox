/**
 * Tests de `RequireAuth` y `RedirectIfAuthenticated`.
 *
 * Son el mecanismo por el que el cierre de sesión (401, otra pestaña, logout)
 * lleva al login sin que cada pantalla lo gestione, y por el que un usuario con
 * sesión no ve el formulario de login.
 */
import { act, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../test/helpers';
import { RedirectIfAuthenticated, RequireAuth } from './RouteGuards';

/** Mini aplicación con una ruta privada (`/`) y una pública (`/login`). */
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
