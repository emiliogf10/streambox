import { expect } from 'vitest';

/**
 * Comprobaciones del estado de un botón de `components/Button` con `pending` o `inactive`.
 *
 * Esos botones no usan el atributo `disabled` (un botón desactivado con el foco
 * lo suelta al `<body>`), sino `aria-disabled`, así que `toBeDisabled()` /
 * `toBeEnabled()` de jest-dom ya no dicen nada útil: `toBeEnabled()` pasaría
 * aunque el botón estuviera bloqueado. Estas funciones comprueban las dos cosas
 * que importan: que se anuncia como no disponible y que sigue pudiendo tener el foco.
 */

/**
 * El botón está bloqueado SIN soltar el foco: `aria-disabled="true"` y sin `disabled`.
 *
 * @param button el botón
 * @param options `busy: true` exige además `aria-busy="true"` (petición en curso)
 */
export function expectButtonUnavailable(button: HTMLElement, options: { busy?: boolean } = {}) {
  expect(button).toHaveAttribute('aria-disabled', 'true');
  expect(button).not.toBeDisabled();
  if (options.busy) expect(button).toHaveAttribute('aria-busy', 'true');
}

/** El botón se puede usar: ni `disabled` ni `aria-disabled`, ni ocupado. */
export function expectButtonAvailable(button: HTMLElement) {
  expect(button).toBeEnabled();
  expect(button).not.toHaveAttribute('aria-disabled');
  expect(button).not.toHaveAttribute('aria-busy');
}
