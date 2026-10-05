/**
 * Tests de las rutas reales de la aplicación (`AppRoutes`), centrados en el
 * panel de administración: `/admin` lleva al listado, las secciones se marcan
 * con `aria-current`, una ruta desconocida del panel vuelve al listado, un
 * usuario normal acaba en la portada y el enlace «Administrar» de la barra lleva
 * al panel. Se montan las MISMAS rutas que en el navegador, con un enrutador en
 * memoria; `fetch` está simulado.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from './App';
import { CURRENT_USER, jsonResponse, makeMovie, makePage, makeUser, renderWithProviders, routeFetch } from './test/helpers';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Lo que piden la barra, la portada y el panel al montarse. */
function routesFor(role: 'ADMIN' | 'USER') {
  routeFetch(fetchMock, {
    [CURRENT_USER]: () => jsonResponse(makeUser({ role, username: role === 'ADMIN' ? 'jefa' : 'ana' })),
    'GET /api/users/me/favorites': () => jsonResponse([]),
    'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc': () =>
      jsonResponse(makePage([makeMovie({ id: 1, title: 'Interstellar' })])),
    'GET /api/movies?page=0&size=10&sort=createdAt&direction=desc': () =>
      jsonResponse(makePage([makeMovie({ id: 1, title: 'Interstellar' })])),
    'GET /api/genres': () => jsonResponse([{ id: 1, name: 'Drama' }]),
  });
}

const adminSections = () => screen.getByRole('navigation', { name: 'Secciones de administración' });

describe('Rutas del panel de administración', () => {
  it('/admin lleva a /admin/peliculas, con un único h1 «Administración» y la sección actual marcada', async () => {
    routesFor('ADMIN');
    renderWithProviders(<AppRoutes />, { route: '/admin', token: 'jwt' });

    // `<Navigate>` redirige tras el primer render: se espera a la ruta final, no solo al título.
    expect(await screen.findByText('ruta:/admin/peliculas')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Administración' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(within(adminSections()).getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');
    expect(within(adminSections()).getByRole('link', { name: 'Géneros' })).not.toHaveAttribute('aria-current');
    expect(await screen.findByRole('row', { name: /^Interstellar/ })).toBeInTheDocument();
  });

  it('las secciones son enlaces (no un tablist): «Géneros» cambia de URL y de sección marcada', async () => {
    routesFor('ADMIN');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/admin/peliculas', token: 'jwt' });
    await screen.findByRole('heading', { level: 1, name: 'Administración' });
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();

    await user.click(within(adminSections()).getByRole('link', { name: 'Géneros' }));

    expect(screen.getByText('ruta:/admin/generos')).toBeInTheDocument();
    expect(within(adminSections()).getByRole('link', { name: 'Géneros' })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByRole('list', { name: 'Géneros' })).toBeInTheDocument();
  });

  it('el alta y la edición mantienen marcada la sección «Películas»', async () => {
    routesFor('ADMIN');
    renderWithProviders(<AppRoutes />, { route: '/admin/peliculas/nueva', token: 'jwt' });

    expect(await screen.findByRole('heading', { level: 2, name: 'Nueva película' })).toBeInTheDocument();
    expect(within(adminSections()).getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');
  });

  it('una ruta desconocida del panel vuelve al listado de películas', async () => {
    routesFor('ADMIN');
    renderWithProviders(<AppRoutes />, { route: '/admin/no-existe', token: 'jwt' });

    expect(await screen.findByText('ruta:/admin/peliculas')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Administración' })).toBeInTheDocument();
  });

  it('un usuario normal que entra en /admin acaba en la portada, sin panel ni enlace «Administrar»', async () => {
    routesFor('USER');
    renderWithProviders(<AppRoutes />, { route: '/admin/generos', token: 'jwt' });

    expect(await screen.findByText('ruta:/')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Administración' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Administrar' })).not.toBeInTheDocument();
    // No llegó a pedir datos del panel.
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/genres')).toBe(false);
  });

  it('sin sesión, /admin lleva al login', () => {
    routesFor('ADMIN');
    renderWithProviders(<AppRoutes />, { route: '/admin' });

    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });

  it('el enlace «Administrar» de la barra lleva al panel', async () => {
    routesFor('ADMIN');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/', token: 'jwt' });

    await user.click(await screen.findByRole('link', { name: 'Administrar' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Administración' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/admin/peliculas')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Administrar' })).toHaveAttribute('aria-current', 'page');
  });
});
