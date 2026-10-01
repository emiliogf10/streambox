/**
 * Funciones puras para organizar el catálogo en la portada.
 * Están separadas de los componentes para poder probarlas sin renderizar nada.
 */
import type { Movie } from './types';

/** Una fila horizontal de la portada. */
export interface CatalogRow {
  /** Identificador estable, útil como `key` de React. */
  id: string;
  title: string;
  movies: Movie[];
}

/** Películas que muestra la fila "Novedades". */
export const NEWS_ROW_SIZE = 12;

/**
 * Mínimo de películas para que un género tenga su propia fila. Con 1 o 2 la
 * fila queda casi vacía y rompe el ritmo visual de la portada; esas películas
 * siguen apareciendo en "Novedades" y en el buscador.
 */
export const MIN_MOVIES_PER_GENRE_ROW = 3;

/**
 * Añade películas a una lista sin duplicar ninguna (por `id`).
 *
 * Hace falta porque la paginación por páginas puede repetir un elemento si
 * alguien crea o borra películas entre dos peticiones y los límites se mueven.
 * Se conserva el orden y la primera aparición.
 *
 * @param current películas ya cargadas
 * @param incoming películas recién recibidas
 */
export function mergeById(current: Movie[], incoming: Movie[]): Movie[] {
  const seen = new Set(current.map((m) => m.id));
  const added = incoming.filter((m) => {
    if (seen.has(m.id)) return false;
    seen.add(m.id);
    return true;
  });
  return added.length === 0 ? current : [...current, ...added];
}

/**
 * Construye las filas de la portada a partir de las películas cargadas
 * (ordenadas de la más reciente a la más antigua y SIN la del banner):
 * primero "Novedades" y después una fila por género con suficientes películas
 * (ver {@link MIN_MOVIES_PER_GENRE_ROW}), de más a menos películas.
 *
 * Los géneros se calculan solo con lo que ya está cargado, así que al pulsar
 * "Cargar más" pueden aparecer filas nuevas y crecer las existentes.
 *
 * @param movies películas del catálogo, más recientes primero
 */
export function buildCatalogRows(movies: Movie[]): CatalogRow[] {
  const rows: CatalogRow[] = [];

  if (movies.length > 0) {
    rows.push({ id: 'news', title: 'Novedades', movies: movies.slice(0, NEWS_ROW_SIZE) });
  }

  const byGenre = new Map<number, { name: string; movies: Movie[] }>();
  for (const movie of movies) {
    for (const genre of movie.genres) {
      const entry = byGenre.get(genre.id) ?? { name: genre.name, movies: [] };
      entry.movies.push(movie);
      byGenre.set(genre.id, entry);
    }
  }

  const genreRows = [...byGenre.entries()]
    .filter(([, entry]) => entry.movies.length >= MIN_MOVIES_PER_GENRE_ROW)
    .sort(([, a], [, b]) => b.movies.length - a.movies.length || a.name.localeCompare(b.name, 'es'))
    .map(([id, entry]): CatalogRow => ({ id: `genre-${id}`, title: entry.name, movies: entry.movies }));

  return [...rows, ...genreRows];
}
