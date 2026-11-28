/**
 * Tests de la fila «Series similares» (`SimilarTitlesRow` + `useSimilarTitles`
 * con `kind="series"`; la de películas, en `SimilarMoviesRow.test.tsx`) montada
 * en su sitio real, la página de una serie (`/series/:id`), con `fetch` simulado.
 *
 * Protegen el contrato con la API (`GET /api/series/{id}/similar?limit=12`, un
 * array ya ordenado que se pinta TAL CUAL) y los estados: cargando (silueta),
 * vacío (no se pinta nada, ni el título), error (aviso dentro de la fila con
 * «Reintentar», sin romper la página), 404 de la serie (no se piden) y la
 * espera a que el servidor confirme la serie. Y lo que pasa al pulsar una
 * similar: la página cambia de serie, el foco va al contenido, la fila nunca
 * enseña las similares de la anterior, y la clave cuelga de `series` (una
 * escritura del panel la vuelve a pedir; una serie que se queda sin episodios
 * desaparece de la fila).
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InfiniteData } from '@tanstack/react-query';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { keysAffectedBy, queryKeys } from '../lib/queryKeys';
import type { PageResponse, Series } from '../lib/types';
import { SeriesDetailPage } from '../pages/SeriesDetailPage';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeEpisode,
  makePage,
  makeSeries,
  makeSeriesDetail,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { RouteChangeFocus } from './RouteChangeFocus';
import { MAIN_CONTENT_ID } from './SkipLink';

const fetchMock = vi.fn<typeof fetch>();
const scrollTo = vi.fn();

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };
const AS_ADMIN = { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) };
const SIMILAR_URL = '/api/series/7/similar?limit=12';
const ROW = { name: 'Series similares' };

/** Una temporada de dos episodios sin vídeo: lo justo para la página (sin progreso que pedir). */
const oneSeason = [{ seasonNumber: 1, episodes: [1, 2].map((n) => makeEpisode({ episodeNumber: n, videoUrl: null })) }];
const series = makeSeriesDetail({ id: 7, title: 'Dark', genres: [{ id: 1, name: 'Ciencia ficción' }], seasons: oneSeason });
/** Similares en el orden del servidor (a propósito, NI por id NI por título: el cliente no debe reordenar). */
const similar = [
  makeSeries({ id: 12, title: 'Mindhunter', releaseYear: 2017, endYear: 2019, seasonCount: 2 }),
  makeSeries({ id: 3, title: 'Black Mirror', releaseYear: 2011, endYear: null, seasonCount: 6 }),
  makeSeries({ id: 9, title: '1899', releaseYear: 2022, endYear: 2022, seasonCount: 1 }),
];

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  scrollTo.mockReset();
  vi.stubGlobal('scrollTo', scrollTo);
});

/** Peticiones hechas a una URL concreta. */
function callsTo(url: string): number {
  return fetchMock.mock.calls.filter(([input]) => String(input) === url).length;
}

/** Detalle de una serie similar (para cuando se navega a ella). */
function detailOf(item: Series) {
  return makeSeriesDetail({ ...item, seasons: oneSeason });
}

/**
 * Monta la página de la serie 7 dentro de un `<main>` como el de `AppShell`
 * (con `RouteChangeFocus`, para ver adónde va el foco al cambiar de serie).
 */
function renderPage(routes: Parameters<typeof routeFetch>[1], queryClient = createTestQueryClient()) {
  routeFetch(fetchMock, { ...FAVORITES, 'GET /api/series/7': () => jsonResponse(series), ...routes });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <RouteChangeFocus />
      <main id={MAIN_CONTENT_ID} tabIndex={-1}>
        <Routes>
          <Route path="/series/:id" element={<SeriesDetailPage />} />
        </Routes>
      </main>
    </FavoritesProvider>,
    { session: true, route: '/series/7', queryClient },
  );
  return { user, queryClient };
}

describe('Series similares: con similares', () => {
  it.each([
    ['un usuario', {}],
    ['un administrador', AS_ADMIN],
  ])('para %s: pide la URL exacta con limit=12 y pinta las tarjetas de serie en el orden recibido, bajo los episodios y la ficha', async (_role, extra) => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar), ...extra });

    const row = await screen.findByRole('region', ROW);
    expect(within(row).getByRole('heading', { level: 2, name: 'Series similares' })).toBeInTheDocument();
    const cards = within(row).getAllByRole('link');
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/series/12', '/series/3', '/series/9']);
    // La misma tarjeta que las filas de la portada: título + años y temporadas.
    expect(cards[0]).toHaveAccessibleName('Mindhunter 2017–2019 · 2 temporadas');
    expect(callsTo(SIMILAR_URL)).toBe(1);

    // Jerarquía: el título, los episodios, la ficha y, al final, la fila (fuera de la rejilla de episodios y ficha).
    const headings = screen.getAllByRole('heading').map((heading) => `${heading.tagName}:${heading.textContent}`);
    expect(headings).toEqual(['H1:Dark', 'H2:Episodios', 'H3:1. Episodio 1', 'H3:2. Episodio 2', 'H2:Ficha', 'H2:Series similares']);
    expect(screen.getByRole('region', { name: 'Episodios' })).not.toContainElement(row);
    expect(screen.getByRole('region', { name: 'Ficha' }).parentElement).not.toContainElement(row);
  });

  it('mientras llegan: la silueta de una fila, anunciada como «Cargando series similares...»', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => new Promise<Response>(() => {}) });

    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(1));
    expect(screen.getByText('Cargando series similares...').closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Series similares' })).not.toBeInTheDocument();
  });
});

describe('Series similares: sin nada que enseñar', () => {
  it.each([
    ['un usuario', {}],
    ['un administrador', AS_ADMIN],
  ])('lista vacía, para %s: no se pinta nada (ni el título, ni la silueta) y su hueco queda vacío', async (_role, extra) => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse([]), ...extra });

    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(1));
    await waitFor(() => expect(screen.queryByText('Cargando series similares...')).not.toBeInTheDocument());
    expect(screen.queryByRole('region', ROW)).not.toBeInTheDocument();
    expect(screen.queryByText(/similares/i)).not.toBeInTheDocument();
    // El hueco de la fila (con su margen superior) no debe dejar espacio al pie: `empty:hidden`.
    const slot = document.body.querySelector('[class~="empty:hidden"]');
    expect(slot).not.toBeNull();
    expect(slot).toBeEmptyDOMElement();
  });

  it('si la serie es un 404 (no existe o no tiene episodios), las similares no se llegan a pedir', async () => {
    renderPage({
      'GET /api/series/7': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada con id 7'),
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
    });

    expect(await screen.findByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(0);
  });

  it('un 404 de las similares (la serie se acaba de quedar sin episodios) no pinta nada ni ofrece «Reintentar»', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada con id 7') });

    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(1));
    await waitFor(() => expect(screen.queryByText('Cargando series similares...')).not.toBeInTheDocument());
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las series similares' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });
});

describe('Series similares: error', () => {
  it('aviso dentro de la fila (la serie sigue en pantalla) y «Reintentar» vuelve a pedirlas', async () => {
    let fail = true;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });

    const title = await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las series similares' });
    const alert = title.closest<HTMLElement>('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
    // El fallo es solo de la fila: el título, los episodios y la ficha siguen ahí.
    expect(screen.getByRole('heading', { level: 1, name: 'Dark' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Episodios' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Ficha' })).toBeInTheDocument();

    fail = false;
    await user.click(within(alert!).getByRole('button', { name: 'Reintentar' }));

    const row = await screen.findByRole('region', ROW);
    expect(within(row).getAllByRole('link')).toHaveLength(3);
    expect(callsTo(SIMILAR_URL)).toBe(2);
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las series similares' })).not.toBeInTheDocument();
  });

  it('el foco tras «Reintentar» (teclado): si vuelve a fallar, al nuevo «Reintentar»; si sale bien, se queda en la zona de la fila', async () => {
    let fail = true;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });
    await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las series similares' });

    screen.getByRole('button', { name: 'Reintentar' }).focus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reintentar' })).toHaveFocus());

    fail = false;
    await user.keyboard('{Enter}');
    const row = await screen.findByRole('region', ROW);
    const focused = document.activeElement as HTMLElement;
    expect(focused).not.toBe(document.body);
    expect(focused).not.toBe(screen.getByRole('main'));
    expect(focused).toContainElement(row);
    // Y el siguiente Tab entra en la fila: la primera similar.
    await user.tab();
    expect(within(row).getByRole('link', { name: /^Mindhunter/ })).toHaveFocus();
  });

  it('sin conexión: el aviso dice la verdad (no hubo respuesta) y ofrece «Reintentar»', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => Promise.reject(new TypeError('Failed to fetch')) });

    const title = await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las series similares' });
    const alert = title.closest<HTMLElement>('[role="alert"]')!;
    expect(alert).toHaveTextContent('No se pudo conectar con el servidor. Comprueba tu conexión e inténtalo de nuevo.');
    expect(within(alert).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });
});

describe('Series similares: caché', () => {
  it('no se piden hasta que el servidor confirma la serie, aunque la serie ya esté en un listado de la caché', async () => {
    const queryClient = createTestQueryClient();
    const page: InfiniteData<PageResponse<Series>> = { pages: [makePage<Series>([series])], pageParams: [0] };
    queryClient.setQueryData(queryKeys.paged('/series', { pageSize: 20, sort: 'createdAt', direction: 'desc' }), page);
    let answerDetail!: (response: Response) => void;
    renderPage(
      {
        'GET /api/series/7': () => new Promise<Response>((resolve) => (answerDetail = resolve)),
        [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      },
      queryClient,
    );

    await waitFor(() => expect(callsTo('/api/series/7')).toBe(1));
    expect(screen.getByText('Cargando serie...')).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(0);

    await act(async () => answerDetail(jsonResponse(series)));

    expect(await screen.findByRole('region', ROW)).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(1);
  });

  it('cuelga de la raíz `series`: si una similar se queda sin episodios (keysAffectedBy("series")), desaparece de la fila', async () => {
    let current = similar;
    const { queryClient } = renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(current) });
    await screen.findByRole('region', ROW);

    // El servidor ya no la incluye: solo devuelve series con episodios.
    current = [similar[0], similar[2]];
    await act(async () => {
      await Promise.all(keysAffectedBy('series').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    await waitFor(() =>
      expect(within(screen.getByRole('region', ROW)).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual([
        '/series/12',
        '/series/9',
      ]),
    );
    expect(callsTo(SIMILAR_URL)).toBe(2);
  });

  it('renombrar o borrar un género (keysAffectedBy("genres")) también las vuelve a pedir; una película, no', async () => {
    let current = similar;
    const { queryClient } = renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(current) });
    await screen.findByRole('region', ROW);

    await act(async () => {
      await Promise.all(keysAffectedBy('movies').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });
    expect(callsTo(SIMILAR_URL)).toBe(1);

    current = [similar[1]];
    await act(async () => {
      await Promise.all(keysAffectedBy('genres').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    await waitFor(() => expect(within(screen.getByRole('region', ROW)).getAllByRole('link')).toHaveLength(1));
    expect(callsTo(SIMILAR_URL)).toBe(2);
  });

  it('si falla un refresco con la fila ya pintada, la fila se queda (sin aviso de error encima)', async () => {
    let fail = false;
    const { queryClient } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });
    await screen.findByRole('region', ROW);

    fail = true;
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.series.similar(7, 12) });
    });

    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(2));
    expect(within(screen.getByRole('region', ROW)).getAllByRole('link')).toHaveLength(3);
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las series similares' })).not.toBeInTheDocument();
  });
});

describe('Series similares: navegar a una similar', () => {
  it('cambia de serie, sube el scroll, lleva el foco al contenido y pide las similares de la nueva', async () => {
    const next = similar[2];
    let answerNext!: (response: Response) => void;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      'GET /api/series/9': () => jsonResponse(detailOf(next)),
      'GET /api/series/9/similar?limit=12': () => new Promise<Response>((resolve) => (answerNext = resolve)),
    });

    const row = await screen.findByRole('region', ROW);
    await user.click(within(row).getByRole('link', { name: /^1899/ }));

    expect(await screen.findByRole('heading', { level: 1, name: '1899' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/series/9')).toBeInTheDocument();
    // La fila NO enseña las similares de la serie anterior: pasa a cargando.
    expect(screen.queryByRole('link', { name: /^Mindhunter/ })).not.toBeInTheDocument();
    expect(screen.getByText('Cargando series similares...')).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(document.activeElement).toBe(document.getElementById(MAIN_CONTENT_ID));

    // Las similares de «1899» (entre ellas, la serie de la que se venía).
    await waitFor(() => expect(callsTo('/api/series/9/similar?limit=12')).toBe(1));
    await act(async () => answerNext(jsonResponse([series, similar[0]])));
    const nextRow = await screen.findByRole('region', ROW);
    expect(within(nextRow).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual(['/series/7', '/series/12']);
  });

  it('volver a una serie ya vista enseña SUS similares de la caché, no las de la serie de la que se viene', async () => {
    const next = similar[2];
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      'GET /api/series/9': () => jsonResponse(detailOf(next)),
      'GET /api/series/9/similar?limit=12': () => jsonResponse([series]),
    });

    let row = await screen.findByRole('region', ROW);
    await user.click(within(row).getByRole('link', { name: /^1899/ }));
    await screen.findByRole('heading', { level: 1, name: '1899' });
    row = await screen.findByRole('region', ROW);
    await waitFor(() => expect(within(row).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual(['/series/7']));

    await user.click(within(row).getByRole('link', { name: /^Dark/ }));
    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    row = await screen.findByRole('region', ROW);
    expect(within(row).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual([
      '/series/12',
      '/series/3',
      '/series/9',
    ]);
  });
});
