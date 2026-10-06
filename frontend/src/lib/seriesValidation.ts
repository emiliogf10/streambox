/**
 * Validación en el cliente del formulario de series del panel de administración.
 *
 * Replica `SeriesRequest` y `ValidSeriesYears` del backend **con sus mismos
 * mensajes**, letra por letra, para que el administrador lea lo mismo lo
 * detecte el cliente o el servidor. A diferencia de `MovieRequest`, todas las
 * restricciones de `SeriesRequest` llevan su mensaje explícito, así que aquí
 * se copian tal cual (por eso la sinopsis se llama «descripción» en los
 * errores: es el texto del servidor).
 *
 * Lo que es idéntico en películas (formato y longitud de la URL de la portada,
 * años válidos, longitudes de título y sinopsis, lectura de números) se importa
 * de `movieValidation.ts` en lugar de repetirse: si cambia una regla común,
 * cambia en un solo sitio.
 *
 * Como en películas, esto es solo comodidad: **manda la validación del
 * servidor**, y sus `validationErrors` también se pintan junto a cada campo.
 */
import {
  MOVIE_DESCRIPTION_MAX,
  MOVIE_TITLE_MAX,
  MOVIE_YEAR_MAX,
  MOVIE_YEAR_MIN,
  parseWholeNumber,
  validateImageUrl,
} from './movieValidation';
import type { SeriesRequest } from './types';

/** Límites de `SeriesRequest`: los mismos que los de una película. */
export const SERIES_TITLE_MAX = MOVIE_TITLE_MAX;
export const SERIES_DESCRIPTION_MAX = MOVIE_DESCRIPTION_MAX;
export const SERIES_YEAR_MIN = MOVIE_YEAR_MIN;
export const SERIES_YEAR_MAX = MOVIE_YEAR_MAX;
/** Máximo de géneros por título (`MovieRequest.MAX_GENRES`, compartido con las películas). */
export const SERIES_MAX_GENRES = 20;

/** Mensajes de `SeriesRequest` (contrato con el backend; sin punto final, como allí). */
export const SERIES_MESSAGES = {
  titleRequired: 'El título es obligatorio',
  titleSize: `El título no puede superar los ${SERIES_TITLE_MAX} caracteres`,
  descriptionRequired: 'La descripción es obligatoria',
  descriptionSize: `La descripción no puede superar los ${SERIES_DESCRIPTION_MAX} caracteres`,
  releaseYearRequired: 'El año de estreno es obligatorio',
  releaseYearRange: `El año de estreno debe estar entre ${SERIES_YEAR_MIN} y ${SERIES_YEAR_MAX}`,
  endYearRange: `El año de finalización debe estar entre ${SERIES_YEAR_MIN} y ${SERIES_YEAR_MAX}`,
  endYearBeforeRelease: 'El año de finalización no puede ser anterior al año de estreno',
  imageUrlRequired: 'La URL de la imagen es obligatoria',
  genresRequired: 'Indica al menos un género',
  genresSize: `Una serie puede tener como máximo ${SERIES_MAX_GENRES} géneros`,
} as const;

/** Campos del formulario de serie tal como se escriben (los años, como texto). */
export interface SeriesFormValues {
  title: string;
  description: string;
  /** Texto del campo; se convierte a número al enviar. */
  releaseYear: string;
  /** Texto del campo; vacío = sigue en emisión (`endYear: null`). */
  endYear: string;
  imageUrl: string;
  genreIds: number[];
}

/** Errores por campo del formulario de serie; un campo sin error no aparece. */
export type SeriesFormErrors = Partial<Record<keyof SeriesFormValues, string>>;

/** Formulario vacío (alta de una serie nueva). */
export const EMPTY_SERIES_FORM: SeriesFormValues = {
  title: '',
  description: '',
  releaseYear: '',
  endYear: '',
  imageUrl: '',
  genreIds: [],
};

/** Año válido (entero entre 1888 y 2100) o `null`. */
function parseYear(raw: string): number | null {
  const year = parseWholeNumber(raw);
  return year !== null && year >= SERIES_YEAR_MIN && year <= SERIES_YEAR_MAX ? year : null;
}

/**
 * Valida la URL de la portada de una serie.
 *
 * Solo cambia el mensaje de "vacía" (el servidor dice «La URL de la imagen es
 * obligatoria» en series); longitud y formato son exactamente las reglas y
 * mensajes de las películas, así que se delega en `validateImageUrl`.
 *
 * @param value URL ya recortada (`trim`)
 * @returns el mensaje de error, o `undefined` si es válida
 */
export function validateSeriesImageUrl(value: string): string | undefined {
  if (!value) return SERIES_MESSAGES.imageUrlRequired;
  return validateImageUrl(value);
}

/**
 * Valida el formulario de serie completo.
 *
 * **Años.** Un texto que no es un número entero recibe el mensaje de rango: el
 * servidor nunca lo vería (no es JSON válido para un `Integer`) y "debe estar
 * entre 1888 y 2100" ya explica qué se espera. La regla "fin no anterior al
 * estreno" solo se comprueba con los dos años válidos, igual que
 * `ValidSeriesYearsValidator`: así cada campo tiene UN error y es el mismo que
 * daría el servidor.
 *
 * @param values valores del formulario
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validateSeriesForm(values: SeriesFormValues): SeriesFormErrors {
  const errors: SeriesFormErrors = {};

  if (!values.title.trim()) errors.title = SERIES_MESSAGES.titleRequired;
  else if (values.title.length > SERIES_TITLE_MAX) errors.title = SERIES_MESSAGES.titleSize;

  if (!values.description.trim()) errors.description = SERIES_MESSAGES.descriptionRequired;
  else if (values.description.length > SERIES_DESCRIPTION_MAX) errors.description = SERIES_MESSAGES.descriptionSize;

  const releaseYear = parseYear(values.releaseYear);
  if (!values.releaseYear.trim()) errors.releaseYear = SERIES_MESSAGES.releaseYearRequired;
  else if (releaseYear === null) errors.releaseYear = SERIES_MESSAGES.releaseYearRange;

  if (values.endYear.trim()) {
    const endYear = parseYear(values.endYear);
    if (endYear === null) errors.endYear = SERIES_MESSAGES.endYearRange;
    else if (releaseYear !== null && endYear < releaseYear) errors.endYear = SERIES_MESSAGES.endYearBeforeRelease;
  }

  const imageError = validateSeriesImageUrl(values.imageUrl.trim());
  if (imageError) errors.imageUrl = imageError;

  if (values.genreIds.length === 0) errors.genreIds = SERIES_MESSAGES.genresRequired;
  else if (values.genreIds.length > SERIES_MAX_GENRES) errors.genreIds = SERIES_MESSAGES.genresSize;

  return errors;
}

/**
 * Convierte el formulario (ya validado) en el cuerpo de la petición: recorta
 * los textos, pasa los años a número y un año de fin vacío a `null` ("en
 * emisión"; el servidor distingue `null` de un año).
 */
export function toSeriesRequest(values: SeriesFormValues): SeriesRequest {
  const endYear = values.endYear.trim();
  return {
    title: values.title.trim(),
    description: values.description.trim(),
    releaseYear: Number(values.releaseYear.trim()),
    endYear: endYear ? Number(endYear) : null,
    imageUrl: values.imageUrl.trim(),
    genreIds: [...values.genreIds],
  };
}
