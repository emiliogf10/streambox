/**
 * Filtros de los catálogos `/peliculas` y `/series` (género, año de estreno y
 * orden) y su traducción a la URL y a la API. Funciones puras: se prueban sin renderizar.
 *
 * **Un solo módulo para los dos catálogos.** Películas y series se filtran
 * igual (los dos buscadores del backend, `GET /api/movies/search` y
 * `GET /api/series/search`, aceptan `genreId`, `releaseYear`, `sort` y
 * `direction`), así que las reglas viven aquí una vez y cada página indica su
 * tipo ({@link CatalogKind}). Lo único que cambia es qué órdenes admite cada
 * servidor (ver {@link SORT_OPTIONS}) y la ruta de la página.
 *
 * **Por qué en la URL** (`?genero=4&anio=2014&orden=titulo-asc`). Así una
 * búsqueda filtrada se puede recargar, compartir o guardar en marcadores, y
 * «Atrás» vuelve al filtro anterior. Los nombres de los parámetros y de las
 * claves de orden van en español porque son lo que ve el usuario en la barra
 * de direcciones, y son los MISMOS en `/peliculas` y en `/series`; la
 * traducción a los nombres de la API (`genreId`, `releaseYear`, `sort` +
 * `direction`) se hace solo aquí.
 *
 * **Valores inválidos.** La URL la puede escribir cualquiera: un valor que no
 * se entiende (`?genero=abc`, `?anio=99`, `?orden=xyz`, o `?orden=duracion-asc`
 * en `/series`, que no tienen duración) se IGNORA (cuenta como "sin ese
 * filtro") en lugar de romper la página o mandar al servidor algo que
 * respondería con un 400.
 */
import type { CatalogQuery } from '../hooks/usePagedCatalog';
import { MOVIE_YEAR_MAX, MOVIE_YEAR_MIN } from './movieValidation';

/** Qué catálogo se filtra: el de películas (`/peliculas`) o el de series (`/series`). */
export type CatalogKind = 'movies' | 'series';

/**
 * Rango de años que se puede filtrar: el MISMO que admite el backend al dar de
 * alta una película o una serie (`MovieRequest` y `SeriesRequest` comparten
 * 1888–2100), así que fuera de él no puede haber resultados.
 */
export const MIN_RELEASE_YEAR = MOVIE_YEAR_MIN;

/** Último año que se puede filtrar (ver {@link MIN_RELEASE_YEAR}). */
export const MAX_RELEASE_YEAR = MOVIE_YEAR_MAX;

/** Nombres de los parámetros de la URL (iguales en los dos catálogos). */
export const FILTER_PARAMS = { genre: 'genero', year: 'anio', sort: 'orden' } as const;

/**
 * Todos los órdenes que conoce la interfaz: clave de la URL, texto del
 * desplegable y cómo se pide a la API. El orden de la lista es el del
 * desplegable. Los textos («más nuevas», «más cortas») concuerdan con
 * «películas» y con «series», las dos femeninas.
 */
const ALL_SORT_OPTIONS = [
  { key: 'recientes', label: 'Más recientes', sort: 'createdAt', direction: 'desc' },
  { key: 'titulo-asc', label: 'Título A–Z', sort: 'title', direction: 'asc' },
  { key: 'titulo-desc', label: 'Título Z–A', sort: 'title', direction: 'desc' },
  { key: 'anio-desc', label: 'Año: más nuevas', sort: 'releaseYear', direction: 'desc' },
  { key: 'anio-asc', label: 'Año: más antiguas', sort: 'releaseYear', direction: 'asc' },
  { key: 'duracion-asc', label: 'Duración: más cortas', sort: 'duration', direction: 'asc' },
  { key: 'duracion-desc', label: 'Duración: más largas', sort: 'duration', direction: 'desc' },
] as const satisfies readonly { key: string; label: string; sort: string; direction: 'asc' | 'desc' }[];

/** Una opción de orden (clave, texto y campo + dirección de la API). */
export type SortOption = (typeof ALL_SORT_OPTIONS)[number];

/** Clave de un orden (`recientes`, `titulo-asc`...). */
export type CatalogSortKey = SortOption['key'];

/**
 * Órdenes que ofrece cada catálogo. Son los de la lista blanca de su
 * controlador (`MovieController.SORTABLE_FIELDS` y
 * `SeriesController.SORTABLE_FIELDS`): una serie no tiene duración, así que
 * `/series` no ofrece los dos órdenes por duración (el servidor respondería
 * 400 `INVALID_SORT_FIELD`).
 */
export const SORT_OPTIONS: Record<CatalogKind, readonly SortOption[]> = {
  movies: ALL_SORT_OPTIONS,
  series: ALL_SORT_OPTIONS.filter((option) => option.sort !== 'duration'),
};

/**
 * Orden por defecto en los dos catálogos: el de la portada (lo último que se
 * ha añadido). No se escribe en la URL, así `/peliculas` a secas y
 * `?orden=recientes` son la misma página.
 */
export const DEFAULT_SORT: CatalogSortKey = 'recientes';

/** Filtros ya validados. `null` = sin ese filtro. */
export interface CatalogFilters {
  genreId: number | null;
  year: number | null;
  sort: CatalogSortKey;
}

/** Sin filtros: lo que muestran `/peliculas` y `/series` a secas. */
export const NO_CATALOG_FILTERS: CatalogFilters = { genreId: null, year: null, sort: DEFAULT_SORT };

/** Ruta de la página de cada catálogo (la de su enlace en la barra superior). */
const CATALOG_PAGE_PATH: Record<CatalogKind, string> = { movies: '/peliculas', series: '/series' };

/** Opción de orden de una clave en un catálogo, o `undefined` si ese catálogo no la admite. */
function findSortOption(kind: CatalogKind, key: string | null): SortOption | undefined {
  return SORT_OPTIONS[kind].find((option) => option.key === key);
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
 * Lee los filtros de la URL. Lo que no se entiende se ignora (ver arriba),
 * también un orden que exista pero que este catálogo no admita.
 *
 * @param params parámetros de la URL actual
 * @param kind catálogo de la página
 */
export function parseCatalogFilters(params: URLSearchParams, kind: CatalogKind): CatalogFilters {
  return {
    genreId: parseGenreId(params.get(FILTER_PARAMS.genre)),
    year: parseReleaseYear(params.get(FILTER_PARAMS.year)),
    sort: findSortOption(kind, params.get(FILTER_PARAMS.sort))?.key ?? DEFAULT_SORT,
  };
}

/**
 * Escribe los filtros como parámetros de URL, omitiendo los que no se usan y el
 * orden por defecto: sin filtros queda la página limpia (`/series`). El orden de
 * los parámetros es siempre el mismo, así que unos mismos filtros dan siempre la misma URL.
 *
 * @param filters filtros ya validados
 */
export function catalogFiltersToSearchParams(filters: CatalogFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.genreId !== null) params.set(FILTER_PARAMS.genre, String(filters.genreId));
  if (filters.year !== null) params.set(FILTER_PARAMS.year, String(filters.year));
  if (filters.sort !== DEFAULT_SORT) params.set(FILTER_PARAMS.sort, filters.sort);
  return params;
}

/**
 * `true` si hay algo que filtrar u ordenar distinto de lo normal. Decide qué
 * enseña la página: banner + filas (sin filtros) o la cuadrícula de resultados.
 */
export function hasActiveFilters(filters: CatalogFilters): boolean {
  return filters.genreId !== null || filters.year !== null || filters.sort !== DEFAULT_SORT;
}

/** `true` si los dos conjuntos de filtros son el mismo. */
export function sameCatalogFilters(a: CatalogFilters, b: CatalogFilters): boolean {
  return a.genreId === b.genreId && a.year === b.year && a.sort === b.sort;
}

/**
 * Traduce los filtros a los parámetros del buscador del catálogo
 * (`GET /api/movies/search` o `GET /api/series/search`: `genreId`,
 * `releaseYear`, `sort`, `direction`).
 *
 * Un orden que el catálogo no admite se cambia por el de por defecto: nunca se
 * manda al servidor un `sort` que respondería con un 400. No debería ocurrir
 * (la URL ya se valida al leerla y el desplegable solo ofrece los admitidos),
 * pero cuesta una línea y protege de un uso equivocado.
 *
 * @param filters filtros aplicados
 * @param kind catálogo al que se pregunta
 */
export function catalogFiltersToQuery(filters: CatalogFilters, kind: CatalogKind): CatalogQuery {
  const option = findSortOption(kind, filters.sort) ?? SORT_OPTIONS[kind][0];
  return {
    sort: option.sort,
    direction: option.direction,
    genreId: filters.genreId ?? undefined,
    releaseYear: filters.year ?? undefined,
  };
}

/**
 * Dirección de un catálogo filtrado por un género (`/peliculas?genero=4`,
 * `/series?genero=4`).
 *
 * La usan los géneros de la ficha de una película o de una serie: son la forma
 * natural de seguir explorando «más como esta». Se construye con
 * {@link catalogFiltersToSearchParams} para que la URL sea exactamente la que
 * escribiría la barra de filtros al elegir ese género (y «Atrás» o un marcador
 * lleven a la misma página).
 *
 * @param kind catálogo al que lleva
 * @param genreId id del género
 */
export function catalogByGenrePath(kind: CatalogKind, genreId: number): string {
  const query = catalogFiltersToSearchParams({ ...NO_CATALOG_FILTERS, genreId }).toString();
  return `${CATALOG_PAGE_PATH[kind]}?${query}`;
}
