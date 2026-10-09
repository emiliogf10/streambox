/**
 * Tests de `useCatalog`, el hook que pagina el catálogo de la portada, y de
 * `useMovieResults`, el de `/peliculas` (mismo `usePagedCatalog`, con filtros).
 *
 * Protegen: el contrato con el servidor (ordenación `createdAt desc`, página 0
 * inicial, página siguiente en "cargar más"), que "cargar más" nunca pierda ni
 * duplique lo ya cargado, y que un fallo no destruya la lista (error de
 * `loadMore`) o se pueda reintentar (error inicial). Con filtros: el endpoint y
 * los parámetros de cada orden, y que al cambiar de filtro se cancele lo anterior
 * y nada del filtro viejo se cuele en la lista nueva. `apiFetch` está simulado.
 */
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../context/ToastContext';
import { createTestQueryClient } from '../test/queryClient';
import * as api from '../lib/api';
import { makeMovie, makePage } from '../test/helpers';
import { NO_MOVIE_FILTERS } from '../lib/movieFilters';
import type { MovieFilters } from '../lib/movieFilters';
import { useCatalog, useMovieResults } from './useCatalog';

// Se simula SOLO `apiFetch`; `ApiError`, `isAbortError`... siguen siendo los reales.
vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  apiFetch: vi.fn(),
}));

const apiFetch = vi.mocked(api.apiFetch);

/** Caché nueva en cada test (ver `beforeEach`): ninguno ve lo que cargó otro. */
let queryClient = createTestQueryClient();

/** `useCatalog` necesita la caché de TanStack Query y `ToastProvider` (avisa de los fallos de "cargar más"). */
function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}

/** Promesa que se resuelve o rechaza a mano: permite observar estados intermedios. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const firstPage = makePage([makeMovie({ id: 1 }), makeMovie({ id: 2 })], {
  page: 0,
  totalElements: 5,
  totalPages: 3,
  hasNext: true,
});

/** Parámetros con los que se pidió la llamada número `n` (0 = la primera). */
function paramsOfCall(n: number) {
  return (apiFetch.mock.calls[n][1] as { params: Record<string, unknown> }).params;
}

beforeEach(() => {
  apiFetch.mockReset();
  queryClient = createTestQueryClient();
});

describe('useCatalog: carga inicial', () => {
  it('empieza en "loading" y pide la página 0 ordenada por createdAt descendente', async () => {
    const pending = deferred<unknown>();
    apiFetch.mockReturnValueOnce(pending.promise);

    const { result } = renderHook(() => useCatalog(), { wrapper });

    expect(result.current.status).toBe('loading');
    expect(apiFetch).toHaveBeenCalledWith('/movies', {
      params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc' },
      signal: expect.any(AbortSignal),
    });

    await act(async () => pending.resolve(firstPage));
    expect(result.current.status).toBe('ready');
  });

  it('expone las películas, el total y hasMore (de hasNext)', async () => {
    apiFetch.mockResolvedValueOnce(firstPage);

    const { result } = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2]);
    expect(result.current.total).toBe(5);
    expect(result.current.hasMore).toBe(true);
  });

  it('hasMore es false cuando el servidor dice hasNext: false', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 1 })], { hasNext: false }));

    const { result } = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.hasMore).toBe(false);
  });

  it('un error inicial deja status "error" con el mensaje del servidor', async () => {
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Servidor caído' }));

    const { result } = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('error'));

    expect(result.current.errorMessage).toBe('Servidor caído');
    expect(result.current.movies).toEqual([]);
  });

  it('al desmontar cancela la petición en vuelo', () => {
    apiFetch.mockReturnValueOnce(new Promise(() => {}));

    const { unmount } = renderHook(() => useCatalog(), { wrapper });
    const signal = (apiFetch.mock.calls[0][1] as { signal: AbortSignal }).signal;
    expect(signal.aborted).toBe(false);

    unmount();

    expect(signal.aborted).toBe(true);
  });
});

describe('useCatalog: cargar más', () => {
  async function renderReady() {
    apiFetch.mockResolvedValueOnce(firstPage);
    const hook = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(hook.result.current.status).toBe('ready'));
    return hook;
  }

  it('pide la página 1 y la añade al final sin duplicar (aunque el servidor repita una película)', async () => {
    const { result } = await renderReady();
    apiFetch.mockResolvedValueOnce(
      makePage([makeMovie({ id: 2 }), makeMovie({ id: 3 })], { page: 1, totalElements: 6, hasNext: false }),
    );

    await act(() => result.current.loadMore());

    expect(paramsOfCall(1)).toEqual({ page: 1, size: 20, sort: 'createdAt', direction: 'desc' });
    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2, 3]);
    expect(result.current.total).toBe(6);
    expect(result.current.hasMore).toBe(false);
  });

  it('cada "cargar más" avanza a la página siguiente', async () => {
    const { result } = await renderReady();
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 3 })], { page: 1, hasNext: true }));
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 4 })], { page: 2, hasNext: false }));

    await act(() => result.current.loadMore());
    await act(() => result.current.loadMore());

    expect(paramsOfCall(1).page).toBe(1);
    expect(paramsOfCall(2).page).toBe(2);
  });

  it('marca loadingMore mientras espera y no lanza una segunda petición a la vez', async () => {
    const { result } = await renderReady();
    const pending = deferred<unknown>();
    apiFetch.mockReturnValueOnce(pending.promise);

    let first!: Promise<void>;
    await act(async () => {
      first = result.current.loadMore();
      void result.current.loadMore(); // segundo clic mientras el primero sigue en vuelo
    });

    expect(result.current.loadingMore).toBe(true);
    expect(apiFetch).toHaveBeenCalledTimes(2); // carga inicial + UNA de "cargar más"

    await act(async () => {
      pending.resolve(makePage([makeMovie({ id: 3 })], { page: 1 }));
      await first;
    });
    expect(result.current.loadingMore).toBe(false);
  });

  it('si falla, conserva lo cargado, marca loadMoreFailed y avisa con un toast', async () => {
    const { result } = await renderReady();
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Sin conexión' }));

    await act(() => result.current.loadMore());

    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2]);
    expect(result.current.status).toBe('ready');
    expect(result.current.loadMoreFailed).toBe(true);
    expect(result.current.loadingMore).toBe(false);
    expect(screen.getByText('Sin conexión')).toBeInTheDocument();
  });

  it('tras un fallo, reintentar vuelve a pedir la MISMA página y limpia el fallo', async () => {
    const { result } = await renderReady();
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Error' }));
    await act(() => result.current.loadMore());
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 3 })], { page: 1, hasNext: false }));

    await act(() => result.current.loadMore());

    expect(paramsOfCall(1).page).toBe(1);
    expect(paramsOfCall(2).page).toBe(1);
    expect(result.current.loadMoreFailed).toBe(false);
    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2, 3]);
  });
});

describe('useCatalog: reload', () => {
  it('tras un error inicial, reload vuelve a "loading" y recupera el catálogo', async () => {
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Error' }));
    const { result } = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('error'));
    const pending = deferred<unknown>();
    apiFetch.mockReturnValueOnce(pending.promise);

    await act(async () => result.current.reload());

    expect(result.current.status).toBe('loading');

    await act(async () => pending.resolve(firstPage));
    expect(result.current.status).toBe('ready');
    expect(result.current.movies).toHaveLength(2);
    expect(paramsOfCall(1).page).toBe(0);
  });

  it('reinicia la paginación: después de recargar, "cargar más" vuelve a pedir la página 1', async () => {
    apiFetch.mockResolvedValueOnce(firstPage);
    const { result } = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 3 })], { page: 1, hasNext: true }));
    await act(() => result.current.loadMore()); // la siguiente sería la página 2

    apiFetch.mockResolvedValueOnce(firstPage);
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.status).toBe('ready'));
    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 9 })], { page: 1 }));
    await act(() => result.current.loadMore());

    expect(paramsOfCall(2).page).toBe(0); // la recarga pide la 0...
    expect(paramsOfCall(3).page).toBe(1); // ...y "cargar más" vuelve a la 1, no a la 2
    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2, 9]);
  });
});

describe('useMovieResults: la página /peliculas con y sin filtros', () => {
  const drama: MovieFilters = { genreId: 4, year: null, sort: 'recientes' };

  it('sin filtros pide lo mismo que la portada: /movies, lo más reciente primero', async () => {
    apiFetch.mockResolvedValueOnce(firstPage);

    const { result } = renderHook(() => useMovieResults(NO_MOVIE_FILTERS), { wrapper });

    expect(apiFetch).toHaveBeenCalledWith('/movies', {
      params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc' },
      signal: expect.any(AbortSignal),
    });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.filtered).toBe(false);
  });

  it('con filtros pide /movies/search con género, año y orden (sin título)', async () => {
    apiFetch.mockResolvedValueOnce(firstPage);

    const { result } = renderHook(() => useMovieResults({ genreId: 4, year: 2014, sort: 'titulo-desc' }), { wrapper });

    expect(apiFetch).toHaveBeenCalledWith('/movies/search', {
      params: { page: 0, size: 20, sort: 'title', direction: 'desc', genreId: 4, releaseYear: 2014 },
      signal: expect.any(AbortSignal),
    });
    expect(paramsOfCall(0)).not.toHaveProperty('title');
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.filtered).toBe(true);
  });

  it('solo cambiar el orden ya cuenta como filtro: /movies/search sin género ni año', () => {
    apiFetch.mockReturnValueOnce(new Promise(() => {}));

    renderHook(() => useMovieResults({ ...NO_MOVIE_FILTERS, sort: 'duracion-asc' }), { wrapper });

    expect(apiFetch.mock.calls[0][0]).toBe('/movies/search');
    expect(paramsOfCall(0)).toEqual({ page: 0, size: 20, sort: 'duration', direction: 'asc' });
  });

  it('al cambiar de filtro cancela la petición anterior, vuelve a "loading" y una respuesta tardía no se pinta', async () => {
    const old = deferred<unknown>();
    apiFetch.mockReturnValueOnce(old.promise);
    const { result, rerender } = renderHook((filters: MovieFilters) => useMovieResults(filters), {
      wrapper,
      initialProps: drama,
    });
    const oldSignal = (apiFetch.mock.calls[0][1] as { signal: AbortSignal }).signal;
    const fresh = deferred<unknown>();
    apiFetch.mockReturnValueOnce(fresh.promise);

    rerender({ ...drama, genreId: 5 });

    expect(oldSignal.aborted).toBe(true);
    expect(paramsOfCall(1)).toMatchObject({ page: 0, genreId: 5 });
    expect(result.current.status).toBe('loading');

    // La respuesta del filtro viejo llega tarde: se descarta.
    await act(async () => old.resolve(makePage([makeMovie({ id: 99 })])));
    expect(result.current.status).toBe('loading');
    expect(result.current.movies).toEqual([]);

    await act(async () => fresh.resolve(makePage([makeMovie({ id: 5 })])));
    expect(result.current.movies.map((m) => m.id)).toEqual([5]);
  });

  it('al cambiar de filtro olvida lo cargado y cancela un "cargar más" en vuelo: la página siguiente vuelve a ser la 1', async () => {
    apiFetch.mockResolvedValueOnce(firstPage);
    const { result, rerender } = renderHook((filters: MovieFilters) => useMovieResults(filters), {
      wrapper,
      initialProps: drama,
    });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const more = deferred<unknown>();
    apiFetch.mockReturnValueOnce(more.promise);
    let pendingMore!: Promise<void>;
    await act(async () => {
      pendingMore = result.current.loadMore();
    });
    const moreSignal = (apiFetch.mock.calls[1][1] as { signal: AbortSignal }).signal;
    expect(result.current.loadingMore).toBe(true);

    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 7 })], { hasNext: true, totalElements: 2 }));
    rerender({ ...drama, year: 2001 });

    expect(moreSignal.aborted).toBe(true);
    expect(result.current.movies).toEqual([]);
    expect(result.current.loadingMore).toBe(false);
    await waitFor(() => expect(result.current.status).toBe('ready'));

    // El "cargar más" del filtro viejo responde tarde: no se mezcla con la lista nueva.
    await act(async () => {
      more.resolve(makePage([makeMovie({ id: 3 })], { page: 1 }));
      await pendingMore;
    });
    expect(result.current.movies.map((m) => m.id)).toEqual([7]);

    apiFetch.mockResolvedValueOnce(makePage([makeMovie({ id: 8 })], { page: 1 }));
    await act(() => result.current.loadMore());
    expect(paramsOfCall(3)).toMatchObject({ page: 1, genreId: 4, releaseYear: 2001 });
    expect(result.current.movies.map((m) => m.id)).toEqual([7, 8]);
  });
});
