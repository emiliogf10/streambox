/**
 * Tests de `useHideOnScroll`: la barra se aparta al BAJAR (pasada su propia
 * altura) y vuelve con cualquier gesto hacia arriba; el rebote elástico de los
 * extremos no cuenta como cambio de sentido y, desactivado, no hace nada.
 *
 * jsdom no desplaza ni maqueta: `scrollY`, la altura del documento y la de la
 * barra se fijan a mano y el desplazamiento se simula con el evento `scroll`.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useHideOnScroll } from './useHideOnScroll';

const BAR_HEIGHT = 105;

/** Lleva la página a `y` y avisa como lo haría el navegador. */
function scrollTo(y: number) {
  act(() => {
    Object.defineProperty(window, 'scrollY', { configurable: true, value: y });
    window.dispatchEvent(new Event('scroll'));
  });
}

beforeEach(() => {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 });
  Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: 3000 });
});

afterEach(() => {
  Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
});

/** Monta el hook con una «barra» de {@link BAR_HEIGHT} px. */
function renderBar(enabled = true) {
  const bar = document.createElement('header');
  Object.defineProperty(bar, 'offsetHeight', { configurable: true, value: BAR_HEIGHT });
  return renderHook(({ on }) => useHideOnScroll({ current: bar }, on), { initialProps: { on: enabled } });
}

describe('useHideOnScroll', () => {
  it('al bajar se aparta, pero no mientras la página sigue dentro de la altura de la barra (no parpadea arriba)', () => {
    const { result } = renderBar();
    expect(result.current).toBe(false);

    scrollTo(60);
    expect(result.current).toBe(false);

    scrollTo(400);
    expect(result.current).toBe(true);
  });

  it('cualquier gesto hacia arriba la trae de vuelta, y al volver a bajar se aparta otra vez', () => {
    const { result } = renderBar();
    scrollTo(600);
    expect(result.current).toBe(true);

    scrollTo(590);
    expect(result.current).toBe(false);

    scrollTo(700);
    expect(result.current).toBe(true);
  });

  it('el rebote elástico pasado el final (iOS) no cuenta como subir', () => {
    const { result } = renderBar();
    scrollTo(2200); // final real: 3000 - 800
    expect(result.current).toBe(true);

    scrollTo(2260); // rebote: más allá del final
    scrollTo(2230); // vuelve al final por el rebote, no por el usuario
    expect(result.current).toBe(true);
  });

  it('desactivado (sin sesión, sin barra que apartar) no escucha nada y devuelve siempre false', () => {
    const { result, rerender } = renderBar(true);
    scrollTo(600);
    expect(result.current).toBe(true);

    rerender({ on: false });
    expect(result.current).toBe(false);
    scrollTo(900);
    expect(result.current).toBe(false);
  });
});
