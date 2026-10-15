/**
 * Tests de `SeriesPage` (`/series`) con el hook real y `fetch` simulado.
 *
 * Protegen: los tres estados (cargando con esqueleto, vacío —distinto para un
 * usuario y para un administrador, que puede tener series ocultas sin
 * episodios— y error con reintento), que la serie más reciente sea el banner y no se
 * repita debajo, las filas por género (la misma regla que las películas), que
 * las tarjetas sean ENLACES a la página de cada serie y «Cargar más series».
 *
 * Y los **filtros** (los mismos que `/peliculas`, ver `MoviesPage.test.tsx`
 * para el detalle de la espera, «Atrás» y la cancelación, que comparten
 * `useCatalogFilters`): la barra «Filtrar series» sin órdenes por duración, la
 * URL leída y escrita con los mismos parámetros, la petición a
 * `/series/search`, la cuadrícula y su estado vacío según el rol.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { CATALOG_FILTER_DEBOUNCE_MS } from '../hooks/useCatalogFilters';
import { installManualTimers, passTime } from '../test/fakeTimers';
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
const drama = { id: 4, name: 'Drama' };
const terror = { id: 7, name: 'Terror' };

/** Lo que pide la página además del catálogo: «Mi lista» y los géneros de la barra de filtros. */
const FAVORITES = {
  'GET /api/users/me/favorites': () => jsonResponse([]),
  'GET /api/genres': () => jsonResponse([terror, drama]),
};

/**
 * Clave de `routeFetch` de una búsqueda con filtros, con los parámetros en el
 * orden en que los escribe `usePagedCatalog`: página, tamaño, orden, dirección, género, año.
 */
function searchKey({
  page = 0,
  sort = 'createdAt',
  direction = 'desc',
  genreId,
  releaseYear,
}: { page?: number; sort?: string; direction?: string; genreId?: number; releaseYear?: number }) {
  const genre = genreId === undefined ? '' : `&genreId=${genreId}`;
  const year = releaseYear === undefined ? '' : `&releaseYear=${releaseYear}`;
  return `GET /api/series/search?page=${page}&size=20&sort=${sort}&direction=${direction}${genre}${year}`;
}

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

/** Muestra la parte `?…` de la URL: así se comprueba lo que la página escribe en ella. */
function SearchProbe() {
  return <p>{`busqueda:${useLocation().search}`}</p>;
}

/** Hace lo mismo que el botón «Atrás» del navegador. */
function BackButton() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(-1)}>
      Atrás del navegador
    </button>
  );
}

function renderPage(route = '/series', user = userEvent.setup()) {
  renderWithProviders(
    <FavoritesProvider>
      <SeriesPage />
      <SearchProbe />
      <BackButton />
      <UserStatusProbe />
    </FavoritesProvider>,
    { session: true, route },
  );
  return user;
}

/** Peticiones de `fetch` cuya URL contiene `fragment`. */
function callsWith(fragment: string) {
  return fetchMock.mock.calls.filter(([url]) => String(url).includes(fragment));
}

const filterForm = () => screen.getByRole('form', { name: 'Filtrar series' });
const genreSelect = () => screen.getByRole('combobox', { name: 'Género' });
const sortSelect = () => screen.getByRole('combobox', { name: 'Ordenar por' });
const yearInput = () => screen.getByRole('textbox', { name: 'Año' });
const results = () => screen.getByRole('region', { name: 'Resultados' });

/** Espera a que el recuento de la cuadrícula diga exactamente `text` («2 series») en su `role="status"`. */
async function expectCount(text: string) {
  expect(await within(results()).findByText(text, { selector: '[role="status"]' })).toBeInTheDocument();
}

/** Espera a que el desplegable de géneros tenga ya los de `GET /api/genres`. */
async function genresLoaded() {
  await within(genreSelect()).findByRole('option', { name: 'Drama' });
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

describe('SeriesPage: barra de filtros', () => {
  it('«Filtrar series» con género, año y los cinco órdenes que admite el servidor (sin duración)', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => new Promise<Response>(() => {}) });

    renderPage();

    const form = filterForm();
    await genresLoaded();
    expect(within(genreSelect()).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Todos los géneros',
      'Drama',
      'Terror',
    ]);
    expect(within(form).getByRole('textbox', { name: 'Año' })).toHaveValue('');
    expect(within(sortSelect()).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Más recientes',
      'Título A–Z',
      'Título Z–A',
      'Año: más nuevas',
      'Año: más antiguas',
    ]);
    expect(sortSelect()).toHaveValue('recientes');
    // Ni la barra habla de películas, ni hay nada que quitar sin filtros.
    expect(screen.queryByRole('form', { name: 'Filtrar películas' })).not.toBeInTheDocument();
    expect(within(form).queryByRole('button', { name: /Quitar filtros/ })).not.toBeInTheDocument();
  });

  it('con ?genero=4&anio=2016&orden=titulo-asc los controles los reflejan y se pide /series/search', async () => {
    const found = [makeSeries({ id: 8, title: 'Ocho', genres: [drama] }), makeSeries({ id: 9, title: 'Nueve' })];
    routeFetch(fetchMock, {
      ...FAVORITES,
      [searchKey({ sort: 'title', direction: 'asc', genreId: 4, releaseYear: 2016 })]: () => jsonResponse(makePage(found)),
    });

    renderPage('/series?genero=4&anio=2016&orden=titulo-asc');

    await expectCount('2 series');
    await genresLoaded();
    expect(genreSelect()).toHaveValue('4');
    expect(yearInput()).toHaveValue('2016');
    expect(sortSelect()).toHaveValue('titulo-asc');
    // Cuadrícula de tarjetas que son enlaces a cada serie; ni banner ni filas.
    const cards = within(results()).getAllByRole('link');
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/series/8', '/series/9']);
    expect(screen.queryByRole('region', { name: 'Novedades' })).not.toBeInTheDocument();
    expect(screen.queryByText('Novedad')).not.toBeInTheDocument();
    expect(callsWith('/api/series?')).toHaveLength(0);
    expect(screen.getByText('Mostrando 2 de 2 series')).toBeInTheDocument();
  });

  it('un orden por duración en la URL (?orden=duracion-asc) se ignora: catálogo normal y nunca sort=duration', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])) });

    renderPage('/series?orden=duracion-asc');

    expect(await screen.findByRole('region', { name: 'Estreno serie' })).toBeInTheDocument();
    expect(sortSelect()).toHaveValue('recientes');
    expect(callsWith('/series/search')).toHaveLength(0);
    expect(callsWith('duration')).toHaveLength(0);
    expect(screen.queryByText(/No se pudieron/)).not.toBeInTheDocument();
  });

  it('un género que no existe (?genero=999) se ignora en cuanto se conocen los géneros', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [searchKey({ genreId: 999 })]: () => jsonResponse(makePage([])),
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
    });

    renderPage('/series?genero=999');

    expect(await screen.findByRole('region', { name: 'Estreno serie' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
    expect(screen.queryByRole('region', { name: 'Resultados' })).not.toBeInTheDocument();
  });
});

describe('SeriesPage: cambiar filtros', () => {
  // La espera de los filtros va con reloj falso (`test/fakeTimers.ts`).
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  /** Monta la página sin filtros y espera a que estén el catálogo y los géneros. */
  async function renderReady(extraRoutes: Parameters<typeof routeFetch>[1] = {}) {
    routeFetch(fetchMock, { ...FAVORITES, [PAGE_0]: () => jsonResponse(makePage([hero, ...others])), ...extraRoutes });
    const user = renderPage('/series', userEvent.setup({ delay: null }));
    await screen.findByRole('region', { name: 'Estreno serie' });
    await genresLoaded();
    return user;
  }

  it('elegir un género lo aplica tras la espera (URL ?genero=4 y genreId) y «Atrás» vuelve al catálogo', async () => {
    const user = await renderReady({
      [searchKey({ genreId: 4 })]: () => jsonResponse(makePage([hero, others[0]], { totalElements: 2 })),
    });

    await user.selectOptions(genreSelect(), 'Drama');
    passTime(CATALOG_FILTER_DEBOUNCE_MS - 1);
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
    expect(callsWith('/series/search')).toHaveLength(0);

    passTime(1);
    expect(await screen.findByText('busqueda:?genero=4')).toBeInTheDocument();
    await expectCount('2 series');
    expect(within(filterForm()).getByRole('button', { name: 'Quitar filtros' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Atrás del navegador' }));
    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Estreno serie' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
  });

  it('ordenar por año (más antiguas) pide sort=releaseYear&direction=asc', async () => {
    const user = await renderReady({
      [searchKey({ sort: 'releaseYear', direction: 'asc' })]: () => jsonResponse(makePage([others[2], others[1]])),
    });

    await user.selectOptions(sortSelect(), 'Año: más antiguas');
    passTime(CATALOG_FILTER_DEBOUNCE_MS);

    expect(await screen.findByText('busqueda:?orden=anio-asc')).toBeInTheDocument();
    await expectCount('2 series');
  });

  it('un año fuera de rango marca el error con el mismo mensaje que en películas y no busca; uno válido, sí', async () => {
    const user = await renderReady({
      [searchKey({ releaseYear: 2016 })]: () => jsonResponse(makePage([others[0]])),
    });

    await user.type(yearInput(), '1500');
    passTime(CATALOG_FILTER_DEBOUNCE_MS);
    expect(yearInput()).toHaveAttribute('aria-invalid', 'true');
    expect(yearInput()).toHaveAccessibleDescription('Escribe un año entre 1888 y 2100.');
    expect(callsWith('/series/search')).toHaveLength(0);

    await user.clear(yearInput());
    await user.type(yearInput(), '2016{Enter}');

    expect(await screen.findByText('busqueda:?anio=2016')).toBeInTheDocument();
    await expectCount('1 serie');
  });
});

describe('SeriesPage: cuadrícula de resultados', () => {
  const route = '/series?genero=4';
  const page0 = searchKey({ genreId: 4 });
  const page1 = searchKey({ genreId: 4, page: 1 });

  it('«Cargar más series» añade la página siguiente sin duplicados', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [page0]: () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true })),
      [page1]: () =>
        jsonResponse(makePage([others[2], makeSeries({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5 })),
    });
    const user = renderPage(route);

    await expectCount('5 series');
    await user.click(screen.getByRole('button', { name: 'Cargar más series' }));

    expect(await screen.findByText('Mostrando 5 de 5 series')).toBeInTheDocument();
    expect(within(results()).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual([
      '/series/1',
      '/series/2',
      '/series/3',
      '/series/4',
      '/series/5',
    ]);
  });

  it('sin resultados, a un usuario: «No hay series con estos filtros» y «Quitar filtros» vuelve al catálogo con el foco en «Género»', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [page0]: () => jsonResponse(makePage([])),
      [PAGE_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    const user = renderPage(route);

    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    const empty = await screen.findByRole('heading', { level: 2, name: 'No hay series con estos filtros' });
    await expectCount('0 series');
    const emptyState = empty.closest('div')!.parentElement!;
    expect(emptyState).toHaveTextContent('Prueba con otro género u otro año, o quita los filtros.');
    // A un usuario no se le habla de series ocultas ni del panel.
    expect(screen.queryByText(/episodios|visibles/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();

    await user.click(within(emptyState).getByRole('button', { name: 'Quitar filtros' }));

    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Estreno serie' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
    expect(genreSelect()).toHaveFocus();
  });

  it('sin resultados, a un administrador: «No hay series visibles con estos filtros» y por qué una serie sin episodios no sale', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [page0]: () => jsonResponse(makePage([])),
    });

    renderPage(route);

    expect(
      await screen.findByRole('heading', { level: 2, name: 'No hay series visibles con estos filtros' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Prueba con otro género u otro año, o quita los filtros. Las series sin episodios no aparecen aunque coincidan.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No hay series con estos filtros' })).not.toBeInTheDocument();
    // El del estado vacío y el de la barra.
    expect(screen.getAllByRole('button', { name: 'Quitar filtros' })).toHaveLength(2);
  });

  it('sin resultados, a un administrador: junto a «Quitar filtros», «Gestionar series» lleva al panel (donde están las ocultas)', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [page0]: () => jsonResponse(makePage([])),
    });

    renderPage(route);

    const empty = await screen.findByRole('heading', { level: 2, name: 'No hay series visibles con estos filtros' });
    const emptyState = empty.closest('div')!.parentElement!;
    const clear = within(emptyState).getByRole('button', { name: 'Quitar filtros' });
    const manage = within(emptyState).getByRole('link', { name: 'Gestionar series' });
    expect(manage).toHaveAttribute('href', '/admin/series');
    // «Quitar filtros» sigue siendo la principal: va antes (orden de lectura y de tabulación).
    expect(clear.compareDocumentPosition(manage) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getAllByRole('link', { name: 'Gestionar series' })).toHaveLength(1);
  });

  it('sin resultados y con el usuario aún cargando: el mensaje de usuario (falla cerrado)', async () => {
    routeFetch(fetchMock, {
      ...FAVORITES,
      [CURRENT_USER]: () => new Promise<Response>(() => {}),
      [page0]: () => jsonResponse(makePage([])),
    });

    renderPage(route);

    expect(await screen.findByRole('heading', { level: 2, name: 'No hay series con estos filtros' })).toBeInTheDocument();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();
    // Ni el enlace al panel: mientras no se confirma el rol, nada de administrador.
    expect(screen.queryByRole('link', { name: 'Gestionar series' })).not.toBeInTheDocument();
  });

  it('error: lo explica y «Reintentar» repite la búsqueda', async () => {
    routeFetch(fetchMock, { ...FAVORITES, [page0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderPage(route);

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las series' })).toBeInTheDocument();

    routeFetch(fetchMock, { ...FAVORITES, [page0]: () => jsonResponse(makePage([others[0]])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await within(results()).findByRole('link', { name: /^Segunda/ })).toBeInTheDocument();
    await waitFor(() => expect(callsWith('genreId=4')).toHaveLength(2));
  });
});
