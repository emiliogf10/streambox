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
import {
  CURRENT_USER,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  makeSeriesDetail,
  makeUser,
  renderWithProviders,
  routeFetch,
} from './test/helpers';

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
    'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc': () =>
      jsonResponse(makePage([makeMovie({ id: 1, title: 'Interstellar' })])),
    'GET /api/genres': () => jsonResponse([{ id: 1, name: 'Drama' }]),
    'GET /api/series?page=0&size=12&sort=createdAt&direction=desc': () => jsonResponse(makePage([])),
    'GET /api/series?page=0&size=20&sort=createdAt&direction=desc': () =>
      jsonResponse(makePage([makeSeries({ id: 4, title: 'Dark' })])),
    'GET /api/series/4': () => jsonResponse(makeSeriesDetail({ id: 4, title: 'Dark' })),
    'GET /api/admin/series?page=0&size=10&sort=createdAt&direction=desc': () =>
      jsonResponse(makePage([makeSeries({ id: 4, title: 'Dark' })])),
    'GET /api/admin/series/4': () => jsonResponse(makeSeriesDetail({ id: 4, title: 'Dark' })),
  });
}

describe('Rutas de series', () => {
  it('«Series» de la barra lleva a /series, con su h1 y el enlace marcado como actual', async () => {
    routesFor('USER');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/', token: 'jwt' });

    const nav = screen.getByRole('navigation', { name: 'Principal' });
    await user.click(within(nav).getByRole('link', { name: 'Series' }));

    expect(screen.getByText('ruta:/series')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Series' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByRole('region', { name: 'Dark' })).toBeInTheDocument();
  });

  it('/series/:id muestra la página de la serie dentro de la aplicación (barra incluida)', async () => {
    routesFor('USER');
    renderWithProviders(<AppRoutes />, { route: '/series/4?temporada=2', token: 'jwt' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Dark' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Episodios de la temporada 2' })).toBeInTheDocument();
  });

  it('sin sesión, /series lleva al login', () => {
    routesFor('USER');
    renderWithProviders(<AppRoutes />, { route: '/series/4' });

    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
  });
});

describe('Ruta del perfil', () => {
  it('/perfil muestra la página dentro de la aplicación (barra incluida), con el nombre del usuario como único h1', async () => {
    routesFor('USER');
    renderWithProviders(<AppRoutes />, { route: '/perfil', token: 'jwt' });

    expect(await screen.findByRole('heading', { level: 1, name: 'ana' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/perfil')).toBeInTheDocument();
    expect(document.title).toBe('Mi perfil — StreamBox');
  });

  it('sin sesión, /perfil lleva al login', () => {
    routesFor('USER');
    renderWithProviders(<AppRoutes />, { route: '/perfil' });

    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'ana' })).not.toBeInTheDocument();
  });

  it('desde el menú de usuario, «Mi perfil» lleva a /perfil', async () => {
    routesFor('USER');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/', token: 'jwt' });

    await user.click(await screen.findByRole('button', { name: 'Menú de usuario' }));
    await user.click(screen.getByRole('link', { name: 'Mi perfil' }));

    expect(screen.getByText('ruta:/perfil')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'ana' })).toBeInTheDocument();
  });

  it('un administrador llega desde el perfil al panel con «Panel de administración»', async () => {
    routesFor('ADMIN');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/perfil', token: 'jwt' });

    await user.click(await screen.findByRole('link', { name: 'Panel de administración' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Administración' })).toBeInTheDocument();
  });

  it('«Cerrar sesión» desde el perfil lleva al login', async () => {
    routesFor('USER');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/perfil', token: 'jwt' });

    await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByText('ruta:/login')).toBeInTheDocument();
    expect(localStorage.getItem('token')).toBeNull();
  });
});

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
    expect(within(adminSections()).getByRole('link', { name: 'Series' })).not.toHaveAttribute('aria-current');
    expect(await screen.findByRole('row', { name: /^Interstellar/ })).toBeInTheDocument();
  });

  it('las tres secciones van en orden (Películas, Series, Géneros) y «Series» lleva al listado de series', async () => {
    routesFor('ADMIN');
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/admin/peliculas', token: 'jwt' });
    await screen.findByRole('heading', { level: 1, name: 'Administración' });
    expect(within(adminSections()).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Películas',
      'Series',
      'Géneros',
    ]);

    await user.click(within(adminSections()).getByRole('link', { name: 'Series' }));

    expect(screen.getByText('ruta:/admin/series')).toBeInTheDocument();
    expect(within(adminSections()).getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
    expect(within(adminSections()).getByRole('link', { name: 'Películas' })).not.toHaveAttribute('aria-current');
    expect(await screen.findByRole('row', { name: /^Dark/ })).toBeInTheDocument();
    // La pestaña «Series» del panel no marca el enlace «Series» de la barra principal (otra ruta: /series).
    expect(within(screen.getByRole('navigation', { name: 'Principal' })).getByRole('link', { name: 'Series' })).not.toHaveAttribute(
      'aria-current',
    );
  });

  it.each([
    ['/admin/series/nueva', 'Nueva serie'],
    ['/admin/series/4/editar', 'Editar «Dark»'],
  ])('%s muestra su formulario y mantiene marcada la sección «Series»', async (route, heading) => {
    routesFor('ADMIN');
    renderWithProviders(<AppRoutes />, { route, token: 'jwt' });

    expect(await screen.findByRole('heading', { level: 2, name: heading })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(within(adminSections()).getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
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
