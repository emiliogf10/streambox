/**
 * Validación en el cliente del formulario de episodios del panel de
 * administración, y los valores que propone al añadir uno.
 *
 * Replica `EpisodeRequest` del backend **con sus mismos mensajes**, letra por
 * letra (igual que `seriesValidation.ts` con `SeriesRequest`), para que el
 * administrador lea lo mismo lo detecte el cliente o el servidor. La URL del
 * vídeo usa exactamente las reglas y mensajes de las películas
 * (`validateVideoUrl`), que es lo que hace también el servidor.
 *
 * Es solo comodidad: **manda la validación del servidor**. Sus
 * `validationErrors` se pintan igualmente junto a cada campo, y lo que el
 * cliente no puede saber (que ya exista ese episodio: 409
 * `EPISODE_ALREADY_EXISTS`) llega del servidor.
 *
 * Funciones puras (sin React) para poder probar cada regla por separado.
 */
import { MOVIE_DESCRIPTION_MAX, MOVIE_TITLE_MAX, parseWholeNumber, validateVideoUrl } from './movieValidation';
import type { Episode, EpisodeRequest, Season } from './types';

/** Límites de `EpisodeRequest` (`@Min`, `@Max`, `@Size`). */
export const EPISODE_SEASON_MIN = 1;
export const EPISODE_SEASON_MAX = 100;
export const EPISODE_NUMBER_MIN = 1;
export const EPISODE_NUMBER_MAX = 1000;
export const EPISODE_DURATION_MIN = 1;
export const EPISODE_DURATION_MAX = 600;
export const EPISODE_TITLE_MAX = MOVIE_TITLE_MAX;
export const EPISODE_DESCRIPTION_MAX = MOVIE_DESCRIPTION_MAX;

/** Mensajes de `EpisodeRequest` (contrato con el backend; sin punto final, como allí). */
export const EPISODE_MESSAGES = {
  seasonRequired: 'La temporada es obligatoria',
  seasonRange: `La temporada debe estar entre ${EPISODE_SEASON_MIN} y ${EPISODE_SEASON_MAX}`,
  numberRequired: 'El número de episodio es obligatorio',
  numberRange: `El número de episodio debe estar entre ${EPISODE_NUMBER_MIN} y ${EPISODE_NUMBER_MAX}`,
  titleRequired: 'El título es obligatorio',
  titleSize: `El título no puede superar los ${EPISODE_TITLE_MAX} caracteres`,
  descriptionSize: `La descripción no puede superar los ${EPISODE_DESCRIPTION_MAX} caracteres`,
  durationRequired: 'La duración es obligatoria',
  durationRange: `La duración debe estar entre ${EPISODE_DURATION_MIN} y ${EPISODE_DURATION_MAX} minutos`,
} as const;

/** Campos del formulario de episodio tal como se escriben (los números, como texto). */
export interface EpisodeFormValues {
  seasonNumber: string;
  episodeNumber: string;
  title: string;
  /** Opcional: vacío (o solo espacios) = sin sinopsis (`null`). */
  description: string;
  duration: string;
  videoUrl: string;
}

/** Errores por campo del formulario de episodio; un campo sin error no aparece. */
export type EpisodeFormErrors = Partial<Record<keyof EpisodeFormValues, string>>;

/**
 * Entero dentro de `[min, max]` o `null` (vacío, con decimales, con letras o
 * fuera de rango). Lo mismo que aceptaría el `Integer` del servidor con sus
 * `@Min`/`@Max`.
 */
export function parseInRange(raw: string, min: number, max: number): number | null {
  const value = parseWholeNumber(raw);
  return value !== null && value >= min && value <= max ? value : null;
}

/**
 * Valida un número obligatorio con rango. Como en series, un texto que no es
 * un entero recibe el mensaje de rango: el servidor nunca lo vería (no sería
 * JSON válido para un `Integer`) y "debe estar entre 1 y 100" ya dice qué se espera.
 */
function validateRequiredRange(
  raw: string,
  min: number,
  max: number,
  required: string,
  range: string,
): string | undefined {
  if (!raw.trim()) return required;
  return parseInRange(raw, min, max) === null ? range : undefined;
}

/**
 * Valida el formulario de episodio completo. Cada campo recibe UN error, en
 * el mismo orden de comprobación que el servidor (vacío → longitud/rango → formato).
 *
 * @param values valores del formulario
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validateEpisodeForm(values: EpisodeFormValues): EpisodeFormErrors {
  const errors: EpisodeFormErrors = {};

  const season = validateRequiredRange(
    values.seasonNumber,
    EPISODE_SEASON_MIN,
    EPISODE_SEASON_MAX,
    EPISODE_MESSAGES.seasonRequired,
    EPISODE_MESSAGES.seasonRange,
  );
  if (season) errors.seasonNumber = season;

  const number = validateRequiredRange(
    values.episodeNumber,
    EPISODE_NUMBER_MIN,
    EPISODE_NUMBER_MAX,
    EPISODE_MESSAGES.numberRequired,
    EPISODE_MESSAGES.numberRange,
  );
  if (number) errors.episodeNumber = number;

  if (!values.title.trim()) errors.title = EPISODE_MESSAGES.titleRequired;
  else if (values.title.length > EPISODE_TITLE_MAX) errors.title = EPISODE_MESSAGES.titleSize;

  // La sinopsis es opcional: solo se mira su longitud (lo que cuenta el contador del campo).
  if (values.description.length > EPISODE_DESCRIPTION_MAX) errors.description = EPISODE_MESSAGES.descriptionSize;

  const duration = validateRequiredRange(
    values.duration,
    EPISODE_DURATION_MIN,
    EPISODE_DURATION_MAX,
    EPISODE_MESSAGES.durationRequired,
    EPISODE_MESSAGES.durationRange,
  );
  if (duration) errors.duration = duration;

  const video = validateVideoUrl(values.videoUrl.trim());
  if (video) errors.videoUrl = video;

  return errors;
}

/**
 * Convierte el formulario (ya validado) en el cuerpo de la petición: recorta
 * los textos, pasa los números a `number` y una sinopsis vacía a `null`. El
 * servidor también guardaría `null` con una en blanco; mandarlo ya así deja
 * claro en la petición que "no tiene sinopsis".
 */
export function toEpisodeRequest(values: EpisodeFormValues): EpisodeRequest {
  const description = values.description.trim();
  return {
    seasonNumber: Number(values.seasonNumber.trim()),
    episodeNumber: Number(values.episodeNumber.trim()),
    title: values.title.trim(),
    description: description || null,
    duration: Number(values.duration.trim()),
    videoUrl: values.videoUrl.trim(),
  };
}

/** Pasa un episodio de la API a los valores del formulario (edición). Sin sinopsis = campo vacío. */
export function toEpisodeFormValues(episode: Episode): EpisodeFormValues {
  return {
    seasonNumber: String(episode.seasonNumber),
    episodeNumber: String(episode.episodeNumber),
    title: episode.title,
    description: episode.description ?? '',
    duration: String(episode.duration),
    videoUrl: episode.videoUrl,
  };
}

/**
 * Número que se propone para un episodio nuevo de `seasonNumber`.
 *
 * - Temporada sin episodios (o que aún no existe): el **1**.
 * - Si no, el **siguiente al más alto** (con 1, 2 y 5 propone el 6, no el 3):
 *   lo habitual es añadir los episodios en orden, y un hueco suele ser un
 *   episodio que se añadirá a propósito más tarde, no un despiste.
 * - Solo si el siguiente pasa del máximo (1000) se busca el primer hueco libre;
 *   si no queda ninguno, `null` (el campo se deja vacío y el admin decide).
 *
 * @param seasons temporadas de la serie con sus episodios
 * @param seasonNumber temporada elegida
 */
export function nextEpisodeNumber(seasons: readonly Season[], seasonNumber: number): number | null {
  const used = new Set(
    seasons.find((season) => season.seasonNumber === seasonNumber)?.episodes.map((episode) => episode.episodeNumber) ?? [],
  );
  if (used.size === 0) return EPISODE_NUMBER_MIN;
  const next = Math.max(...used) + 1;
  if (next <= EPISODE_NUMBER_MAX) return next;
  for (let candidate = EPISODE_NUMBER_MIN; candidate <= EPISODE_NUMBER_MAX; candidate += 1) {
    if (!used.has(candidate)) return candidate;
  }
  return null;
}

/**
 * Valores iniciales del formulario para AÑADIR un episodio: la **última
 * temporada** existente (o la 1 si la serie está vacía) y el siguiente número
 * de esa temporada ({@link nextEpisodeNumber}). Es lo que se hace casi siempre
 * (seguir donde se quedó la serie) y se puede cambiar.
 *
 * @param seasons temporadas de la serie, ordenadas por número (como las devuelve la API)
 */
export function newEpisodeFormValues(seasons: readonly Season[]): EpisodeFormValues {
  const season = seasons.length > 0 ? Math.max(...seasons.map((item) => item.seasonNumber)) : EPISODE_SEASON_MIN;
  const number = nextEpisodeNumber(seasons, season);
  return {
    seasonNumber: String(season),
    episodeNumber: number === null ? '' : String(number),
    title: '',
    description: '',
    duration: '',
    videoUrl: '',
  };
}
