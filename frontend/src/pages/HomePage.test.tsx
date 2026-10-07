/**
 * Tests de `HomePage` (portada) con el hook `useCatalog` real y `fetch` simulado.
 *
 * Protegen los tres estados obligatorios (cargando, vacío, error con reintento),
 * que la película del banner no se repita en las filas, el reparto en filas por
 * género y que un fallo de "Cargar más" no destruya lo que ya se ve. Y que lo
 * vacío diga la verdad a cada rol: sin películas no se da el catálogo por vacío
 * si hay series, y un administrador sabe por qué no ve la fila «Series».
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
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
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true },
  );
  return user;
}

/** La fila «Series» de la portada: las 12 series más recientes. */
const SERIES_ROW = 'GET /api/series?page=0&size=12&sort=createdAt&direction=desc';

/**
 * Lo que la portada pide además del catálogo: la lista (para «Mi lista») y la
 * fila «Series», vacía por defecto (así no aparece y los tests de películas no cambian).
 */
const FAVORITES = {
  'GET /api/users/me/favorites': () => jsonResponse([]),
  [SERIES_ROW]: () => jsonResponse(makePage([])),
};

describe('HomePage: estados', () => {
  it('muestra "cargando" (con role="status") mientras espera y siempre tiene un único h1', () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => new Promise<Response>(() => {}) });

    renderHome();

    expect(screen.getByText('Cargando catálogo...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('sin películas ni series, a un usuario: «El catálogo está vacío» (las dos cosas), sin banner ni botón', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([])) });

    renderHome();

    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'El catálogo está vacío' })).toBeInTheDocument();
    expect(screen.getByText('Todavía no hay películas ni series. Vuelve más tarde.')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
  });

  it('sin películas PERO con series: no dice que el catálogo esté vacío, enseña la fila «Series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([])),
      [SERIES_ROW]: () => jsonResponse(makePage([makeSeries({ id: 10, title: 'Serie nueva' })])),
    });

    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(within(row).getByRole('link', { name: /^Serie nueva/ })).toHaveAttribute('href', '/series/10');
    expect(screen.queryByRole('heading', { name: 'El catálogo está vacío' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('sin películas ni series visibles, a un administrador: qué hace visible cada cosa y «Gestionar catálogo»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [PAGE_0]: () => jsonResponse(makePage([])),
    });

    renderHome();

    expect(await screen.findByRole('link', { name: 'Gestionar catálogo' })).toHaveAttribute('href', '/admin/peliculas');
    expect(screen.getByRole('heading', { level: 2, name: 'Todavía no hay nada visible en el catálogo' })).toBeInTheDocument();
    expect(screen.getByText(/las series, cuando tienen al menos un episodio/)).toBeInTheDocument();
    // «Vacío» sería falso si tiene series ocultas; y el aviso pequeño de la fila no se suma al grande.
    expect(screen.queryByRole('heading', { name: 'El catálogo está vacío' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
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

describe('HomePage: fila «Series»', () => {
  const latest = [
    makeSeries({ id: 10, title: 'Serie nueva', endYear: null, seasonCount: 1 }),
    makeSeries({ id: 11, title: 'Serie antigua' }),
  ];

  it('con series, aparece justo después de «Novedades», con tarjetas-enlace a cada serie y «Ver todas»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => jsonResponse(makePage(latest)),
    });
    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Series',
      'Terror',
    ]);
    const cards = within(row).getAllByRole('link', { name: /^Serie/ });
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/series/10', '/series/11']);
    expect(cards[0]).toHaveAccessibleName('Serie nueva 2019– · 1 temporada');
    expect(within(row).getByRole('link', { name: /^Ver todas\s*las series$/ })).toHaveAttribute('href', '/series');
  });

  it('sin series, a un usuario no le aparece (ni un hueco ni un aviso)', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });
    renderHome();

    await screen.findByRole('region', { name: 'Novedades' });
    // Espera a que termine la carga de la fila (su esqueleto anuncia «Cargando series...») y a que se sepa el rol.
    await waitFor(() => expect(screen.queryByText('Cargando series...')).not.toBeInTheDocument());
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Series' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
  });

  it('sin series visibles, a un administrador le deja un aviso de una línea con el motivo y «Gestionar series»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(row).toHaveTextContent(
      'Todavía no hay series visibles, así que los usuarios no ven esta fila. Una serie aparece en cuanto tiene al menos un episodio.',
    );
    expect(within(row).getByRole('link', { name: 'Gestionar series' })).toHaveAttribute('href', '/admin/series');
    // En su sitio de siempre (tras «Novedades») y sin tapar el resto de la portada.
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Series',
      'Terror',
    ]);
  });

  it('si falla, la fila lo dice con «Reintentar» sin tapar el resto de la portada, y reintentar la recupera', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = renderHome();

    const row = await screen.findByRole('region', { name: 'Series' });
    expect(within(row).getByRole('alert')).toHaveTextContent(/No se pudieron cargar las series\./);
    expect(screen.getByRole('region', { name: 'Novedades' })).toBeInTheDocument();

    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => jsonResponse(makePage(latest)),
    });
    await user.click(within(row).getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('link', { name: /^Serie nueva/ })).toBeInTheDocument();
  });

  it('mientras carga, la fila reserva su hueco con un esqueleto (las filas de debajo no saltan al llegar)', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
      [SERIES_ROW]: () => new Promise<Response>(() => {}),
    });
    renderHome();

    await screen.findByRole('region', { name: 'Novedades' });
    expect(screen.getByText('Cargando series...')).toBeInTheDocument();
  });
});
