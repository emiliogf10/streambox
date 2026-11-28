import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { FakeImage, failImage, installFakeImage, requestedImages } from '../test/fakeImage';
import { useFirstLoadableImage } from './useFirstLoadableImage';

/**
 * Tests de `useFirstLoadableImage` (incidencia QA-V16-1): la primera candidata
 * se usa desde el principio, se salta a la siguiente solo si da `error`, la
 * cadena vuelve a empezar al cambiar de lista y un `error` que llega tarde no
 * se salta una imagen buena. El `Image` es el falso de `test/fakeImage.ts`.
 */
beforeEach(() => {
  installFakeImage();
});

describe('useFirstLoadableImage', () => {
  it('sin candidatas, undefined y ninguna petición', () => {
    const { result } = renderHook(() => useFirstLoadableImage([]));
    expect(result.current).toBeUndefined();
    expect(requestedImages()).toEqual([]);
  });

  it('usa la primera desde el primer renderizado y va bajando por la lista con cada error', () => {
    const { result } = renderHook(() => useFirstLoadableImage(['/a.webp', '/b.webp', '/c.webp']));
    expect(result.current).toBe('/a.webp');
    failImage('/a.webp');
    expect(result.current).toBe('/b.webp');
    failImage('/b.webp');
    expect(result.current).toBe('/c.webp');
    // La última no se comprueba: no queda nada a lo que pasar.
    expect(requestedImages()).not.toContain('/c.webp');
  });

  it('al cambiar de lista (otro episodio) vuelve a empezar por su primera candidata', () => {
    const { result, rerender } = renderHook(({ list }) => useFirstLoadableImage(list), {
      initialProps: { list: ['/e1.webp', '/serie.webp'] },
    });
    failImage('/e1.webp');
    expect(result.current).toBe('/serie.webp');

    rerender({ list: ['/e2.webp', '/serie.webp'] });
    expect(result.current).toBe('/e2.webp');
  });

  it('la misma lista con otra referencia (cada renderizado del padre) no reinicia nada', () => {
    const { result, rerender } = renderHook(({ list }) => useFirstLoadableImage(list), {
      initialProps: { list: ['/a.webp', '/b.webp'] },
    });
    failImage('/a.webp');
    rerender({ list: ['/a.webp', '/b.webp'] });
    expect(result.current).toBe('/b.webp');
  });

  it('un error de la lista anterior que llega tarde no afecta a la nueva', () => {
    const { result, rerender } = renderHook(({ list }) => useFirstLoadableImage(list), {
      initialProps: { list: ['/e1.webp', '/serie.webp'] },
    });
    const stale = FakeImage.created.find((image) => image.src === '/e1.webp');
    rerender({ list: ['/e2.webp', '/serie.webp'] });
    // La comprobación anterior quedó desenganchada al cambiar de lista.
    expect(stale?.onerror).toBeNull();
    expect(result.current).toBe('/e2.webp');
  });
});
