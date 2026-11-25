/**
 * Tests del reproductor integrado (`WatchMoviePage`, `WatchEpisodePage`).
 *
 * El `<video>` de jsdom no reproduce: cada test lo sustituye por el simulado de
 * `test/fakeVideo.ts` y le hace «cargar» con `loadMetadata`. Se comprueba lo que
 * vive el usuario: desde dónde empieza, cómo vuelve al principio, qué dice cada
 * estado (según el rol cuando importa), cómo se sale (Volver, Escape) y que el
 * progreso se guarda al salir.
 */
import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, Route, Routes } from 'react-router-dom';
import { focusManager } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../lib/queryKeys';
import type { WatchProgress } from '../lib/types';
import { fakeVideo } from '../test/fakeVideo';
import { createTestQueryClient } from '../test/queryClient';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeEpisode,
  makeMovie,
  makeSeriesDetail,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { WatchEpisodePage, WatchMoviePage } from './WatchPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const movie = makeMovie({ id: 7, title: 'Interstellar', videoUrl: 'https://cdn.example/interstellar.mp4' });
const MOVIE_PROGRESS = 'GET /api/users/me/progress/movies/7';
const AS_ADMIN = { [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })) };

function progress(overrides: Partial<WatchProgress> = {}): WatchProgress {
  return { positionSeconds: 754, durationSeconds: 8160, finished: false, watched: overrides.finished ?? false, updatedAt: '2026-11-21T10:00:00Z', ...overrides };
}

/** Pantallas montadas: el reproductor y, para comprobar adónde se vuelve, la ruta de la película (solo su nombre). */
function renderMoviePlayer(extra: Parameters<typeof routeFetch>[1] = {}, route = '/ver/peliculas/7') {
  routeFetch(fetchMock, {
    'GET /api/movies/7': () => jsonResponse(movie),
    [MOVIE_PROGRESS]: () => jsonResponse(progress()),
    'PUT /api/users/me/progress/movies/7': noContentResponse,
    ...extra,
  });
  const user = userEvent.setup();
  renderWithProviders(
    <Routes>
      <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
      <Route path="/peliculas/:id" element={<Link to="/ver/peliculas/7">Ver ahora</Link>} />
    </Routes>,
    { session: true, route },
  );
  return user;
}

/** Espera al `<video>` y lo convierte en uno simulado. */
async function playerVideo(options?: Parameters<typeof fakeVideo>[1]) {
  await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
  const element = document.querySelector('video');
  if (!element) throw new Error('No se ha pintado el <video>');
  return fakeVideo(element, options);
}

describe('WatchMoviePage: empezar y reanudar', () => {
  it('reanuda donde se dejó, lo dice y deja volver al principio con un botón', async () => {
    const user = renderMoviePlayer();
    const video = await playerVideo();
    expect(video.element).toHaveAttribute('src', 'https://cdn.example/interstellar.mp4');
    expect(video.element).toHaveAttribute('playsinline');
    expect(video.element).toHaveAttribute('controls');

    video.loadMetadata(8160);

    expect(video.state.currentTime).toBe(754);
    expect(video.state.paused).toBe(false);
    expect(screen.getByText('Sigues por 12:34.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Empezar desde el principio' }));
    expect(video.state.currentTime).toBe(0);
    expect(screen.queryByText('Sigues por 12:34.')).not.toBeInTheDocument();
  });

  it.each([
    ['sin progreso (204)', () => noContentResponse()],
    ['terminada', () => jsonResponse(progress({ positionSeconds: 8100, finished: true }))],
  ])('%s: empieza desde el principio, sin «Sigues por»', async (_name, response) => {
    renderMoviePlayer({ [MOVIE_PROGRESS]: response });
    const video = await playerVideo();
    video.loadMetadata(8160);

    expect(video.state.currentTime).toBe(0);
    expect(screen.queryByText(/Sigues por/)).not.toBeInTheDocument();
  });

  it('si el servidor del vídeo no deja saltar (sin `Range`), empieza desde el principio y NO dice «Sigues por»', async () => {
    renderMoviePlayer();
    const video = await playerVideo({ seekable: false });
    video.loadMetadata(8160);

    expect(video.state.currentTime).toBe(0);
    expect(screen.queryByText(/Sigues por/)).not.toBeInTheDocument();
  });

  it('abierto sin ningún gesto en la visita (dirección pegada), no se reproduce solo: espera en pausa en su sitio', async () => {
    // jsdom no tiene `navigator.userActivation`: se pone solo en este test.
    Object.defineProperty(navigator, 'userActivation', { configurable: true, value: { hasBeenActive: false } });
    try {
      renderMoviePlayer();
      const video = await playerVideo();
      video.loadMetadata(8160);

      expect(video.state.paused).toBe(true);
      expect(video.state.currentTime).toBe(754);
    } finally {
      Reflect.deleteProperty(navigator, 'userActivation');
    }
  });

  it('si no se puede saber por dónde iba, no bloquea el vídeo: empieza desde el principio y lo dice', async () => {
    renderMoviePlayer({ [MOVIE_PROGRESS]: () => errorResponse(500, 'INTERNAL_ERROR', 'Fallo.') });
    const video = await playerVideo();
    video.loadMetadata(8160);

    expect(video.state.currentTime).toBe(0);
    expect(screen.getByText(PROGRESS_UNAVAILABLE)).toBeInTheDocument();
  });
});

/** Aviso del pie cuando no se pudo leer el progreso (QA-3: dice también que esta vez no se guarda). */
const PROGRESS_UNAVAILABLE =
  'No se ha podido recuperar por dónde ibas: el vídeo empieza desde el principio y, para no borrar lo que tenías guardado, esta vez no se guardará por dónde vas.';

/** `PUT` de progreso enviados hasta ahora. */
function progressPuts() {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');
}

describe('WatchMoviePage: sin poder leer el progreso, no se escribe encima (QA-3)', () => {
  afterEach(() => {
    focusManager.setFocused(undefined);
  });

  it('no guarda nada: ni mientras se ve, ni al pausar, ni al salir (el progreso bueno del servidor queda intacto)', async () => {
    const user = renderMoviePlayer({ [MOVIE_PROGRESS]: () => errorResponse(500, 'INTERNAL_ERROR', 'Fallo.') });
    const video = await playerVideo();
    video.loadMetadata(8160);
    video.play(20);
    video.pause();

    await user.click(screen.getByRole('button', { name: 'Volver' }));

    expect(await screen.findByText('ruta:/peliculas/7')).toBeInTheDocument();
    expect(progressPuts()).toEqual([]);
  });

  it('si la lectura acierta después (al volver a la pestaña), el vídeo sigue montado, el aviso sigue siendo verdad y sigue sin guardar', async () => {
    const queryClient = createTestQueryClient();
    // La app real repite las consultas al volver a la pestaña (el cliente de los tests lo desactiva por defecto).
    queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, refetchOnWindowFocus: true } });
    let serverUp = false;
    routeFetch(fetchMock, {
      'GET /api/movies/7': () => jsonResponse(movie),
      [MOVIE_PROGRESS]: () =>
        serverUp ? jsonResponse(progress({ positionSeconds: 4800 })) : errorResponse(500, 'INTERNAL_ERROR', 'Fallo.'),
      'PUT /api/users/me/progress/movies/7': noContentResponse,
    });
    renderWithProviders(
      <Routes>
        <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
      </Routes>,
      { session: true, route: '/ver/peliculas/7', queryClient },
    );
    const video = await playerVideo();
    video.loadMetadata(8160);
    video.play(12);

    serverUp = true;
    await act(async () => {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    });
    await act(async () => {});

    // La consulta ya tiene el progreso bueno...
    expect(queryClient.getQueryData(queryKeys.progress.movie(7))).toMatchObject({ positionSeconds: 4800 });
    // ...pero el reproductor no se ha desmontado ni ha cambiado lo decidido al abrirlo.
    expect(document.querySelector('video')).toBe(video.element);
    expect(screen.getByText(PROGRESS_UNAVAILABLE)).toBeInTheDocument();
    video.play(20);
    video.pause();
    expect(progressPuts()).toEqual([]);
  });
});

describe('WatchMoviePage: salir', () => {
  it('Escape cierra el reproductor; sin pantalla anterior en la visita, a la página de la película', async () => {
    renderMoviePlayer();
    await playerVideo();

    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(await screen.findByText('ruta:/peliculas/7')).toBeInTheDocument();
  });

  it('Escape con el foco en los controles del vídeo es del vídeo (cierra sus menús): no cierra el reproductor', async () => {
    renderMoviePlayer();
    const video = await playerVideo();

    fireEvent.keyDown(video.element, { key: 'Escape' });

    expect(screen.getByText('ruta:/ver/peliculas/7')).toBeInTheDocument();
  });

  it('«Volver» vuelve a la pantalla de antes aunque no sea la del título (como «Atrás»: p. ej. la portada)', async () => {
    routeFetch(fetchMock, {
      'GET /api/movies/7': () => jsonResponse(movie),
      [MOVIE_PROGRESS]: noContentResponse,
    });
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
        <Route path="/" element={<Link to="/ver/peliculas/7">Seguir viendo Interstellar</Link>} />
      </Routes>,
      { session: true, route: '/' },
    );
    await user.click(screen.getByRole('link', { name: 'Seguir viendo Interstellar' }));
    await playerVideo();

    await user.click(screen.getByRole('button', { name: 'Volver' }));

    expect(await screen.findByText('ruta:/')).toBeInTheDocument();
  });

  it('«Volver» vuelve a la pantalla de antes y, al salir, guarda por dónde iba', async () => {
    const user = renderMoviePlayer({}, '/peliculas/7');
    await user.click(screen.getByRole('link', { name: 'Ver ahora' }));
    const video = await playerVideo();
    video.loadMetadata(8160);
    video.play(12);

    await user.click(screen.getByRole('button', { name: 'Volver' }));

    expect(await screen.findByText('ruta:/peliculas/7')).toBeInTheDocument();
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT');
    expect(JSON.parse(String(put?.[1]?.body))).toEqual({ positionSeconds: 766, durationSeconds: 8160 });
    expect(put?.[1]?.keepalive).toBe(true);
  });
});

describe('WatchMoviePage: el vídeo no carga', () => {
  /** El aviso de error del vídeo (la zona de avisos también es un `alert`: se busca por su título). */
  async function videoAlert(): Promise<HTMLElement> {
    const heading = await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' });
    const alert = heading.closest<HTMLElement>('[role="alert"]');
    if (!alert) throw new Error('El aviso no es un alert');
    return alert;
  }

  /** Simula un fallo del `<video>` con el código de `MediaError` dado. */
  function failVideo(element: HTMLVideoElement, code: number) {
    Object.defineProperty(element, 'error', { configurable: true, value: { code } });
    act(() => {
      element.dispatchEvent(new Event('error'));
    });
  }

  it('usuario: dice qué ha pasado, con «Reintentar» y la vuelta a la película (sin «Editar»)', async () => {
    const user = renderMoviePlayer();
    const video = await playerVideo();
    failVideo(video.element, 4);

    const alert = await videoAlert();
    // El foco estaba en el vídeo (o en ningún sitio) y el vídeo ya no está: pasa al título del aviso.
    expect(within(alert).getByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toHaveFocus();
    expect(alert).toHaveTextContent('No se ha podido abrir el archivo de vídeo');
    expect(alert).not.toHaveTextContent('Editar');
    expect(within(alert).getByRole('link', { name: 'Volver a Interstellar' })).toHaveAttribute('href', '/peliculas/7');

    await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));
    expect(document.querySelector('video')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No se pudo reproducir el vídeo' })).not.toBeInTheDocument();
  });

  it('administrador: además, dónde arreglarlo («Editar»)', async () => {
    renderMoviePlayer(AS_ADMIN);
    const video = await playerVideo();
    // Se espera al rol: mientras carga, el texto es el de un usuario (falla cerrado).
    await act(async () => {});
    failVideo(video.element, 2);

    const alert = await videoAlert();
    expect(alert).toHaveTextContent('Se ha cortado la conexión');
    expect(await within(alert).findByRole('link', { name: 'Editar Interstellar' })).toHaveAttribute(
      'href',
      '/admin/peliculas/7/editar',
    );
    expect(alert).toHaveTextContent('Comprueba la dirección del vídeo desde «Editar».');
  });
});

describe('WatchMoviePage: sin vídeo que se vea aquí', () => {
  it('película no encontrada: lo dice con un <h1> y una salida', async () => {
    renderMoviePlayer({ 'GET /api/movies/7': () => errorResponse(404, 'MOVIE_NOT_FOUND', 'No existe.') });

    expect(await screen.findByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver todas las películas' })).toHaveAttribute('href', '/peliculas');
  });

  it('vídeo de otra web (no es un archivo): se dice y se ofrece abrirlo en otra pestaña', async () => {
    renderMoviePlayer({
      'GET /api/movies/7': () => jsonResponse({ ...movie, videoUrl: 'https://vimeo.com/123' }),
    });

    expect(await screen.findByRole('heading', { name: 'Este vídeo se ve fuera de StreamBox' })).toBeInTheDocument();
    const open = screen.getByRole('link', { name: 'Abrir el vídeo Interstellar (se abre en una pestaña nueva)' });
    expect(open).toHaveAttribute('href', 'https://vimeo.com/123');
    expect(document.querySelector('video')).not.toBeInTheDocument();
    // Sin reproductor no se pregunta por el progreso.
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/progress/'))).toBe(false);
  });

  it('archivo de vídeo por `http://` (QA-2): no va al <video>, que la CSP bloquearía; se abre en otra pestaña', async () => {
    renderMoviePlayer({
      'GET /api/movies/7': () => jsonResponse({ ...movie, videoUrl: 'http://cdn.example/interstellar.mp4' }),
    });

    expect(await screen.findByRole('heading', { name: 'Este vídeo se ve fuera de StreamBox' })).toBeInTheDocument();
    const open = screen.getByRole('link', { name: 'Abrir el vídeo Interstellar (se abre en una pestaña nueva)' });
    expect(open).toHaveAttribute('href', 'http://cdn.example/interstellar.mp4');
    expect(open).toHaveAttribute('target', '_blank');
    expect(document.querySelector('video')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/progress/'))).toBe(false);
  });

  it.each([
    ['usuario', {}, '«Interstellar» todavía no está disponible para ver.'],
    ['administrador', AS_ADMIN, '«Interstellar» todavía no tiene vídeo. Puedes añadirlo desde «Editar».'],
  ])('sin vídeo (%s): el texto es verdad para su rol', async (_role, routes, text) => {
    renderMoviePlayer({ ...routes, 'GET /api/movies/7': () => jsonResponse({ ...movie, videoUrl: null }) });

    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a Interstellar' })).toBeInTheDocument();
  });
});

describe('WatchMoviePage: al terminar', () => {
  it('lo dice y ofrece volver a la película', async () => {
    renderMoviePlayer({ [MOVIE_PROGRESS]: noContentResponse });
    const video = await playerVideo();
    video.loadMetadata(30);
    video.play(15);
    video.end();

    expect(await screen.findByText('Has terminado «Interstellar».')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a Interstellar' })).toBeInTheDocument();
  });
});

describe('WatchEpisodePage', () => {
  const e1 = makeEpisode({ id: 41, seasonNumber: 1, episodeNumber: 2, title: 'Mentiras', videoUrl: 'https://cdn.example/dark-1x02.webm' });
  const e2 = makeEpisode({ id: 42, seasonNumber: 1, episodeNumber: 3, title: 'Pasado y presente', videoUrl: 'https://cdn.example/dark-1x03.webm' });
  const series = makeSeriesDetail({ id: 3, title: 'Dark', seasons: [{ seasonNumber: 1, episodes: [e1, e2] }] });

  function renderEpisodePlayer(route: string) {
    routeFetch(fetchMock, {
      'GET /api/series/3': () => jsonResponse(series),
      'GET /api/users/me/progress/episodes/41': noContentResponse,
      'GET /api/users/me/progress/episodes/42': noContentResponse,
    });
    renderWithProviders(
      <Routes>
        <Route path="/ver/series/:seriesId/episodios/:episodeId" element={<WatchEpisodePage />} />
      </Routes>,
      { session: true, route },
    );
  }

  it('la cabecera dice la serie y el episodio; al terminar ofrece el siguiente, en el reproductor', async () => {
    renderEpisodePlayer('/ver/series/3/episodios/41');
    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    expect(screen.getByText('T1:E2 · Mentiras')).toBeInTheDocument();
    const video = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    video.loadMetadata(30);
    video.end();

    expect(await screen.findByText('Has terminado T1:E2 · Mentiras.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver el siguiente: T1:E3 · Pasado y presente' })).toHaveAttribute(
      'href',
      '/ver/series/3/episodios/42',
    );
  });

  it('«Ver el siguiente» sustituye al episodio terminado: después, «Volver» lleva a la serie y no al episodio anterior', async () => {
    routeFetch(fetchMock, {
      'GET /api/series/3': () => jsonResponse(series),
      'GET /api/users/me/progress/episodes/41': noContentResponse,
      'GET /api/users/me/progress/episodes/42': noContentResponse,
    });
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/ver/series/:seriesId/episodios/:episodeId" element={<WatchEpisodePage />} />
        <Route path="/series/:id" element={<Link to="/ver/series/3/episodios/41">Empezar a ver</Link>} />
      </Routes>,
      { session: true, route: '/series/3' },
    );
    await user.click(screen.getByRole('link', { name: 'Empezar a ver' }));
    await screen.findByText('T1:E2 · Mentiras');
    const first = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    first.loadMetadata(30);
    first.end();
    await user.click(await screen.findByRole('link', { name: 'Ver el siguiente: T1:E3 · Pasado y presente' }));
    await screen.findByText('T1:E3 · Pasado y presente');

    await user.click(screen.getByRole('button', { name: 'Volver' }));

    expect(await screen.findByText('ruta:/series/3')).toBeInTheDocument();
  });

  it('en el último episodio, lo dice y lleva a la serie', async () => {
    renderEpisodePlayer('/ver/series/3/episodios/42');
    await screen.findByRole('heading', { level: 1, name: 'Dark' });
    const video = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    video.loadMetadata(30);
    video.end();

    expect(await screen.findByText('Has visto el último episodio de «Dark».')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a Dark' })).toHaveAttribute('href', '/series/3');
  });

  it('un episodio que no es de la serie: «Episodio no encontrado», con sus episodios como salida', async () => {
    renderEpisodePlayer('/ver/series/3/episodios/999');

    expect(await screen.findByRole('heading', { level: 1, name: 'Episodio no encontrado' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver los episodios de Dark' })).toHaveAttribute('href', '/series/3');
  });
});
