/**
 * Revisión independiente (QA) de `useProgressSaver`: casos que
 * `useProgressSaver.test.tsx` no cubre.
 *
 * - **Cambio de sesión a mitad del vídeo** (otra persona entra en otra
 *   pestaña): no se guarda nada más, tampoco al cerrar, y una respuesta que
 *   llega después no escribe en la caché de la sesión nueva. Se simula
 *   sustituyendo `currentSessionKey` de `lib/api` (el resto del módulo es el real).
 * - Los demás fallos definitivos de la lista (400, 403) también paran el
 *   guardado; un 500 o un 429 no.
 * - Un vídeo sin duración finita (directo, o aún sin metadatos) no guarda nada.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../lib/queryKeys';
import { fakeVideo } from '../test/fakeVideo';
import type { FakeVideo } from '../test/fakeVideo';
import { errorResponse, noContentResponse, routeFetch } from '../test/helpers';
import { createTestQueryClient, queryWrapper } from '../test/queryClient';
import { useProgressSaver } from './useProgressSaver';

/** Clave de sesión que devuelve el `currentSessionKey` simulado; cada test la cambia cuando quiere. */
let sessionKey = 1;

vi.mock('../lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/api')>();
  return { ...actual, currentSessionKey: () => sessionKey };
});

const MOVIE_PUT = 'PUT /api/users/me/progress/movies/7';

const fetchMock = vi.fn<typeof fetch>();
let queryClient = createTestQueryClient();
let video: FakeVideo;

beforeEach(() => {
  sessionKey = 1;
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, { [MOVIE_PUT]: noContentResponse });
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  queryClient = createTestQueryClient();
  video = fakeVideo();
  video.loadMetadata(120.4);
});

afterEach(() => {
  vi.useRealTimers();
});

function renderSaver() {
  return renderHook(() => useProgressSaver(video.element, { kind: 'movie', id: 7 }), {
    wrapper: queryWrapper(queryClient),
  });
}

function putCount(): number {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT').length;
}

describe('useProgressSaver (QA): cambio de sesión', () => {
  it('si entra otra persona a mitad del vídeo, no se guarda nada más (ni al pausar ni al cerrar)', () => {
    const { unmount } = renderSaver();
    video.play(12);

    sessionKey = 2;
    video.pause();
    video.play(12);
    act(() => {
      window.dispatchEvent(new Event('pagehide'));
    });
    unmount();

    expect(putCount()).toBe(0);
  });

  it('una respuesta que llega tras el cambio de sesión no escribe en la caché de la sesión nueva', async () => {
    let answer: (response: Response) => void = () => {};
    routeFetch(fetchMock, { [MOVIE_PUT]: () => new Promise<Response>((resolve) => (answer = resolve)) });
    renderSaver();
    video.play(12);
    video.pause();
    await vi.waitFor(() => expect(putCount()).toBe(1));

    sessionKey = 2;
    await act(async () => {
      answer(noContentResponse());
    });

    expect(queryClient.getQueryData(queryKeys.progress.movie(7))).toBeUndefined();
  });
});

describe('useProgressSaver (QA): fallos', () => {
  it.each([
    ['400 (la API rechaza los datos)', 400, 'VALIDATION_ERROR'],
    ['403 (sin permiso)', 403, 'ACCESS_DENIED'],
  ])('%s: deja de guardar', async (_name, status, code) => {
    routeFetch(fetchMock, { [MOVIE_PUT]: () => errorResponse(status, code, 'No.') });
    const { unmount } = renderSaver();
    video.play(12);
    video.pause();
    await vi.waitFor(() => expect(putCount()).toBe(1));
    await act(async () => {});

    video.play(12);
    video.pause();
    unmount();

    expect(putCount()).toBe(1);
  });

  it.each([
    ['500', 500, 'INTERNAL_ERROR'],
    ['429', 429, 'RATE_LIMIT_EXCEEDED'],
  ])('%s es pasajero: el siguiente guardado se vuelve a intentar', async (_name, status, code) => {
    routeFetch(fetchMock, { [MOVIE_PUT]: () => errorResponse(status, code, 'Luego.') });
    renderSaver();
    video.play(12);
    video.pause();
    await vi.waitFor(() => expect(putCount()).toBe(1));
    await act(async () => {});

    video.play(12);
    video.pause();

    await vi.waitFor(() => expect(putCount()).toBe(2));
  });
});

describe('useProgressSaver (QA): sin duración finita', () => {
  it('un vídeo en directo (duración infinita) no guarda nada aunque se vea más de 10 s', () => {
    video = fakeVideo();
    video.state.duration = Number.POSITIVE_INFINITY;
    const { unmount } = renderSaver();
    video.play(30);
    video.pause();
    unmount();

    expect(putCount()).toBe(0);
  });
});
