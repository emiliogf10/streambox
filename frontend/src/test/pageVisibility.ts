/**
 * Simula que la pestaña se oculta o vuelve a verse (`document.visibilityState` +
 * evento `visibilitychange`), como cuando el usuario cambia de pestaña.
 *
 * jsdom siempre dice `'visible'` y no tiene forma de cambiarlo, así que se espía el
 * getter. `restoreMocks: true` (`vite.config.ts`) lo devuelve a su valor real
 * después de cada test: no hace falta limpiarlo a mano.
 */
import { act } from '@testing-library/react';
import { vi } from 'vitest';

/**
 * Cambia la visibilidad de la pestaña y emite `visibilitychange` dentro de `act`
 * (React aplica los cambios de estado que provoque antes de volver al test).
 *
 * @param state `'hidden'` (otra pestaña delante) o `'visible'`
 */
export function setPageVisibility(state: DocumentVisibilityState): void {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}
