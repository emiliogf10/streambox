/**
 * Funciones puras para organizar el catálogo en filas (portada y página de series).
 * Están separadas de los componentes para poder probarlas sin renderizar nada.
 *
 * Son genéricas: sirven igual para películas que para series, porque solo usan
 * lo que comparten (`id`, `genres`; ver `CatalogItem`).
 */
import type { CatalogItem, Movie } from './types';

/** Una fila horizontal de títulos. Por defecto, de películas. */
export interface CatalogRow<T extends CatalogItem = Movie> {
  /** Identificador estable, útil como `key` de React. */
  id: string;
  title: string;
  items: T[];
}

/** Títulos que muestra la fila "Novedades" (y la fila «Series» de la portada). */
export const NEWS_ROW_SIZE = 12;

/**
 * Mínimo de títulos para que un género tenga su propia fila. Con 1 o 2 la
 * fila queda casi vacía y rompe el ritmo visual de la página; esos títulos
 * siguen apareciendo en "Novedades" y en el buscador.
 */
export const MIN_MOVIES_PER_GENRE_ROW = 3;

/**
 * Añade elementos a una lista sin duplicar ninguno (por `id`).
 *
 * Hace falta porque la paginación por páginas puede repetir un elemento si
 * alguien crea o borra títulos entre dos peticiones y los límites se mueven.
 * Se conserva el orden y la primera aparición.
 *
 * @param current elementos ya cargados
 * @param incoming elementos recién recibidos
 */
export function mergeById<T extends { id: number }>(current: T[], incoming: T[]): T[] {
  const seen = new Set(current.map((m) => m.id));
  const added = incoming.filter((m) => {
    if (seen.has(m.id)) return false;
    seen.add(m.id);
    return true;
  });
  return added.length === 0 ? current : [...current, ...added];
}

/**
 * Construye las filas a partir de los títulos cargados (ordenados del más
 * reciente al más antiguo y SIN el del banner): primero "Novedades" y después
 * una fila por género con suficientes títulos (ver {@link MIN_MOVIES_PER_GENRE_ROW}),
 * de más a menos títulos.
 *
 * Los géneros se calculan solo con lo que ya está cargado, así que al pulsar
 * "Cargar más" pueden aparecer filas nuevas y crecer las existentes.
 *
 * @param items títulos del catálogo (películas o series), más recientes primero
 */
export function buildCatalogRows<T extends CatalogItem>(items: T[]): CatalogRow<T>[] {
  const rows: CatalogRow<T>[] = [];

  if (items.length > 0) {
    rows.push({ id: 'news', title: 'Novedades', items: items.slice(0, NEWS_ROW_SIZE) });
  }

  const byGenre = new Map<number, { name: string; items: T[] }>();
  for (const item of items) {
    for (const genre of item.genres) {
      const entry = byGenre.get(genre.id) ?? { name: genre.name, items: [] };
      entry.items.push(item);
      byGenre.set(genre.id, entry);
    }
  }

  const genreRows = [...byGenre.entries()]
    .filter(([, entry]) => entry.items.length >= MIN_MOVIES_PER_GENRE_ROW)
    .sort(([, a], [, b]) => b.items.length - a.items.length || a.name.localeCompare(b.name, 'es'))
    .map(([id, entry]): CatalogRow<T> => ({ id: `genre-${id}`, title: entry.name, items: entry.items }));

  return [...rows, ...genreRows];
}
