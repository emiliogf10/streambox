/**
 * Validación en el cliente de los formularios del panel de administración
 * (películas y géneros).
 *
 * Replica `MovieRequest`, `GenreRequest` y `HttpsUrlValidator` del backend, con
 * sus mismos mensajes, para avisar al momento sin ir al servidor. Es solo
 * comodidad: **la validación que manda es la del servidor**, y si responde con
 * `validationErrors` la pantalla los pinta igualmente junto a cada campo.
 *
 * Son funciones puras (sin React) para poder probar cada regla por separado.
 */
import type { MovieRequest } from './types';

/** Límites de `MovieRequest` (`@Size`, `@Min`, `@Max`). */
export const MOVIE_TITLE_MAX = 150;
export const MOVIE_DESCRIPTION_MAX = 1000;
export const MOVIE_YEAR_MIN = 1888;
export const MOVIE_YEAR_MAX = 2100;
/** Longitud máxima de `imageUrl` y `videoUrl` (columnas `VARCHAR(500)`). */
export const URL_MAX = 500;
/**
 * Mayor entero que cabe en un `Integer` de Java (`duration` y `releaseYear`).
 * Un número mayor ni siquiera llegaría a validarse: el servidor no podría leer
 * el JSON y respondería con un error genérico.
 */
const JAVA_INT_MAX = 2_147_483_647;

/** Límites de `GenreRequest`. */
export const GENRE_NAME_MIN = 2;
export const GENRE_NAME_MAX = 50;

/*
 * Mensajes del contrato con el backend (constantes de `MovieRequest` y
 * `GenreRequest`). Se copian literalmente, sin punto final, para que el
 * usuario lea lo mismo lo detecte el cliente o el servidor.
 */
export const IMAGE_URL_FORMAT_MESSAGE =
  'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)';
export const IMAGE_URL_SIZE_MESSAGE = `La URL de la imagen no puede superar los ${URL_MAX} caracteres`;
export const VIDEO_URL_FORMAT_MESSAGE = 'La URL del vídeo debe empezar por https://';
export const VIDEO_URL_SIZE_MESSAGE = `La URL del vídeo no puede superar los ${URL_MAX} caracteres`;
export const GENRE_NAME_SIZE_MESSAGE = `El nombre del género debe tener entre ${GENRE_NAME_MIN} y ${GENRE_NAME_MAX} caracteres`;

/** Prefijo obligatorio de las URL absolutas, en minúsculas (el backend rechaza `HTTPS://`). */
const HTTPS_PREFIX = 'https://';

/**
 * Portada propia servida por el frontend (`public/covers`): `/covers/` y un
 * nombre de archivo sin subcarpetas que empieza por letra o número. Es la misma
 * expresión que el backend: sin `/`, `?`, `#` ni `%` no se puede salir de la
 * carpeta (`/covers/../x`) ni colar caracteres codificados (`%2e%2e`).
 */
const LOCAL_COVER = /^\/covers\/[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** Campos del formulario de película tal como se escriben (los números, como texto). */
export interface MovieFormValues {
  title: string;
  description: string;
  /** Texto del campo; se convierte a número al enviar. */
  duration: string;
  /** Texto del campo; se convierte a número al enviar. */
  releaseYear: string;
  imageUrl: string;
  videoUrl: string;
  genreIds: number[];
}

/** Errores por campo del formulario de película; un campo sin error no aparece. */
export type MovieFormErrors = Partial<Record<keyof MovieFormValues, string>>;

/** Formulario vacío (alta de una película nueva). */
export const EMPTY_MOVIE_FORM: MovieFormValues = {
  title: '',
  description: '',
  duration: '',
  releaseYear: '',
  imageUrl: '',
  videoUrl: '',
  genreIds: [],
};

/**
 * Indica si el texto solo tiene caracteres ASCII visibles (del `!` al `~`).
 *
 * Igual que el backend: descarta espacios interiores, saltos de línea,
 * caracteres de control y cualquier carácter no ASCII, incluidos los invisibles
 * (espacio de ancho cero, marcas de dirección) que pueden disfrazar una URL.
 */
function isPrintableAscii(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 0x20 || code >= 0x7f) return false;
  }
  return true;
}

/**
 * Indica si el texto es una URL `https://` absoluta, con host y sin credenciales.
 *
 * - El prefijo literal `https://` (en minúsculas) descarta `http:`,
 *   `javascript:`, `data:`, `ftp:`, las URL relativas al protocolo (`//host`)...
 * - La **autoridad** (lo que hay entre `https://` y la primera `/`, `?` o `#`)
 *   no puede estar vacía (`https:///ruta`) ni contener `@`: con
 *   `https://banco.com@malo.com/` el navegador iría a `malo.com`, y cualquier
 *   `@` es la marca de unas credenciales (`usuario:clave@`).
 * - `URL` (el analizador estándar del navegador) rechaza lo mal formado
 *   (`https://`, puertos no numéricos...) y confirma que hay host.
 *
 * Se mira la autoridad en el texto original porque `URL` normaliza: convierte
 * `https:///ruta` en `https://ruta/` y daría por buena una URL sin host.
 */
export function isAbsoluteHttpsUrl(value: string): boolean {
  if (!value.startsWith(HTTPS_PREFIX)) return false;
  const authority = value.slice(HTTPS_PREFIX.length).split(/[/?#]/, 1)[0];
  if (!authority || authority.includes('@')) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname !== '';
  } catch {
    return false;
  }
}

/** Indica si el texto es una portada propia válida (`/covers/<archivo>`). */
export function isLocalCover(value: string): boolean {
  return LOCAL_COVER.test(value);
}

/**
 * Valida la URL de la portada. El orden de las comprobaciones es el del
 * backend: vacía → demasiado larga → formato; así cada caso recibe UN mensaje.
 *
 * @param value URL ya recortada (`trim`)
 * @returns el mensaje de error, o `undefined` si es válida
 */
export function validateImageUrl(value: string): string | undefined {
  if (!value) return 'La URL de la portada es obligatoria';
  if (value.length > URL_MAX) return IMAGE_URL_SIZE_MESSAGE;
  if (!isPrintableAscii(value)) return IMAGE_URL_FORMAT_MESSAGE;
  if (isLocalCover(value) || isAbsoluteHttpsUrl(value)) return undefined;
  return IMAGE_URL_FORMAT_MESSAGE;
}

/**
 * Valida la URL del vídeo: como la de la portada, pero SIN portadas propias
 * (solo `https://`).
 *
 * @param value URL ya recortada (`trim`)
 * @returns el mensaje de error, o `undefined` si es válida
 */
export function validateVideoUrl(value: string): string | undefined {
  if (!value) return 'La URL del vídeo es obligatoria';
  if (value.length > URL_MAX) return VIDEO_URL_SIZE_MESSAGE;
  if (!isPrintableAscii(value) || !isAbsoluteHttpsUrl(value)) return VIDEO_URL_FORMAT_MESSAGE;
  return undefined;
}

/**
 * URL que puede usar la vista previa de la portada, o `null` si no cumple las
 * reglas. Así la vista previa nunca pide al navegador una URL que el servidor
 * rechazaría (p. ej. `http://`, que además daría contenido mixto en HTTPS).
 *
 * @param raw texto del campo, tal cual
 */
export function getPreviewImageUrl(raw: string): string | null {
  const value = raw.trim();
  return validateImageUrl(value) === undefined ? value : null;
}

/**
 * Convierte un texto de solo dígitos a número; `null` si no es un entero que quepa en un `Integer`.
 * Exportada para que la validación de series (`seriesValidation.ts`) lea los años igual.
 */
export function parseWholeNumber(raw: string): number | null {
  const value = raw.trim();
  if (!/^\d+$/.test(value)) return null;
  const number = Number(value);
  return number <= JAVA_INT_MAX ? number : null;
}

/**
 * Valida el formulario de película completo.
 *
 * Los textos se miden tal como se ven en el campo (el contador de la sinopsis
 * cuenta lo mismo); al enviar se recortan los espacios de los extremos.
 *
 * @param values valores del formulario
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validateMovieForm(values: MovieFormValues): MovieFormErrors {
  const errors: MovieFormErrors = {};

  if (!values.title.trim()) errors.title = 'El título es obligatorio';
  else if (values.title.length > MOVIE_TITLE_MAX) {
    errors.title = `El título no puede superar los ${MOVIE_TITLE_MAX} caracteres`;
  }

  if (!values.description.trim()) errors.description = 'La sinopsis es obligatoria';
  else if (values.description.length > MOVIE_DESCRIPTION_MAX) {
    errors.description = `La sinopsis no puede superar los ${MOVIE_DESCRIPTION_MAX} caracteres`;
  }

  if (!values.duration.trim()) errors.duration = 'Indica la duración en minutos';
  else {
    const duration = parseWholeNumber(values.duration);
    if (duration === null || duration < 1) {
      errors.duration = 'La duración debe ser un número entero de minutos mayor que 0';
    }
  }

  if (!values.releaseYear.trim()) errors.releaseYear = 'Indica el año de estreno';
  else {
    const year = parseWholeNumber(values.releaseYear);
    if (year === null || year < MOVIE_YEAR_MIN || year > MOVIE_YEAR_MAX) {
      errors.releaseYear = `El año debe estar entre ${MOVIE_YEAR_MIN} y ${MOVIE_YEAR_MAX}`;
    }
  }

  const imageError = validateImageUrl(values.imageUrl.trim());
  if (imageError) errors.imageUrl = imageError;

  const videoError = validateVideoUrl(values.videoUrl.trim());
  if (videoError) errors.videoUrl = videoError;

  if (values.genreIds.length === 0) errors.genreIds = 'Elige al menos un género';

  return errors;
}

/**
 * Convierte el formulario (ya validado) en el cuerpo de la petición: recorta
 * los textos de los extremos y pasa los números a `number`.
 *
 * Recortar las URL en el cliente es deliberado: un espacio pegado sin querer
 * al copiar una dirección haría que el servidor la rechazara (solo admite
 * caracteres visibles), y el usuario no vería por qué.
 */
export function toMovieRequest(values: MovieFormValues): MovieRequest {
  return {
    title: values.title.trim(),
    description: values.description.trim(),
    duration: Number(values.duration.trim()),
    releaseYear: Number(values.releaseYear.trim()),
    imageUrl: values.imageUrl.trim(),
    videoUrl: values.videoUrl.trim(),
    genreIds: [...values.genreIds],
  };
}

/**
 * Valida el nombre de un género.
 *
 * El servidor normaliza el nombre antes de medirlo (recorta los extremos y
 * colapsa los espacios interiores en uno), así que aquí se mide igual: "  a  "
 * cuenta como "a" (1 carácter) y se rechaza en el acto.
 *
 * @param raw texto del campo, tal cual
 * @returns el mensaje de error, o `undefined` si es válido
 */
export function validateGenreName(raw: string): string | undefined {
  const normalized = raw.trim().replace(/\s+/g, ' ');
  if (!normalized) return 'El nombre del género es obligatorio';
  if (normalized.length < GENRE_NAME_MIN || normalized.length > GENRE_NAME_MAX) return GENRE_NAME_SIZE_MESSAGE;
  return undefined;
}
