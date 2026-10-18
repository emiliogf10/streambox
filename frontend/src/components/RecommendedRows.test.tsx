/**
 * Tests de las filas «… recomendadas para ti» de la portada (`RecommendedRows`)
 * con el hook real `useRecommendations` y `fetch` simulado.
 *
 * Protegen: que sin «Me gusta» (`basedOnLikes: false`) no se pinta nada (ni un
 * título sin tarjetas), que con datos salen dos filas de un solo tipo y cada
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

/** Monta las filas con una caché nueva. */
function renderRows() {
  const user = userEvent.setup();
  queryClient = createTestQueryClient();
  renderWithProviders(<RecommendedRows />, { session: true, queryClient });
  return user;
}

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

  it('sin ningún «Me gusta» (basedOnLikes: false) no pinta nada: ni filas ni títulos vacíos', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations()) });
    renderRows();
    await waitForLoaded();

    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/users/me/recommendations?limit=12')).toBe(true);
  });

  it('con «Me gusta» pero nada que recomendar tampoco pinta nada', async () => {
    routeFetch(fetchMock, { [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basedOnLikes: true })) });
    renderRows();
    await waitForLoaded();

    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('con datos, una fila de películas y otra de series, en el orden del servidor y enlazadas a su página', async () => {
    routeFetch(fetchMock, {
      [RECOMMENDATIONS]: () =>
        jsonResponse(makeRecommendations({ basedOnLikes: true, movies: [blade, origen], series: [dark] })),
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
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basedOnLikes: true, series: [dark] })),
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
      [RECOMMENDATIONS]: () => jsonResponse(makeRecommendations({ basedOnLikes: true, movies: [blade] })),
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
