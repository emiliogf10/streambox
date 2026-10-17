/**
 * Tests de la fila «Películas similares» (`SimilarMoviesRow` + `useSimilarMovies`)
 * montada en su sitio real, la página de una película (`/peliculas/:id`), con
 * `fetch` simulado.
 *
 * Protegen el contrato con la API (`GET /api/movies/{id}/similar?limit=12`, un
 * array ya ordenado que se pinta TAL CUAL) y los estados: cargando (silueta),
 * vacío (no se pinta nada, ni el título), error (aviso dentro de la fila con
 * «Reintentar», sin romper la página), 404 de la película (no se piden) y la
 * copia provisional de un listado (se espera a que el servidor la confirme).
 * Y lo que pasa al pulsar una similar: la página cambia de película, el foco va
 * al contenido, la fila nunca enseña las similares de la anterior, y la clave
 * cuelga de `movies` (una escritura del panel la vuelve a pedir).
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import type { InfiniteData } from '@tanstack/react-query';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { keysAffectedBy, queryKeys } from '../lib/queryKeys';
import type { Movie, PageResponse } from '../lib/types';
import { MovieDetailPage } from '../pages/MovieDetailPage';
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
import { createTestQueryClient } from '../test/queryClient';
import userEvent from '@testing-library/user-event';
import { RouteChangeFocus } from './RouteChangeFocus';
import { MAIN_CONTENT_ID } from './SkipLink';

const fetchMock = vi.fn<typeof fetch>();
const scrollTo = vi.fn();

const FAVORITES = { 'GET /api/users/me/favorites': () => jsonResponse([]) };
const AS_ADMIN = { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) };
const SIMILAR_URL = '/api/movies/7/similar?limit=12';

const movie = makeMovie({ id: 7, title: 'Interstellar', genres: [{ id: 1, name: 'Ciencia ficción' }] });
/** Similares en el orden del servidor (a propósito, NI por id NI por título: el cliente no debe reordenar). */
const similar = [
  makeMovie({ id: 12, title: 'Gravity', releaseYear: 2013, duration: 91 }),
  makeMovie({ id: 3, title: 'Arrival', releaseYear: 2016, duration: 116 }),
  makeMovie({ id: 9, title: 'Contact', releaseYear: 1997, duration: 150 }),
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

/**
 * Monta la página de la película 7 dentro de un `<main>` como el de `AppShell`
 * (con `RouteChangeFocus`, para ver adónde va el foco al cambiar de película).
 */
function renderPage(routes: Parameters<typeof routeFetch>[1], queryClient = createTestQueryClient()) {
  routeFetch(fetchMock, { ...FAVORITES, 'GET /api/movies/7': () => jsonResponse(movie), ...routes });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <RouteChangeFocus />
      <main id={MAIN_CONTENT_ID} tabIndex={-1}>
        <Routes>
          <Route path="/peliculas/:id" element={<MovieDetailPage />} />
        </Routes>
      </main>
    </FavoritesProvider>,
    { session: true, route: '/peliculas/7', queryClient },
  );
  return { user, queryClient };
}

describe('SimilarMoviesRow: con similares', () => {
  it.each([
    ['un usuario', {}],
    ['un administrador', AS_ADMIN],
  ])('para %s: pide la URL exacta con limit=12 y pinta las tarjetas en el orden recibido, tras la ficha', async (_role, extra) => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar), ...extra });

    const row = await screen.findByRole('region', { name: 'Películas similares' });
    expect(within(row).getByRole('heading', { level: 2, name: 'Películas similares' })).toBeInTheDocument();
    const cards = within(row).getAllByRole('link');
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/peliculas/12', '/peliculas/3', '/peliculas/9']);
    // Mismo nombre accesible que las tarjetas del resto de la aplicación: título + año y duración.
    expect(cards[0]).toHaveAccessibleName('Gravity 2013 · 1h 31m');
    expect(callsTo(SIMILAR_URL)).toBe(1);

    // Jerarquía: el título, la ficha y después la fila.
    const headings = screen.getAllByRole('heading').map((heading) => `${heading.tagName}:${heading.textContent}`);
    expect(headings).toEqual(['H1:Interstellar', 'H2:Ficha', 'H2:Películas similares']);
  });

  it('mientras llegan: la silueta de una fila, anunciada como «Cargando películas similares...»', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => new Promise<Response>(() => {}) });

    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(1));
    expect(screen.getByText('Cargando películas similares...').closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Películas similares' })).not.toBeInTheDocument();
  });
});

describe('SimilarMoviesRow: sin nada que enseñar', () => {
  it('lista vacía: no se pinta nada (ni el título, ni la silueta)', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse([]) });

    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    await waitFor(() => expect(screen.queryByText('Cargando películas similares...')).not.toBeInTheDocument());
    expect(callsTo(SIMILAR_URL)).toBe(1);
    expect(screen.queryByRole('region', { name: 'Películas similares' })).not.toBeInTheDocument();
    expect(screen.queryByText('Películas similares')).not.toBeInTheDocument();
  });

  it('si la película es un 404, las similares no se llegan a pedir', async () => {
    renderPage({
      'GET /api/movies/7': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Película no encontrada con id 7'),
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
    });

    expect(await screen.findByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(0);
  });

  it('un 404 de las similares (la película se acaba de borrar) no pinta nada ni ofrece «Reintentar»', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Película no encontrada con id 7') });

    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    await waitFor(() => expect(screen.queryByText('Cargando películas similares...')).not.toBeInTheDocument());
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las películas similares' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });
});

describe('SimilarMoviesRow: error', () => {
  it('aviso dentro de la fila (la película sigue en pantalla) y «Reintentar» vuelve a pedirlas', async () => {
    let fail = true;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });

    // El aviso es un `role="alert"` (se anuncia al aparecer) con su título y el mensaje de `apiFetch` (un 5xx nunca enseña el texto interno).
    const title = await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las películas similares' });
    const alert = title.closest<HTMLElement>('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
    // El fallo es solo de la fila: el título, «Ver ahora» y la ficha siguen ahí.
    expect(screen.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Ficha' })).toBeInTheDocument();

    fail = false;
    await user.click(within(alert!).getByRole('button', { name: 'Reintentar' }));

    const row = await screen.findByRole('region', { name: 'Películas similares' });
    expect(within(row).getAllByRole('link')).toHaveLength(3);
    expect(callsTo(SIMILAR_URL)).toBe(2);
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las películas similares' })).not.toBeInTheDocument();
  });

  it('el foco tras «Reintentar» (teclado): si vuelve a fallar, al nuevo «Reintentar»; si sale bien, se queda en la zona de la fila', async () => {
    let fail = true;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });
    await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las películas similares' });

    // Vuelve a fallar: el foco no se pierde en <body>, acaba en el «Reintentar» nuevo.
    screen.getByRole('button', { name: 'Reintentar' }).focus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reintentar' })).toHaveFocus());

    // Sale bien: el foco queda en el contenedor de la fila (no en <body> ni arriba del todo, en el <main>).
    fail = false;
    await user.keyboard('{Enter}');
    const row = await screen.findByRole('region', { name: 'Películas similares' });
    const focused = document.activeElement as HTMLElement;
    expect(focused).not.toBe(document.body);
    expect(focused).not.toBe(screen.getByRole('main'));
    expect(focused).toContainElement(row);
    // Y el siguiente Tab entra en la fila: la primera similar.
    await user.tab();
    expect(within(row).getByRole('link', { name: 'Gravity 2013 · 1h 31m' })).toHaveFocus();
  });
});

describe('SimilarMoviesRow: caché', () => {
  it('con la copia provisional de un listado, espera a que el servidor confirme la película para pedirlas', async () => {
    const queryClient = createTestQueryClient();
    const page: InfiniteData<PageResponse<Movie>> = { pages: [makePage([movie])], pageParams: [0] };
    queryClient.setQueryData(queryKeys.paged('/movies', { pageSize: 20, sort: 'createdAt', direction: 'desc' }), page);
    let answerDetail!: (response: Response) => void;
    renderPage(
      {
        'GET /api/movies/7': () => new Promise<Response>((resolve) => (answerDetail = resolve)),
        [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      },
      queryClient,
    );

    // Se pinta al instante con la copia, pero las similares aún no se piden (silueta mientras tanto).
    expect(screen.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
    expect(screen.getByText('Cargando películas similares...')).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(0);

    await act(async () => answerDetail(jsonResponse(movie)));

    expect(await screen.findByRole('region', { name: 'Películas similares' })).toBeInTheDocument();
    expect(callsTo(SIMILAR_URL)).toBe(1);
  });

  it('cuelga de la raíz `movies`: una escritura del panel (keysAffectedBy) las vuelve a pedir', async () => {
    let current = similar;
    const { queryClient } = renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(current) });
    await screen.findByRole('region', { name: 'Películas similares' });

    current = [similar[1]];
    await act(async () => {
      await Promise.all(keysAffectedBy('movies').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Películas similares' })).getAllByRole('link')).toHaveLength(1));
    expect(callsTo(SIMILAR_URL)).toBe(2);
  });
});

describe('SimilarMoviesRow: navegar a una similar', () => {
  it('cambia de película, sube el scroll, lleva el foco al contenido y pide las similares de la nueva', async () => {
    const contact = similar[2];
    let answerNext!: (response: Response) => void;
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      'GET /api/movies/9': () => jsonResponse(contact),
      'GET /api/movies/9/similar?limit=12': () => new Promise<Response>((resolve) => (answerNext = resolve)),
    });

    const row = await screen.findByRole('region', { name: 'Películas similares' });
    await user.click(within(row).getByRole('link', { name: /^Contact/ }));

    // La página es la nueva al instante (la copia de la fila sirve de anticipo) y la fila NO enseña las de la anterior.
    expect(await screen.findByRole('heading', { level: 1, name: 'Contact' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/peliculas/9')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Gravity/ })).not.toBeInTheDocument();
    expect(screen.getByText('Cargando películas similares...')).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(document.activeElement).toBe(document.getElementById(MAIN_CONTENT_ID));

    // Las similares de «Contact» (entre ellas, la película de la que se venía).
    await waitFor(() => expect(callsTo('/api/movies/9/similar?limit=12')).toBe(1));
    await act(async () => answerNext(jsonResponse([movie, similar[0]])));
    const nextRow = await screen.findByRole('region', { name: 'Películas similares' });
    expect(within(nextRow).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual([
      '/peliculas/7',
      '/peliculas/12',
    ]);
  });
});

/**
 * Casos añadidos en la revisión independiente (QA): lo que la fila promete en
 * su documentación y no fijaba ningún test.
 */
describe('SimilarMoviesRow: revisión QA', () => {
  it('renombrar o borrar un género (keysAffectedBy("genres")) también las vuelve a pedir', async () => {
    let current = similar;
    const { queryClient } = renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse(current) });
    await screen.findByRole('region', { name: 'Películas similares' });

    current = [similar[0], similar[2]];
    await act(async () => {
      await Promise.all(keysAffectedBy('genres').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Películas similares' })).getAllByRole('link')).toHaveLength(2));
    expect(callsTo(SIMILAR_URL)).toBe(2);
  });

  it('si falla un refresco con la fila ya pintada, la fila se queda (sin aviso de error encima)', async () => {
    let fail = false;
    const { queryClient } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(similar)),
    });
    await screen.findByRole('region', { name: 'Películas similares' });

    fail = true;
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.movies.all });
    });

    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(2));
    expect(within(screen.getByRole('region', { name: 'Películas similares' })).getAllByRole('link')).toHaveLength(3);
    expect(screen.queryByRole('heading', { name: 'No se pudieron cargar las películas similares' })).not.toBeInTheDocument();
  });

  it('sin conexión: el aviso dice la verdad (no hubo respuesta) y ofrece «Reintentar»', async () => {
    renderPage({
      [`GET ${SIMILAR_URL}`]: () => Promise.reject(new TypeError('Failed to fetch')),
    });

    const title = await screen.findByRole('heading', { level: 2, name: 'No se pudieron cargar las películas similares' });
    const alert = title.closest<HTMLElement>('[role="alert"]')!;
    expect(alert).toHaveTextContent('No se pudo conectar con el servidor. Comprueba tu conexión e inténtalo de nuevo.');
    expect(within(alert).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeInTheDocument();
  });

  it('para un administrador, la lista vacía tampoco pinta nada: su contenedor queda vacío (y `empty:hidden` lo oculta)', async () => {
    renderPage({ [`GET ${SIMILAR_URL}`]: () => jsonResponse([]), ...AS_ADMIN });

    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    await waitFor(() => expect(callsTo(SIMILAR_URL)).toBe(1));
    await waitFor(() => expect(screen.queryByText('Cargando películas similares...')).not.toBeInTheDocument());
    // El hueco de la fila (con su margen superior) no debe quedar al pie de la página.
    const slot = document.body.querySelector('[class~="empty:hidden"]');
    expect(slot).not.toBeNull();
    expect(slot).toBeEmptyDOMElement();
    expect(screen.queryByText(/similares/i)).not.toBeInTheDocument();
  });

  it('volver a una película ya vista enseña SUS similares de la caché, no las de la película de la que se viene', async () => {
    const contact = similar[2];
    const { user } = renderPage({
      [`GET ${SIMILAR_URL}`]: () => jsonResponse(similar),
      'GET /api/movies/9': () => jsonResponse(contact),
      'GET /api/movies/9/similar?limit=12': () => jsonResponse([movie]),
    });

    let row = await screen.findByRole('region', { name: 'Películas similares' });
    await user.click(within(row).getByRole('link', { name: /^Contact/ }));
    await screen.findByRole('heading', { level: 1, name: 'Contact' });
    row = await screen.findByRole('region', { name: 'Películas similares' });
    await waitFor(() => expect(within(row).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual(['/peliculas/7']));

    await user.click(within(row).getByRole('link', { name: /^Interstellar/ }));
    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    row = await screen.findByRole('region', { name: 'Películas similares' });
    expect(within(row).getAllByRole('link').map((card) => card.getAttribute('href'))).toEqual([
      '/peliculas/12',
      '/peliculas/3',
      '/peliculas/9',
    ]);
  });
});
