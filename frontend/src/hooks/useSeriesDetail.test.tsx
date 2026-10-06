/**
 * Tests de `useSeriesDetail` (detalle de una serie) con `apiFetch` simulado.
 *
 * Protegen lo que la página no deja ver fácilmente: al cambiar de serie no se
 * enseña ni un render la anterior (pasa a "cargando" en el acto) y la petición
 * de la anterior se cancela; 404 es "no encontrada" (distinto de un error que
 * se puede reintentar) y un id mal formado no llega a pedir nada.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../lib/api';
import { makeSeriesDetail } from '../test/helpers';
import { useSeriesDetail } from './useSeriesDetail';

// Se simula SOLO `apiFetch`; `ApiError`, `isAbortError`... siguen siendo los reales.
vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  apiFetch: vi.fn(),
}));

const apiFetch = vi.mocked(api.apiFetch);

beforeEach(() => {
  apiFetch.mockReset();
});

describe('useSeriesDetail', () => {
  it('pide /series/{id} y expone la serie', async () => {
    const detail = makeSeriesDetail({ id: 3 });
    apiFetch.mockResolvedValueOnce(detail);

    const { result } = renderHook(() => useSeriesDetail('3'));

    expect(result.current.status).toBe('loading');
    expect(apiFetch).toHaveBeenCalledWith('/series/3', { signal: expect.any(AbortSignal) });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.series).toBe(detail);
  });

  it('al cambiar de serie pasa a "cargando" en el mismo render (sin la anterior) y cancela su petición', async () => {
    apiFetch.mockResolvedValueOnce(makeSeriesDetail({ id: 3, title: 'Tres' }));
    const { result, rerender } = renderHook(({ id }) => useSeriesDetail(id), { initialProps: { id: '3' } });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    apiFetch.mockReturnValueOnce(new Promise(() => {}));

    rerender({ id: '4' });

    expect(result.current.status).toBe('loading');
    expect(result.current.series).toBeNull();
    expect(apiFetch).toHaveBeenLastCalledWith('/series/4', { signal: expect.any(AbortSignal) });

    // La 4 sigue en vuelo: si se cambia a la 5, su petición se cancela (su respuesta ya no interesa).
    const signalOf4 = (apiFetch.mock.calls[1][1] as { signal: AbortSignal }).signal;
    apiFetch.mockReturnValueOnce(new Promise(() => {}));
    rerender({ id: '5' });
    expect(signalOf4.aborted).toBe(true);
  });

  it('404 → "not-found" (no "error"); otro fallo → "error" con mensaje y reload vuelve a pedir', async () => {
    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', message: 'No' }));
    const notFound = renderHook(() => useSeriesDetail('3'));
    await waitFor(() => expect(notFound.result.current.status).toBe('not-found'));

    apiFetch.mockRejectedValueOnce(new api.ApiError({ status: 500, code: 'X', message: 'Servidor caído' }));
    const failing = renderHook(() => useSeriesDetail('5'));
    await waitFor(() => expect(failing.result.current.status).toBe('error'));
    expect(failing.result.current.errorMessage).toBe('Servidor caído');

    apiFetch.mockResolvedValueOnce(makeSeriesDetail({ id: 5 }));
    act(() => failing.result.current.reload());
    expect(failing.result.current.status).toBe('loading');
    await waitFor(() => expect(failing.result.current.status).toBe('ready'));
  });

  it.each([['abc'], ['0'], ['1.5'], ['-1'], [undefined]])('id mal formado (%j) → "not-found" sin petición', (raw) => {
    const { result } = renderHook(() => useSeriesDetail(raw));

    expect(result.current.status).toBe('not-found');
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it('al desmontar cancela la petición en vuelo', () => {
    apiFetch.mockReturnValueOnce(new Promise(() => {}));
    const { unmount } = renderHook(() => useSeriesDetail('3'));
    const signal = (apiFetch.mock.calls[0][1] as { signal: AbortSignal }).signal;

    unmount();

    expect(signal.aborted).toBe(true);
  });
});
