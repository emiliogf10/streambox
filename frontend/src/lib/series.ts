/**
 * Funciones puras para mostrar series: años, recuentos, códigos de episodio y
 * la temporada elegida en la URL. Separadas de los componentes para poder
 * probarlas sin renderizar nada (igual que `catalog.ts`).
 */
import type { Season, Series } from './types';

/**
 * Años de emisión de una serie, con raya (–) como separador:
 * - `2019–2022` si terminó;
 * - `2021–` si sigue en emisión (`endYear` es `null`): el hueco tras la raya
 *   es la convención de "hasta hoy". La etiqueta «En emisión» que lo acompaña
 *   lo dice con palabras (no se confía solo en el signo);
 * - `2020` si empezó y terminó el mismo año (`2020–2020` sería ruido).
 *
 * @param releaseYear año de la primera temporada
 * @param endYear año de la última, o `null` si sigue en emisión
 */
export function formatSeriesYears(releaseYear: number, endYear: number | null): string {
  if (endYear === null) return `${releaseYear}–`;
  if (endYear === releaseYear) return String(releaseYear);
  return `${releaseYear}–${endYear}`;
}

/** `true` si la serie sigue en emisión (no tiene año de fin). */
export function isOnAir(series: Pick<Series, 'endYear'>): boolean {
  return series.endYear === null;
}

/**
 * Cantidad con su palabra en singular o plural: "1 temporada", "3 temporadas".
 *
 * @param count cantidad
 * @param singular palabra para 1
 * @param plural palabra para el resto (incluido 0)
 */
export function pluralize(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

/** "1 temporada" / "3 temporadas". */
export function formatSeasonCount(count: number): string {
  return pluralize(count, 'temporada', 'temporadas');
}

/** "1 episodio" / "24 episodios". */
export function formatEpisodeCount(count: number): string {
  return pluralize(count, 'episodio', 'episodios');
}

/**
 * Código corto de un episodio: `T1:E3` (temporada 1, episodio 3). Se usa en
 * el nombre accesible del botón «Ver», para que cada uno sea único dentro de la
 * página aunque dos episodios de temporadas distintas se llamen igual.
 */
export function episodeCode(seasonNumber: number, episodeNumber: number): string {
  return `T${seasonNumber}:E${episodeNumber}`;
}

/**
 * Datos de la serie bajo el título de su tarjeta: "2019–2022 · 3 temporadas".
 * Es el equivalente a "año · duración" de las películas.
 */
export function seriesCardMeta(series: Pick<Series, 'releaseYear' | 'endYear' | 'seasonCount'>): string {
  return `${formatSeriesYears(series.releaseYear, series.endYear)} · ${formatSeasonCount(series.seasonCount)}`;
}

/** Nombre del parámetro de la URL con la temporada elegida (`/series/7?temporada=2`). */
export const SEASON_PARAM = 'temporada';

/**
 * Decide qué temporada se muestra a partir del parámetro `?temporada=` de la URL.
 *
 * La temporada vive en la URL (y no solo en el estado) para que se pueda
 * compartir o recargar sin perderla. Como la URL la puede escribir cualquiera,
 * se valida: si falta, no es un número entero o esa temporada no existe, se
 * muestra la PRIMERA de la serie (que no tiene por qué ser la 1: puede haber
 * series que empiecen en la 2 si la 1 aún no está). Nunca devuelve una
 * temporada inexistente; con una lista vacía devuelve `null`.
 *
 * @param raw valor del parámetro tal como viene en la URL (`null` si no está)
 * @param seasons temporadas de la serie, ordenadas por número
 */
export function resolveSeasonNumber(raw: string | null, seasons: readonly Season[]): number | null {
  if (seasons.length === 0) return null;
  // Solo dígitos: `Number('2.5')`, `Number('1e1')` o `Number(' 2')` no deben colarse como temporadas válidas.
  if (raw !== null && /^\d+$/.test(raw)) {
    const requested = Number(raw);
    if (seasons.some((season) => season.seasonNumber === requested)) return requested;
  }
  return seasons[0].seasonNumber;
}
