/**
 * Tests de `useSeriesResults`, el hook de la página `/series` con y sin filtros
 * (el gemelo de `useMovieResults`, que se prueba en `useCatalog.test.tsx`).
 *
 * Revisión QA de los filtros de `/series`. Protegen el contrato con el servidor:
 * - sin filtros, el catálogo público `/series` (lo más reciente primero);
 * - con filtros, el buscador PÚBLICO `/series/search` (nunca un endpoint del
 *   panel, que sí devolvería las series sin episodios) con `genreId`,
 *   `releaseYear`, `sort` y `direction`, sin `title`;
 * - nunca `sort=duration`, que el servidor rechazaría con un 400
 *   (`SeriesController.SORTABLE_FIELDS` no lo tiene), ni aunque llegue un orden
 *   por duración por un uso equivocado del hook;
 * - y que, tras una escritura del panel sobre series
 *   (`keysAffectedBy('series')`, lo que llama `useInvalidateCatalog`), el
 *   listado FILTRADO que está en pantalla se vuelva a pedir, mientras que un
 *   cambio de películas no lo toca.
 *
 * `apiFetch` está simulado; la caché de TanStack Query es real.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '../context/ToastContext';
import * as api from '../lib/api';
import { NO_CATALOG_FILTERS } from '../lib/catalogFilters';
import type { CatalogFilters } from '../lib/catalogFilters';
import { keysAffectedBy } from '../lib/queryKeys';
import { createTestQueryClient } from '../test/queryClient';
import { makePage, makeSeries } from '../test/helpers';
import { useSeriesResults } from './useSeriesCatalog';

// Se simula SOLO `apiFetch`; `ApiError`, `isAbortError`... siguen siendo los reales.
vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  apiFetch: vi.fn(),
}));

const apiFetch = vi.mocked(api.apiFetch);

/** Caché nueva en cada test: ninguno ve lo que cargó otro. */
let queryClient = createTestQueryClient();

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}

/** Endpoint y parámetros de la llamada número `n` (0 = la primera). */
function call(n: number) {
  const [path, options] = apiFetch.mock.calls[n];
  return { path, params: (options as { params: Record<string, unknown> }).params };
}

beforeEach(() => {
  apiFetch.mockReset();
  queryClient = createTestQueryClient();
});

describe('useSeriesResults: la página /series con y sin filtros', () => {
  it('sin filtros pide el catálogo público /series, lo más reciente primero, y no está filtrado', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 1 })]));

    const { result } = renderHook(() => useSeriesResults(NO_CATALOG_FILTERS), { wrapper });

    expect(call(0)).toEqual({ path: '/series', params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc' } });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.filtered).toBe(false);
    expect(result.current.series.map((series) => series.id)).toEqual([1]);
  });

  it('con filtros pide el buscador público /series/search con género, año y orden (sin título, nunca /admin)', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 8 })]));

    const { result } = renderHook(() => useSeriesResults({ genreId: 4, year: 2016, sort: 'anio-asc' }), { wrapper });

    expect(call(0)).toEqual({
      path: '/series/search',
      params: { page: 0, size: 20, sort: 'releaseYear', direction: 'asc', genreId: 4, releaseYear: 2016 },
    });
    expect(call(0).params).not.toHaveProperty('title');
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.filtered).toBe(true);
    expect(apiFetch.mock.calls.every(([path]) => !String(path).includes('admin'))).toBe(true);
  });

  it.each(['duracion-asc', 'duracion-desc'] as const)(
    'un orden por duración (%s) que llegue por error nunca se manda como sort=duration (el servidor daría 400)',
    (sort) => {
      apiFetch.mockReturnValueOnce(new Promise(() => {}));

      renderHook(() => useSeriesResults({ ...NO_CATALOG_FILTERS, genreId: 3, sort }), { wrapper });

      expect(call(0).path).toBe('/series/search');
      expect(call(0).params).toMatchObject({ sort: 'createdAt', direction: 'desc', genreId: 3 });
    },
  );

  it('al cambiar de filtro cancela la búsqueda anterior y pide la nueva desde la página 0', () => {
    apiFetch.mockReturnValue(new Promise(() => {}));
    const { rerender } = renderHook((filters: CatalogFilters) => useSeriesResults(filters), {
      wrapper,
      initialProps: { ...NO_CATALOG_FILTERS, genreId: 4 },
    });
    const firstSignal = (apiFetch.mock.calls[0][1] as { signal: AbortSignal }).signal;

    rerender({ ...NO_CATALOG_FILTERS, genreId: 7 });

    expect(firstSignal.aborted).toBe(true);
    expect(call(1)).toEqual({
      path: '/series/search',
      params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc', genreId: 7 },
    });
  });
});

describe('useSeriesResults: invalidación tras escribir en el panel', () => {
  const drama: CatalogFilters = { genreId: 4, year: null, sort: 'recientes' };

  it('crear, editar o borrar una serie (o un episodio) vuelve a pedir el listado filtrado que está en pantalla', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 1, title: 'Antes' })]));
    const { result } = renderHook(() => useSeriesResults(drama), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(apiFetch).toHaveBeenCalledTimes(1);

    // El panel acaba de dar episodios a otra serie de Drama: ahora es visible.
    apiFetch.mockResolvedValueOnce(
      makePage([makeSeries({ id: 2, title: 'Recién visible' }), makeSeries({ id: 1, title: 'Antes' })], {
        totalElements: 2,
      }),
    );
    await act(async () => {
      await Promise.all(keysAffectedBy('series').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    expect(apiFetch).toHaveBeenCalledTimes(2);
    expect(call(1)).toEqual({
      path: '/series/search',
      params: { page: 0, size: 20, sort: 'createdAt', direction: 'desc', genreId: 4 },
    });
    await waitFor(() => expect(result.current.series.map((series) => series.title)).toEqual(['Recién visible', 'Antes']));
    expect(result.current.total).toBe(2);
  });

  it('un cambio solo de películas no vuelve a pedir el listado filtrado de series', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 1 })]));
    const { result } = renderHook(() => useSeriesResults(drama), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));

    await act(async () => {
      await Promise.all(keysAffectedBy('movies').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    expect(apiFetch).toHaveBeenCalledTimes(1);
  });

  it('renombrar o borrar un género también lo vuelve a pedir (el nombre del género va dentro de cada serie)', async () => {
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 1 })]));
    const { result } = renderHook(() => useSeriesResults(drama), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    apiFetch.mockResolvedValueOnce(makePage([makeSeries({ id: 1 })]));

    await act(async () => {
      await Promise.all(keysAffectedBy('genres').map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    });

    expect(apiFetch).toHaveBeenCalledTimes(2);
    expect(call(1).path).toBe('/series/search');
  });
});
