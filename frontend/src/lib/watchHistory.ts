/**
 * Reglas del historial de visionado del perfil («Vistos recientemente» y las
 * cifras de lo visto), sin React: a qué página lleva cada entrada, cómo se
 * nombra y cómo se dice el tiempo visto. Funciones puras para poder probarlas
 * sin montar la página.
 */
import { moviePath, seriesPath } from './catalog';
import { episodeCode, SEASON_PARAM } from './series';
import type { WatchHistoryItem } from './types';

/**
 * Página del título de una entrada del historial: la de la película o la de la
 * SERIE (no hay página de un episodio suelto), abierta en la temporada del
 * episodio (`/series/3?temporada=2`), que es donde está lo último que vio.
 *
 * Lleva a la página y no al reproductor a propósito: esto es historial (puede
 * estar terminado o haberse quedado sin vídeo) y el atajo para seguir viendo ya
 * es la fila «Seguir viendo» del inicio.
 */
export function watchHistoryPath(item: WatchHistoryItem): string {
  if (item.type === 'MOVIE') return moviePath(item.id);
  const path = seriesPath(item.seriesId ?? 0);
  return item.seasonNumber === null ? path : `${path}?${SEASON_PARAM}=${item.seasonNumber}`;
}

/** Nombre con el que se reconoce la entrada: la película, o la serie (no el episodio). */
export function watchHistoryName(item: WatchHistoryItem): string {
  return item.type === 'EPISODE' && item.seriesTitle ? item.seriesTitle : item.title;
}

/**
 * Línea del episodio bajo el nombre de la serie: «T1:E2 · Mentiras». `null` en
 * una película (o si faltaran la temporada o el número, que el contrato no prevé).
 */
export function watchHistoryEpisodeLine(item: WatchHistoryItem): string | null {
  if (item.type !== 'EPISODE' || item.seasonNumber === null || item.episodeNumber === null) return null;
  return `${episodeCode(item.seasonNumber, item.episodeNumber)} · ${item.title}`;
}

/** El tiempo visto, en corto para la vista y entero para los lectores de pantalla. */
export interface WatchedTimeText {
  /** Lo que se ve, en corto: «45 min», «1,5 h», «12 h». */
  short: string;
  /** Lo que se lee: «45 minutos», «1,5 horas», «menos de 1 minuto». */
  spoken: string;
}

/** Por debajo de esto (10 h), las horas llevan un decimal: «1,5 h» dice más que «1 h». */
const ONE_DECIMAL_BELOW_SECONDS = 10 * 3600;

/** Números en español (coma decimal): «1,5». */
const decimal = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 1 });

/**
 * El tiempo visto de las cifras del perfil, redondeado **hacia abajo** y en la
 * unidad que se lee de un vistazo:
 * - 0 s → «0 min»; menos de un minuto → «<1 min» («menos de 1 minuto»);
 * - menos de una hora → minutos enteros («45 min»);
 * - menos de 10 h → horas con un decimal («1,5 h»; «1 h» si es exacto);
 * - desde 10 h → horas enteras («12 h»).
 *
 * **Por qué hacia abajo y sin segundos.** El dato del servidor ya es una
 * estimación (solo guarda el último punto de cada título: no cuenta lo que se
 * vuelve a ver ni lo que se rebobina). Redondear hacia arriba o dar los minutos
 * exactos de 30 h vistas aparentaría una precisión que no hay; la etiqueta de la
 * cifra dice además «aprox.».
 *
 * @param seconds `watchedSeconds` de la API (negativos o no finitos cuentan como 0)
 */
export function formatWatchedTime(seconds: number): WatchedTimeText {
  const total = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
  if (total === 0) return { short: '0 min', spoken: '0 minutos' };
  if (total < 60) return { short: '<1 min', spoken: 'menos de 1 minuto' };
  if (total < 3600) {
    const minutes = Math.floor(total / 60);
    return { short: `${minutes} min`, spoken: `${minutes} ${minutes === 1 ? 'minuto' : 'minutos'}` };
  }
  const hours = total < ONE_DECIMAL_BELOW_SECONDS ? Math.floor(total / 360) / 10 : Math.floor(total / 3600);
  const text = decimal.format(hours);
  return { short: `${text} h`, spoken: `${text} ${hours === 1 ? 'hora' : 'horas'}` };
}
