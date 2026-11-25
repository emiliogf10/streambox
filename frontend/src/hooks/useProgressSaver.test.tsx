/**
 * Tests de `useProgressSaver`: cuándo se guarda el progreso, qué se envía y qué
 * pasa con la caché y con los fallos.
 *
 * Se usa un `<video>` simulado (`test/fakeVideo.ts`) que lanza los mismos
 * eventos que el de un navegador, y un reloj falso solo para `setInterval` (el
 * guardado periódico): el resto (promesas, `fetch` simulado) va con el reloj real.
 * Por eso se espera con `vi.waitFor` y no con el `waitFor` de Testing Library:
 * este sondea con `setInterval`, que aquí es falso, y no volvería a mirar.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../lib/queryKeys';
import type { ContinueWatchingItem, WatchProgress } from '../lib/types';
import { fakeVideo } from '../test/fakeVideo';
import type { FakeVideo } from '../test/fakeVideo';
import { errorResponse, noContentResponse, routeFetch } from '../test/helpers';
import { setPageVisibility } from '../test/pageVisibility';
import { createTestQueryClient, queryWrapper } from '../test/queryClient';
import { useProgressSaver } from './useProgressSaver';
import type { ProgressTarget } from './useProgressSaver';

const MOVIE_PUT = 'PUT /api/users/me/progress/movies/7';
const EPISODE_PUT = 'PUT /api/users/me/progress/episodes/41';

const fetchMock = vi.fn<typeof fetch>();
let queryClient = createTestQueryClient();
let video: FakeVideo;

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, { [MOVIE_PUT]: noContentResponse, [EPISODE_PUT]: noContentResponse });
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  queryClient = createTestQueryClient();
  video = fakeVideo();
  video.loadMetadata(120.4);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderSaver(target: ProgressTarget | null = { kind: 'movie', id: 7 }) {
  return renderHook(({ t }) => useProgressSaver(video.element, t), {
    wrapper: queryWrapper(queryClient),
    initialProps: { t: target },
  });
}

/** Cuerpos de los `PUT` de progreso enviados hasta ahora. */
function savedBodies(): unknown[] {
  return fetchMock.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)));
}

/** Opciones `fetch` del último `PUT`. */
function lastPutInit(): RequestInit | undefined {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').at(-1)?.[1];
}

describe('useProgressSaver: cuándo guarda', () => {
  it('no guarda nada antes de 10 s de reproducción real (un título abierto por error no va a «Seguir viendo»)', () => {
    renderSaver();
    video.play(9);
    video.pause();

    expect(savedBodies()).toEqual([]);
  });

  it('los saltos con la barra no cuentan como reproducción', () => {
    renderSaver();
    video.play(2);
    video.seek(100);
    video.play(2);
    video.pause();

    expect(savedBodies()).toEqual([]);
  });

  it('al pausar tras 10 s, guarda la posición y la duración en segundos enteros, con la cabecera anti-CSRF', async () => {
    renderSaver();
    video.play(12.5);
    video.pause();

    expect(savedBodies()).toEqual([{ positionSeconds: 12, durationSeconds: 120 }]);
    expect(new Headers(lastPutInit()?.headers).get('X-Requested-With')).toBe('StreamBox');
    await vi.waitFor(() => expect(queryClient.getQueryData(queryKeys.progress.movie(7))).toBeTruthy());
  });

  it('mientras se reproduce, guarda cada 15 s; en pausa, no repite el guardado', () => {
    renderSaver();
    video.play(11);
    act(() => vi.advanceTimersByTime(15_000));
    expect(savedBodies()).toEqual([{ positionSeconds: 11, durationSeconds: 120 }]);

    video.play(4);
    act(() => vi.advanceTimersByTime(15_000));
    expect(savedBodies()).toHaveLength(2);

    video.pause();
    act(() => vi.advanceTimersByTime(45_000));
    expect(savedBodies()).toEqual([
      { positionSeconds: 11, durationSeconds: 120 },
      { positionSeconds: 15, durationSeconds: 120 },
    ]);
  });

  it('al terminar envía posición = duración, una sola vez (el navegador lanza `pause` y `ended`)', () => {
    renderSaver();
    video.play(20);
    video.end();

    expect(savedBodies()).toEqual([{ positionSeconds: 120, durationSeconds: 120 }]);
  });

  it('al cerrar el reproductor (desmontar) guarda con `keepalive`: la petición sobrevive aunque se cierre la página', () => {
    const { unmount } = renderSaver();
    video.play(30);
    unmount();

    expect(savedBodies()).toEqual([{ positionSeconds: 30, durationSeconds: 120 }]);
    expect(lastPutInit()?.keepalive).toBe(true);
  });

  it('al ocultarse la pestaña y al salir de la página guarda con `keepalive`, sin repetir lo mismo', () => {
    renderSaver();
    video.play(30);
    setPageVisibility('hidden');
    act(() => {
      window.dispatchEvent(new Event('pagehide'));
    });

    expect(savedBodies()).toEqual([{ positionSeconds: 30, durationSeconds: 120 }]);
    expect(lastPutInit()?.keepalive).toBe(true);
  });

  it('al saltar con la barra guarda la posición nueva un segundo después del ÚLTIMO salto (arrastrar no envía uno por punto)', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] });
    renderSaver();
    video.play(12);
    video.seek(40);
    video.seek(55);
    video.seek(70);
    act(() => vi.advanceTimersByTime(999));
    expect(savedBodies()).toEqual([]);

    act(() => vi.advanceTimersByTime(1));
    expect(savedBodies()).toEqual([{ positionSeconds: 70, durationSeconds: 120 }]);
  });

  it('un salto antes de 10 s de reproducción real no guarda (ni el salto a la posición guardada al reanudar)', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] });
    renderSaver();
    video.seek(80);
    video.play(3);
    act(() => vi.advanceTimersByTime(5_000));

    expect(savedBodies()).toEqual([]);
  });

  it('sin título que guardar (vídeo externo), no se engancha a nada', () => {
    const { unmount } = renderSaver(null);
    video.play(30);
    video.pause();
    unmount();

    expect(savedBodies()).toEqual([]);
  });
});

describe('useProgressSaver: fallos', () => {
  it.each([
    ['409 NO_VIDEO_AVAILABLE (le han quitado el vídeo)', 409, 'NO_VIDEO_AVAILABLE'],
    ['404 (han borrado el título)', 404, 'RESOURCE_NOT_FOUND'],
  ])('%s: deja de guardar, sin avisos', async (_name, status, code) => {
    routeFetch(fetchMock, {
      [MOVIE_PUT]: () => errorResponse(status, code, 'No se puede guardar.'),
    });
    const { unmount } = renderSaver();
    video.play(12);
    video.pause();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});

    video.play(12);
    video.pause();
    unmount();
    expect(savedBodies()).toHaveLength(1);
  });

  it('un fallo de red no se avisa ni para el guardado: se reintenta en el siguiente', async () => {
    let fail = true;
    routeFetch(fetchMock, {
      [MOVIE_PUT]: () => {
        if (fail) throw new TypeError('Failed to fetch');
        return noContentResponse();
      },
    });
    renderSaver();
    video.play(12);
    video.pause();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});

    fail = false;
    // Misma posición que la fallida: se vuelve a enviar (el fallo no cuenta como «ya guardado»).
    act(() => {
      video.element.dispatchEvent(new Event('pause'));
    });
    expect(savedBodies()).toEqual([
      { positionSeconds: 12, durationSeconds: 120 },
      { positionSeconds: 12, durationSeconds: 120 },
    ]);
  });
});

describe('useProgressSaver: caché', () => {
  it('escribe el progreso del episodio y descarta «Seguir viendo» y el progreso de su serie', async () => {
    const continueWatching: ContinueWatchingItem[] = [];
    queryClient.setQueryData(queryKeys.progress.continueWatching(12), continueWatching);
    queryClient.setQueryData(queryKeys.progress.series(3), []);
    queryClient.setQueryData(queryKeys.progress.movie(7), null);

    renderSaver({ kind: 'episode', id: 41, seriesId: 3 });
    video.play(60);
    video.pause();

    await vi.waitFor(() =>
      expect(queryClient.getQueryData<WatchProgress>(queryKeys.progress.episode(41))).toMatchObject({
        positionSeconds: 60,
        durationSeconds: 120,
        finished: false,
      }),
    );
    await vi.waitFor(() =>
      expect(queryClient.getQueryState(queryKeys.progress.continueWatching(12))?.isInvalidated).toBe(true),
    );
    expect(queryClient.getQueryState(queryKeys.progress.series(3))?.isInvalidated).toBe(true);
    // Lo de otros títulos no se toca.
    expect(queryClient.getQueryState(queryKeys.progress.movie(7))?.isInvalidated).toBe(false);
  });

  it('descarta también el historial y las cifras de lo visto del perfil (el título sube al principio y suma tiempo)', async () => {
    queryClient.setQueryData(queryKeys.progress.history(12), []);
    queryClient.setQueryData(queryKeys.progress.stats, { finishedMovies: 0, finishedEpisodes: 0, watchedSeconds: 0 });

    renderSaver();
    video.play(30);
    video.pause();

    await vi.waitFor(() => expect(queryClient.getQueryState(queryKeys.progress.history(12))?.isInvalidated).toBe(true));
    expect(queryClient.getQueryState(queryKeys.progress.stats)?.isInvalidated).toBe(true);
  });

  it('al terminar, la caché lo da por visto (la página del título ya no dirá «Reanudar»)', async () => {
    renderSaver();
    video.play(20);
    video.end();

    await vi.waitFor(() =>
      expect(queryClient.getQueryData<WatchProgress>(queryKeys.progress.movie(7))).toMatchObject({
        finished: true,
        watched: true,
      }),
    );
  });

  it('volver a ver algo ya visto no lo desmarca: `finished` pasa a false (posición) pero `watched` sigue a true', async () => {
    queryClient.setQueryData<WatchProgress>(queryKeys.progress.movie(7), {
      positionSeconds: 120,
      durationSeconds: 120,
      finished: true,
      watched: true,
      updatedAt: '2026-11-20T10:00:00Z',
    });

    renderSaver();
    video.play(30);
    video.pause();

    await vi.waitFor(() =>
      expect(queryClient.getQueryData<WatchProgress>(queryKeys.progress.movie(7))).toMatchObject({
        positionSeconds: 30,
        finished: false,
        watched: true,
      }),
    );
  });

  it('a medias y sin haberlo terminado nunca: ni terminado ni visto', async () => {
    queryClient.setQueryData(queryKeys.progress.movie(7), null);

    renderSaver();
    video.play(30);
    video.pause();

    await vi.waitFor(() =>
      expect(queryClient.getQueryData<WatchProgress>(queryKeys.progress.movie(7))).toMatchObject({
        positionSeconds: 30,
        finished: false,
        watched: false,
      }),
    );
  });
});
