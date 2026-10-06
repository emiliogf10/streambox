/**
 * Tests de `useCatalogPresence`: la pregunta mínima «¿hay algo visible en
 * `/movies` o `/series`?» con la que «Mi lista» decide si ofrece «Explorar...».
 *
 * Protegen la petición (una página de tamaño 1, con el orden de siempre) y los
 * cuatro estados: preguntando, hay algo, no hay nada y no se pudo saber.
 * `fetch` está simulado; `apiFetch` y los avisos son los reales.
 */
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '../context/ToastContext';
import { errorResponse, jsonResponse, makePage, makeSeries, routeFetch } from '../test/helpers';
import { useCatalogPresence } from './useCatalogPresence';

const fetchMock = vi.fn<typeof fetch>();

const ANY_SERIES = 'GET /api/series?page=0&size=1&sort=createdAt&direction=desc';

/** `usePagedCatalog` necesita `ToastProvider`. */
function wrapper({ children }: { children: ReactNode }) {
  return <ToastProvider>{children}</ToastProvider>;
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

describe('useCatalogPresence', () => {
  it('mientras pregunta vale «checking»; con algún título, «some»', async () => {
    routeFetch(fetchMock, { [ANY_SERIES]: () => jsonResponse(makePage([makeSeries()], { totalElements: 7 })) });

    const { result } = renderHook(() => useCatalogPresence('/series'), { wrapper });

    expect(result.current).toBe('checking');
    await waitFor(() => expect(result.current).toBe('some'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sin ningún título (p. ej. todas las series sin episodios), «none»', async () => {
    routeFetch(fetchMock, { [ANY_SERIES]: () => jsonResponse(makePage([])) });

    const { result } = renderHook(() => useCatalogPresence('/series'), { wrapper });

    await waitFor(() => expect(result.current).toBe('none'));
  });

  it('si la petición falla, «unknown» (no «none»: no se sabe si hay algo)', async () => {
    routeFetch(fetchMock, { [ANY_SERIES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });

    const { result } = renderHook(() => useCatalogPresence('/series'), { wrapper });

    await waitFor(() => expect(result.current).toBe('unknown'));
  });

  it('para películas pregunta a /movies con la misma consulta mínima', async () => {
    routeFetch(fetchMock, { 'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc': () => jsonResponse(makePage([])) });

    const { result } = renderHook(() => useCatalogPresence('/movies'), { wrapper });

    await waitFor(() => expect(result.current).toBe('none'));
  });
});
