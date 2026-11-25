/**
 * Tests de la sección «Vistos recientemente» del perfil (`WatchHistorySection`)
 * con el hook real `useWatchHistory` y `fetch` simulado.
 *
 * Protegen los tres estados (cargando con silueta, error con «Reintentar» que
 * no pierde el foco, vacío con el mismo texto verdadero para USER y ADMIN y una
 * salida al catálogo solo si hay algo), y las tarjetas: terminado («Visto», sin
 * barra) frente a medias (barra y lo que queda), episodio (la serie y «T1:E2 ·
 * título») frente a película, enlaces a la PÁGINA del título (nunca al
 * reproductor) y el resumen junto al título, que no inventa un total.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WATCH_HISTORY_LIMIT } from '../../hooks/useWatchProgress';
import {
  CURRENT_USER,
  WATCH_HISTORY,
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  makeUser,
  makeWatchHistoryItem,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { UserStatusProbe } from '../../test/UserStatusProbe';
import { WatchHistorySection } from './WatchHistorySection';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Lo que preguntan los botones del estado vacío para saber si hay algo que elegir. */
const ANY_MOVIE = 'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc';
const ANY_SERIES = 'GET /api/series?page=0&size=1&sort=createdAt&direction=desc';

/** Película a medias: 10 de 50 min. */
const dune = makeWatchHistoryItem({ id: 7, title: 'Dune', positionSeconds: 600, durationSeconds: 3000 });
/** Película terminada (≥ 95 %). */
const arrival = makeWatchHistoryItem({
  id: 8,
  title: 'Arrival',
  positionSeconds: 6300,
  durationSeconds: 6360,
  finished: true,
});
/** Episodio a medias, con el mismo id que la película `dune`: deben ser tarjetas distintas. */
const darkEpisode = makeWatchHistoryItem({
  type: 'EPISODE',
  id: 7,
  title: 'Mentiras',
  imageUrl: 'https://img.example/dark.jpg',
  seasonNumber: 1,
  episodeNumber: 2,
  seriesId: 3,
  seriesTitle: 'Dark',
  positionSeconds: 2000,
  durationSeconds: 3000,
});

/** Región de la sección. */
const section = () => screen.getByRole('region', { name: 'Vistos recientemente' });

const EMPTY_TITLE = 'Aún no has visto nada';
const EMPTY_TEXT = 'Cuando reproduzcas una película o un episodio en StreamBox, aparecerá aquí con lo que llevas visto.';

describe('WatchHistorySection: estados', () => {
  it('pide las 12 más recientes y, mientras llegan, enseña la silueta anunciada (sin estado vacío falso)', () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => new Promise<Response>(() => {}) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    expect(within(section()).getByRole('status')).toHaveTextContent('Cargando lo que has visto...');
    expect(within(section()).queryByText(EMPTY_TITLE)).not.toBeInTheDocument();
    expect(WATCH_HISTORY).toBe(`GET /api/users/me/watch-history?limit=${WATCH_HISTORY_LIMIT}`);
  });

  it.each(['USER', 'ADMIN'] as const)(
    'vacía (%s): el mismo texto verdadero y una salida a películas y a series si las hay',
    async (role) => {
      routeFetch(fetchMock, {
        [CURRENT_USER]: () => jsonResponse(makeUser({ role })),
        [ANY_MOVIE]: () => jsonResponse(makePage([makeMovie()])),
        [ANY_SERIES]: () => jsonResponse(makePage([makeSeries()])),
      });
      renderWithProviders(
        <>
          <WatchHistorySection />
          <UserStatusProbe />
        </>,
        { session: true },
      );

      expect(await within(section()).findByText(EMPTY_TITLE)).toBeInTheDocument();
      expect(within(section()).getByText(EMPTY_TEXT)).toBeInTheDocument();
      await screen.findByText('estado:ready');
      expect(await within(section()).findByRole('link', { name: 'Elegir una película' })).toHaveAttribute(
        'href',
        '/peliculas',
      );
      expect(await within(section()).findByRole('link', { name: 'Elegir una serie' })).toHaveAttribute('href', '/series');
    },
  );

  it('vacía y sin nada en el catálogo: no ofrece caminos a páginas vacías (el texto sigue siendo verdad)', async () => {
    routeFetch(fetchMock, {
      [ANY_MOVIE]: () => jsonResponse(makePage([])),
      [ANY_SERIES]: () => jsonResponse(makePage([])),
    });
    renderWithProviders(<WatchHistorySection />, { session: true });

    expect(await within(section()).findByText(EMPTY_TEXT)).toBeInTheDocument();
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/series?'))).toBe(true));
    await waitFor(() => expect(within(section()).queryByRole('link')).not.toBeInTheDocument());
  });

  it('si falla, avisa con «Reintentar»; al pulsarlo el foco se queda en la sección y llegan las tarjetas', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(<WatchHistorySection />, { session: true });

    const alert = await within(section()).findByRole('alert');
    expect(within(alert).getByRole('heading', { level: 3, name: 'No se pudo cargar lo que has visto' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');

    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([dune]) });
    await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));

    expect(await within(section()).findByRole('link', { name: /^Dune/ })).toBeInTheDocument();
    expect(document.activeElement).not.toBe(document.body);
    expect(section()).toContainElement(document.activeElement as HTMLElement);
  });
});

describe('WatchHistorySection: tarjetas', () => {
  it('en el orden del servidor, cada una enlazada a la página de su título (la serie, en su temporada), nunca al reproductor', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([darkEpisode, dune, arrival]) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    await within(section()).findByRole('link', { name: /^Dark/ });
    const links = within(section()).getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/series/3?temporada=1', '/peliculas/7', '/peliculas/8']);
    for (const link of links) expect(link.getAttribute('href')).not.toMatch(/^\/ver\//);
  });

  it('a medias: la barra de lo visto (oculta al lector, el texto ya lo dice) y lo que queda', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([dune]) });
    const { container } = renderWithProviders(<WatchHistorySection />, { session: true });

    const link = await within(section()).findByRole('link', { name: 'Dune Quedan 40 min' });
    const bar = container.querySelector('progress');
    expect(link).toContainElement(bar);
    expect(bar).toHaveAttribute('aria-hidden', 'true');
    expect(bar).toHaveAttribute('value', '600');
    expect(bar).toHaveAttribute('max', '3000');
    expect(within(section()).queryByRole('progressbar')).not.toBeInTheDocument();
    expect(within(link).queryByText('Visto')).not.toBeInTheDocument();
  });

  it('terminado: la marca «Visto» y sin barra', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([arrival]) });
    const { container } = renderWithProviders(<WatchHistorySection />, { session: true });

    const link = await within(section()).findByRole('link', { name: 'Arrival Visto' });
    expect(within(link).getByText('Visto')).toBeInTheDocument();
    expect(container.querySelector('progress')).toBeNull();
    expect(within(link).queryByText(/^Queda/)).not.toBeInTheDocument();
  });

  it('visto y vuelto a empezar (`watched` sin `finished`): la marca «Visto» Y la barra con lo que queda de esta vez', async () => {
    const rewatching = makeWatchHistoryItem({
      id: 8,
      title: 'Arrival',
      positionSeconds: 600,
      durationSeconds: 6360,
      finished: false,
      watched: true,
    });
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([rewatching]) });
    const { container } = renderWithProviders(<WatchHistorySection />, { session: true });

    const link = await within(section()).findByRole('link', { name: 'Arrival Visto Quedan 1 h 36 min' });
    expect(within(link).getByText('Visto')).toBeInTheDocument();
    expect(within(link).getByText('Quedan 1 h 36 min')).toBeInTheDocument();
    expect(container.querySelector('progress')).toHaveAttribute('value', '600');
  });

  it('«Visto» sale de `watched`, no de la posición: uno a medias que nunca se terminó no lo lleva', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([makeWatchHistoryItem({ watched: false, finished: false })]) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    const link = await within(section()).findByRole('link', { name: 'Dune Quedan 40 min' });
    expect(within(link).queryByText('Visto')).not.toBeInTheDocument();
  });

  it('episodio: el nombre es la SERIE y debajo «T1:E2 · título del episodio»; la portada es la de la serie', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([darkEpisode]) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    const link = await within(section()).findByRole('link', { name: 'Dark T1:E2 · Mentiras Quedan 17 min' });
    expect(within(link).getByText('Dark')).toBeInTheDocument();
    expect(within(link).getByText('T1:E2 · Mentiras')).toBeInTheDocument();
    expect(link.querySelector('img')).toHaveAttribute('src', 'https://img.example/dark.jpg');
  });

  it('una película y un episodio con el mismo id son dos tarjetas', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([dune, darkEpisode]) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    await within(section()).findByRole('link', { name: /^Dune/ });
    expect(within(section()).getAllByRole('listitem')).toHaveLength(2);
  });

  it('junto al título, cuántos hay con singular y plural', async () => {
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([dune]) });
    const { unmount } = renderWithProviders(<WatchHistorySection />, { session: true });
    expect(await within(section()).findByText('1 título · lo más reciente primero')).toBeInTheDocument();
    unmount();

    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse([dune, arrival]) });
    renderWithProviders(<WatchHistorySection />, { session: true });
    expect(await within(section()).findByText('2 títulos · lo más reciente primero')).toBeInTheDocument();
  });

  it(`con ${WATCH_HISTORY_LIMIT} (la página llena) no inventa un total: «Los ${WATCH_HISTORY_LIMIT} más recientes»`, async () => {
    const items = Array.from({ length: WATCH_HISTORY_LIMIT }, (_, index) =>
      makeWatchHistoryItem({ id: index + 1, title: `Peli ${index + 1}` }),
    );
    routeFetch(fetchMock, { [WATCH_HISTORY]: () => jsonResponse(items) });
    renderWithProviders(<WatchHistorySection />, { session: true });

    expect(await within(section()).findByText(`Los ${WATCH_HISTORY_LIMIT} más recientes`)).toBeInTheDocument();
    expect(within(section()).queryByText(/títulos · lo más reciente primero/)).not.toBeInTheDocument();
    expect(within(section()).getAllByRole('link')).toHaveLength(WATCH_HISTORY_LIMIT);
  });
});
