/**
 * Comprobaciones del aviso general de un formulario (`components/FormAlert`).
 *
 * El aviso tiene dos modos (ver su JSDoc): **con foco** (lo normal tras un error
 * que no es de ningún campo: un grupo enfocable, sin región viva, que se lee una
 * vez al recibir el foco) y **sin foco** (cuando el mismo envío ha marcado
 * campos: `role="alert"` y el foco en el primer campo con error). Estas ayudas
 * dicen en una línea en qué modo está y que se cumple todo lo de ese modo.
 */
import { expect } from 'vitest';

/**
 * Comprueba que `element` (el texto del aviso, p. ej. el que devuelve
 * `findByText`) está dentro de un aviso general que TIENE EL FOCO y que se
 * anuncia una sola vez: un grupo nombrado por su propio texto, sin `role="alert"`
 * ni ninguna región viva dentro (si no, el lector lo leería dos veces).
 *
 * @returns el contenedor del aviso, por si el test quiere comprobar algo más
 */
export function expectFocusedFormAlert(element: HTMLElement): HTMLElement {
  const alert = element.closest<HTMLElement>('[role="group"][tabindex="-1"]');
  expect(alert, 'el texto no está dentro de un aviso enfocable (FormAlert con foco)').not.toBeNull();
  expect(alert).toHaveFocus();
  expect(alert!.closest('[role="alert"], [role="status"], [aria-live]')).toBeNull();
  expect(alert!.querySelector('[role="alert"], [role="status"], [aria-live]')).toBeNull();
  // El nombre accesible es el texto del aviso (con su detalle): basta con que contenga el texto propio de
  // `element` (sus nodos de texto, como los compara `getByText`; el detalle va en otro párrafo).
  const ownText = Array.from(element.childNodes)
    .filter((node) => node.nodeType === Node.TEXT_NODE)
    .map((node) => node.textContent)
    .join('')
    .trim();
  expect(alert).toHaveAccessibleName(expect.stringContaining(ownText));
  return alert!;
}

/**
 * Comprueba que `element` está dentro de un aviso general en modo «sin foco»
 * (`role="alert"`): el que acompaña a errores de campo, cuando el foco va al
 * primer campo con error.
 *
 * @returns el contenedor del aviso
 */
export function expectAnnouncedFormAlert(element: HTMLElement): HTMLElement {
  const alert = element.closest<HTMLElement>('[role="alert"]');
  expect(alert, 'el texto no está dentro de un aviso role="alert"').not.toBeNull();
  expect(alert).not.toHaveFocus();
  return alert!;
}
