/**
 * Tests de las filas «… recomendadas para ti» de la portada (`RecommendedRows`)
 * con el hook real `useRecommendations` y `fetch` simulado.
 *
 * Protegen: que sin nada en qué basarlas (`basis: 'NONE'`) no se pinta ninguna
 * fila (ni un título sin tarjetas) y, si la portada lo pide, solo la tarjeta que
 * invita a elegir géneros; que el subtítulo de las filas dice la verdad según
 * `basis` (nombrando los géneros favoritos cuando se conocen, y sin pedirlos si
 * no hacen falta); que con datos salen dos filas de un solo tipo y cada
 * una solo si tiene títulos, que mientras cargan sin motivo para esperar filas
 * no se pinta ni se anuncia nada (y que tras «Reintentar» sí se anuncia), y que
 * un fallo deja un aviso compacto con «Reintentar» en lugar de romper la
 * portada. La reserva del hueco cuando la caché sabe que hay «Me gusta» se
 * prueba en `HomePage.test.tsx`, donde las filas se montan con la sesión ya
 * resuelta, como en la aplicación.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RECOMMENDATIONS_LIMIT } from '../hooks/useRecommendations';
import { queryKeys } from '../lib/queryKeys';
import {
  FAVORITE_GENRES,
  GENRE_OPTIONS,
  RECOMMENDATIONS,
  errorResponse,
  jsonResponse,
  makeMovie,
  makeRecommendations,
  makeSeries,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { GENRES_INVITE_TITLE } from './GenresInviteCard';
import { RECOMMENDED_MOVIES_TITLE, RECOMMENDED_SERIES_TITLE, RecommendedRows } from './RecommendedRows';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const blade = makeMovie({ id: 3, title: 'Blade Runner 2049', releaseYear: 2017 });
const origen = makeMovie({ id: 4, title: 'Origen', releaseYear: 2010 });
const dark = makeSeries({ id: 5, title: 'Dark', releaseYear: 2017, endYear: 2020, seasonCount: 3 });

/** Caché del test en curso (para saber cuándo han respondido las recomendaciones). */
let queryClient = createTestQueryClient();

/** Monta las filas con una caché nueva; `inviteToChooseGenres` como en la portada con películas. */
function renderRows({ inviteToChooseGenres = false }: { inviteToChooseGenres?: boolean } = {}) {
  const user = userEvent.setup();
  queryClient = createTestQueryClient();
  renderWithProviders(<RecommendedRows inviteToChooseGenres={inviteToChooseGenres} />, { session: true, queryClient });
  return user;
}

/** ¿Se han pedido los géneros elegibles (para decidir si se pinta la tarjeta)? */
function genreOptionsRequested(): boolean {
  return fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/genres/options');
}

/** ¿Se ha pedido la lista de géneros favoritos? */
function favoriteGenresRequested(): boolean {
  return fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/genres');
}

const accion = { id: 1, name: 'Acción' };
const comedia = { id: 2, name: 'Comedia' };
const drama = { id: 3, name: 'Drama' };

/**
 * Espera a que las recomendaciones hayan respondido con éxito. Ya no vale
 * esperar a que se vaya un esqueleto (sin «Me gusta» no lo hay): se mira la
 * caché, así una afirmación de «no se pinta nada» no puede pasar por haberse
 * hecho antes de que llegue la respuesta.
 */
async function waitForLoaded() {
  await vi.waitFor(() =>
    expect(queryClient.getQueryState(queryKeys.recommendations.list(RECOMMENDATIONS_LIMIT))?.status).toBe('success'),
  );
}

/** Textos de las regiones `role="status"` (la zona de avisos tiene siempre una, vacía). */
function statusTexts(): string[] {
  return screen
    .queryAllByRole('status')
    .map((status) => status.textContent ?? '')
    .filter((text) => text !== '');
}

describe('RecommendedRows', () => {
  it('mientras carga, sin saber si al usuario le gusta algo, no pinta ni anuncia nada', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => new Promise<Response>(() => {}) });
    renderRows();

    // La petición ya ha salido (no es que aún no haya empezado la carga).
    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/recommendations?limit=12')).toBe(true),
    );
    expect(screen.queryByText('Cargando recomendaciones...')).not.toBeInTheDocument();
    expect(statusTexts()).toEqual([]);
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('sin nada en qué basarlas (basis: NONE) y sin permiso para invitar, no pinta nada: ni filas ni títulos vacíos', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()) });
    renderRows();
    await waitForLoaded();

    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/recommendations?limit=12')).toBe(true);
    expect(favoriteGenresRequested()).toBe(false);
    expect(genreOptionsRequested()).toBe(false);
  });

  it('con basis NONE y la portada con películas, solo la tarjeta «Dinos qué géneros te gustan…», que lleva a «Tus gustos»', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()) });
    renderRows({ inviteToChooseGenres: true });

    const invite = await screen.findByRole('region', { name: GENRES_INVITE_TITLE });
    expect(invite).toHaveTextContent(
      'Elige entre 3 y 5 en tu perfil. También aprendemos de los títulos que marcas con «Me gusta».',
    );
    expect(within(invite).getByRole('link', { name: 'Elegir mis géneros' })).toHaveAttribute('href', '/perfil#tus-gustos');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([GENRES_INVITE_TITLE]);
    expect(favoriteGenresRequested()).toBe(false);
  });

  /**
   * Revisión de QA: con menos de 3 géneros elegibles, «Tus gustos» no tiene
   * selector (el mínimo es imposible), así que la tarjeta llevaría a un
   * callejón sin salida: no se pinta.
   */
  it.each([
    ['ninguno', []],
    ['dos', [accion, comedia]],
  ])('con basis NONE y %s géneros elegibles, no invita a elegir (no se podría)', async (_label, options) => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()),
      [GENRE_OPTIONS]: () => jsonResponse(options),
    });
    renderRows({ inviteToChooseGenres: true });

    await vi.waitFor(() => expect(genreOptionsRequested()).toBe(true));
    await vi.waitFor(() => expect(queryClient.getQueryState(queryKeys.favoriteGenres.options)?.status).toBe('success'));
    expect(screen.queryByRole('region', { name: GENRES_INVITE_TITLE })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('si no se pueden cargar los géneros elegibles, tampoco invita (podría ser falso) y la portada sigue entera', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()),
      [GENRE_OPTIONS]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    renderRows({ inviteToChooseGenres: true });

    await vi.waitFor(() => expect(queryClient.getQueryState(queryKeys.favoriteGenres.options)?.status).toBe('error'));
    expect(screen.queryByRole('region', { name: GENRES_INVITE_TITLE })).not.toBeInTheDocument();
    // Ni la tarjeta ni un aviso de error: el fallo de esta petición auxiliar no estropea la portada.
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('con recomendaciones (basis distinto de NONE) no pide los géneros elegibles', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'LIKES', movies: [blade] })),
    });
    renderRows({ inviteToChooseGenres: true });

    await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE });
    expect(genreOptionsRequested()).toBe(false);
  });

  it('con géneros favoritos (GENRES) las filas dicen cuáles: «Porque te gustan Acción, Comedia y Drama.»', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'GENRES', movies: [blade], series: [dark] })),
      [FAVORITE_GENRES]: () => jsonResponse([accion, comedia, drama]),
    });
    renderRows({ inviteToChooseGenres: true });

    const movies = await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE });
    await vi.waitFor(() => expect(movies).toHaveAccessibleDescription('Porque te gustan Acción, Comedia y Drama.'));
    expect(screen.getByRole('region', { name: RECOMMENDED_SERIES_TITLE })).toHaveAccessibleDescription(
      'Porque te gustan Acción, Comedia y Drama.',
    );
    expect(screen.queryByRole('region', { name: GENRES_INVITE_TITLE })).not.toBeInTheDocument();
  });

  it('con GENRES, mientras llegan los nombres (o si fallan), una frase igual de cierta que no los inventa', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'GENRES', movies: [blade] })),
      [FAVORITE_GENRES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    renderRows();

    const movies = await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE });
    expect(movies).toHaveAccessibleDescription('Según los géneros que has elegido como favoritos.');
    await vi.waitFor(() => expect(favoriteGenresRequested()).toBe(true));
    expect(movies).toHaveAccessibleDescription('Según los géneros que has elegido como favoritos.');
  });

  it('con solo «Me gusta» (LIKES) lo dice, sin pedir los géneros favoritos', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'LIKES', movies: [blade] })),
    });
    renderRows();

    expect(await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE })).toHaveAccessibleDescription(
      'Según los títulos que has marcado con «Me gusta».',
    );
    expect(favoriteGenresRequested()).toBe(false);
  });

  it('con géneros y «Me gusta» (GENRES_AND_LIKES) combina las dos razones', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'GENRES_AND_LIKES', series: [dark] })),
      [FAVORITE_GENRES]: () => jsonResponse([accion, drama, comedia]),
    });
    renderRows();

    const series = await screen.findByRole('region', { name: RECOMMENDED_SERIES_TITLE });
    await vi.waitFor(() =>
      expect(series).toHaveAccessibleDescription(
        'Porque te gustan Acción, Drama y Comedia y según los títulos que has marcado con «Me gusta».',
      ),
    );
  });

  it('con «Me gusta» pero nada que recomendar tampoco pinta nada', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'LIKES' })) });
    renderRows();
    await waitForLoaded();

    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('con datos, una fila de películas y otra de series, en el orden del servidor y enlazadas a su página', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () =>
        jsonResponse(makeRecommendations({ basis: 'LIKES', movies: [blade, origen], series: [dark] })),
    });
    renderRows();

    const movies = await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE });
    expect(within(movies).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/peliculas/3',
      '/peliculas/4',
    ]);
    const series = screen.getByRole('region', { name: RECOMMENDED_SERIES_TITLE });
    expect(within(series).getByRole('link', { name: 'Dark 2017–2020 · 3 temporadas' })).toHaveAttribute('href', '/series/5');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual([
      RECOMMENDED_MOVIES_TITLE,
      RECOMMENDED_SERIES_TITLE,
    ]);
  });

  it('cada fila sale solo si tiene títulos (aquí, solo series)', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'LIKES', series: [dark] })),
    });
    renderRows();

    expect(await screen.findByRole('region', { name: RECOMMENDED_SERIES_TITLE })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: RECOMMENDED_MOVIES_TITLE })).not.toBeInTheDocument();
  });

  it('si falla, un aviso compacto con «Reintentar» que solo vuelve a pedir las recomendaciones', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderRows();

    // La zona de avisos tiene siempre un `role="alert"` vacío: se llega al aviso por su título.
    const heading = await screen.findByRole('heading', { name: 'No se pudieron cargar tus recomendaciones' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso de error debe ser role="alert"');
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');

    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basis: 'LIKES', movies: [blade] })),
    });
    await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('region', { name: RECOMMENDED_MOVIES_TITLE })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar tus recomendaciones' })).not.toBeInTheDocument();
  });

  it('tras «Reintentar», mientras se repite la petición, el hueco se reserva y se anuncia la carga', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = renderRows();
    await screen.findByRole('heading', { name: 'No se pudieron cargar tus recomendaciones' });

    // El usuario ha pedido algo: debe ver y oír que está en marcha (y el foco tiene dónde quedarse).
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => new Promise<Response>(() => {}) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByText('Cargando recomendaciones...')).toBeInTheDocument();
    expect(statusTexts()).toEqual(['Cargando recomendaciones...']);
  });
});
