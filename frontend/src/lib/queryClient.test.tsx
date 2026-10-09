/**
 * Tests de la configuración de TanStack Query (`lib/queryClient.ts`).
 *
 * Protegen las decisiones que no se ven en ninguna pantalla:
 * - La política de reintentos: como mucho UNO y solo en fallos pasajeros (red,
 *   5xx); nunca un 4xx. Sobre todo, un 401 ya pasó por la renovación de la
 *   sesión dentro de `apiFetch`: si TanStack lo repitiera, pediría otro refresh
 *   condenado a fallar (y cada uno gasta del límite del servidor).
 * - La caché: dentro del minuto de `staleTime`, volver a montar un listado no
 *   lo pide otra vez.
 * - `loadStatusOf`, que traduce el estado de TanStack a los tres de las pantallas.
 *
 * Los tests de integración usan el `QueryClient` REAL de la aplicación (no el de
 * los demás tests, que desactiva los reintentos) y el reloj falso: así se
 * comprueba que, pasado el tiempo de espera del reintento, no ha habido ninguno.
 */
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCatalog } from '../hooks/useCatalog';
import { installManualTimers, passTime } from '../test/fakeTimers';
import {
  REFRESH_SESSION,
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { queryWrapper } from '../test/queryClient';
import { ApiError } from './api';
import { STALE_TIME_MS, createQueryClient, isTransientError, loadStatusOf, shouldRetryQuery } from './queryClient';

const fetchMock = vi.fn<typeof fetch>();

/** Catálogo de la portada. */
const CATALOG = 'GET /api/movies?page=0&size=20&sort=createdAt&direction=desc';

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** Error de la API con el `status` indicado. */
function apiError(status: number, extra: { sessionExpired?: boolean } = {}): ApiError {
  return new ApiError({ status, code: `HTTP_${status}`, message: 'x', ...extra });
}

/** Veces que se ha pedido `url` (con cualquier método). */
function callsTo(url: string): number {
  return fetchMock.mock.calls.filter(([input]) => String(input) === url).length;
}

describe('shouldRetryQuery: política de reintentos', () => {
  it.each([0, 500, 502, 503, 504])('reintenta UNA vez un fallo pasajero (status %i)', (status) => {
    expect(shouldRetryQuery(0, apiError(status))).toBe(true);
    expect(shouldRetryQuery(1, apiError(status))).toBe(false);
  });

  it.each([400, 401, 403, 404, 409, 429])('nunca reintenta un %i', (status) => {
    expect(isTransientError(apiError(status))).toBe(false);
    expect(shouldRetryQuery(0, apiError(status))).toBe(false);
  });

  it('nunca reintenta una sesión caducada ni un error que no es de la API (un fallo de programación)', () => {
    expect(shouldRetryQuery(0, apiError(401, { sessionExpired: true }))).toBe(false);
    expect(shouldRetryQuery(0, new TypeError('x is undefined'))).toBe(false);
  });

  it('es la política por defecto del QueryClient de la aplicación', () => {
    const defaults = createQueryClient().getDefaultOptions();
    expect(defaults.queries?.retry).toBe(shouldRetryQuery);
    expect(defaults.queries?.staleTime).toBe(STALE_TIME_MS);
    expect(defaults.mutations?.retry).toBe(false);
  });
});

describe('loadStatusOf', () => {
  it('con datos es «ready» aunque falle un refresco en segundo plano', () => {
    expect(loadStatusOf({ data: [], isError: true, isFetching: false })).toBe('ready');
  });

  it('sin datos: «error» si la carga falló y «loading» mientras carga o se reintenta', () => {
    expect(loadStatusOf({ data: undefined, isError: true, isFetching: false })).toBe('error');
    expect(loadStatusOf({ data: undefined, isError: true, isFetching: true })).toBe('loading');
    expect(loadStatusOf({ data: undefined, isError: false, isFetching: true })).toBe('loading');
  });
});

describe('QueryClient real de la aplicación', () => {
  it('un 401 pasa por el refresh de apiFetch y TanStack no lo repite: una petición y un solo refresh', async () => {
    installManualTimers(); // desde el principio: un reintento de TanStack usaría este reloj
    routeFetch(fetchMock, { [CATALOG]: () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.') });
    renderWithProviders(<CatalogProbe />, { session: true, queryClient: createQueryClient() });

    // Sesión caducada sin arreglo: `AuthProvider` la cierra (en la app, la ruta protegida lleva al login).
    await waitFor(() => expect(callsTo('/api/auth/refresh')).toBe(1));
    passTime(60_000); // mucho más que la espera de cualquier reintento
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(1);
    expect(callsTo('/api/auth/refresh')).toBe(1);
  });

  it('si el refresh renueva la sesión, apiFetch repite la petición UNA vez y la pantalla recibe los datos', async () => {
    let first = true;
    routeFetch(fetchMock, {
      [CATALOG]: () => {
        if (!first) return jsonResponse(makePage([makeMovie({ id: 1, title: 'Interstellar' })]));
        first = false;
        return errorResponse(401, 'UNAUTHORIZED', 'No autenticado.');
      },
      [REFRESH_SESSION]: () => noContentResponse(),
    });
    renderWithProviders(<CatalogProbe />, { session: true, queryClient: createQueryClient() });

    expect(await screen.findByText('estado:ready · Interstellar')).toBeInTheDocument();
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(2);
    expect(callsTo('/api/auth/refresh')).toBe(1);
  });

  it('un 404 se enseña sin reintentar; un 503 se reintenta una vez (tras la espera) antes de enseñarse', async () => {
    installManualTimers();
    let status = 404;
    routeFetch(fetchMock, { [CATALOG]: () => errorResponse(status, 'X', 'Fallo') });
    const { unmount } = renderWithProviders(<CatalogProbe />, { session: true, queryClient: createQueryClient() });
    expect(await screen.findByText(/^estado:error/)).toBeInTheDocument();
    passTime(60_000);
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(1);
    unmount();

    fetchMock.mockClear();
    status = 503;
    renderWithProviders(<CatalogProbe />, { session: true, queryClient: createQueryClient() });
    await waitFor(() => expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(1));
    expect(screen.getByText(/^estado:loading/)).toBeInTheDocument(); // esperando el reintento
    // Asíncrono: deja terminar de procesar la respuesta (que es quien programa la espera) antes de avanzar.
    await act(() => vi.advanceTimersByTimeAsync(999));
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(await screen.findByText(/^estado:error/)).toBeInTheDocument();
    passTime(60_000);
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(2);
  });

  it('volver a montar un listado dentro del minuto de staleTime lo pinta desde la caché, sin petición', async () => {
    routeFetch(fetchMock, { [CATALOG]: () => jsonResponse(makePage([makeMovie({ id: 1 })])) });
    const wrapper = queryWrapper(createQueryClient());
    const first = renderHook(() => useCatalog(), { wrapper });
    await waitFor(() => expect(first.result.current.status).toBe('ready'));
    first.unmount();

    const second = renderHook(() => useCatalog(), { wrapper });

    // Listo en el PRIMER render (sin pasar por «cargando») y sin pedir nada más.
    expect(second.result.current.status).toBe('ready');
    expect(second.result.current.movies.map((movie) => movie.id)).toEqual([1]);
    expect(callsTo('/api/movies?page=0&size=20&sort=createdAt&direction=desc')).toBe(1);
  });
});

/** Muestra el estado del catálogo como texto, para leerlo desde el test. */
function CatalogProbe() {
  const { status, movies } = useCatalog();
  return <p>{`estado:${status} · ${movies.map((movie) => movie.title).join(', ')}`}</p>;
}
