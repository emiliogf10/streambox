/**
 * Tests de `useMovieDetail` (detalle de una película) con `apiFetch` simulado.
 *
 * Protegen lo que la página no deja ver fácilmente:
 * - 404 es «no encontrada» (distinto de un error que se puede reintentar), también
 *   si llega al refrescar tras borrarla desde el panel, y un id mal formado no
 *   llega a pedir nada.
 * - Pintado inmediato con lo que ya hay en la caché de un listado o de «Mi lista»
 *   (`placeholderData`), SIN dejar de pedir la versión buena, y sin que esa copia
 *   provisional tape un 404.
 * - Al cambiar de película no se enseña ni un render la anterior.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import type { InfiniteData } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../lib/api';
import { keysAffectedBy, queryKeys } from '../lib/queryKeys';
import type { Movie, PageResponse } from '../lib/types';
import { makeMovie, makePage } from '../test/helpers';
import { createTestQueryClient, queryWrapper } from '../test/queryClient';
import { findCachedMovie, useMovieDetail } from './useMovieDetail';

// Se simula SOLO `apiFetch`; `ApiError`, `isAbortError`... siguen siendo los reales.
vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  apiFetch: vi.fn(),
}));

const apiFetch = vi.mocked(api.apiFetch);

const notFound = () => new api.ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', message: 'Película no encontrada' });

/** Clave de la portada (`usePagedCatalog` con sus valores por defecto). */
const homeKey = queryKeys.paged('/movies', { pageSize: 20, sort: 'createdAt', direction: 'desc' });

/** Guarda `movies` en la caché como lo haría un listado paginado (`useInfiniteQuery`). */
function cacheAsPagedList(client: ReturnType<typeof createTestQueryClient>, movies: Movie[]) {
  const data: InfiniteData<PageResponse<Movie>> = { pages: [makePage(movies)], pageParams: [0] };
  client.setQueryData(homeKey, data);
}

beforeEach(() => {
  apiFetch.mockReset();
});

describe('useMovieDetail: carga', () => {
  it('pide /movies/{id} y expone la película', async () => {
    const movie = makeMovie({ id: 3 });
    apiFetch.mockResolvedValueOnce(movie);

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper() });

    expect(result.current.status).toBe('loading');
    expect(apiFetch).toHaveBeenCalledWith('/movies/3', { signal: expect.any(AbortSignal) });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.movie).toBe(movie);
  });

  it('404 → "not-found" (no "error"); otro fallo → "error" con mensaje y reload vuelve a pedir', async () => {
    apiFetch.mockRejectedValueOnce(notFound());
    const missing = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper() });
    await waitFor(() => expect(missing.result.current.status).toBe('not-found'));

    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Servidor caído' }));
    const failing = renderHook(() => useMovieDetail('5'), { wrapper: queryWrapper() });
    await waitFor(() => expect(failing.result.current.status).toBe('error'));
    expect(failing.result.current.errorMessage).toBe('Servidor caído');

    let answer!: (movie: unknown) => void;
    apiFetch.mockReturnValueOnce(new Promise((resolve) => (answer = resolve)));
    await act(async () => failing.result.current.reload());
    expect(failing.result.current.status).toBe('loading');
    await act(async () => answer(makeMovie({ id: 5 })));
    expect(failing.result.current.status).toBe('ready');
  });

  it.each([['abc'], ['0'], ['1.5'], ['-1'], ['1e3'], [undefined]])('id mal formado (%j) → "not-found" sin petición', (raw) => {
    const { result } = renderHook(() => useMovieDetail(raw), { wrapper: queryWrapper() });

    expect(result.current.status).toBe('not-found');
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('al cambiar de película pasa a "cargando" en el mismo render, sin enseñar la anterior', async () => {
    apiFetch.mockResolvedValueOnce(makeMovie({ id: 3, title: 'Tres' }));
    const { result, rerender } = renderHook(({ id }) => useMovieDetail(id), {
      initialProps: { id: '3' },
      wrapper: queryWrapper(),
    });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    apiFetch.mockReturnValueOnce(new Promise(() => {}));

    rerender({ id: '4' });

    expect(result.current.status).toBe('loading');
    expect(result.current.movie).toBeNull();
    expect(apiFetch).toHaveBeenLastCalledWith('/movies/4', { signal: expect.any(AbortSignal) });
  });

  it('si la borran desde el panel mientras se ve, el refresco da 404 y pasa a "not-found" (sin enseñar la copia vieja)', async () => {
    const client = createTestQueryClient();
    apiFetch.mockResolvedValueOnce(makeMovie({ id: 3 }));
    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(result.current.status).toBe('ready'));

    apiFetch.mockRejectedValueOnce(notFound());
    // Lo mismo que hace el panel tras borrar (`useInvalidateCatalog('movies')`).
    await act(async () => {
      await Promise.all(keysAffectedBy('movies').map((queryKey) => client.invalidateQueries({ queryKey })));
    });

    await waitFor(() => expect(result.current.status).toBe('not-found'));
    expect(result.current.movie).toBeNull();
  });
});

describe('useMovieDetail: pintado inmediato desde la caché', () => {
  it('si la película está en un listado ya cargado, se ve al instante y aun así se pide la versión del servidor', async () => {
    const client = createTestQueryClient();
    const fromList = makeMovie({ id: 3, title: 'Desde el listado' });
    cacheAsPagedList(client, [makeMovie({ id: 1 }), fromList]);
    let answer!: (movie: unknown) => void;
    apiFetch.mockReturnValueOnce(new Promise((resolve) => (answer = resolve)));

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });

    expect(result.current.status).toBe('ready');
    expect(result.current.movie).toBe(fromList);
    expect(apiFetch).toHaveBeenCalledWith('/movies/3', { signal: expect.any(AbortSignal) });

    const fresh = makeMovie({ id: 3, title: 'Título actualizado' });
    await act(async () => answer(fresh));
    expect(result.current.movie).toBe(fresh);
  });

  it('la copia provisional NO tapa un 404: si la película ya no existe, "not-found"', async () => {
    const client = createTestQueryClient();
    cacheAsPagedList(client, [makeMovie({ id: 3 })]);
    apiFetch.mockRejectedValueOnce(notFound());

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });

    expect(result.current.status).toBe('ready');
    await waitFor(() => expect(result.current.status).toBe('not-found'));
  });

  it('la copia provisional tampoco tapa un fallo del servidor: pasa a "error" (con «Reintentar»), no se queda con ella', async () => {
    const client = createTestQueryClient();
    cacheAsPagedList(client, [makeMovie({ id: 3, title: 'Desde el listado' })]);
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Servidor caído' }));

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });

    expect(result.current.status).toBe('ready');
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.movie).toBeNull();
    expect(result.current.errorMessage).toBe('Servidor caído');
  });

  it('`confirmed` es false con la copia provisional y true cuando responde el servidor', async () => {
    const client = createTestQueryClient();
    cacheAsPagedList(client, [makeMovie({ id: 3 })]);
    let answer!: (movie: unknown) => void;
    apiFetch.mockReturnValueOnce(new Promise((resolve) => (answer = resolve)));

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });

    expect(result.current.status).toBe('ready');
    expect(result.current.confirmed).toBe(false);
    await act(async () => answer(makeMovie({ id: 3 })));
    expect(result.current.confirmed).toBe(true);
  });

  it('una película que no está en ninguna caché espera a la petición ("cargando")', () => {
    const client = createTestQueryClient();
    cacheAsPagedList(client, [makeMovie({ id: 1 })]);
    apiFetch.mockReturnValueOnce(new Promise(() => {}));

    const { result } = renderHook(() => useMovieDetail('3'), { wrapper: queryWrapper(client) });

    expect(result.current.status).toBe('loading');
  });
});

describe('findCachedMovie', () => {
  it('la encuentra en un listado paginado, en una página suelta (panel) y en «Mi lista»', () => {
    const client = createTestQueryClient();
    const inList = makeMovie({ id: 1, title: 'Portada' });
    const inAdmin = makeMovie({ id: 2, title: 'Panel' });
    const inFavorites = makeMovie({ id: 3, title: 'Favorita' });
    cacheAsPagedList(client, [inList]);
    client.setQueryData(queryKeys.movies.admin('', 1), makePage([inAdmin]));
    client.setQueryData(queryKeys.favorites.all, { movie: [inFavorites], series: [] });

    expect(findCachedMovie(client, 1)).toBe(inList);
    expect(findCachedMovie(client, 2)).toBe(inAdmin);
    expect(findCachedMovie(client, 3)).toBe(inFavorites);
    expect(findCachedMovie(client, 4)).toBeUndefined();
  });

  it('la encuentra también en la fila «Películas similares» de otra película (un array suelto)', () => {
    const client = createTestQueryClient();
    const similar = makeMovie({ id: 9, title: 'Similar' });
    client.setQueryData(queryKeys.movies.similar(3, 12), [makeMovie({ id: 8 }), similar]);

    expect(findCachedMovie(client, 9)).toBe(similar);
  });

  it('no confunde una SERIE con el mismo id con una película (las series no se miran)', () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.paged('/series', { pageSize: 20, sort: 'createdAt', direction: 'desc' }), {
      pages: [makePage([{ id: 7, title: 'Una serie' }])],
      pageParams: [0],
    });
    client.setQueryData(queryKeys.favorites.all, { movie: [], series: [{ id: 7, title: 'Serie favorita' }] });

    expect(findCachedMovie(client, 7)).toBeUndefined();
  });
});
