/**
 * Tráileres de YouTube: validación y normalización del enlace (la misma que el
 * backend con `@YouTubeUrl`) y construcción de la dirección del reproductor
 * incrustado.
 *
 * **Por qué un módulo propio y no `getSafeVideoUrl`.** El vídeo de una película
 * se abre en una pestaña nueva: basta con que sea http(s) sin credenciales. El
 * tráiler, en cambio, se INCRUSTA en la página con un `<iframe>`, y lo que se
 * incrusta tiene que ser exactamente el reproductor de YouTube y nada más. Por
 * eso aquí no se valida «una URL», sino unas pocas formas fijas de enlace de
 * YouTube, y el `<iframe>` nunca recibe el texto que vino de la API: se
 * construye de cero con el id del vídeo, que se revalida con
 * {@link YOUTUBE_ID_PATTERN} (11 caracteres de `[A-Za-z0-9_-]`). Aunque la base
 * de datos guardara algo malicioso, no hay forma de que acabe en el `src`.
 *
 * Formas aceptadas (contrato con el backend; siempre `https://` en minúsculas,
 * sin credenciales, sin puerto y sin fragmento `#`):
 * - `https://www.youtube.com/watch?v=<id>` (también `m.youtube.com` y
 *   `youtube.com` a secas); el resto de parámetros (`&t=30s`, `&list=...`) se ignora.
 * - `https://youtu.be/<id>` (con parámetros opcionales, p. ej. `?si=...`).
 * - `https://www.youtube.com/embed/<id>` y `https://www.youtube-nocookie.com/embed/<id>`.
 *
 * El servidor guarda siempre la forma normalizada
 * `https://www.youtube.com/watch?v=<id>`, así que la API solo devuelve esa.
 */

/** Longitud máxima del enlace del tráiler (columna `VARCHAR(500)`). */
export const TRAILER_URL_MAX = 500;

/** Mensajes del contrato con el backend (sin punto final, como allí). */
export const TRAILER_URL_FORMAT_MESSAGE = 'El tráiler debe ser un enlace de un vídeo de YouTube';
export const TRAILER_URL_SIZE_MESSAGE = `La URL del tráiler no puede superar los ${TRAILER_URL_MAX} caracteres`;

/** Id de un vídeo de YouTube: exactamente 11 caracteres de letras, dígitos, `_` o `-`. */
export const YOUTUBE_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;

/**
 * Un carácter válido en los parámetros (`?...`), con el mismo criterio que
 * `java.net.URI` en el backend: todo salvo el fragmento `#`, espacios y
 * controles, lo que no es ASCII, `< > " { } | ^`, el acento grave y la barra
 * invertida, y un `%` solo si va
 * seguido de dos dígitos hexadecimales (`%20`; `%`, `%4` o `%zz` → 400). Los
 * corchetes `[ ]` sí valen: `URI` los admite en la query (`a[]=1`). Si el
 * cliente aceptara algo que el servidor rechaza (o al revés), el formulario
 * daría por bueno un enlace al que la API responde 400. La paridad la vigila el
 * catálogo compartido `src/test/youtubeTrailerUrlCases.json`.
 */
const QUERY_CHAR = '(?:[^#<>"{}|^`\\\\%\\u0000-\\u0020\\u007F-\\uFFFF]|%[0-9A-Fa-f]{2})';

/** Parámetros opcionales tras la ruta (`?...`), sin fragmento. */
const OPTIONAL_QUERY = `(?:\\?${QUERY_CHAR}*)?`;

/**
 * Formas cuyo id va en la RUTA (`youtu.be/<id>`, `/embed/<id>`). Expresiones
 * ancladas de principio a fin: tras el host viene obligatoriamente `/`, así que
 * `youtube.com.evil.com`, `youtube.com:443` o `usuario@youtube.com` no encajan.
 */
const PATH_ID_PATTERNS: readonly RegExp[] = [
  new RegExp(`^https://youtu\\.be/([A-Za-z0-9_-]{11})${OPTIONAL_QUERY}$`),
  new RegExp(`^https://www\\.youtube\\.com/embed/([A-Za-z0-9_-]{11})${OPTIONAL_QUERY}$`),
  new RegExp(`^https://www\\.youtube-nocookie\\.com/embed/([A-Za-z0-9_-]{11})${OPTIONAL_QUERY}$`),
];

/** Forma `watch`: el id va en el parámetro `v`, entre otros que se ignoran. */
const WATCH_PATTERN = new RegExp(`^https://(?:www\\.|m\\.)?youtube\\.com/watch\\?(${QUERY_CHAR}*)$`);

/** Forma normalizada, la única que devuelve la API (y la única de la que se saca el id para incrustarlo). */
const NORMALIZED_PATTERN = /^https:\/\/www\.youtube\.com\/watch\?v=([A-Za-z0-9_-]{11})$/;

/**
 * Indica si el texto solo tiene caracteres ASCII visibles (del `!` al `~`), igual
 * que el resto de validaciones de URL: descarta espacios, saltos de línea y los
 * caracteres invisibles que pueden disfrazar un enlace.
 */
function isPrintableAscii(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 0x20 || code >= 0x7f) return false;
  }
  return true;
}

/**
 * Saca el id del parámetro `v` de la parte `?...` de un enlace `watch`.
 *
 * Tiene que haber **exactamente un** `v` y con un id válido: con dos
 * (`?v=AAA&v=BBB`) no está claro qué vídeo se quiso enlazar y se rechaza.
 */
function idFromWatchQuery(query: string): string | null {
  const ids = query
    .split('&')
    .filter((pair) => pair.startsWith('v='))
    .map((pair) => pair.slice(2));
  return ids.length === 1 && YOUTUBE_ID_PATTERN.test(ids[0]) ? ids[0] : null;
}

/** Enlace normalizado de un id ya validado. */
function watchUrl(id: string): string {
  return `https://www.youtube.com/watch?v=${id}`;
}

/**
 * Normaliza un enlace de YouTube a `https://www.youtube.com/watch?v=<id>`.
 *
 * @param value enlace ya recortado (`trim`)
 * @returns la forma normalizada, o `null` si no es una de las formas aceptadas
 */
export function normalizeYouTubeUrl(value: string): string | null {
  if (!value || value.length > TRAILER_URL_MAX || !isPrintableAscii(value)) return null;
  for (const pattern of PATH_ID_PATTERNS) {
    const match = pattern.exec(value);
    if (match) return watchUrl(match[1]);
  }
  const watch = WATCH_PATTERN.exec(value);
  if (!watch) return null;
  const id = idFromWatchQuery(watch[1]);
  return id ? watchUrl(id) : null;
}

/**
 * Valida el campo «Tráiler» de los formularios del panel. Es **opcional**:
 * vacío = sin tráiler. Mismo orden que el backend (longitud → formato), así que
 * cada caso recibe un único mensaje.
 *
 * @param value enlace ya recortado (`trim`)
 * @returns el mensaje de error, o `undefined` si es válido o está vacío
 */
export function validateTrailerUrl(value: string): string | undefined {
  if (!value) return undefined;
  if (value.length > TRAILER_URL_MAX) return TRAILER_URL_SIZE_MESSAGE;
  return normalizeYouTubeUrl(value) === null ? TRAILER_URL_FORMAT_MESSAGE : undefined;
}

/** Direcciones de un tráiler listo para mostrarse (ver {@link getTrailer}). */
export interface Trailer {
  /** Reproductor incrustado: `https://www.youtube-nocookie.com/embed/<id>?autoplay=1&rel=0`. */
  embedUrl: string;
  /** Enlace normalizado para «Ver en YouTube». */
  watchUrl: string;
}

/**
 * Prepara el tráiler que viene de la API para mostrarlo, o `null` si no hay
 * (o no es válido: entonces no se pinta el botón «Ver tráiler»).
 *
 * Solo acepta la forma **normalizada** que guarda el servidor: el frontend no
 * «arregla» lo que la API devuelva. El id se saca con una expresión anclada y
 * se vuelve a comprobar con {@link YOUTUBE_ID_PATTERN} antes de construir
 * nada: la dirección del `<iframe>` se compone de cero, nunca se interpola el
 * texto recibido.
 *
 * - **`youtube-nocookie.com`**: el modo de privacidad mejorada de YouTube, que
 *   no guarda cookies de seguimiento hasta que se reproduce el vídeo. Es además
 *   el único origen que la CSP (`frame-src`) deja incrustar.
 * - **`autoplay=1`**: el modal se abre porque se ha pulsado «Ver tráiler»; obligar
 *   a pulsar otra vez «reproducir» sería un paso de más.
 * - **`rel=0`**: al terminar, los vídeos sugeridos son solo del mismo canal, no
 *   de cualquiera.
 *
 * @param trailerUrl `trailerUrl` de la película o de la serie, tal cual llega
 */
export function getTrailer(trailerUrl: string | null | undefined): Trailer | null {
  if (!trailerUrl) return null;
  const match = NORMALIZED_PATTERN.exec(trailerUrl);
  const id = match?.[1];
  if (!id || !YOUTUBE_ID_PATTERN.test(id)) return null;
  return {
    embedUrl: `https://www.youtube-nocookie.com/embed/${id}?autoplay=1&rel=0`,
    watchUrl: watchUrl(id),
  };
}

/**
 * Solo la dirección del reproductor incrustado del tráiler, o `null` (ver {@link getTrailer}).
 *
 * @param trailerUrl `trailerUrl` de la película o de la serie, tal cual llega
 */
export function getYouTubeEmbedUrl(trailerUrl: string | null | undefined): string | null {
  return getTrailer(trailerUrl)?.embedUrl ?? null;
}
