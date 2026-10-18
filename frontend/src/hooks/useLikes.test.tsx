/**
 * Tests del estado de `useLikes` (sin pasar por el botón): lo que no se ve
 * pulsando una sola vez.
 *
 * Protegen: que una respuesta que llega con la sesión ya cerrada no toca nada
 * de la sesión nueva (ni avisa, ni cambia sus listas, ni descarta sus
 * recomendaciones); que con dos títulos en vuelo un fallo deshace SOLO el suyo;
 * que un refresco de la lista a mitad de la petición no devuelve el estado de
 * antes del clic; que al fallar un «quitar» el título vuelve a su posición; y
 * que un 429 se deshace y dice cuándo reintentar. Los casos de un solo clic
 * (optimismo, bloqueo, 404, recomendaciones descartadas) están en
 * `components/LikeButton.test.tsx`.
 */
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { queryKeys } from '../lib/queryKeys';
import {
  LIKED_MOVIES,
  LIKED_SERIES,
  errorResponse,
  jsonResponse,
  makeMovie,
  makeRecommendations,
  makeSeries,
  makeUser,
  noContentResponse,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { likedMessage, useLikes } from './useLikes';

const fetchMock = vi.fn<typeof fetch>();
let queryClient = createTestQueryClient();

const m1 = makeMovie({ id: 1, title: 'Uno' });
const m2 = makeMovie({ id: 2, title: 'Dos' });
const m3 = makeMovie({ id: 3, title: 'Tres' });
const s5 = makeSeries({ id: 5, title: 'Serie Cinco' });

/** Promesa controlable a mano para observar el estado «petición en curso». */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  queryClient = createTestQueryClient();
});

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthProvider>{children}</AuthProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}

/** Monta el hook (y `useAuth`, para cerrar sesión) con las listas indicadas y espera a que carguen. */
async function renderLoaded(
  { movies = [], series = [] }: { movies?: (typeof m1)[]; series?: (typeof s5)[] } = {},
  extra: Parameters<typeof routeFetch>[1] = {},
) {
  routeFetch(fetchMock, {
    'GET /api/users/me': () => jsonResponse(makeUser()),
    [LIKED_MOVIES]: () => jsonResponse(movies),
    [LIKED_SERIES]: () => jsonResponse(series),
    ...extra,
  });
  const hook = renderHook(() => ({ likes: useLikes(), auth: useAuth() }), { wrapper });
  await waitFor(() => expect(hook.result.current.likes.status).toBe('ready'));
  await waitFor(() => expect(hook.result.current.auth.user).not.toBeNull());
  return hook;
}

const ids = (list: { id: number }[]) => list.map((item) => item.id);

describe('useLikes: sesión', () => {
  it('si el PUT responde con la sesión ya cerrada, no avisa ni toca nada de la sesión nueva', async () => {
    const put = deferred<Response>();
    const { result } = await renderLoaded({}, {
      'PUT /api/users/me/likes/movies/2': () => put.promise,
      'POST /api/auth/logout': () => noContentResponse(),
    });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.likes.toggle('movie', m2);
    });
    expect(result.current.likes.isLiked(2, 'movie')).toBe(true);
    await act(async () => {
      await result.current.auth.logout();
    });
    // La sesión nueva ya tiene sus recomendaciones en la caché.
    const recommendations = queryKeys.recommendations.list(12);
    queryClient.setQueryData(recommendations, makeRecommendations({ basedOnLikes: true, movies: [m3] }));

    await act(async () => {
      put.resolve(noContentResponse());
      await toggling;
    });

    expect(screen.queryByText(likedMessage('Dos'))).not.toBeInTheDocument();
    expect(queryClient.getQueryData(recommendations)).toEqual(
      makeRecommendations({ basedOnLikes: true, movies: [m3] }),
    );
    await waitFor(() => expect(result.current.likes.status).toBe('ready'));
    expect(result.current.likes.isLiked(2, 'movie')).toBe(false);
    expect(result.current.likes.isPending(2, 'movie')).toBe(false);
  });
});

describe('useLikes: varios cambios', () => {
  it('con dos títulos en vuelo, si falla uno solo se deshace ese', async () => {
    const moviePut = deferred<Response>();
    const { result } = await renderLoaded({ movies: [m1] }, {
      'PUT /api/users/me/likes/movies/2': () => moviePut.promise,
      'PUT /api/users/me/likes/series/5': () => noContentResponse(),
    });

    let movieToggle!: Promise<void>;
    await act(async () => {
      movieToggle = result.current.likes.toggle('movie', m2);
    });
    await act(() => result.current.likes.toggle('series', s5));
    expect(result.current.likes.isLiked(5, 'series')).toBe(true);

    await act(async () => {
      moviePut.resolve(errorResponse(500, 'INTERNAL_ERROR', 'boom'));
      await movieToggle;
    });

    expect(ids(result.current.likes.movies)).toEqual([1]);
    expect(ids(result.current.likes.series)).toEqual([5]);
    expect(result.current.likes.isPending(2, 'movie')).toBe(false);
  });

  it('un refresco de la lista a mitad del PUT no devuelve el estado de antes del clic', async () => {
    const put = deferred<Response>();
    const { result } = await renderLoaded({ movies: [m1] }, {
      'PUT /api/users/me/likes/movies/2': () => put.promise,
    });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.likes.toggle('movie', m2);
    });
    // Otra parte de la aplicación pide la lista mientras tanto: el servidor aún no tiene el cambio.
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.likes.all });
    });

    await act(async () => {
      put.resolve(noContentResponse());
      await toggling;
    });

    expect(result.current.likes.isLiked(2, 'movie')).toBe(true);
    expect(ids(result.current.likes.movies)).toEqual([2, 1]);
  });

  it('si quitar falla, el título vuelve a su posición original', async () => {
    const { result } = await renderLoaded({ movies: [m1, m2, m3] }, {
      'DELETE /api/users/me/likes/movies/2': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await act(() => result.current.likes.toggle('movie', m2));

    expect(ids(result.current.likes.movies)).toEqual([1, 2, 3]);
    expect(await screen.findByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
  });

  it('un 429 al dar «Me gusta» se deshace y dice cuándo reintentar', async () => {
    const { result } = await renderLoaded({}, {
      'PUT /api/users/me/likes/movies/2': () =>
        errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'Demasiadas peticiones', {}, { 'Retry-After': '30' }),
    });

    await act(() => result.current.likes.toggle('movie', m2));

    expect(result.current.likes.isLiked(2, 'movie')).toBe(false);
    expect(await screen.findByText('Demasiados intentos. Inténtalo de nuevo en 30 s.')).toBeInTheDocument();
  });
});
