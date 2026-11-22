/**
 * Revisión independiente (QA) del reproductor integrado: casos que
 * `WatchPage.test.tsx` no cubre.
 *
 * - **Salir tras «Ver el siguiente» habiendo abierto el enlace directamente**
 *   (QA-1, ya corregido con `InAppHistoryProvider`: ver la descripción del test).
 * - Reanudar en un archivo más corto que la posición guardada (el vídeo se ha
 *   sustituido): empieza por el principio y no dice «Sigues por».
 * - «Reintentar» tras un fallo del vídeo sigue por donde iba (no por donde
 *   se reanudó al abrir) y el progreso se sigue guardando con el `<video>` nuevo.
 * - Una URL de vídeo insegura cuenta como «sin vídeo»: no llega al `<video>`
 *   ni se pregunta por el progreso.
 */
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WatchProgress } from '../lib/types';
import { fakeVideo } from '../test/fakeVideo';
import {
  jsonResponse,
  makeEpisode,
  makeMovie,
  makeSeriesDetail,
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

function progress(overrides: Partial<WatchProgress> = {}): WatchProgress {
  return { positionSeconds: 754, durationSeconds: 8160, finished: false, updatedAt: '2026-11-21T10:00:00Z', ...overrides };
}

function renderMoviePlayer(routes: Parameters<typeof routeFetch>[1] = {}) {
  routeFetch(fetchMock, {
    'GET /api/movies/7': () => jsonResponse(movie),
    'GET /api/users/me/progress/movies/7': () => jsonResponse(progress()),
    'PUT /api/users/me/progress/movies/7': noContentResponse,
    ...routes,
  });
  const user = userEvent.setup();
  renderWithProviders(
    <Routes>
      <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
    </Routes>,
    { session: true, route: '/ver/peliculas/7' },
  );
  return user;
}

/** Cuerpos de los `PUT` de progreso enviados hasta ahora. */
function savedBodies(): unknown[] {
  return fetchMock.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)));
}

describe('WatchEpisodePage (QA): salir tras «Ver el siguiente»', () => {
  const e1 = makeEpisode({ id: 41, seasonNumber: 1, episodeNumber: 2, title: 'Mentiras', videoUrl: 'https://cdn.example/dark-1x02.webm' });
  const e2 = makeEpisode({ id: 42, seasonNumber: 1, episodeNumber: 3, title: 'Pasado y presente', videoUrl: 'https://cdn.example/dark-1x03.webm' });
  const series = makeSeriesDetail({ id: 3, title: 'Dark', seasons: [{ seasonNumber: 1, episodes: [e1, e2] }] });

  /**
   * QA-1 (reproductor-2026-11, corregido): si el episodio se abrió
   * directamente (enlace pegado o recarga: no hay pantalla anterior en la
   * visita) y se pulsa «Ver el siguiente», «Volver» debe llevar a la serie.
   *
   * El fallo: `usePlayerClose` (`components/player/PlayerShell.tsx`) decidía si
   * había pantalla anterior con `location.key !== 'default'`, pero la navegación
   * con `replace` de «Ver el siguiente» da a la entrada una clave nueva aunque
   * siga siendo la primera de la visita, así que «Volver» (y Escape) hacían
   * `navigate(-1)`: en el navegador, salir de StreamBox. Ahora lo decide
   * `InAppHistoryProvider`, que cuenta las pantallas propias de cada entrada.
   * Este test nació como `it.fails` y pasó a `it` con el arreglo.
   */
  it('abierto directamente: tras «Ver el siguiente», «Volver» lleva a la serie', async () => {
    routeFetch(fetchMock, {
      'GET /api/series/3': () => jsonResponse(series),
      'GET /api/users/me/progress/episodes/41': noContentResponse,
      'GET /api/users/me/progress/episodes/42': noContentResponse,
    });
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/ver/series/:seriesId/episodios/:episodeId" element={<WatchEpisodePage />} />
        <Route path="/series/:id" element={<p>Página de la serie</p>} />
      </Routes>,
      { session: true, route: '/ver/series/3/episodios/41' },
    );
    await screen.findByText('T1:E2 · Mentiras');
    const first = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    first.loadMetadata(30);
    first.end();
    await user.click(await screen.findByRole('link', { name: 'Ver el siguiente: T1:E3 · Pasado y presente' }));
    await screen.findByText('T1:E3 · Pasado y presente');

    await user.click(screen.getByRole('button', { name: 'Volver' }));

    expect(await screen.findByText('ruta:/series/3', {}, { timeout: 500 })).toBeInTheDocument();
  });
});

describe('WatchMoviePage (QA): reanudar y reintentar', () => {
  it('si el archivo es más corto que la posición guardada (vídeo sustituido), empieza por el principio sin «Sigues por»', async () => {
    renderMoviePlayer();
    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    const video = fakeVideo(document.querySelector('video') as HTMLVideoElement);

    video.loadMetadata(600);

    expect(video.state.currentTime).toBe(0);
    expect(screen.queryByText(/Sigues por/)).not.toBeInTheDocument();
  });

  it('una posición guardada de menos de 5 s no se «reanuda»: se empieza por el principio sin el aviso', async () => {
    renderMoviePlayer({ 'GET /api/users/me/progress/movies/7': () => jsonResponse(progress({ positionSeconds: 3 })) });
    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    const video = fakeVideo(document.querySelector('video') as HTMLVideoElement);

    video.loadMetadata(8160);

    expect(video.state.currentTime).toBe(0);
    expect(screen.queryByText(/Sigues por/)).not.toBeInTheDocument();
  });

  it('«Reintentar» tras un corte sigue por donde iba (no por donde se reanudó) y sigue guardando con el vídeo nuevo', async () => {
    const user = renderMoviePlayer();
    await screen.findByRole('heading', { level: 1, name: 'Interstellar' });
    const first = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    first.loadMetadata(8160);
    first.play(20);
    const reachedSecond = Math.floor(first.state.currentTime);

    Object.defineProperty(first.element, 'error', { configurable: true, value: { code: 2 } });
    act(() => {
      first.element.dispatchEvent(new Event('error'));
    });
    const alert = (await screen.findByRole('heading', { name: 'No se pudo reproducir el vídeo' })).closest('[role="alert"]');
    // Al desaparecer el <video> por el error se guarda por dónde iba (la despedida del guardado).
    expect(savedBodies()).toEqual([{ positionSeconds: reachedSecond, durationSeconds: 8160 }]);

    await user.click(within(alert as HTMLElement).getByRole('button', { name: 'Reintentar' }));
    const second = fakeVideo(document.querySelector('video') as HTMLVideoElement);
    expect(second.element).not.toBe(first.element);
    second.loadMetadata(8160);

    expect(second.state.currentTime).toBeCloseTo(first.state.currentTime, 5);
    second.play(12);
    second.pause();
    expect(savedBodies().at(-1)).toEqual({ positionSeconds: Math.floor(second.state.currentTime), durationSeconds: 8160 });
  });
});

describe('WatchMoviePage (QA): URL de vídeo insegura', () => {
  it.each([['javascript:alert(1)//.mp4'], ['https://usuario:clave@cdn.example/peli.mp4']])(
    '%s cuenta como «sin vídeo»: no hay <video> ni se pide el progreso',
    async (videoUrl) => {
      renderMoviePlayer({ 'GET /api/movies/7': () => jsonResponse({ ...movie, videoUrl }) });

      expect(await screen.findByText('Todavía no hay vídeo')).toBeInTheDocument();
      expect(document.querySelector('video')).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /Abrir el vídeo/ })).not.toBeInTheDocument();
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/progress/'))).toBe(false);
    },
  );
});
