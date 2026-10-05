/**
 * Tests de `useHeightCssVariable`: publica la altura de un elemento en una
 * variable CSS de `<html>` y la mantiene al día.
 *
 * jsdom no maqueta (todo mide 0) y su `ResizeObserver` es un sustituto mudo
 * (`src/test/setup.ts`), así que aquí se simulan las dos piezas del navegador:
 * la altura (`getBoundingClientRect`) y un `ResizeObserver` que se dispara a
 * mano. Lo que de verdad importa para el usuario —que el foco no quede debajo
 * de la barra— lo comprueba en un navegador real el E2E `accessibility.spec.ts`.
 */
import { act, render } from '@testing-library/react';
import { useRef } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useHeightCssVariable } from './useHeightCssVariable';

const VARIABLE = '--altura-de-prueba';

/** Altura que "mide" el navegador simulado; cada test la cambia a su gusto. */
let currentHeight = 0;

/** `ResizeObserver` falso: recuerda qué observa, con qué opciones, y permite disparar el aviso. */
class FakeResizeObserver {
  static instances: FakeResizeObserver[] = [];
  readonly callback: ResizeObserverCallback;
  readonly observed: { target: Element; options?: ResizeObserverOptions }[] = [];
  disconnected = false;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    FakeResizeObserver.instances.push(this);
  }

  observe(target: Element, options?: ResizeObserverOptions) {
    this.observed.push({ target, options });
  }

  unobserve() {}

  disconnect() {
    this.disconnected = true;
  }

  /** Simula que el navegador avisa de un cambio de tamaño. */
  trigger() {
    this.callback([], this as unknown as ResizeObserver);
  }
}

/** Componente mínimo que usa el hook sobre su propio `<div>`. */
function Measured() {
  const ref = useRef<HTMLDivElement>(null);
  useHeightCssVariable(ref, VARIABLE);
  return <div ref={ref}>medido</div>;
}

/** Valor actual de la variable en `<html>` ('' si no existe). */
const published = () => document.documentElement.style.getPropertyValue(VARIABLE);

beforeEach(() => {
  FakeResizeObserver.instances = [];
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(() => ({ height: currentHeight }) as DOMRect);
});

describe('useHeightCssVariable', () => {
  it('al montarse publica la altura, redondeada hacia arriba, y observa la caja de borde del elemento', () => {
    currentHeight = 164.4;
    const { getByText } = render(<Measured />);

    // Hacia arriba: 164 px dejarían el borde del elemento enfocado bajo la barra.
    expect(published()).toBe('165px');
    const [observer] = FakeResizeObserver.instances;
    expect(observer.observed).toEqual([{ target: getByText('medido'), options: { box: 'border-box' } }]);
  });

  it('cuando el elemento cambia de alto (otro ancho de ventana, zoom...) la variable se actualiza', () => {
    currentHeight = 165;
    render(<Measured />);
    expect(published()).toBe('165px');

    currentHeight = 57;
    act(() => FakeResizeObserver.instances[0].trigger());

    expect(published()).toBe('57px');
  });

  it('al desmontarse borra la variable y deja de observar (las pantallas sin barra no la heredan)', () => {
    currentHeight = 57;
    const { unmount } = render(<Measured />);
    expect(published()).toBe('57px');

    unmount();

    expect(published()).toBe('');
    expect(FakeResizeObserver.instances.every((observer) => observer.disconnected)).toBe(true);
  });
});
