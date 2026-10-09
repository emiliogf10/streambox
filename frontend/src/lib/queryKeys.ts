/**
 * Claves de la caché de datos del servidor (TanStack Query), TODAS en un sitio.
 *
 * **Por qué centralizarlas.** Una clave es a la vez el «nombre» de un dato en la
 * caché y la forma de invalidarlo. Si cada hook escribiera la suya a mano
 * (`['movies', …]` aquí, `['peliculas', …]` allá), una invalidación tras editar
 * una película podría no alcanzar a algún listado y este seguiría enseñando el
 * título viejo. Aquí cada clave cuelga de una **raíz** (`movies`, `series`,
 * `genres`, `favorites`) y las invalidaciones van por raíz: «ha cambiado una
 * película» = todo lo que empieza por `['movies']` (portada, `/peliculas`, sus
 * filtros, el listado del panel, la comprobación de si hay películas...).
 *
 * Las claves son tuplas `as const`: TypeScript conoce su forma exacta y un error
 * de escritura no compila.
 */
import type { QueryKey } from '@tanstack/react-query';

/** Listados paginados del catálogo que conoce la aplicación (relativos a `/api`). */
export type CatalogPath = '/movies' | '/movies/search' | '/series';

/** Orden, tamaño de página y filtros de un listado paginado: forman parte de su clave. */
export interface PagedCatalogParams {
  pageSize: number;
  sort: string;
  direction: 'asc' | 'desc';
  genreId?: number;
  releaseYear?: number;
}

/** Raíz de la caché a la que pertenece un listado (`/movies/search` también es de películas). */
function catalogRoot(path: CatalogPath): 'movies' | 'series' {
  return path === '/series' ? 'series' : 'movies';
}

export const queryKeys = {
  /** Todo lo que sale de `/api/movies...`. */
  movies: {
    all: ['movies'] as const,
    /** Listado del panel (`/admin/peliculas`): búsqueda y página de la URL. */
    admin: (query: string, page: number) => ['movies', 'admin', { query, page }] as const,
  },
  /** Todo lo que sale de `/api/series...` y `/api/admin/series` (episodios incluidos). */
  series: {
    all: ['series'] as const,
    /** Detalle público de una serie (`GET /api/series/{id}`), con temporadas y episodios. */
    detail: (id: number) => ['series', 'detail', id] as const,
    /** Listado del panel (`/admin/series`, que incluye las series sin episodios). */
    admin: (query: string, page: number) => ['series', 'admin', { query, page }] as const,
  },
  /** Lista de géneros (`GET /api/genres`). */
  genres: {
    all: ['genres'] as const,
  },
  /**
   * «Mi lista» del usuario de la sesión (películas y series). Es el único dato
   * PERSONAL de la caché: por eso `AuthProvider` vacía la caché entera al
   * cambiar de sesión (ver `AuthContext`).
   */
  favorites: {
    all: ['favorites'] as const,
  },
  /**
   * Un listado paginado del catálogo (`usePagedCatalog`): portada, `/peliculas`
   * con o sin filtros, `/series`, la fila «Series» y las comprobaciones de
   * presencia. El endpoint y los parámetros van en la clave, así que cada
   * combinación de filtros tiene su propia entrada (volver a un filtro ya visto
   * no repite la petición) y nunca se mezclan sus resultados.
   */
  paged: (path: CatalogPath, params: PagedCatalogParams) => [catalogRoot(path), 'paged', path, params] as const,
} as const;

/**
 * Qué ha cambiado en el catálogo tras una escritura del panel de administración.
 * - `movies`: alta, edición o borrado de una película.
 * - `series`: alta, edición o borrado de una serie, o de uno de sus episodios
 *   (con el primer episodio una serie se hace visible; sin ninguno, se oculta).
 * - `genres`: alta, renombrado o borrado de un género.
 */
export type CatalogChange = 'movies' | 'series' | 'genres';

/**
 * Raíces de la caché que deja anticuadas cada tipo de cambio. «Mi lista» va en
 * todas porque guarda copias de los títulos (título, portada, géneros, número de
 * episodios). Un género renombrado aparece dentro de cada película y serie, por
 * eso arrastra también sus listados. La propia lista de géneros NO se invalida
 * tras un cambio de géneros: la pestaña Géneros ya la actualiza con la respuesta
 * del servidor (ver `AdminGenresPage`).
 *
 * @param change qué tipo de dato se ha escrito
 */
export function keysAffectedBy(change: CatalogChange): QueryKey[] {
  switch (change) {
    case 'movies':
      return [queryKeys.movies.all, queryKeys.favorites.all];
    case 'series':
      return [queryKeys.series.all, queryKeys.favorites.all];
    case 'genres':
      return [queryKeys.movies.all, queryKeys.series.all, queryKeys.favorites.all];
  }
}
