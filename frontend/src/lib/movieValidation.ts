/**
 * Validación en el cliente de los formularios del panel de administración
 * (películas y géneros).
 *
 * Replica `MovieRequest`, `GenreRequest` y `HttpsUrlValidator` del backend (y,
 * para el tráiler, `@YouTubeUrl` a través de `youtube.ts`), con sus mismos
 * mensajes, para avisar al momento sin ir al servidor. Es solo comodidad: **la validación que manda es la del servidor**, y si responde con
 * `validationErrors` la pantalla los pinta igualmente junto a cada campo.
 *
 * Son funciones puras (sin React) para poder probar cada regla por separado.
 */
import type { MovieRequest } from './types';
import { validateTrailerUrl } from './youtube';

/** Límites de `MovieRequest` (`@Size`, `@Min`, `@Max`). */
export const MOVIE_TITLE_MAX = 150;
export const MOVIE_DESCRIPTION_MAX = 1000;
export const MOVIE_YEAR_MIN = 1888;
export const MOVIE_YEAR_MAX = 2100;
/**
 * Longitud máxima de `imageUrl`, `videoUrl` y `subtitlesUrl` (columnas
 * `VARCHAR(500)`; la del tráiler, en `youtube.ts`).
 */
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
/** Subtítulos demasiado largos (`SUBTITLES_URL_SIZE_MESSAGE` del backend; películas y episodios). */
export const SUBTITLES_URL_SIZE_MESSAGE = `La URL de los subtítulos no puede superar los ${URL_MAX} caracteres`;
/** Subtítulos que no son una URL `https://` de un archivo `.vtt` (`SUBTITLES_URL_FORMAT_MESSAGE`). */
export const SUBTITLES_URL_FORMAT_MESSAGE =
  'Los subtítulos deben ser un archivo .vtt (WebVTT) con una URL que empiece por https://';
/** Subtítulos válidos sin vídeo al que acompañar (`SUBTITLES_REQUIRE_VIDEO_MESSAGE`). */
export const SUBTITLES_REQUIRE_VIDEO_MESSAGE = 'Los subtítulos necesitan un vídeo: indica también la URL del vídeo';
export const GENRE_NAME_SIZE_MESSAGE = `El nombre del género debe tener entre ${GENRE_NAME_MIN} y ${GENRE_NAME_MAX} caracteres`;
/**
 * Título con caracteres de control (`MovieRequest.TITLE_CONTROL_CHARACTERS_MESSAGE`,
 * el mismo para películas, series y episodios).
 */
export const TITLE_CONTROL_CHARACTERS_MESSAGE = 'El título no puede contener caracteres de control';
/**
 * Sinopsis de una película con caracteres de control no permitidos
 * (`MovieRequest.DESCRIPTION_CONTROL_CHARACTERS_MESSAGE`). No dice «no puede
 * contener caracteres de control» porque sería falso: los saltos de línea
 * también lo son y se admiten.
 */
export const MOVIE_DESCRIPTION_CONTROL_CHARACTERS_MESSAGE = 'La sinopsis contiene caracteres de control no permitidos';

/**
 * Cualquier carácter de control (categoría Unicode `Cc`: U+0000 a U+001F,
 * U+007F y U+0080 a U+009F), tabulador y saltos de línea incluidos. Es la
 * regla `ControlCharacters.SINGLE_LINE_TEXT` del backend (`[^\p{Cc}]*`) vista
 * al revés: aquí se busca el carácter que sobra.
 */
const CONTROL_CHARACTER = /\p{Cc}/u;

/**
 * Un carácter de control que NO es tabulador, `\n` ni `\r`: lo que rechaza
 * `ControlCharacters.MULTILINE_TEXT` (`[\t\n\r[^\p{Cc}]]*`). Esos tres se
 * admiten porque la sinopsis se escribe en un `<textarea>` (Intro mete un
 * `\n`) y un texto pegado puede traer `\r\n` o tabuladores.
 */
const DISALLOWED_MULTILINE_CONTROL_CHARACTER = /(?![\t\n\r])\p{Cc}/u;

/**
 * Indica si un texto de UNA línea (un título) tiene algún carácter de control.
 *
 * Los caracteres de formato invisibles (`Cf`, como el espacio de ancho cero
 * U+200B) no son de control: el servidor los acepta y aquí también.
 *
 * @param value el texto tal como se enviará (ya recortado)
 */
export function hasControlCharacters(value: string): boolean {
  return CONTROL_CHARACTER.test(value);
}

/**
 * Indica si un texto de VARIAS líneas (una sinopsis) tiene algún carácter de
 * control distinto del tabulador y los saltos de línea (p. ej. el NUL U+0000,
 * que PostgreSQL no admite, o el ESC).
 *
 * @param value el texto tal como se enviará (ya recortado)
 */
export function hasDisallowedMultilineControlCharacters(value: string): boolean {
  return DISALLOWED_MULTILINE_CONTROL_CHARACTER.test(value);
}

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
  /** Opcional: vacío (o solo espacios) = sin vídeo (`null`). */
  videoUrl: string;
  /** Opcional: enlace de YouTube; vacío = sin tráiler (`null`). */
  trailerUrl: string;
  /** Opcional: subtítulos WebVTT; vacío = sin subtítulos (`null`). Exigen vídeo. */
  subtitlesUrl: string;
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
  trailerUrl: '',
  subtitlesUrl: '',
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
 * Valida la URL del vídeo (de una película o de un episodio): como la de la
 * portada, pero SIN portadas propias (solo `https://`).
 *
 * Es **opcional**: StreamBox no aloja vídeos, así que un título puede no
 * tenerlo (entonces no hay «Ver ahora» ni «Ver»). Vacío = sin vídeo, igual que
 * el backend, que guarda `null` con una cadena vacía o en blanco.
 *
 * @param value URL ya recortada (`trim`)
 * @returns el mensaje de error, o `undefined` si es válida o está vacía
 */
export function validateVideoUrl(value: string): string | undefined {
  if (!value) return undefined;
  if (value.length > URL_MAX) return VIDEO_URL_SIZE_MESSAGE;
  if (!isPrintableAscii(value) || !isAbsoluteHttpsUrl(value)) return VIDEO_URL_FORMAT_MESSAGE;
  return undefined;
}

/** Terminación obligatoria de la ruta de los subtítulos (archivo WebVTT). */
const SUBTITLES_EXTENSION = '.vtt';

/**
 * Indica si la RUTA de una URL termina en `.vtt`, sin distinguir mayúsculas.
 *
 * La ruta es lo que hay antes del primer `?` o `#`, igual que en el backend:
 * `subs.vtt?token=…` y `SUBS.VTT` valen; `subs.srt`, `subs.vtt.html` o
 * `/descargar?f=subs.vtt` (la terminación está en la consulta), no. Se corta el
 * texto tal cual y no se usa `URL` para no depender de cómo normaliza cada
 * navegador. Solo se llama con texto ASCII ya comprobado, así que `toLowerCase`
 * equivale a las «minúsculas ASCII» del servidor.
 *
 * @param value URL ya recortada
 */
function hasVttPath(value: string): boolean {
  const path = value.split(/[?#]/, 1)[0];
  return path.toLowerCase().endsWith(SUBTITLES_EXTENSION);
}

/**
 * Valida la URL de los subtítulos (de una película o de un episodio), con la
 * misma prioridad que el backend y UN mensaje por caso:
 *
 * 1. vacía → sin subtítulos (opcional, como el vídeo);
 * 2. más de {@link URL_MAX} caracteres → {@link SUBTITLES_URL_SIZE_MESSAGE};
 * 3. no es una URL `https://` válida (las reglas de {@link validateVideoUrl}) o
 *    su ruta no termina en `.vtt` → {@link SUBTITLES_URL_FORMAT_MESSAGE};
 * 4. válida pero SIN vídeo → {@link SUBTITLES_REQUIRE_VIDEO_MESSAGE}: unos
 *    subtítulos sin nada que reproducir no se verían nunca. El error va en este
 *    campo y no en el del vídeo (que es opcional y está bien vacío): lo que
 *    sobra o falta lo decide quien escribió los subtítulos.
 *
 * El vídeo cuenta como «hay» con solo no estar en blanco, aunque tenga su propio
 * error: ese se ve en su campo, y aquí se repetiría un aviso que no ayuda.
 *
 * @param value URL de los subtítulos, ya recortada (`trim`)
 * @param videoUrl URL del vídeo, ya recortada
 * @returns el mensaje de error, o `undefined` si es válida o está vacía
 */
export function validateSubtitlesUrl(value: string, videoUrl: string): string | undefined {
  if (!value) return undefined;
  if (value.length > URL_MAX) return SUBTITLES_URL_SIZE_MESSAGE;
  if (!isPrintableAscii(value) || !isAbsoluteHttpsUrl(value) || !hasVttPath(value)) return SUBTITLES_URL_FORMAT_MESSAGE;
  if (!videoUrl) return SUBTITLES_REQUIRE_VIDEO_MESSAGE;
  return undefined;
}

/**
 * Ayuda del campo de subtítulos en los formularios de película y de episodio.
 * Dice lo que el administrador necesita saber para que funcionen de verdad:
 * - qué archivo (WebVTT, `.vtt`, por https) y que se presentan como español
 *   (la pista se llama «Español»);
 * - que solo los usa el reproductor de StreamBox: un vídeo que no es un archivo
 *   directo (las terminaciones de `DIRECT_VIDEO_EXTENSIONS` en `lib/playback.ts`,
 *   todas listadas: lo vigila un test) se abre en otra pestaña, sin ellos. El
 *   «por https» del vídeo no se repite: su propio campo ya lo exige;
 * - CORS en los DOS servidores («ambos»: el del vídeo y el de los subtítulos):
 *   con una pista de otro origen el navegador también pide el vídeo con CORS.
 *   Si falla, el vídeo se ve sin subtítulos (nunca deja de verse), que es
 *   exactamente lo que dice la última frase.
 *
 * Corta a propósito (revisión QA, BAJA-4): la anterior ocupaba cinco líneas a 375 px.
 */
export const SUBTITLES_HINT =
  'Archivo WebVTT (.vtt) en español por https://. Solo en el reproductor de StreamBox (vídeo .mp4, .webm, .ogg u .ogv). Ambos servidores deben permitir CORS; si no, el vídeo irá sin ellos.';

/**
 * Quita el error «Los subtítulos necesitan un vídeo» ({@link SUBTITLES_REQUIRE_VIDEO_MESSAGE})
 * cuando el administrador cambia el vídeo: dependía de él y ya no se sabe si
 * sigue siendo verdad (se vuelve a comprobar al guardar). Los demás errores de
 * los subtítulos son de su propio texto y se quedan.
 *
 * @param errors errores actuales del formulario (película o episodio)
 * @returns los mismos errores, sin ese aviso si estaba
 */
export function withoutStaleSubtitlesError<T extends { subtitlesUrl?: string }>(errors: T): T {
  return errors.subtitlesUrl === SUBTITLES_REQUIRE_VIDEO_MESSAGE ? { ...errors, subtitlesUrl: undefined } : errors;
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
 * Los caracteres de control, en cambio, se buscan en el texto **recortado**,
 * que es el que llega al servidor: un tabulador o un salto de línea en un
 * extremo del título desaparece al enviar (`trim`) y el servidor nunca lo ve,
 * así que marcarlo sería un error que el servidor no daría. Uno en medio del
 * título sí se envía y sí es un error. El NUL (U+0000) no lo quita `trim`.
 *
 * @param values valores del formulario
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validateMovieForm(values: MovieFormValues): MovieFormErrors {
  const errors: MovieFormErrors = {};

  if (!values.title.trim()) errors.title = 'El título es obligatorio';
  else if (values.title.length > MOVIE_TITLE_MAX) {
    errors.title = `El título no puede superar los ${MOVIE_TITLE_MAX} caracteres`;
  } else if (hasControlCharacters(values.title.trim())) errors.title = TITLE_CONTROL_CHARACTERS_MESSAGE;

  if (!values.description.trim()) errors.description = 'La sinopsis es obligatoria';
  else if (values.description.length > MOVIE_DESCRIPTION_MAX) {
    errors.description = `La sinopsis no puede superar los ${MOVIE_DESCRIPTION_MAX} caracteres`;
  } else if (hasDisallowedMultilineControlCharacters(values.description.trim())) {
    errors.description = MOVIE_DESCRIPTION_CONTROL_CHARACTERS_MESSAGE;
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

  const trailerError = validateTrailerUrl(values.trailerUrl.trim());
  if (trailerError) errors.trailerUrl = trailerError;

  const subtitlesError = validateSubtitlesUrl(values.subtitlesUrl.trim(), values.videoUrl.trim());
  if (subtitlesError) errors.subtitlesUrl = subtitlesError;

  if (values.genreIds.length === 0) errors.genreIds = 'Elige al menos un género';

  return errors;
}

/**
 * Convierte un campo de URL opcional en su valor para la API: recortado, o
 * `null` si queda vacío («no tiene»). Exportada para episodios y series.
 */
export function optionalUrl(raw: string): string | null {
  return raw.trim() || null;
}

/**
 * Convierte el formulario (ya validado) en el cuerpo de la petición: recorta
 * los textos de los extremos, pasa los números a `number` y el vídeo, el
 * tráiler y los subtítulos vacíos a `null` (el servidor haría lo mismo con una cadena vacía;
 * mandarlo ya así deja claro en la petición que «no tiene»). El tráiler se
 * envía tal como se escribió: lo normaliza el servidor.
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
    videoUrl: optionalUrl(values.videoUrl),
    trailerUrl: optionalUrl(values.trailerUrl),
    subtitlesUrl: optionalUrl(values.subtitlesUrl),
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
