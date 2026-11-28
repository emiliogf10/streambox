import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useMediaQuery } from './useMediaQuery';

/**
 * `useMediaQuery` decide si se MONTA una imagen (el póster del banner), así que
 * importa que el primer pintado ya tenga el valor correcto y que siga los
 * cambios de la ventana (girar la tableta, cambiar su tamaño).
 */
afterEach(() => vi.restoreAllMocks());

/** `matchMedia` falso cuyo resultado se puede cambiar y que avisa a quien escucha. */
function controllableMatchMedia(initial: boolean) {
  let matches = initial;
  const listeners = new Set<() => void>();
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches,
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
        removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  );
  return {
    set(next: boolean) {
      matches = next;
      for (const listener of listeners) listener();
    },
    listenerCount: () => listeners.size,
  };
}

describe('useMediaQuery', () => {
  it('el primer valor ya es el de la ventana y sigue sus cambios', () => {
    const media = controllableMatchMedia(true);
    const { result } = renderHook(() => useMediaQuery('(min-width: 48rem)'));
    expect(result.current).toBe(true);

    act(() => media.set(false));
    expect(result.current).toBe(false);
  });

  /**
   * Revisión de QA (V16): sin `matchMedia` (un entorno sin ventana completa,
   * como un jsdom sin el *polyfill* de `src/test/setup.ts`) no rompe el
   * componente: da `false`, el diseño de móvil, que es el que no descarga
   * nada de más.
   */
  it('sin matchMedia devuelve false y no lanza', () => {
    const original = window.matchMedia;
    // @ts-expect-error -- se simula un entorno que no lo tiene.
    delete window.matchMedia;
    try {
      const { result, unmount } = renderHook(() => useMediaQuery('(min-width: 48rem)'));
      expect(result.current).toBe(false);
      unmount();
    } finally {
      window.matchMedia = original;
    }
  });

  /**
   * Revisión de QA (V16): si quien lo usa cambia la consulta (otro punto de
   * corte), el valor sale ya de la consulta nueva en ese mismo pintado y se
   * cambia de suscripción sin dejar la vieja escuchando (una fuga que, además,
   * volvería a pintar el componente por cambios que ya no le importan).
   */
  it('al cambiar la consulta, responde a la nueva y suelta la vieja', () => {
    const listeners = new Map<string, Set<() => void>>();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches: query === '(min-width: 64rem)',
          media: query,
          onchange: null,
          addEventListener: (_type: string, listener: () => void) => {
            if (!listeners.has(query)) listeners.set(query, new Set());
            listeners.get(query)!.add(listener);
          },
          removeEventListener: (_type: string, listener: () => void) => listeners.get(query)?.delete(listener),
          addListener: () => {},
          removeListener: () => {},
          dispatchEvent: () => false,
        }) as unknown as MediaQueryList,
    );

    const { result, rerender } = renderHook(({ query }) => useMediaQuery(query), {
      initialProps: { query: '(min-width: 48rem)' },
    });
    expect(result.current).toBe(false);
    expect(listeners.get('(min-width: 48rem)')?.size).toBe(1);

    rerender({ query: '(min-width: 64rem)' });
    expect(result.current).toBe(true);
    expect(listeners.get('(min-width: 48rem)')?.size).toBe(0);
    expect(listeners.get('(min-width: 64rem)')?.size).toBe(1);
  });

  it('deja de escuchar al desmontarse', () => {
    const media = controllableMatchMedia(false);
    const { unmount } = renderHook(() => useMediaQuery('(min-width: 48rem)'));
    expect(media.listenerCount()).toBe(1);

    unmount();
    expect(media.listenerCount()).toBe(0);
  });
});
