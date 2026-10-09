/**
 * Preparación común de TODOS los tests (la carga Vitest antes de cada archivo, ver `vite.config.ts`).
 *
 * Hace estas cosas:
 * 1. Registra los matchers de `@testing-library/jest-dom` (`toBeInTheDocument`,
 *    `toHaveAttribute`, `toBeDisabled`...). Al importarse desde `src/` también
 *    amplía los tipos de `expect`, así que `tsc -b` los reconoce.
 * 2. Limpia entre tests: desmonta lo renderizado y vacía `localStorage`, para que
 *    ningún test herede la sesión o el DOM de otro.
 * 3. Completa lo que jsdom NO implementa y la aplicación usa (cada parche explica
 *    por qué y hasta dónde simula el navegador real). `navigator.locks` NO se
 *    simula aquí: sin él, `lib/api.ts` usa su cola local de reserva; los tests que
 *    prueban los Web Locks los instalan con `src/test/webLocks.ts`.
 * 4. Hace que TanStack Query avise de sus cambios en una microtarea (ver abajo).
 *    La caché en sí es nueva en cada test (`src/test/queryClient.tsx`).
 */
import '@testing-library/jest-dom/vitest';
import { notifyManager } from '@tanstack/react-query';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  // Sin `globals: true` Testing Library no registra su limpieza automática.
  cleanup();
  localStorage.clear();
  document.title = '';
});

/**
 * Polyfill mínimo de `HTMLDialogElement.show/showModal/close`.
 *
 * jsdom define el elemento `<dialog>` y el atributo `open`, pero no los métodos
 * (`showModal is not a function`), y `Modal` los necesita. Se simula solo lo que
 * la aplicación usa:
 * - `showModal()` pone `open` (y lanza `InvalidStateError` si ya estaba abierto,
 *   como el navegador) y lo apila como diálogo modal.
 * - `close()` quita `open` y emite `close` (de forma asíncrona, como el navegador:
 *   `Modal` depende de ese detalle para ignorar cierres antiguos).
 * - Escape: el navegador lanza `cancel` sobre el diálogo modal superior y, si nadie
 *   llama a `preventDefault()`, lo cierra. Se imita con un `keydown` en `document`.
 *
 * NO se simula el fondo inerte ni el foco atrapado (`inert`, `::backdrop`): son
 * comportamientos del motor de renderizado que jsdom no tiene; quedan para E2E.
 */
const modalStack: HTMLDialogElement[] = [];

HTMLDialogElement.prototype.show = function show(this: HTMLDialogElement) {
  this.setAttribute('open', '');
};

HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
  if (this.hasAttribute('open')) {
    throw new DOMException('El diálogo ya está abierto.', 'InvalidStateError');
  }
  this.setAttribute('open', '');
  modalStack.push(this);
};

HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
  if (!this.hasAttribute('open')) return;
  this.removeAttribute('open');
  const index = modalStack.indexOf(this);
  if (index >= 0) modalStack.splice(index, 1);
  setTimeout(() => this.dispatchEvent(new Event('close')), 0);
};

document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  const top = modalStack.at(-1);
  if (!top) return;
  const cancel = new Event('cancel', { cancelable: true });
  top.dispatchEvent(cancel);
  if (!cancel.defaultPrevented) top.close();
});

/**
 * TanStack Query avisa a los componentes de cada cambio de una consulta en un
 * `setTimeout(0)` (agrupa así varios cambios en un solo render). En los tests eso
 * tiene dos problemas: `await act(...)` termina ANTES de ese temporizador, así que
 * justo después de una acción el hook aún devolvería el estado anterior; y con el
 * reloj falso de `fakeTimers.ts` el aviso no llegaría nunca. Aquí se entregan en
 * una microtarea, que `act` sí espera. Solo cambia CUÁNDO llega el aviso, no qué
 * dice; la aplicación conserva el comportamiento por defecto.
 */
notifyManager.setScheduler((callback) => queueMicrotask(callback));

/**
 * `scrollIntoView` no existe en jsdom (no hay maquetación). `SearchBar` lo llama al
 * recorrer los resultados con las flechas; aquí es un espía sin efecto para poder
 * comprobar que se pide mantener visible la opción activa.
 */
Element.prototype.scrollIntoView = vi.fn();

/**
 * `ResizeObserver` no existe en jsdom (no hay maquetación, nunca cambia ningún tamaño).
 * `MovieRow` lo usa para saber si hay más tarjetas a cada lado; aquí no hace nada.
 */
globalThis.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};

/**
 * `BroadcastChannel` en memoria. `lib/api.ts` lo usa para que una pestaña avise a
 * las demás de que ha renovado la sesión. jsdom no lo trae y el que se ve es el de
 * Node, que comunica con TODO el proceso (otros archivos de test que corran en él)
 * y mantiene vivo el bucle de eventos mientras no se cierre. Este solo habla con
 * los canales de este archivo de test y entrega los mensajes de forma asíncrona,
 * como el navegador (nunca al propio emisor). Los tests simulan «otra pestaña»
 * abriendo un segundo canal con el mismo nombre.
 */
class InMemoryBroadcastChannel extends EventTarget {
  private static readonly open = new Set<InMemoryBroadcastChannel>();
  readonly name: string;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onmessageerror: ((event: MessageEvent) => void) | null = null;
  private closed = false;

  constructor(name: string) {
    super();
    this.name = name;
    InMemoryBroadcastChannel.open.add(this);
  }

  postMessage(message: unknown): void {
    if (this.closed) throw new DOMException('El canal está cerrado.', 'InvalidStateError');
    for (const channel of InMemoryBroadcastChannel.open) {
      if (channel === this || channel.name !== this.name) continue;
      setTimeout(() => channel.deliver(message), 0);
    }
  }

  close(): void {
    this.closed = true;
    InMemoryBroadcastChannel.open.delete(this);
  }

  private deliver(message: unknown): void {
    if (this.closed) return;
    const event = new MessageEvent('message', { data: message });
    this.onmessage?.(event);
    this.dispatchEvent(event);
  }
}

globalThis.BroadcastChannel = InMemoryBroadcastChannel as unknown as typeof BroadcastChannel;

/**
 * `matchMedia` tampoco existe en jsdom. `MovieRow` lo consulta para respetar
 * `prefers-reduced-motion`; se simula un navegador sin ninguna preferencia activa.
 */
window.matchMedia = (query: string): MediaQueryList =>
  ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) satisfies MediaQueryList;
