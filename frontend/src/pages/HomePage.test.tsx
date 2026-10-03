/**
 * Tests de `HomePage` (portada) con el hook `useCatalog` real y `fetch` simulado.
 *
 * Protegen los tres estados obligatorios (cargando, vacío, error con reintento),
 * que la película del banner no se repita en las filas, el reparto en filas por
 * género y que un fallo de "Cargar más" no destruya lo que ya se ve.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { errorResponse, jsonResponse, makeMovie, makePage, renderWithProviders, routeFetch } from '../test/helpers';
import { HomePage } from './HomePage';

const fetchMock = vi.fn<typeof fetch>();

const PAGE_0 = 'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc';
const PAGE_1 = 'GET /api/movies?page=1&size=20&sort=createdAt&direction=desc';

const terror = { id: 7, name: 'Terror' };

// El orden recibido es el del servidor: más reciente primero. La 1 será el banner.
const hero = makeMovie({ id: 1, title: 'Estreno estrella', genres: [terror] });
const others = [
  makeMovie({ id: 2, title: 'Segunda', genres: [terror] }),
  makeMovie({ id: 3, title: 'Tercera', genres: [terror] }),
  makeMovie({ id: 4, title: 'Cuarta', genres: [terror] }),
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

function renderHome() {
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <HomePage />
    </FavoritesProvider>,
    { token: 'jwt' },
  );
  return user;
}

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };

describe('HomePage: estados', () => {
  it('muestra "cargando" (con role="status") mientras espera y siempre tiene un único h1', () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => new Promise<Response>(() => {}) });

    renderHome();

    expect(screen.getByText('Cargando catálogo...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('catálogo vacío: mensaje explicativo, sin banner', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([])) });

    renderHome();

    expect(await screen.findByRole('heading', { name: 'El catálogo está vacío' })).toBeInTheDocument();
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
  });

  it('error: muestra el mensaje y "Reintentar" recupera el catálogo', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderHome();

    expect(await screen.findByRole('heading', { name: 'No se pudo cargar el catálogo' })).toBeInTheDocument();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();

    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Estreno estrella' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No se pudo cargar el catálogo' })).not.toBeInTheDocument();
  });
});

describe('HomePage: contenido', () => {
  beforeEach(() => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
  });

  it('la película más reciente es el banner y NO se repite en las filas', async () => {
    renderHome();

    const banner = await screen.findByRole('region', { name: 'Estreno estrella' });
    expect(within(banner).getByRole('link', { name: /Ver ahora Estreno estrella/ })).toBeInTheDocument();

    const news = screen.getByRole('region', { name: 'Novedades' });
    expect(within(news).queryByText('Estreno estrella')).not.toBeInTheDocument();
    expect(within(news).getAllByRole('button', { name: /^(Segunda|Tercera|Cuarta)/ })).toHaveLength(3);
  });

  it('jerarquía de encabezados: un único h1 y, debajo, banner y filas como h2 (sin h3 sueltos)', async () => {
    renderHome();
    await screen.findByRole('region', { name: 'Novedades' });

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Terror',
    ]);
    // Los antiguos paneles "Sinopsis / Reparto / Ficha" (h3) ya no existen.
    expect(screen.queryByRole('heading', { level: 3 })).not.toBeInTheDocument();
    expect(screen.queryByText(/Información no disponible/)).not.toBeInTheDocument();
  });

  it('crea una fila por género con al menos 3 películas (sin contar el banner)', async () => {
    renderHome();

    const row = await screen.findByRole('region', { name: 'Terror' });

    expect(within(row).getAllByRole('button', { name: /^(Segunda|Tercera|Cuarta)/ })).toHaveLength(3);
  });

  it('indica cuántas películas se muestran del total', async () => {
    renderHome();

    expect(await screen.findByText('Mostrando 4 de 4 películas')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('pulsar una tarjeta abre el diálogo de detalles de esa película', async () => {
    const user = renderHome();

    const news = await screen.findByRole('region', { name: 'Novedades' });
    await user.click(within(news).getByRole('button', { name: /^Tercera/ }));

    const dialog = await screen.findByRole('dialog', { name: 'Tercera' });
    expect(within(dialog).getByText('Sinopsis de la película 3.')).toBeInTheDocument();
  });
});

describe('HomePage: cargar más', () => {
  const withNext = () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true }));

  it('añade la página siguiente al final sin duplicados y retira el botón al llegar al final', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      // El servidor repite la "Cuarta" (límites que se mueven) y añade una nueva.
      [PAGE_1]: () => jsonResponse(makePage([others[2], makeMovie({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5, hasNext: false })),
    });
    const user = renderHome();

    await user.click(await screen.findByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByText('Mostrando 5 de 5 películas')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Cuarta/ })).toHaveLength(2); // una en Novedades y otra en Terror, no cuatro
    expect(screen.getAllByRole('button', { name: /^Quinta/ }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('si falla conserva lo cargado, avisa y el botón pasa a "Reintentar carga"', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: withNext,
      [PAGE_1]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = renderHome();

    await user.click(await screen.findByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByRole('button', { name: 'Reintentar carga' })).toBeEnabled();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(screen.getByText('Mostrando 4 de 5 películas')).toBeInTheDocument();
  });
});
