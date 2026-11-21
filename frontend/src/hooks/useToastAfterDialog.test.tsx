/**
 * Tests de `useToastAfterDialog`: con el diálogo abierto el aviso espera a que se
 * cierre; con el diálogo cerrado sale en el acto. Que de verdad nazca en la
 * página (y una sola vez) lo comprueban los tests de cada pantalla con
 * `watchToastInsertions` (borrar géneros, películas, vaciar «Mi lista»…).
 */
import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useToastAfterDialog } from './useToastAfterDialog';

describe('useToastAfterDialog', () => {
  it('con el diálogo abierto, guarda el aviso y lo lanza al cerrarse (una sola vez)', () => {
    const show = vi.fn();
    const { result, rerender } = renderHook(({ open }) => useToastAfterDialog(open), { initialProps: { open: true } });

    result.current(show);
    expect(show).not.toHaveBeenCalled();

    rerender({ open: false });
    expect(show).toHaveBeenCalledTimes(1);

    rerender({ open: true });
    rerender({ open: false });
    expect(show).toHaveBeenCalledTimes(1);
  });

  it('con el diálogo cerrado, lo lanza en el acto', () => {
    const show = vi.fn();
    const { result } = renderHook(() => useToastAfterDialog(false));

    result.current(show);
    expect(show).toHaveBeenCalledTimes(1);
  });

  it('si llegan dos antes de cerrar, solo sale el último (cada acción termina con un aviso)', () => {
    const first = vi.fn();
    const last = vi.fn();
    const { result, rerender } = renderHook(({ open }) => useToastAfterDialog(open), { initialProps: { open: true } });

    result.current(first);
    result.current(last);
    rerender({ open: false });

    expect(first).not.toHaveBeenCalled();
    expect(last).toHaveBeenCalledTimes(1);
  });
});
