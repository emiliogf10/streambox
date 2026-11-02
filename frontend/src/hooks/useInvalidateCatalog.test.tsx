/**
 * Tests de `useInvalidateCatalog` con la caché real de TanStack Query.
 *
 * Protegen la carrera que encontró `qa`: una lectura que salió ANTES de una
 * escritura del panel y responde DESPUÉS no puede quedarse como dato bueno.
 * `invalidateQueries` por sí solo solo corta el refresco en curso si la entrada
 * ya tiene datos; en la PRIMERA carga de una clave reutiliza la promesa en vuelo
 * (`Query.fetch` en `query-core`) y la copia vieja se queda hasta el siguiente
 * refresco. De ahí salen los casos:
 * - primera carga en vuelo: se corta, se repite y la pantalla no se queda en
 *   «cargando» (tampoco si la repetición falla ni en un listado infinito);
 * - con datos: la lista sigue en pantalla mientras llega la nueva;
 * - sin pantallas (una precarga): no se repite ahora, pero la lectura vieja no
 *   entra en la caché como fresca;
 * - cada cambio corta solo lo que deja anticuado.
 *
 * Las lecturas simuladas ignoran el `signal` a propósito: es el peor caso, una
 * respuesta que ya venía de camino cuando se canceló.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { useQuery } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../lib/queryKeys';
import type { Movie } from '../lib/types';
import { jsonResponse, makeMovie, makePage, routeFetch } from '../test/helpers';
import { createTestQueryClient, queryWrapper } from '../test/queryClient';
import { useInvalidateCatalog } from './useInvalidateCatalog';
import { usePagedCatalog } from './usePagedCatalog';

/** Una lectura simulada: el test decide cuándo y cómo responde. */
interface PendingRead {
  signal: AbortSignal;
  resolve: (value: string) => void;
  reject: (error: Error) => void;
}

/** `queryFn` cuyas respuestas controla el test, una por llamada y en orden (`reads[0]` = la primera). */
function scriptedReads() {
  const reads: PendingRead[] = [];
  const read = ({ signal }: { signal: AbortSignal }) =>
    new Promise<string>((resolve, reject) => {
      reads.push({ signal, resolve, reject });
    });
  return { reads, read };
}

/** Un dato del catálogo en pantalla y la invalidación del panel. */
function useScreen(queryKey: QueryKey, read: ReturnType<typeof scriptedReads>['read']) {
  return { query: useQuery({ queryKey, queryFn: read }), invalidate: useInvalidateCatalog() };
}

let client = createTestQueryClient();

beforeEach(() => {
  client = createTestQueryClient();
});

/** Avisa de la escritura sin esperar al refresco, como hace el panel (`void invalidateCatalog(...)`). */
async function notifyWrite(invalidate: ReturnType<typeof useInvalidateCatalog>, change: 'movies' | 'series' | 'genres') {
  await act(async () => {
    void invalidate(change);
    await Promise.resolve();
  });
}

describe('useInvalidateCatalog: primera carga en vuelo', () => {
  it('descarta la lectura anterior a la escritura, la repite y se queda con la nueva', async () => {
    const { reads, read } = scriptedReads();
    const { result } = renderHook(() => useScreen(queryKeys.movies.detail(1), read), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(reads).toHaveLength(1));

    await notifyWrite(result.current.invalidate, 'movies');
    await waitFor(() => expect(reads).toHaveLength(2));
    expect(reads[0].signal.aborted).toBe(true);

    // Llegan la vieja (ya cancelada) y la nueva, en ese orden: manda la nueva.
    reads[0].resolve('antes de escribir');
    reads[1].resolve('despues de escribir');

    await waitFor(() => expect(result.current.query.data).toBe('despues de escribir'));
    expect(result.current.query.fetchStatus).toBe('idle');
    expect(result.current.query.isStale).toBe(false);
  });

  it('la respuesta vieja que llega DESPUÉS de la nueva tampoco la pisa', async () => {
    const { reads, read } = scriptedReads();
    const { result } = renderHook(() => useScreen(queryKeys.movies.detail(1), read), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(reads).toHaveLength(1));

    await notifyWrite(result.current.invalidate, 'movies');
    await waitFor(() => expect(reads).toHaveLength(2));
    reads[1].resolve('despues de escribir');
    await waitFor(() => expect(result.current.query.data).toBe('despues de escribir'));

    await act(async () => {
      reads[0].resolve('antes de escribir');
      await Promise.resolve();
    });

    expect(result.current.query.data).toBe('despues de escribir');
  });

  it('si la repetición falla, enseña el error: la pantalla no se queda en «cargando»', async () => {
    const { reads, read } = scriptedReads();
    const { result } = renderHook(() => useScreen(queryKeys.movies.detail(1), read), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(reads).toHaveLength(1));

    await notifyWrite(result.current.invalidate, 'movies');
    await waitFor(() => expect(reads).toHaveLength(2));
    reads[1].reject(new Error('Sin conexión'));

    await waitFor(() => expect(result.current.query.isError).toBe(true));
    expect(result.current.query.isPending).toBe(false);
    expect(result.current.query.fetchStatus).toBe('idle');
  });

  it('también en un listado infinito (`usePagedCatalog`): pasa de «cargando» a listo sin ningún error', async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetchMock);
    const stale = makeMovie({ id: 1, title: 'Borrada' });
    const fresh = makeMovie({ id: 2, title: 'Sigue' });
    let answerStale: (response: Response) => void = () => {};
    let lists = 0;
    routeFetch(fetchMock, {
      'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc': () => {
        lists += 1;
        if (lists === 1) {
          return new Promise<Response>((resolve) => {
            answerStale = resolve;
          });
        }
        return jsonResponse(makePage([fresh]));
      },
    });
    const statuses: string[] = [];
    const { result } = renderHook(
      () => {
        const catalog = usePagedCatalog<Movie>('/movies', 20, 'No se pudo cargar más.');
        statuses.push(catalog.status);
        return { catalog, invalidate: useInvalidateCatalog() };
      },
      { wrapper: queryWrapper(client) },
    );
    await waitFor(() => expect(lists).toBe(1));

    await notifyWrite(result.current.invalidate, 'movies');
    answerStale(jsonResponse(makePage([stale, fresh])));

    await waitFor(() => expect(result.current.catalog.status).toBe('ready'));
    expect(lists).toBe(2);
    expect(result.current.catalog.items.map((movie) => movie.title)).toEqual(['Sigue']);
    expect(statuses).not.toContain('error');
  });
});

describe('useInvalidateCatalog: con datos en pantalla', () => {
  it('sin nada en vuelo, pide lo que está en pantalla una sola vez', async () => {
    const { reads, read } = scriptedReads();
    const { result } = renderHook(() => useScreen(queryKeys.movies.detail(1), read), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(reads).toHaveLength(1));
    reads[0].resolve('primera');
    await waitFor(() => expect(result.current.query.data).toBe('primera'));

    await notifyWrite(result.current.invalidate, 'movies');
    await waitFor(() => expect(reads).toHaveLength(2));
    reads[1].resolve('segunda');

    await waitFor(() => expect(result.current.query.data).toBe('segunda'));
    expect(reads).toHaveLength(2);
  });

  it('con un refresco en vuelo, lo que se ve no desaparece mientras llega lo nuevo', async () => {
    const { reads, read } = scriptedReads();
    const seen: unknown[] = [];
    const { result } = renderHook(
      () => {
        const screenState = useScreen(queryKeys.movies.detail(1), read);
        seen.push(screenState.query.data);
        return screenState;
      },
      { wrapper: queryWrapper(client) },
    );
    await waitFor(() => expect(reads).toHaveLength(1));
    reads[0].resolve('primera');
    await waitFor(() => expect(result.current.query.data).toBe('primera'));
    seen.length = 0;

    await act(async () => {
      void result.current.query.refetch();
      await Promise.resolve();
    });
    await waitFor(() => expect(reads).toHaveLength(2));

    await notifyWrite(result.current.invalidate, 'movies');
    await waitFor(() => expect(reads).toHaveLength(3));
    reads[1].resolve('refresco viejo');
    reads[2].resolve('despues de escribir');

    await waitFor(() => expect(result.current.query.data).toBe('despues de escribir'));
    expect(seen).not.toContain(undefined);
    expect(seen).not.toContain('refresco viejo');
  });
});

describe('useInvalidateCatalog: lo que no está en pantalla', () => {
  it('una precarga en vuelo anterior a la escritura no entra en la caché como dato fresco', async () => {
    const { reads, read } = scriptedReads();
    const key = queryKeys.recommendations.list(12);
    void client.prefetchQuery({ queryKey: key, queryFn: read });
    await waitFor(() => expect(reads).toHaveLength(1));
    const { result } = renderHook(() => useInvalidateCatalog(), { wrapper: queryWrapper(client) });

    await notifyWrite(result.current, 'movies');
    reads[0].resolve('antes de escribir');
    await act(async () => {
      await Promise.resolve();
    });

    expect(client.getQueryData(key)).toBeUndefined();
    expect(client.getQueryState(key)?.fetchStatus).toBe('idle');
    // Nadie la está mirando: se pedirá cuando se abra su pantalla, no ahora.
    expect(reads).toHaveLength(1);
  });

  it('un cambio de películas no corta lo que no le afecta (la primera carga de una serie sigue su curso)', async () => {
    const { reads, read } = scriptedReads();
    const { result } = renderHook(() => useScreen(queryKeys.series.detail(1), read), { wrapper: queryWrapper(client) });
    await waitFor(() => expect(reads).toHaveLength(1));

    await notifyWrite(result.current.invalidate, 'movies');
    reads[0].resolve('serie');

    await waitFor(() => expect(result.current.query.data).toBe('serie'));
    expect(reads).toHaveLength(1);
    expect(reads[0].signal.aborted).toBe(false);
  });
});
