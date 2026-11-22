/**
 * Reglas del reproductor integrado y del progreso de visionado («Seguir
 * viendo»), sin React: qué vídeo se puede ver dentro de StreamBox, cuándo y qué
 * se guarda, desde dónde se reanuda y cómo se dice el tiempo que queda.
 *
 * Están aquí, como funciones puras, para poder probarlas sin navegador: el
 * reproductor (`components/player/PlayerScreen.tsx`), el guardado
 * (`hooks/useProgressSaver.ts`) y las páginas de cada título solo las usan.
 */
import { episodeCode } from './series';
import type { EpisodeProgress, SeriesDetail, Episode, WatchProgress, WatchProgressRequest } from './types';
import { getSafeVideoUrl } from './utils';

/**
 * Terminaciones de los archivos de vídeo que el `<video>` nativo reproduce en
 * cualquier navegador actual: MP4 (H.264), WebM y Ogg (`.ogv` es su nombre
 * habitual para vídeo).
 *
 * **Por qué solo estos.** Un `videoUrl` puede ser cualquier página (YouTube,
 * Vimeo, un servicio de streaming...): metida en un `<video>` no se vería, y el
 * reproductor diría «No se pudo reproducir» de un enlace que funciona. Tampoco
 * entra HLS (`.m3u8`): fuera de Safari exige una biblioteca (`hls.js`). Todo lo
 * que no sea un archivo directo sigue abriéndose en otra pestaña, como antes.
 */
const DIRECT_VIDEO_EXTENSIONS = ['.mp4', '.webm', '.ogg', '.ogv'] as const;

/**
 * La URL del vídeo si es un **archivo de vídeo directo** que el reproductor
 * integrado puede abrir; `null` si no lo es (o no es segura).
 *
 * Primero pasa por {@link getSafeVideoUrl} (solo http/https, sin credenciales):
 * lo que no es seguro para un enlace tampoco lo es para un `<video>`. Después:
 *
 * - **Solo `https:`.** Un `http://` sigue siendo un vídeo válido, pero se abre
 *   en otra pestaña (como uno de otra web) y no aquí, por dos motivos que el
 *   usuario vería como «No se ha podido abrir el archivo» de un enlace que
 *   funciona: la CSP de nginx solo deja cargar vídeo de `'self'` y de `https:`
 *   (`media-src`), y con la aplicación servida por HTTPS sería **contenido
 *   mixto**, que el navegador sube a HTTPS por su cuenta (y falla si el
 *   servidor no lo admite) o bloquea. No se hace una excepción para el mismo
 *   origen: el catálogo guarda URLs absolutas de un CDN y los E2E también usan
 *   `https:` (interceptado), así que no la necesita nadie.
 * - Mira la terminación de la RUTA, sin la consulta ni el fragmento
 *   (`clip.mp4?token=…` vale; `clip.mp4.html` o `/ver?f=clip.mp4`, no) y sin
 *   distinguir mayúsculas (`CLIP.MP4`).
 *
 * @param rawUrl `videoUrl` tal como viene de la API
 */
export function getDirectVideoUrl(rawUrl: string | null | undefined): string | null {
  const safe = getSafeVideoUrl(rawUrl);
  if (!safe) return null;
  const url = new URL(safe);
  if (url.protocol !== 'https:') return null;
  const path = url.pathname.toLowerCase();
  return DIRECT_VIDEO_EXTENSIONS.some((extension) => path.endsWith(extension)) ? safe : null;
}

/** Página del reproductor de una película (`/ver/peliculas/:id`). */
export function moviePlayerPath(movieId: number): string {
  return `/ver/peliculas/${movieId}`;
}

/**
 * Página del reproductor de un episodio (`/ver/series/:seriesId/episodios/:episodeId`).
 * Lleva la serie porque el episodio se busca dentro de su detalle (la API no
 * tiene un `GET` de un episodio suelto) y porque «Volver» lleva a la serie.
 */
export function episodePlayerPath(seriesId: number, episodeId: number): string {
  return `/ver/series/${seriesId}/episodios/${episodeId}`;
}

/**
 * Segundos de reproducción REAL (no de saltos con la barra) que hacen falta
 * antes de guardar nada. Quien abre un título por error y lo cierra a los tres
 * segundos no debe encontrárselo en «Seguir viendo».
 */
export const MIN_WATCHED_SECONDS = 10;

/**
 * Cada cuánto se guarda mientras se reproduce. Quince segundos: si se cierra el
 * navegador de golpe se pierden como mucho esos; más a menudo solo serían
 * peticiones (una película de dos horas son ~480 guardados).
 */
export const SAVE_INTERVAL_MS = 15_000;

/**
 * Mayor salto entre dos `timeupdate` seguidos que todavía cuenta como
 * reproducción. El navegador los lanza cada 250 ms aprox. (a velocidad 2×, cada
 * uno avanza ~0,5 s); un salto mayor es que el usuario ha movido la barra.
 */
const MAX_PLAYBACK_STEP_SECONDS = 3;

/**
 * Cuánto ha avanzado la reproducción entre dos `timeupdate`: la diferencia si
 * es un avance normal, o 0 si es un salto (atrás o más de
 * {@link MAX_PLAYBACK_STEP_SECONDS} hacia delante).
 *
 * @param previousTime `currentTime` del aviso anterior
 * @param currentTime `currentTime` de este aviso
 */
export function playbackStep(previousTime: number, currentTime: number): number {
  const step = currentTime - previousTime;
  return step > 0 && step <= MAX_PLAYBACK_STEP_SECONDS ? step : 0;
}

/** Mayor duración que admite la API (24 h); más que eso no es un vídeo de catálogo. */
export const MAX_DURATION_SECONDS = 86_400;

/**
 * Cuerpo del `PUT` de progreso a partir del estado del `<video>`, o `null` si no
 * hay nada que se pueda guardar.
 *
 * - Enteros (`Math.floor`): la API pide segundos enteros.
 * - Sin duración conocida (`NaN` antes de cargar, `Infinity` en directo), de
 *   menos de 1 s o de más de 24 h, `null`: la API la rechazaría (400).
 * - Al terminar (`ended`), la posición es la duración: así el servidor lo da por
 *   visto y sale de «Seguir viendo».
 * - La posición nunca pasa de la duración (con decimales podría: 30,9 s de
 *   posición redondea a 30 y la duración, 30,4, también a 30).
 *
 * @param currentTime `video.currentTime`
 * @param duration `video.duration`
 * @param ended `video.ended`
 */
export function toProgressPayload(currentTime: number, duration: number, ended: boolean): WatchProgressRequest | null {
  if (!Number.isFinite(duration)) return null;
  const durationSeconds = Math.floor(duration);
  if (durationSeconds < 1 || durationSeconds > MAX_DURATION_SECONDS) return null;
  const position = ended ? durationSeconds : Math.floor(Number.isFinite(currentTime) ? currentTime : 0);
  return { positionSeconds: Math.min(Math.max(position, 0), durationSeconds), durationSeconds };
}

/**
 * Proporción vista a partir de la cual el servidor da un título por terminado
 * (`WatchProgress.finished`, 95 %). Es una COPIA de la regla del backend: el
 * cliente solo la usa para actualizar su caché tras guardar sin volver a pedir
 * el progreso; lo que diga el servidor en la siguiente lectura manda.
 */
export const FINISHED_RATIO = 0.95;

/** `true` si con esa posición el servidor considera el título terminado (ver {@link FINISHED_RATIO}). */
export function isFinishedAt(positionSeconds: number, durationSeconds: number): boolean {
  return durationSeconds > 0 && positionSeconds >= durationSeconds * FINISHED_RATIO;
}

/**
 * Menos de esto no merece «Reanudar»: volver a los 3 s de un vídeo es lo mismo
 * que empezar, y el botón diría algo que no aporta nada.
 */
const MIN_RESUME_SECONDS = 5;

/**
 * Segundo desde el que se reanuda, o 0 para empezar desde el principio: sin
 * progreso, si ya se terminó (se vuelve a ver entero) o si apenas se había empezado.
 *
 * @param progress progreso guardado del título, o `null` si no hay
 */
export function resumePositionOf(progress: WatchProgress | null | undefined): number {
  if (!progress || progress.finished) return 0;
  return progress.positionSeconds >= MIN_RESUME_SECONDS ? progress.positionSeconds : 0;
}

/** Proporción vista (0 a 1) para pintar la barra de progreso. */
export function progressRatio(positionSeconds: number, durationSeconds: number): number {
  if (durationSeconds <= 0) return 0;
  return Math.min(Math.max(positionSeconds / durationSeconds, 0), 1);
}

/**
 * Instante del vídeo como en cualquier reproductor: «0:05», «12:34», «1:02:03».
 *
 * @param totalSeconds segundos (se redondea hacia abajo; negativos = 0)
 */
export function formatTimestamp(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = String(seconds % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

/**
 * Lo que queda por ver, con palabras: «Quedan 34 min», «Queda 1 min», «Queda
 * menos de 1 min», «Quedan 1 h 5 min», «Queda 1 h».
 *
 * **Por qué el tiempo que queda y no un porcentaje.** Es la pregunta de quien
 * va a seguir viendo («¿me da tiempo antes de cenar?»), y además es el texto que
 * acompaña a la barra: la información no depende solo de un dibujo.
 * Los minutos se redondean hacia arriba: con 30 s pendientes no queda «0 min».
 */
export function formatRemaining(positionSeconds: number, durationSeconds: number): string {
  const remaining = Math.max(0, durationSeconds - positionSeconds);
  if (remaining < 60) return 'Queda menos de 1 min';
  const totalMinutes = Math.ceil(remaining / 60);
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  if (h === 0) return m === 1 ? 'Queda 1 min' : `Quedan ${m} min`;
  if (m === 0) return h === 1 ? 'Queda 1 h' : `Quedan ${h} h`;
  return `Quedan ${h} h ${m} min`;
}

/** Código de error de la API cuando se guarda el progreso de un título que ya no tiene vídeo. */
export const NO_VIDEO_AVAILABLE = 'NO_VIDEO_AVAILABLE';

/** Qué hace el botón principal de la cabecera de una serie (ver {@link seriesWatchTarget}). */
export interface SeriesWatchTarget {
  episode: Episode;
  /**
   * - `resume`: hay un episodio a medias → «Reanudar T1:E3».
   * - `episode`: se sigue por un episodio concreto que no se reanuda: el
   *   siguiente al último terminado, o el último tocado si apenas se empezó → «Ver T1:E4».
   * - `start`: sin progreso que sirva (o todo visto) → el primer episodio con vídeo.
   */
  mode: 'resume' | 'episode' | 'start';
}

/** Episodios de la serie en su orden (temporadas y números ya vienen ordenados de la API). */
function episodesInOrder(series: SeriesDetail): Episode[] {
  return series.seasons.flatMap((season) => season.episodes);
}

/** `true` si el episodio tiene un vídeo que se pueda abrir (en StreamBox o en otra pestaña). */
function hasVideo(episode: Episode): boolean {
  return getSafeVideoUrl(episode.videoUrl) !== null;
}

/**
 * A qué episodio lleva el botón principal de una serie, como en cualquier
 * plataforma: al **último que tocó** el usuario (`updatedAt` más reciente).
 * - Si está a medias, se reanuda ese (o se abre sin más, si apenas se había
 *   empezado: ver {@link resumePositionOf}).
 * - Si lo terminó, el siguiente con vídeo (si no hay más, se vuelve a empezar
 *   por el primero).
 * - Sin progreso, el primero con vídeo; `null` si ninguno tiene.
 *
 * Solo cuentan los progresos de episodios que siguen en la serie y con vídeo:
 * uno borrado o que ha perdido el vídeo no puede ser el destino del botón.
 *
 * @param series detalle de la serie
 * @param progress progreso de sus episodios (`GET /progress/series/{id}`), o `undefined` si aún no ha llegado
 */
export function seriesWatchTarget(
  series: SeriesDetail,
  progress: readonly EpisodeProgress[] | undefined,
): SeriesWatchTarget | null {
  const episodes = episodesInOrder(series);
  const watchable = episodes.filter(hasVideo);
  if (watchable.length === 0) return null;

  const latest = [...(progress ?? [])]
    .filter((item) => watchable.some((episode) => episode.id === item.episodeId))
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))[0];
  if (latest) {
    const index = episodes.findIndex((episode) => episode.id === latest.episodeId);
    const episode = episodes[index];
    if (!latest.finished) return { episode, mode: resumePositionOf(latest) > 0 ? 'resume' : 'episode' };
    const next = episodes.slice(index + 1).find(hasVideo);
    if (next) return { episode: next, mode: 'episode' };
  }
  return { episode: watchable[0], mode: 'start' };
}

/** «T1:E3 · El regreso»: el código de siempre ({@link episodeCode}) y el título del episodio. */
export function episodeLabel(episode: Pick<Episode, 'seasonNumber' | 'episodeNumber' | 'title'>): string {
  return `${episodeCode(episode.seasonNumber, episode.episodeNumber)} · ${episode.title}`;
}
