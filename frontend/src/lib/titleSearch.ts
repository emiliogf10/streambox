import { ApiError } from './api';

/**
 * Reglas del texto de las búsquedas por título (`title` de `GET /api/movies/search`,
 * `GET /api/series/search` y `GET /api/admin/series`), compartidas por el buscador de la
 * barra y los del panel de administración.
 *
 * El servidor las valida (`TitleSearchParameter` en el backend) y, si no se cumplen,
 * responde 400 `VALIDATION_ERROR` con el motivo en `validationErrors.title`. Aquí se
 * replican para que el usuario **no llegue nunca a ese 400** por algo que no ve; si aun
 * así llega (p. ej. un `?q=` larguísimo en la URL del panel), {@link getTitleSearchError}
 * saca el mensaje real para enseñarlo junto al campo.
 */

/**
 * Longitud máxima del texto buscado: la misma que la de un título. Ningún título puede
 * contener un texto más largo que él mismo, así que no se pierde ninguna búsqueda útil.
 * Se usa como `maxLength` de los campos: el navegador mide en unidades UTF-16, igual que
 * el `@Size` del servidor, así que los dos cuentan exactamente lo mismo.
 */
export const TITLE_SEARCH_MAX_LENGTH = 150;

/**
 * Tramo de texto que contiene al menos un carácter de control (`\p{Cc}`: tabulador,
 * saltos de línea, NUL...) junto con los espacios que lo rodean.
 *
 * Es la misma categoría que rechaza el servidor. Los caracteres de formato invisibles
 * (`\p{Cf}`, como U+200B) no están incluidos: el servidor los acepta y quitarlos sería
 * cambiar una búsqueda que funciona.
 */
const CONTROL_RUN = /[\s\p{Cc}]*\p{Cc}[\s\p{Cc}]*/gu;

/**
 * Texto que de verdad se envía al buscar: sin caracteres de control y sin espacios a los lados.
 *
 * **Por qué se sustituyen por un espacio y no se borran.** Un tabulador o un salto de línea
 * llegan casi siempre al pegar texto copiado de una tabla o de varias líneas
 * («Blade\tRunner», «Star\nWars»): ahí separan palabras. Borrarlos pegaría las palabras
 * («BladeRunner») y la búsqueda no encontraría el título; un espacio conserva lo que el
 * usuario quería buscar. Todo el tramo (el carácter y los espacios que lo rodean) se queda en
 * **un solo** espacio porque la búsqueda compara el texto tal cual: «Blade \tRunner» con dos
 * espacios tampoco encontraría «Blade Runner». Los espacios dobles que el usuario escribe a
 * propósito no se tocan (no es este el sitio para cambiar cómo se busca).
 *
 * @param raw lo que hay en el campo (o en `?q=`)
 * @returns el texto limpio y recortado (`''` si no queda nada que buscar)
 */
export function normalizeTitleSearch(raw: string): string {
  return raw.replace(CONTROL_RUN, ' ').trim();
}

/**
 * Motivo por el que el servidor ha rechazado el texto de una búsqueda por título, o `null`
 * si el error es otro (red, 5xx, 403...: esos se tratan como un fallo normal de la carga).
 *
 * Se decide por `status` y `code` (contrato estable), y el texto que se enseña es el que
 * manda el servidor en `validationErrors.title` («La búsqueda no puede superar los 150
 * caracteres»), no el genérico «Los datos proporcionados no son válidos», que no dice qué
 * corregir. Se le añade el punto final para que se lea igual que el resto de avisos.
 *
 * @param error valor capturado de la petición
 */
export function getTitleSearchError(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 400 || error.code !== 'VALIDATION_ERROR') return null;
  const message = error.validationErrors?.title?.trim();
  if (!message) return null;
  return /[.!?…]$/.test(message) ? message : `${message}.`;
}
