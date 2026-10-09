/**
 * Tests de `MoviesPage` (`/peliculas`) con los hooks reales y `fetch` simulado.
 *
 * Protegen:
 * - **Sin filtros**, la misma estructura que `/series`: esqueleto, único h1
 *   «Películas», banner con la más reciente (sin repetirla en las filas), filas
 *   por género, «Cargar más películas», vacío y error con «Reintentar».
 * - **Filtros en la URL**: se leen al entrar, se escriben al cambiar (tras la
 *   espera, no en cada cambio), «Atrás» vuelve al anterior y los valores
 *   inválidos se ignoran sin romper la página.
 * - **Con filtros**, la cuadrícula: la petición a `/movies/search` con los
 *   parámetros correctos, el recuento en `role="status"`, «Cargar más» sin
 *   duplicados, el vacío con «Quitar filtros», el error y la cancelación de la
 *   petición anterior al cambiar de filtro.
 * - Que una tarjeta abra el diálogo de detalles en las dos vistas.
 *
 * La espera de los filtros va con el reloj falso (`test/fakeTimers.ts`): el
 * tiempo solo avanza cuando el test lo pide, nunca con el reloj real.
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { MOVIE_FILTER_DEBOUNCE_MS } from '../hooks/useMovieFilters';
import { installManualTimers, passTime } from '../test/fakeTimers';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';
import { MoviesPage } from './MoviesPage';

const fetchMock = vi.fn<typeof fetch>();

/** Catálogo sin filtros (lo mismo que pide la portada). */
const CATALOG_0 = 'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc';
const CATALOG_1 = 'GET /api/movies?page=1&size=20&sort=createdAt&direction=desc';

/**
 * Clave de `routeFetch` de una búsqueda con filtros. Los parámetros van en el
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
  return `GET /api/movies/search?page=${page}&size=20&sort=${sort}&direction=${direction}${genre}${year}`;
}

const drama = { id: 4, name: 'Drama' };
const terror = { id: 7, name: 'Terror' };

/** Lo que pide cualquier pantalla con la lista de favoritos y la barra de filtros. */
const BASE = {
  'GET /api/users/me/favorites': () => jsonResponse([]),
  'GET /api/genres': () => jsonResponse([terror, drama]),
};

// Orden del servidor: más reciente primero. La 1 será el banner.
const hero = makeMovie({ id: 1, title: 'Estreno estrella', genres: [drama] });
const others = [
  makeMovie({ id: 2, title: 'Segunda', genres: [drama] }),
  makeMovie({ id: 3, title: 'Tercera', genres: [drama] }),
  makeMovie({ id: 4, title: 'Cuarta', genres: [drama] }),
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Muestra la parte `?…` de la URL: así se comprueba lo que la página escribe en ella. */
function SearchProbe() {
  const location = useLocation();
  return <p>{`busqueda:${location.search}`}</p>;
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

function renderPage(route = '/peliculas', user = userEvent.setup()) {
  renderWithProviders(
    <FavoritesProvider>
      <MoviesPage />
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

const genreSelect = () => screen.getByRole('combobox', { name: 'Género' });
const sortSelect = () => screen.getByRole('combobox', { name: 'Ordenar por' });
const yearInput = () => screen.getByRole('textbox', { name: 'Año' });
const results = () => screen.getByRole('region', { name: 'Resultados' });

/**
 * Espera a que el recuento de la cuadrícula diga exactamente `text` («2 películas»)
 * en su región `role="status"` (la que se anuncia sin mover el foco).
 */
async function expectCount(text: string) {
  expect(await within(results()).findByText(text, { selector: '[role="status"]' })).toBeInTheDocument();
}

/** Espera a que el desplegable de géneros tenga ya los de `GET /api/genres`. */
async function genresLoaded() {
  await within(genreSelect()).findByRole('option', { name: 'Drama' });
}

describe('MoviesPage sin filtros: estados', () => {
  it('mientras carga muestra el esqueleto, su único h1 «Películas» y la barra de filtros', () => {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => new Promise<Response>(() => {}) });

    renderPage();

    expect(screen.getByText('Cargando películas...')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Películas']);
    expect(document.title).toBe('Películas — StreamBox');
    const form = screen.getByRole('form', { name: 'Filtrar películas' });
    expect(within(form).getByRole('combobox', { name: 'Género' })).toHaveValue('');
    expect(within(form).getByRole('textbox', { name: 'Año' })).toHaveValue('');
    expect(within(form).getByRole('combobox', { name: 'Ordenar por' })).toHaveValue('recientes');
    // Sin filtros no hay nada que quitar.
    expect(within(form).queryByRole('button', { name: /Quitar filtros/ })).not.toBeInTheDocument();
  });

  it('sin películas, a un usuario: «Todavía no hay películas» e «Ir al inicio»; ni series ni panel', async () => {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([])) });

    renderPage();

    // Con el rol USER ya confirmado, no solo mientras carga.
    expect(await screen.findByText('estado:ready')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: 'Todavía no hay películas' })).toBeInTheDocument();
    expect(screen.getByText('Cuando se publiquen películas aparecerán aquí.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    // No manda a /series (podría estar vacía también) ni enseña el panel a un usuario.
    expect(screen.queryByRole('link', { name: /series/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Gestionar películas' })).not.toBeInTheDocument();
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
  });

  it('sin películas, a un administrador: le dice que las añada y lleva a «Gestionar películas»', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
      [CATALOG_0]: () => jsonResponse(makePage([])),
    });

    renderPage();

    expect(await screen.findByRole('link', { name: 'Gestionar películas' })).toHaveAttribute('href', '/admin/peliculas');
    expect(screen.getByRole('heading', { level: 2, name: 'Todavía no hay películas' })).toBeInTheDocument();
    expect(
      screen.getByText('Añádelas desde el panel de administración y aparecerán aquí al momento.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ir al inicio' })).not.toBeInTheDocument();
  });

  it('error: muestra el mensaje y «Reintentar» recupera el catálogo', async () => {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderPage();

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las películas' })).toBeInTheDocument();
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();

    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Estreno estrella' })).toBeInTheDocument();
  });
});

describe('MoviesPage sin filtros: banner y filas', () => {
  beforeEach(() => {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])) });
  });

  it('la más reciente es el banner («Estreno reciente», «Mi lista», «Más información»)', async () => {
    renderPage();

    const banner = await screen.findByRole('region', { name: 'Estreno estrella' });
    expect(within(banner).getByText('Estreno reciente')).toBeInTheDocument();
    expect(within(banner).getByRole('button', { name: /^Mi lista\s*—\s*Estreno estrella$/ })).toBeInTheDocument();
    expect(within(banner).getByRole('button', { name: /^Más información/ })).toBeInTheDocument();
  });

  it('jerarquía: un h1 y, debajo, banner, «Novedades» y una fila por género con ≥3 títulos (sin repetir el banner)', async () => {
    renderPage();

    const news = await screen.findByRole('region', { name: 'Novedades' });
    expect(within(news).queryByText('Estreno estrella')).not.toBeInTheDocument();
    expect(within(news).getAllByRole('button', { name: /\d{4} ·/ })).toHaveLength(3);
    expect(within(screen.getByRole('region', { name: 'Drama' })).getAllByRole('button')).toHaveLength(3 + 2); // 3 tarjetas + 2 flechas
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      'Estreno estrella',
      'Novedades',
      'Drama',
    ]);
    // Sin filtros no hay cuadrícula de resultados.
    expect(screen.queryByRole('region', { name: 'Resultados' })).not.toBeInTheDocument();
  });

  it('pulsar una tarjeta abre el diálogo de detalles de esa película', async () => {
    const user = renderPage();

    const news = await screen.findByRole('region', { name: 'Novedades' });
    await user.click(within(news).getByRole('button', { name: /^Tercera/ }));

    const dialog = await screen.findByRole('dialog', { name: 'Tercera' });
    expect(within(dialog).getByText('Sinopsis de la película 3.')).toBeInTheDocument();
  });

  it('indica cuántas se muestran del total y sin más páginas no ofrece «Cargar más»', async () => {
    renderPage();

    expect(await screen.findByText('Mostrando 4 de 4 películas')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });
});

describe('MoviesPage sin filtros: cargar más', () => {
  it('añade la página siguiente sin duplicados y retira el botón al llegar al final', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [CATALOG_0]: () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true })),
      [CATALOG_1]: () =>
        jsonResponse(makePage([others[2], makeMovie({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5 })),
    });
    const user = renderPage();

    await user.click(await screen.findByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByText('Mostrando 5 de 5 películas')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Quinta/ }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });
});

describe('MoviesPage: filtros leídos de la URL', () => {
  it('con ?genero=4&anio=2014&orden=titulo-asc los controles los reflejan y se pide /movies/search', async () => {
    const found = [makeMovie({ id: 8, title: 'Ocho', genres: [drama] }), makeMovie({ id: 9, title: 'Nueve' })];
    routeFetch(fetchMock, {
      ...BASE,
      [searchKey({ sort: 'title', direction: 'asc', genreId: 4, releaseYear: 2014 })]: () => jsonResponse(makePage(found)),
    });

    renderPage('/peliculas?genero=4&anio=2014&orden=titulo-asc');

    await expectCount('2 películas');
    await genresLoaded();
    expect(genreSelect()).toHaveValue('4');
    expect(yearInput()).toHaveValue('2014');
    expect(sortSelect()).toHaveValue('titulo-asc');
    // Cuadrícula, no banner ni filas.
    expect(within(results()).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('region', { name: 'Novedades' })).not.toBeInTheDocument();
    expect(screen.queryByText('Estreno reciente')).not.toBeInTheDocument();
    expect(callsWith('/api/movies?')).toHaveLength(0);
  });

  it('valores inválidos (?genero=abc&anio=99&orden=xyz) se ignoran: catálogo normal, sin errores', async () => {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])) });

    renderPage('/peliculas?genero=abc&anio=99&orden=xyz');

    expect(await screen.findByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
    expect(yearInput()).toHaveValue('');
    expect(sortSelect()).toHaveValue('recientes');
    expect(callsWith('/movies/search')).toHaveLength(0);
    // Ni error de carga ni error en el campo del año: la URL rara no es culpa de quien la abre.
    expect(screen.queryByText(/No se pudieron/)).not.toBeInTheDocument();
    expect(yearInput()).not.toHaveAttribute('aria-invalid');
  });

  it('un género que no existe (?genero=999) se ignora en cuanto se conocen los géneros', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [searchKey({ genreId: 999 })]: () => jsonResponse(makePage([])),
      [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])),
    });

    renderPage('/peliculas?genero=999');

    expect(await screen.findByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
    expect(screen.queryByRole('region', { name: 'Resultados' })).not.toBeInTheDocument();
  });

  it('si fallan los géneros se explica junto a la barra y se puede reintentar; el resto sigue funcionando', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      'GET /api/genres': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
      [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    const user = renderPage();

    expect(await screen.findByText('No se pudieron cargar los géneros.')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();

    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])) });
    await user.click(screen.getByRole('button', { name: /^Reintentar\s*la carga de géneros$/ }));

    await genresLoaded();
    expect(screen.queryByText('No se pudieron cargar los géneros.')).not.toBeInTheDocument();
  });
});

describe('MoviesPage: cambiar filtros', () => {
  // La espera de los filtros va con reloj falso (ver la cabecera del archivo).
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  const setupUser = () => userEvent.setup({ delay: null });

  /** Monta la página sin filtros y espera a que estén el catálogo y los géneros. */
  async function renderReady(extraRoutes: Parameters<typeof routeFetch>[1] = {}) {
    routeFetch(fetchMock, { ...BASE, [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])), ...extraRoutes });
    const user = renderPage('/peliculas', setupUser());
    await screen.findByRole('region', { name: 'Estreno estrella' });
    await genresLoaded();
    return user;
  }

  it('elegir un género lo aplica tras la espera: URL ?genero=4, petición con genreId y recuento anunciado', async () => {
    const user = await renderReady({
      [searchKey({ genreId: 4 })]: () => jsonResponse(makePage([hero, others[0]], { totalElements: 2 })),
    });

    await user.selectOptions(genreSelect(), 'Drama');

    // Antes de la espera no pasa nada: ni URL ni petición.
    passTime(MOVIE_FILTER_DEBOUNCE_MS - 1);
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
    expect(callsWith('/movies/search')).toHaveLength(0);

    passTime(1);
    expect(await screen.findByText('busqueda:?genero=4')).toBeInTheDocument();
    await expectCount('2 películas');
    expect(callsWith('/movies/search')).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Quitar filtros/ })).toBeInTheDocument();
  });

  it('recorrer los órdenes con el teclado solo aplica el último (una petición y una entrada de historial)', async () => {
    const user = await renderReady({
      [searchKey({ sort: 'releaseYear', direction: 'asc' })]: () => jsonResponse(makePage([others[1]])),
    });

    await user.selectOptions(sortSelect(), 'Título A–Z');
    await user.selectOptions(sortSelect(), 'Año: más nuevas');
    await user.selectOptions(sortSelect(), 'Año: más antiguas');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);

    expect(await screen.findByText('busqueda:?orden=anio-asc')).toBeInTheDocument();
    await expectCount('1 película');
    expect(callsWith('/movies/search')).toHaveLength(1);

    // Una sola entrada nueva: «Atrás» vuelve directamente al catálogo sin filtros.
    await user.click(screen.getByRole('button', { name: 'Atrás del navegador' }));
    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(sortSelect()).toHaveValue('recientes');
  });

  it('«Atrás» vuelve al filtro anterior y los controles se ponen al día', async () => {
    const user = await renderReady({
      [searchKey({ genreId: 4 })]: () => jsonResponse(makePage([hero])),
      [searchKey({ genreId: 7 })]: () => jsonResponse(makePage([others[0], others[1]])),
    });

    await user.selectOptions(genreSelect(), 'Drama');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    expect(await screen.findByText('busqueda:?genero=4')).toBeInTheDocument();
    await user.selectOptions(genreSelect(), 'Terror');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    expect(await screen.findByText('busqueda:?genero=7')).toBeInTheDocument();
    await expectCount('2 películas');

    await user.click(screen.getByRole('button', { name: 'Atrás del navegador' }));

    expect(await screen.findByText('busqueda:?genero=4')).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('4');
    await expectCount('1 película');
  });

  it('«Atrás» a un filtro ya visto lo pinta desde la caché: sin esqueleto y sin repetir la petición', async () => {
    const user = await renderReady({
      [searchKey({ genreId: 4 })]: () => jsonResponse(makePage([hero])),
      [searchKey({ genreId: 7 })]: () => jsonResponse(makePage([others[0], others[1]])),
    });
    await user.selectOptions(genreSelect(), 'Drama');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    await expectCount('1 película');
    await user.selectOptions(genreSelect(), 'Terror');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    await expectCount('2 películas');

    await user.click(screen.getByRole('button', { name: 'Atrás del navegador' }));

    // Cada combinación de filtros tiene su entrada en la caché (la clave lleva los filtros de la URL).
    expect(await screen.findByText('busqueda:?genero=4')).toBeInTheDocument();
    expect(screen.queryByText('Buscando películas...')).not.toBeInTheDocument();
    await expectCount('1 película');
    expect(callsWith('genreId=4')).toHaveLength(1);

    // Y quitar los filtros vuelve al catálogo que ya estaba cargado.
    await user.click(screen.getByRole('button', { name: 'Atrás del navegador' }));
    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === CATALOG_0.slice(4))).toHaveLength(1);
  });

  it('el año se aplica al escribir uno válido; uno fuera de rango marca el error y no cambia la URL', async () => {
    const user = await renderReady({
      [searchKey({ releaseYear: 2014 })]: () => jsonResponse(makePage([others[2]])),
    });

    await user.type(yearInput(), '1500');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);

    expect(yearInput()).toHaveAttribute('aria-invalid', 'true');
    expect(yearInput()).toHaveAccessibleDescription('Escribe un año entre 1888 y 2100.');
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
    expect(callsWith('/movies/search')).toHaveLength(0);

    await user.clear(yearInput());
    await user.type(yearInput(), '2014');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);

    expect(await screen.findByText('busqueda:?anio=2014')).toBeInTheDocument();
    expect(yearInput()).not.toHaveAttribute('aria-invalid');
    await expectCount('1 película');
  });

  it('mientras se escribe el año no se marca error («20»); al salir del campo, sí', async () => {
    const user = await renderReady();

    await user.type(yearInput(), '20');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    expect(yearInput()).not.toHaveAttribute('aria-invalid');

    await user.tab();
    expect(yearInput()).toHaveAttribute('aria-invalid', 'true');
    expect(callsWith('/movies/search')).toHaveLength(0);
  });

  it('Intro en el campo del año aplica sin esperar', async () => {
    const user = await renderReady({
      [searchKey({ releaseYear: 1999 })]: () => jsonResponse(makePage([others[0]])),
    });

    await user.type(yearInput(), '1999{Enter}');

    expect(await screen.findByText('busqueda:?anio=1999')).toBeInTheDocument();
    expect(callsWith('/movies/search')).toHaveLength(1);
  });

  it('cambiar de filtro cancela la petición anterior, y su respuesta tardía no se pinta', async () => {
    let releaseDrama!: (response: Response) => void;
    const user = await renderReady({
      [searchKey({ genreId: 4 })]: () => new Promise<Response>((resolve) => (releaseDrama = resolve)),
      [searchKey({ genreId: 7 })]: () => jsonResponse(makePage([makeMovie({ id: 70, title: 'Solo terror' })])),
    });

    await user.selectOptions(genreSelect(), 'Drama');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);
    await waitFor(() => expect(callsWith('genreId=4')).toHaveLength(1));
    const dramaSignal = callsWith('genreId=4')[0][1]?.signal as AbortSignal;
    expect(screen.getByText('Buscando películas...')).toBeInTheDocument();

    await user.selectOptions(genreSelect(), 'Terror');
    passTime(MOVIE_FILTER_DEBOUNCE_MS);

    expect(await within(results()).findByRole('button', { name: /^Solo terror/ })).toBeInTheDocument();
    expect(dramaSignal.aborted).toBe(true);

    await act(async () => releaseDrama(jsonResponse(makePage([makeMovie({ id: 40, title: 'Solo drama' })]))));
    expect(screen.queryByRole('button', { name: /^Solo drama/ })).not.toBeInTheDocument();
    await expectCount('1 película');
  });
});

describe('MoviesPage: cuadrícula de resultados', () => {
  const route = '/peliculas?genero=4';
  const page0 = searchKey({ genreId: 4 });
  const page1 = searchKey({ genreId: 4, page: 1 });

  it('«Cargar más películas» añade la página siguiente sin duplicados', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [page0]: () => jsonResponse(makePage([hero, ...others], { totalElements: 5, totalPages: 2, hasNext: true })),
      [page1]: () =>
        jsonResponse(makePage([others[2], makeMovie({ id: 5, title: 'Quinta' })], { page: 1, totalElements: 5 })),
    });
    const user = renderPage(route);

    await expectCount('5 películas');
    expect(screen.getByText('Mostrando 4 de 5 películas')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cargar más películas' }));

    expect(await screen.findByText('Mostrando 5 de 5 películas')).toBeInTheDocument();
    const cards = within(within(results()).getAllByRole('list')[0]).getAllByRole('listitem');
    expect(cards.map((card) => card.textContent)).toEqual([
      expect.stringMatching(/^Estreno estrella/),
      expect.stringMatching(/^Segunda/),
      expect.stringMatching(/^Tercera/),
      expect.stringMatching(/^Cuarta/),
      expect.stringMatching(/^Quinta/),
    ]);
    expect(screen.queryByRole('button', { name: /Cargar más/ })).not.toBeInTheDocument();
  });

  it('pulsar una tarjeta de la cuadrícula abre el diálogo de detalles', async () => {
    routeFetch(fetchMock, { ...BASE, [page0]: () => jsonResponse(makePage([hero, others[0]])) });
    const user = renderPage(route);

    await user.click(await within(results()).findByRole('button', { name: /^Segunda/ }));

    expect(await screen.findByRole('dialog', { name: 'Segunda' })).toBeInTheDocument();
  });

  it('sin resultados: «No hay películas con estos filtros» y «Quitar filtros» vuelve al catálogo con el foco en «Género»', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [page0]: () => jsonResponse(makePage([])),
      [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    const user = renderPage(route);

    const empty = await screen.findByRole('heading', { level: 2, name: 'No hay películas con estos filtros' });
    await expectCount('0 películas');
    const emptyState = empty.closest('div')!.parentElement!;
    // Quitar los filtros enseña todas las películas (no "todo el catálogo": las series están en /series).
    expect(emptyState).toHaveTextContent(
      'Prueba con otro género u otro año, o quita los filtros para ver todas las películas.',
    );
    await user.click(within(emptyState).getByRole('button', { name: 'Quitar filtros' }));

    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Estreno estrella' })).toBeInTheDocument();
    expect(genreSelect()).toHaveValue('');
    expect(genreSelect()).toHaveFocus();
    expect(screen.queryByRole('button', { name: /Quitar filtros/ })).not.toBeInTheDocument();
  });

  it('«Quitar filtros» de la barra también los quita', async () => {
    routeFetch(fetchMock, {
      ...BASE,
      [searchKey({ sort: 'duration', direction: 'desc', releaseYear: 2001 })]: () => jsonResponse(makePage([hero])),
      [CATALOG_0]: () => jsonResponse(makePage([hero, ...others])),
    });
    const user = renderPage('/peliculas?anio=2001&orden=duracion-desc');

    await within(results()).findByRole('button', { name: /^Estreno estrella/ });
    const form = screen.getByRole('form', { name: 'Filtrar películas' });
    await user.click(within(form).getByRole('button', { name: 'Quitar filtros' }));

    expect(await screen.findByText('busqueda:')).toBeInTheDocument();
    expect(yearInput()).toHaveValue('');
    expect(sortSelect()).toHaveValue('recientes');
    expect(await screen.findByRole('region', { name: 'Novedades' })).toBeInTheDocument();
  });

  it('error: lo explica y «Reintentar» repite la búsqueda', async () => {
    routeFetch(fetchMock, { ...BASE, [page0]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderPage(route);

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las películas' })).toBeInTheDocument();

    routeFetch(fetchMock, { ...BASE, [page0]: () => jsonResponse(makePage([others[0]])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await within(results()).findByRole('button', { name: /^Segunda/ })).toBeInTheDocument();
    expect(callsWith('genreId=4')).toHaveLength(2);
  });
});
