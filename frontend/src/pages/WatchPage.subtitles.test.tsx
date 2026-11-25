/**
 * Tests de los subtítulos del reproductor integrado (`PlayerScreen` a través de
 * `WatchMoviePage` y `WatchEpisodePage`).
 *
 * La regla que se protege: **los subtítulos nunca rompen ni retrasan el vídeo**.
 * - Solo hay pista (`<track>`) y `crossorigin` si el título tiene subtítulos
 *   válidos (https, sin credenciales).
 * - Si el vídeo falla con `crossorigin` (su servidor no admite CORS), se vuelve
 *   a cargar una vez sin él ni pista, en el mismo punto y sin pantalla de error,
 *   y el pie avisa; si falla también así, es el error de siempre.
 * - Si falla solo la pista, se quita, el vídeo sigue y el pie avisa.
 *
 * jsdom no carga vídeos ni pistas: los fallos se simulan lanzando `error` en el
 * elemento (el de la pista no burbujea, como en un navegador).
 */
import { act, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WatchProgress } from '../lib/types';
import { fakeVideo } from '../test/fakeVideo';
import {
  CURRENT_USER,
  jsonResponse,
  makeEpisode,
  makeMovie,
  makeSeriesDetail,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { Route, Routes } from 'react-router-dom';
import { WatchEpisodePage, WatchMoviePage } from './WatchPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const SUBTITLES = 'https://subs.example/interstellar.es.vtt';
const NOTICE = 'No se han podido cargar los subtítulos: el vídeo sigue sin ellos.';
const ADMIN_NOTICE = 'Comprueba su URL en el panel y que los servidores del vídeo y de los subtítulos permitan CORS.';
const MOVIE_PROGRESS = 'GET /api/users/me/progress/movies/7';
const MOVIE_PUT = 'PUT /api/users/me/progress/movies/7';

function progress(overrides: Partial<WatchProgress> = {}): WatchProgress {
  return {
    positionSeconds: 754,
    durationSeconds: 8160,
    finished: false,
    watched: false,
    updatedAt: '2026-11-21T10:00:00Z',
    ...overrides,
  };
}

function renderMoviePlayer(subtitlesUrl: string | null, extra: Parameters<typeof routeFetch>[1] = {}) {
  const movie = makeMovie({
    id: 7,
    title: 'Interstellar',
    videoUrl: 'https://cdn.example/interstellar.mp4',
    subtitlesUrl,
  });
  routeFetch(fetchMock, {
    'GET /api/movies/7': () => jsonResponse(movie),
    [MOVIE_PROGRESS]: () => jsonResponse(progress()),
    [MOVIE_PUT]: noContentResponse,
    ...extra,
  });
  renderWithProviders(
    <Routes>
      <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
    </Routes>,
    { session: true, route: '/ver/peliculas/7' },
  );
}

/** El `<video>` que hay ahora en pantalla (tras un reintento es OTRO elemento). */
function currentVideo(): HTMLVideoElement {
  const element = document.querySelector('video');
  if (!element) throw new Error('No hay <video>');
  return element;
}

async function playerVideo() {
  await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
  return fakeVideo(currentVideo());
}

/** Simula un fallo del `<video>` (código de `MediaError` 4: el navegador no pudo abrirlo; también lo da un rechazo de CORS). */
function failVideo(element: HTMLVideoElement, code = 4) {
  Object.defineProperty(element, 'error', { configurable: true, value: { code } });
  act(() => {
    element.dispatchEvent(new Event('error'));
  });
}

/** Cuerpos de los `PUT` de progreso enviados. */
function savedBodies(): unknown[] {
  return fetchMock.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)));
}

describe('Reproductor: pista de subtítulos', () => {
  it('sin subtítulos: ni pista ni crossorigin (el vídeo se carga como siempre)', async () => {
    renderMoviePlayer(null);
    const video = await playerVideo();

    expect(video.element.querySelector('track')).toBeNull();
    expect(video.element).not.toHaveAttribute('crossorigin');
  });

  it('con subtítulos: pista en español desactivada por defecto y crossorigin="anonymous" en el vídeo', async () => {
    renderMoviePlayer(SUBTITLES);
    const video = await playerVideo();

    const track = video.element.querySelector('track');
    expect(track).not.toBeNull();
    expect(track).toHaveAttribute('kind', 'subtitles');
    expect(track).toHaveAttribute('srclang', 'es');
    expect(track).toHaveAttribute('label', 'Español');
    expect(track).toHaveAttribute('src', SUBTITLES);
    // Sin `default`: desactivada hasta que el usuario la active (o su sistema lo pida).
    expect(track).not.toHaveAttribute('default');
    expect(video.element).toHaveAttribute('crossorigin', 'anonymous');
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it.each([
    ['http://', 'http://subs.example/interstellar.vtt'],
    ['con credenciales', 'https://user:pass@subs.example/interstellar.vtt'],
    ['javascript:', 'javascript:alert(1)'],
  ])('subtítulos no seguros (%s): ni pista ni crossorigin', async (_caso, url) => {
    renderMoviePlayer(url);
    const video = await playerVideo();

    expect(video.element.querySelector('track')).toBeNull();
    expect(video.element).not.toHaveAttribute('crossorigin');
  });
});

describe('Reproductor: el vídeo falla con crossorigin (su servidor no admite CORS)', () => {
  it('antes de empezar: se recarga sin crossorigin ni pista, sin pantalla de error, reanuda donde iba y avisa', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    failVideo(first.element);

    // Otro <video>, sin CORS ni pista; ningún aviso de error del vídeo.
    const second = fakeVideo(currentVideo());
    expect(second.element).not.toBe(first.element);
    expect(second.element).not.toHaveAttribute('crossorigin');
    expect(second.element.querySelector('track')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'No se pudo reproducir el vídeo' })).not.toBeInTheDocument();
    expect(screen.getByText(NOTICE)).toBeInTheDocument();

    // Empieza donde se dejó, como si no hubiera pasado nada.
    second.loadMetadata(8160);
    expect(second.state.currentTime).toBe(754);
    expect(screen.getByText('Sigues por 12:34.')).toBeInTheDocument();
  });

  it('tras el aviso, «Reintentar» por un error posterior del vídeo sigue sin subtítulos y desde donde iba', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    failVideo(first.element);

    // Sin CORS el vídeo sí carga y se ve un rato; luego la red se corta.
    const second = fakeVideo(currentVideo());
    second.loadMetadata(8160);
    second.play(20);
    expect(second.state.currentTime).toBe(774);
    failVideo(second.element, 2);

    // El vídeo anterior se despide guardando su posición.
    await vi.waitFor(() => expect(savedBodies()).toEqual([{ positionSeconds: 774, durationSeconds: 8160 }]));
    const heading = await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso no es un alert');
    act(() => {
      within(alert).getByRole('button', { name: 'Reintentar' }).click();
    });

    const third = fakeVideo(currentVideo());
    expect(third.element).not.toHaveAttribute('crossorigin');
    expect(third.element.querySelector('track')).toBeNull();
    third.loadMetadata(8160);
    expect(third.state.currentTime).toBe(774);
  });

  it('si también falla sin crossorigin, es el error de siempre (y no se dice que el vídeo sigue)', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    failVideo(first.element);
    failVideo(currentVideo());

    expect(await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toBeInTheDocument();
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
  });

  it('«Reintentar» tras ese error no vuelve a probar los subtítulos', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    failVideo(first.element);
    failVideo(currentVideo());

    const heading = await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso no es un alert');
    act(() => {
      within(alert).getByRole('button', { name: 'Reintentar' }).click();
    });

    const retried = currentVideo();
    expect(retried).not.toHaveAttribute('crossorigin');
    expect(retried.querySelector('track')).toBeNull();
    expect(screen.getByText(NOTICE)).toBeInTheDocument();
  });

  it('sin subtítulos, un fallo del vídeo va directo a la pantalla de error (no hay nada que quitar)', async () => {
    renderMoviePlayer(null);
    const video = await playerVideo();
    failVideo(video.element);

    expect(await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toBeInTheDocument();
  });
});

/**
 * Un error **después** de `loadedmetadata` no puede ser de CORS (el vídeo ya se
 * estaba leyendo con él): es un corte de red u otro fallo del vídeo. No se
 * culpa a los subtítulos ni se quitan (revisión QA, BAJA-3).
 */
describe('Reproductor: el vídeo con subtítulos falla después de cargar (p. ej. la red se corta)', () => {
  it('es el error de siempre: sin aviso de subtítulos y sin volver a montarlo sin crossorigin', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    first.loadMetadata(8160);
    first.play(20);
    expect(first.state.currentTime).toBe(774);

    // MEDIA_ERR_NETWORK (2): la red se ha cortado a mitad.
    failVideo(first.element, 2);

    expect(await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toBeInTheDocument();
    expect(document.querySelector('video')).toBeNull();
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    // El vídeo se despide guardando lo visto, como en cualquier error.
    await vi.waitFor(() => expect(savedBodies()).toEqual([{ positionSeconds: 774, durationSeconds: 8160 }]));
  });

  it('«Reintentar» vuelve a montarlo CON la pista y crossorigin (los subtítulos no habían fallado), desde donde iba', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    first.loadMetadata(8160);
    first.play(20);
    failVideo(first.element, 2);

    const heading = await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso no es un alert');
    act(() => {
      within(alert).getByRole('button', { name: 'Reintentar' }).click();
    });

    const retried = fakeVideo(currentVideo());
    expect(retried.element).not.toBe(first.element);
    expect(retried.element).toHaveAttribute('crossorigin', 'anonymous');
    expect(retried.element.querySelector('track')).toHaveAttribute('src', SUBTITLES);
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    retried.loadMetadata(8160);
    expect(retried.state.currentTime).toBe(774);
  });

  it('tras «Reintentar», un fallo antes de los metadatos vuelve a tratarse como posible CORS', async () => {
    renderMoviePlayer(SUBTITLES);
    const first = await playerVideo();
    first.loadMetadata(8160);
    failVideo(first.element, 2);

    const heading = await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso no es un alert');
    act(() => {
      within(alert).getByRole('button', { name: 'Reintentar' }).click();
    });
    // El vídeo nuevo falla sin haber llegado a sus metadatos: se prueba sin CORS, con el aviso.
    failVideo(currentVideo());

    expect(currentVideo()).not.toHaveAttribute('crossorigin');
    expect(screen.queryByRole('heading', { name: 'No se pudo reproducir el vídeo' })).not.toBeInTheDocument();
    expect(screen.getByText(NOTICE)).toBeInTheDocument();
  });
});

describe('Reproductor: falla solo la pista', () => {
  it('se quita la pista, el vídeo sigue (el mismo elemento, en su sitio) y el pie avisa', async () => {
    renderMoviePlayer(SUBTITLES);
    const video = await playerVideo();
    video.loadMetadata(8160);
    video.play(5);
    const track = video.element.querySelector('track');
    if (!track) throw new Error('No hay pista');

    act(() => {
      // Como en un navegador, el `error` de la pista no burbujea.
      track.dispatchEvent(new Event('error', { bubbles: false }));
    });

    expect(currentVideo()).toBe(video.element);
    expect(video.element.querySelector('track')).toBeNull();
    expect(video.state.currentTime).toBe(759);
    expect(screen.queryByRole('heading', { name: 'No se pudo reproducir el vídeo' })).not.toBeInTheDocument();
    const notice = screen.getByText(NOTICE);
    // Se anuncia sin mover el foco: está en la región `status` del pie.
    expect(notice.closest('[role="status"]')).not.toBeNull();
    expect(notice).not.toHaveTextContent(ADMIN_NOTICE);
  });

  it('a un administrador le dice además dónde mirar', async () => {
    renderMoviePlayer(SUBTITLES, { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) });
    const video = await playerVideo();
    // Se espera al rol: mientras carga, el texto es el de un usuario.
    await act(async () => {});
    const track = video.element.querySelector('track');
    if (!track) throw new Error('No hay pista');
    act(() => {
      track.dispatchEvent(new Event('error'));
    });

    expect(await screen.findByText(`${NOTICE} ${ADMIN_NOTICE}`)).toBeInTheDocument();
  });
});

describe('Reproductor de episodios: subtítulos', () => {
  it('usa los subtítulos del episodio', async () => {
    const episode = makeEpisode({
      id: 41,
      seasonNumber: 1,
      episodeNumber: 2,
      title: 'Mentiras',
      videoUrl: 'https://cdn.example/dark-1x02.webm',
      subtitlesUrl: 'https://subs.example/dark-1x02.vtt',
    });
    const series = makeSeriesDetail({ id: 3, title: 'Dark', seasons: [{ seasonNumber: 1, episodes: [episode] }] });
    routeFetch(fetchMock, {
      'GET /api/series/3': () => jsonResponse(series),
      'GET /api/users/me/progress/episodes/41': noContentResponse,
    });
    renderWithProviders(
      <Routes>
        <Route path="/ver/series/:seriesId/episodios/:episodeId" element={<WatchEpisodePage />} />
      </Routes>,
      { session: true, route: '/ver/series/3/episodios/41' },
    );

    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    const video = currentVideo();
    expect(video).toHaveAttribute('crossorigin', 'anonymous');
    expect(video.querySelector('track')).toHaveAttribute('src', 'https://subs.example/dark-1x02.vtt');
  });
});
