/**
 * Tests de la caché de datos del servidor (TanStack Query) con las rutas reales
 * de la aplicación (`AppRoutes`), como las recorre un usuario:
 *
 * - **Caché entre navegaciones:** volver a una pantalla ya visitada (dentro del
 *   minuto de `staleTime`) no repite la petición, y la portada y `/peliculas`
 *   comparten la misma.
 * - **Invalidación:** tras editar una película en el panel, `/peliculas` enseña
 *   el título nuevo sin recargar (sin la invalidación, la caché fresca enseñaría
 *   el viejo).
 * - **Cambio de usuario:** al cerrar sesión (o al caducar la sesión) y entrar
 *   con otra cuenta no aparece ni un instante la lista de la anterior
 *   (`AuthProvider` vacía la caché).
 *
 * `fetch` está simulado con un pequeño «servidor» en memoria que recuerda qué
 * sesión hay y qué datos tiene cada usuario.
 */
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from './App';
import { queryKeys } from './lib/queryKeys';
import type { Movie, User } from './lib/types';
import {
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from './test/helpers';
import { createTestQueryClient } from './test/queryClient';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Catálogo de la portada y de `/peliculas` sin filtros (20 por página, lo más reciente primero). */
const CATALOG = '/api/movies?page=0&size=20&sort=createdAt&direction=desc';
/** Listado del panel (10 por página). */
const ADMIN_CATALOG = '/api/movies?page=0&size=10&sort=createdAt&direction=desc';

/** Veces que se ha pedido exactamente `url` con `GET`. */
function getCalls(url: string): number {
  return fetchMock.mock.calls.filter(([input, init]) => String(input) === url && (init?.method ?? 'GET') === 'GET')
    .length;
}

/** Barra de navegación principal. */
const mainNav = () => screen.getByRole('navigation', { name: 'Principal' });

/** Respuestas comunes a todas las pantallas (barra, fila «Series», presencia...). */
function commonRoutes(movies: () => Movie[]) {
  return {
    [`GET ${CATALOG}`]: () => jsonResponse(makePage(movies())),
    [`GET ${ADMIN_CATALOG}`]: () => jsonResponse(makePage(movies(), { size: 10 })),
    'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc': () => jsonResponse(makePage(movies().slice(0, 1))),
    'GET /api/series?page=0&size=12&sort=createdAt&direction=desc': () => jsonResponse(makePage([])),
    'GET /api/series?page=0&size=1&sort=createdAt&direction=desc': () => jsonResponse(makePage([])),
    'GET /api/genres': () => jsonResponse([{ id: 1, name: 'Drama' }]),
    'GET /api/users/me/favorites': () => jsonResponse([]),
  };
}

describe('Caché entre navegaciones', () => {
  it('la portada y /peliculas comparten el catálogo, y volver a una pantalla ya vista no lo pide otra vez', async () => {
    routeFetch(fetchMock, commonRoutes(() => [makeMovie({ id: 1, title: 'Interstellar' })]));
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/', session: true });

    expect(await screen.findByRole('heading', { level: 2, name: 'Interstellar' })).toBeInTheDocument();
    expect(getCalls(CATALOG)).toBe(1);

    // /peliculas sin filtros pide EXACTAMENTE lo mismo que la portada: se pinta desde la caché, al momento.
    await user.click(within(mainNav()).getByRole('link', { name: 'Películas' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Películas' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Interstellar' })).toBeInTheDocument();
    expect(screen.queryByText('Cargando películas...')).not.toBeInTheDocument();
    expect(await screen.findByRole('option', { name: 'Drama' })).toBeInTheDocument();

    // Ida y vuelta: ni el catálogo ni los géneros se vuelven a pedir.
    await user.click(within(mainNav()).getByRole('link', { name: 'Inicio' }));
    expect(screen.getByRole('heading', { level: 2, name: 'Interstellar' })).toBeInTheDocument();
    await user.click(within(mainNav()).getByRole('link', { name: 'Películas' }));
    expect(screen.getByRole('option', { name: 'Drama' })).toBeInTheDocument();

    expect(getCalls(CATALOG)).toBe(1);
    expect(getCalls('/api/genres')).toBe(1);
  });
});

describe('Invalidación tras escribir desde el panel', () => {
  it('tras editar una película, /peliculas y el listado del panel enseñan el título nuevo sin recargar', async () => {
    let movie = makeMovie({ id: 1, title: 'Interstellar', genres: [{ id: 1, name: 'Drama' }] });
    routeFetch(fetchMock, {
      ...commonRoutes(() => [movie]),
      'GET /api/users/me': () => jsonResponse(makeUser({ role: 'ADMIN', username: 'jefa' })),
      'GET /api/movies/1': () => jsonResponse(movie),
      'PUT /api/movies/1': ({ init }) => {
        movie = { ...movie, ...(JSON.parse(String(init.body)) as Partial<Movie>) };
        return jsonResponse(movie);
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/peliculas', session: true });
    expect(await screen.findByRole('heading', { level: 2, name: 'Interstellar' })).toBeInTheDocument();

    // Al panel: listado → «Editar Interstellar» → cambiar el título → «Guardar cambios».
    await user.click(within(mainNav()).getByRole('link', { name: 'Administrar' }));
    await user.click(await screen.findByRole('link', { name: 'Editar Interstellar' }));
    const title = await screen.findByLabelText('Título');
    await user.clear(title);
    await user.type(title, 'Interstellar (montaje del director)');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    // Vuelve al listado del panel, que ya tiene el título nuevo.
    expect(await screen.findByRole('link', { name: 'Editar Interstellar (montaje del director)' })).toBeInTheDocument();

    // /peliculas se había cargado hace unos instantes (dato «fresco»), pero la edición lo invalidó.
    await user.click(within(mainNav()).getByRole('link', { name: 'Películas' }));
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Interstellar (montaje del director)' }),
    ).toBeInTheDocument();
    expect(getCalls(CATALOG)).toBe(2);
  });
});

describe('Cambio de usuario en el mismo navegador', () => {
  it('al cerrar sesión se vacía la caché y quien entra después no ve ni un instante la lista del anterior', async () => {
    const ana = makeUser({ id: 1, username: 'ana', email: 'ana@example.com' });
    const bea = makeUser({ id: 2, username: 'bea', email: 'bea@example.com' });
    const listOf: Record<number, Movie[]> = { 1: [makeMovie({ id: 7, title: 'La favorita de Ana' })], 2: [] };
    let current: User | null = ana;

    routeFetch(fetchMock, {
      ...commonRoutes(() => [makeMovie({ id: 1, title: 'Interstellar' })]),
      'GET /api/users/me': () => (current ? jsonResponse(current) : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.')),
      'GET /api/users/me/favorites': () => jsonResponse(current ? listOf[current.id] : []),
      'POST /api/auth/logout': () => {
        current = null;
        return noContentResponse();
      },
      'POST /api/auth/login': () => {
        current = bea;
        return noContentResponse();
      },
    });
    const queryClient = createTestQueryClient();
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/favorites', session: true, queryClient });
    expect(await screen.findByText('La favorita de Ana')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Menú de usuario' }));
    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    expect(await screen.findByText('ruta:/login')).toBeInTheDocument();
    // Nada de la sesión anterior queda en la caché (ni su lista ni el catálogo que vio).
    expect(queryClient.getQueryData(queryKeys.favorites.all)).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);

    await user.type(screen.getByLabelText('Correo electrónico'), bea.email);
    await user.type(screen.getByLabelText('Contraseña'), 'una-contraseña-cualquiera');
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
    await screen.findByText('ruta:/');

    await user.click(within(mainNav()).getByRole('link', { name: 'Mi lista' }));
    // Mientras llega la lista de Bea se ve «cargando», nunca la de Ana.
    expect(screen.queryByText('La favorita de Ana')).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Tu lista está vacía' })).toBeInTheDocument();
    expect(screen.queryByText('La favorita de Ana')).not.toBeInTheDocument();
    expect(getCalls('/api/users/me/favorites')).toBe(2);
  });
});

describe('Sesión caducada con datos en la caché', () => {
  it('un 401 que el refresh no arregla vacía la caché: ni al volver al login ni al entrar con OTRA cuenta queda rastro de la lista anterior', async () => {
    const ana = makeUser({ id: 1, username: 'ana', email: 'ana@example.com' });
    const bea = makeUser({ id: 2, username: 'bea', email: 'bea@example.com' });
    const listOf: Record<number, Movie[]> = { 1: [makeMovie({ id: 7, title: 'La favorita de Ana' })], 2: [] };
    let current: User | null = ana;
    const unauthorized = () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.');

    routeFetch(fetchMock, {
      ...commonRoutes(() => [makeMovie({ id: 1, title: 'Interstellar' })]),
      'GET /api/users/me': () => (current ? jsonResponse(current) : unauthorized()),
      // Con la sesión caducada, la lista responde 401 (y el refresh, por defecto, también).
      'GET /api/users/me/favorites': () => (current ? jsonResponse(listOf[current.id]) : unauthorized()),
      'POST /api/auth/logout': () => noContentResponse(),
      'POST /api/auth/login': () => {
        current = bea;
        return noContentResponse();
      },
    });
    const queryClient = createTestQueryClient();
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/favorites', session: true, queryClient });
    expect(await screen.findByText('La favorita de Ana')).toBeInTheDocument();

    // La sesión caduca en el servidor y la lista se refresca (p. ej. al volver a la pestaña).
    current = null;
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all });
    });

    expect(await screen.findByText('ruta:/login')).toBeInTheDocument();
    expect(screen.getByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toBeInTheDocument();
    expect(queryClient.getQueryData(queryKeys.favorites.all)).toBeUndefined();
    expect(screen.queryByText('La favorita de Ana')).not.toBeInTheDocument();

    // Entra otra persona en el mismo navegador: su lista empieza de cero, sin un instante de la de Ana.
    await user.type(screen.getByLabelText('Correo electrónico'), bea.email);
    await user.type(screen.getByLabelText('Contraseña'), 'una-contraseña-cualquiera');
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
    await screen.findByText('ruta:/');
    await user.click(within(mainNav()).getByRole('link', { name: 'Mi lista' }));

    expect(screen.queryByText('La favorita de Ana')).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Tu lista está vacía' })).toBeInTheDocument();
    expect(screen.queryByText('La favorita de Ana')).not.toBeInTheDocument();
  });
});
