/**
 * Filtros de la página `/peliculas` (género, año de estreno y orden) y su
 * traducción a la URL y a la API. Funciones puras: se prueban sin renderizar.
 *
 * **Por qué en la URL** (`?genero=4&anio=2014&orden=titulo-asc`). Así una
 * búsqueda filtrada se puede recargar, compartir o guardar en marcadores, y
 * «Atrás» vuelve al filtro anterior. Los nombres de los parámetros y de las
 * claves de orden van en español porque son lo que ve el usuario en la barra
 * de direcciones; la traducción a los nombres de la API (`genreId`,
 * `releaseYear`, `sort` + `direction`) se hace solo aquí.
 *
 * **Valores inválidos.** La URL la puede escribir cualquiera: un valor que no
 * se entiende (`?genero=abc`, `?anio=99`, `?orden=xyz`) se IGNORA (cuenta como
 * "sin ese filtro") en lugar de romper la página o mandar basura al servidor,
 * que respondería 400.
 */
import type { CatalogQuery } from '../hooks/usePagedCatalog';
import { MOVIE_YEAR_MAX, MOVIE_YEAR_MIN } from './movieValidation';

/**
 * Rango de años que se puede filtrar: el MISMO que admite el backend al dar de
 * alta una película (`MovieRequest`), así que fuera de él no puede haber resultados.
 */
export const MIN_RELEASE_YEAR = MOVIE_YEAR_MIN;

/** Último año que se puede filtrar (ver {@link MIN_RELEASE_YEAR}). */
export const MAX_RELEASE_YEAR = MOVIE_YEAR_MAX;

/** Nombres de los parámetros de la URL. */
export const FILTER_PARAMS = { genre: 'genero', year: 'anio', sort: 'orden' } as const;

/**
 * Opciones de orden: clave de la URL, texto del desplegable y cómo se pide a la API.
 * El orden de la lista es el del desplegable.
 */
export const MOVIE_SORT_OPTIONS = [
  { key: 'recientes', label: 'Más recientes', sort: 'createdAt', direction: 'desc' },
  { key: 'titulo-asc', label: 'Título A–Z', sort: 'title', direction: 'asc' },
  { key: 'titulo-desc', label: 'Título Z–A', sort: 'title', direction: 'desc' },
  { key: 'anio-desc', label: 'Año: más nuevas', sort: 'releaseYear', direction: 'desc' },
  { key: 'anio-asc', label: 'Año: más antiguas', sort: 'releaseYear', direction: 'asc' },
  { key: 'duracion-asc', label: 'Duración: más cortas', sort: 'duration', direction: 'asc' },
  { key: 'duracion-desc', label: 'Duración: más largas', sort: 'duration', direction: 'desc' },
] as const satisfies readonly { key: string; label: string; sort: string; direction: 'asc' | 'desc' }[];

/** Clave de un orden (`recientes`, `titulo-asc`...). */
export type MovieSortKey = (typeof MOVIE_SORT_OPTIONS)[number]['key'];

/**
 * Orden por defecto: el mismo de la portada (lo último que se ha añadido). No
 * se escribe en la URL, así `/peliculas` a secas y `?orden=recientes` son la misma página.
 */
export const DEFAULT_MOVIE_SORT: MovieSortKey = 'recientes';

/** Filtros ya validados. `null` = sin ese filtro. */
export interface MovieFilters {
  genreId: number | null;
  year: number | null;
  sort: MovieSortKey;
}

/** Sin filtros: lo que muestra `/peliculas` a secas. */
export const NO_MOVIE_FILTERS: MovieFilters = { genreId: null, year: null, sort: DEFAULT_MOVIE_SORT };

/** `true` si el texto es una clave de orden conocida. */
function isSortKey(value: string | null): value is MovieSortKey {
  return MOVIE_SORT_OPTIONS.some((option) => option.key === value);
}

/** Id de género de la URL: un entero positivo, o `null` si no lo es. */
function parseGenreId(raw: string | null): number | null {
  if (raw === null || !/^\d+$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * Interpreta un año escrito por el usuario o leído de la URL.
 *
 * Solo admite cuatro cifras dentro de {@link MIN_RELEASE_YEAR}–{@link MAX_RELEASE_YEAR}.
 * Se valida con una expresión regular y no con `Number()`, que daría por buenos
 * `" 2014"`, `"2014.0"` o `"2e3"`.
 *
 * @param raw texto del campo o del parámetro (`null` = no está)
 * @returns el año, o `null` si el texto no es un año válido
 */
export function parseReleaseYear(raw: string | null): number | null {
  if (raw === null || !/^\d{4}$/.test(raw)) return null;
  const year = Number(raw);
  return year >= MIN_RELEASE_YEAR && year <= MAX_RELEASE_YEAR ? year : null;
}

/**
 * Lee los filtros de la URL. Lo que no se entiende se ignora (ver arriba).
 *
 * @param params parámetros de la URL actual
 */
export function parseMovieFilters(params: URLSearchParams): MovieFilters {
  const sort = params.get(FILTER_PARAMS.sort);
  return {
    genreId: parseGenreId(params.get(FILTER_PARAMS.genre)),
    year: parseReleaseYear(params.get(FILTER_PARAMS.year)),
    sort: isSortKey(sort) ? sort : DEFAULT_MOVIE_SORT,
  };
}

/**
 * Escribe los filtros como parámetros de URL, omitiendo los que no se usan y el
 * orden por defecto: sin filtros queda `/peliculas` limpio. El orden de los
 * parámetros es siempre el mismo, así que unos mismos filtros dan siempre la misma URL.
 *
 * @param filters filtros ya validados
 */
export function movieFiltersToSearchParams(filters: MovieFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.genreId !== null) params.set(FILTER_PARAMS.genre, String(filters.genreId));
  if (filters.year !== null) params.set(FILTER_PARAMS.year, String(filters.year));
  if (filters.sort !== DEFAULT_MOVIE_SORT) params.set(FILTER_PARAMS.sort, filters.sort);
  return params;
}

/**
 * `true` si hay algo que filtrar u ordenar distinto de lo normal. Decide qué
 * enseña la página: banner + filas (sin filtros) o la cuadrícula de resultados.
 */
export function hasActiveFilters(filters: MovieFilters): boolean {
  return filters.genreId !== null || filters.year !== null || filters.sort !== DEFAULT_MOVIE_SORT;
}

/** `true` si los dos conjuntos de filtros son el mismo. */
export function sameMovieFilters(a: MovieFilters, b: MovieFilters): boolean {
  return a.genreId === b.genreId && a.year === b.year && a.sort === b.sort;
}

/**
 * Traduce los filtros a los parámetros de `GET /api/movies/search`
 * (`genreId`, `releaseYear`, `sort`, `direction`).
 */
export function movieFiltersToQuery(filters: MovieFilters): CatalogQuery {
  const option = MOVIE_SORT_OPTIONS.find((candidate) => candidate.key === filters.sort) ?? MOVIE_SORT_OPTIONS[0];
  return {
    sort: option.sort,
    direction: option.direction,
    genreId: filters.genreId ?? undefined,
    releaseYear: filters.year ?? undefined,
  };
}
