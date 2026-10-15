/**
 * `ResizeObserver` falso para los tests que dependen de tamaños.
 *
 * jsdom no maqueta (todo mide 0) y el `ResizeObserver` de `setup.ts` es un
 * sustituto mudo que nunca avisa. Este recuerda qué observa (y con qué
 * opciones) y deja que el test dispare el aviso cuando ha cambiado a mano las
 * medidas que simula (`getBoundingClientRect`, `scrollWidth`...), como haría el
 * navegador al cambiar el tamaño de la ventana o del contenido.
 *
 * Uso: `installFakeResizeObserver()` en un `beforeEach` y, tras cambiar las
 * medidas, `act(() => FakeResizeObserver.triggerAll())`.
 */
import { vi } from 'vitest';

/** `ResizeObserver` falso: recuerda qué observa, con qué opciones, y permite disparar el aviso. */
export class FakeResizeObserver {
  /** Todos los observadores creados desde el último `installFakeResizeObserver()`. */
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

  /** Dispara el aviso en todos los observadores que siguen conectados. */
  static triggerAll() {
    for (const observer of FakeResizeObserver.instances) {
      if (!observer.disconnected) observer.trigger();
    }
  }
}

/** Sustituye el `ResizeObserver` global por {@link FakeResizeObserver} (se deshace con `vi.unstubAllGlobals`). */
export function installFakeResizeObserver(): void {
  FakeResizeObserver.instances = [];
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
}
