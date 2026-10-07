/**
 * Tests de `SeriesPage` (`/series`) con el hook real y `fetch` simulado.
 *
 * Protegen: los tres estados (cargando con esqueleto, vacío —distinto para un
 * usuario y para un administrador, que puede tener series ocultas sin
 * episodios— y error con reintento), que la serie más reciente sea el banner y no se
 * repita debajo, las filas por género (la misma regla que las películas), que
 * las tarjetas sean ENLACES a la página de cada serie y «Cargar más series».
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makePage,
  makeSeries,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { SeriesPage } from './SeriesPage';

const fetchMock = vi.fn<typeof fetch>();

const PAGE_0 = 'GET /api/series?page=0&size=20&sort=createdAt&direction=desc';
const PAGE_1 = 'GET /api/series?page=1&size=20&sort=createdAt&direction=desc';
const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };

const drama = { id: 4, name: 'Drama' };

// Orden del servidor: más reciente primero. La 1 será el banner.
const hero = makeSeries({ id: 1, title: 'Estreno serie', genres: [drama], endYear: null, seasonCount: 3, episodeCount: 24 });
const others = [
  makeSeries({ id: 2, title: 'Segunda', genres: [drama] }),
  makeSeries({ id: 3, title: 'Tercera', genres: [drama] }),
  makeSeries({ id: 4, title: 'Cuarta', genres: [drama] }),
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

function renderPage() {
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <SeriesPage />
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true, route: '/series' },
  );
  return user;
}

describe('SeriesPage: estados', () => {
  it('mientras carga muestra el esqueleto (role="status") y ya tiene su único h1 «Series»', () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => new Promise<Response>(() => {}) });

    renderPage();

    expect(screen.getByText('Cargando series...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Series']);
    expect(document.title).toBe('Series — StreamBox');
  });

  it('sin series, a un usuario: «Todavía no hay series», cuándo aparecerán e «Ir al inicio»; ni películas ni panel', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([])) });

    renderPage();

    // Con el usuario ya cargado (rol USER confirmado), no solo mientras carga.
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Todavía no hay series' })).toBeInTheDocument();
    expect(screen.getByText('Cuando se publiquen series aparecerán aquí.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByText(/película/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
    expect(screen.queryByText(/visibles|episodio/)).not.toBeInTheDocument();
    expect(screen.queryByText('Novedad')).not.toBeInTheDocument();
  });

  it('sin series visibles, a un administrador: dice que solo se ven con episodios y lleva a «Gestionar series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [PAGE_0]: () => jsonResponse(makePage([])),
    });

    renderPage();

    expect(
      await screen.findByRole('heading', { level: 2, name: 'Todavía no hay series visibles' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Las series solo aparecen aquí cuando tienen al menos un episodio. Créalas y añádeles episodios desde el panel de administración.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Gestionar series' })).toHaveAttribute('href', '/admin/series');
    // El mensaje del usuario (falso para él: SÍ hay series, ocultas) no aparece.
    expect(screen.queryByRole('heading', { name: 'Todavía no hay series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ir al inicio' })).not.toBeInTheDocument();
  });

  it('sin series y con el usuario aún cargando: mensaje de usuario normal (falla cerrado), sin enlace al panel', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => new Promise<Response>(() => {}),
      [PAGE_0]: () => jsonResponse(makePage([])),
    });

    renderPage();

    expect(await screen.findByRole('heading', { level: 2, name: 'Todavía no hay series' })).toBeInTheDocument();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
  });

  it('error: muestra el mensaje y «Reintentar» recupera las series', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderPage();

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las series' })).toBeInTheDocument();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();

    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Estreno serie' })).toBeInTheDocument();
  });
});

describe('SeriesPage: contenido', () => {
  beforeEach(() => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
  });

  it('la serie más reciente es el banner: «Novedad», sus datos, «Ver episodios» a su página y «Mi lista»', async () => {
    renderPage();

    const banner = await screen.findByRole('region', { name: 'Estreno serie' });
    expect(within(banner).getByText('Novedad')).toBeInTheDocument();
    expect(within(banner).getByRole('list', { name: 'Datos de la serie' })).toHaveTextContent(
      /2019–\s*En emisión\s*3 temporadas\s*24 episodios\s*Drama/,
    );
    expect(within(banner).getByRole('link', { name: /^Ver episodios\s*de Estreno serie$/ })).toHaveAttribute(
      'href',
      '/series/1',
    );
    expect(within(banner).getByRole('button', { name: /^Mi lista\s*—\s*Estreno serie$/ })).toBeInTheDocument();
    // Una serie no tiene un único vídeo: el banner no ofrece «Ver ahora».
    expect(within(banner).queryByRole('link', { name: /Ver ahora/ })).not.toBeInTheDocument();
  });

  it('filas «Novedades» y una por género con ≥3 series, sin repetir la del banner, con tarjetas que son enlaces', async () => {
    renderPage();

    const news = await screen.findByRole('region', { name: 'Novedades' });
    expect(within(news).queryByText('Estreno serie')).not.toBeInTheDocument();
    const cards = within(news).getAllByRole('link');
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/series/2', '/series/3', '/series/4']);
    expect(cards[0]).toHaveAccessibleName('Segunda 2019–2022 · 2 temporadas');
    expect(within(screen.getByRole('region', { name: 'Drama' })).getAllByRole('link')).toHaveLength(3);
    // Las tarjetas de serie navegan: no son botones que abran un diálogo.
    expect(within(news).queryByRole('button', { name: /^Segunda/ })).not.toBeInTheDocument();
  });

  it('jerarquía: un único h1 y, debajo, banner y filas como h2', async () => {
    renderPage();
    await screen.findByRole('region', { name: 'Novedades' });

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno serie',
      'Novedades',
      'Drama',
    ]);
  });

  it('indica cuántas se muestran del total y sin más páginas no ofrece «Cargar más»', async () => {
    renderPage();

    expect(await screen.findByText('Mostrando 4 de 4 series')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });
});

describe('SeriesPage: cargar más', () => {
  const withNext = () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true }));

  it('añade la página siguiente sin duplicados y retira el botón al llegar al final', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      [PAGE_1]: () =>
        jsonResponse(makePage([others[2], makeSeries({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5 })),
    });
    const user = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cargar más series' }));

    expect(await screen.findByText('Mostrando 5 de 5 series')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /^Quinta/ }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('si falla conserva lo cargado, avisa y el botón pasa a «Reintentar carga»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      [PAGE_1]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cargar más series' }));

    expect(await screen.findByRole('button', { name: 'Reintentar carga' })).toBeEnabled();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Estreno serie' })).toBeInTheDocument();
    expect(screen.getByText('Mostrando 4 de 5 series')).toBeInTheDocument();
  });
});
