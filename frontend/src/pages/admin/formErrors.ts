/**
 * Ayudas comunes a los formularios del panel (películas y series) para los
 * errores por campo: repartir los `validationErrors` del servidor y llevar el
 * foco al primer campo con error.
 *
 * Archivo sin componentes (ver `adminPaths.ts`: el "fast refresh" de Vite pide
 * que un archivo de componentes solo exporte componentes).
 */

/**
 * Lleva el foco al primer campo con error, en el orden en que se ven.
 *
 * Si el elemento es un grupo (`<fieldset>`, como las casillas de géneros), el
 * foco va a su primer control: un `fieldset` no es enfocable y quien usa el
 * teclado tiene que aterrizar en algo que pueda cambiar.
 *
 * @param errors errores por campo (un campo sin error no aparece o es `undefined`)
 * @param order campos en el orden de la pantalla
 * @param ids `id` del elemento de cada campo
 */
export function focusFirstInvalidField<K extends string>(
  errors: Partial<Record<K, string>>,
  order: readonly K[],
  ids: Record<K, string>,
): void {
  const first = order.find((field) => errors[field]);
  if (!first) return;
  const element = document.getElementById(ids[first]);
  if (element instanceof HTMLFieldSetElement) element.querySelector<HTMLElement>('input, a, button')?.focus();
  else element?.focus();
}

/**
 * Reparte los `validationErrors` del servidor entre los campos que conoce el
 * formulario y los que no (p. ej. un error de un campo que la pantalla no
 * muestra): estos últimos se enseñan en el aviso general para que no se pierdan.
 *
 * @param validationErrors mapa campo → mensaje de la respuesta 400
 * @param ids campos del formulario (solo se miran sus claves)
 */
export function splitValidationErrors<K extends string>(
  validationErrors: Record<string, string>,
  ids: Record<K, string>,
): { perField: Partial<Record<K, string>>; unmatched: string[] } {
  const perField: Partial<Record<K, string>> = {};
  const unmatched: string[] = [];
  for (const [field, message] of Object.entries(validationErrors)) {
    if (Object.hasOwn(ids, field)) perField[field as K] = message;
    else unmatched.push(message);
  }
  return { perField, unmatched };
}
