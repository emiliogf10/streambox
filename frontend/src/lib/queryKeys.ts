/**
 * Claves de la caché de datos del servidor (TanStack Query), TODAS en un sitio.
 *
 * **Por qué centralizarlas.** Una clave es a la vez el «nombre» de un dato en la
 * caché y la forma de invalidarlo. Si cada hook escribiera la suya a mano
 * (`['movies', …]` aquí, `['peliculas', …]` allá), una invalidación tras editar
 * una película podría no alcanzar a algún listado y este seguiría enseñando el
 * título viejo. Aquí cada clave cuelga de una **raíz** (`movies`, `series`,
 * `genres`, `favorites`, `likes`, `favoriteGenres`, `recommendations`) y las invalidaciones van por raíz: «ha cambiado una
 * película» = todo lo que empieza por `['movies']` (portada, `/peliculas`, sus
 * filtros, el listado del panel, la comprobación de si hay películas...).
 *
 * Las claves son tuplas `as const`: TypeScript conoce su forma exacta y un error
 * de escritura no compila.
 */
import type { QueryKey } from '@tanstack/react-query';

/** Listados paginados del catálogo que conoce la aplicación (relativos a `/api`). */
export type CatalogPath = '/movies' | '/movies/search' | '/series' | '/series/search';

/** Orden, tamaño de página y filtros de un listado paginado: forman parte de su clave. */
export interface PagedCatalogParams {
  pageSize: number;
  sort: string;
  direction: 'asc' | 'desc';
  genreId?: number;
  releaseYear?: number;
}

/** Raíz de la caché a la que pertenece un listado (cada buscador, con su catálogo: `/series/search` es de series). */
function catalogRoot(path: CatalogPath): 'movies' | 'series' {
  return path.startsWith('/series') ? 'series' : 'movies';
}

export const queryKeys = {
  /** Todo lo que sale de `/api/movies...`. */
  movies: {
    all: ['movies'] as const,
    /**
     * Detalle de una película (`GET /api/movies/{id}`, página `/peliculas/:id`).
     * Cuelga de la raíz `movies`: editar o borrar una película en el panel
     * (`keysAffectedBy('movies')`) también deja anticuada su página.
     */
    detail: (id: number) => ['movies', 'detail', id] as const,
    /** Listado del panel (`/admin/peliculas`): búsqueda y página de la URL. */
    admin: (query: string, page: number) => ['movies', 'admin', { query, page }] as const,
    /**
     * «Películas similares» a una película (`GET /api/movies/{id}/similar?limit=N`,
     * fila al pie de `/peliculas/:id`). Cuelga de la raíz `movies` porque depende
     * de las películas Y de sus géneros: editar una película (sus géneros deciden
     * el parecido), borrarla (no debe seguir saliendo como similar) o renombrar un
     * género invalidan `['movies']` y, con ello, esta fila. El `limit` va en la
     * clave: con otro tamaño sería otra respuesta.
     */
    similar: (id: number, limit: number) => ['movies', 'similar', id, { limit }] as const,
  },
  /** Todo lo que sale de `/api/series...` y `/api/admin/series` (episodios incluidos). */
  series: {
    all: ['series'] as const,
    /** Detalle público de una serie (`GET /api/series/{id}`), con temporadas y episodios. */
    detail: (id: number) => ['series', 'detail', id] as const,
    /** Listado del panel (`/admin/series`, que incluye las series sin episodios). */
    admin: (query: string, page: number) => ['series', 'admin', { query, page }] as const,
  },
  /**
   * Géneros del catálogo. `all` es a la vez la raíz y la lista completa
   * (`GET /api/genres`, la de los formularios del panel); de ella cuelgan:
   * - `withTitles(kind)`: solo los géneros con alguna película (`movies`) o con
   *   alguna serie CON episodios (`series`), `GET /api/genres?withTitles=…`. Son
   *   las opciones de los filtros de `/peliculas` y `/series` (D-9): un género sin
   *   títulos solo llevaría a «No hay … con estos filtros». Una entrada por valor:
   *   las dos listas son distintas y no deben pisarse.
   * - `admin`: la de la pestaña Géneros del panel, con cuántas películas y series
   *   usan cada uno (`GET /api/admin/genres`, solo ADMIN; D-4).
   *
   * Ojo al invalidar `all`: por prefijo alcanza también a las otras dos.
   */
  genres: {
    all: ['genres'] as const,
    withTitlesAll: ['genres', 'withTitles'] as const,
    withTitles: (kind: 'movies' | 'series') => ['genres', 'withTitles', kind] as const,
    admin: ['genres', 'admin'] as const,
  },
  /**
   * «Mi lista» del usuario de la sesión (películas y series). Es un dato
   * PERSONAL (como `likes` y `recommendations`): por eso `AuthProvider` vacía
   * la caché entera al cambiar de sesión (ver `AuthContext`).
   */
  favorites: {
    all: ['favorites'] as const,
  },
  /**
   * Títulos que le gustan al usuario de la sesión («Me gusta», películas y
   * series en UNA entrada, como «Mi lista»). Dato PERSONAL: lo borra el
   * `queryClient.clear()` de cada cambio de sesión.
   */
  likes: {
    all: ['likes'] as const,
  },
  /**
   * Géneros favoritos del usuario de la sesión («Tus gustos»), con su propia raíz:
   * - `mine`: los que eligió (`GET /api/users/me/genres`). Dato PERSONAL (lo
   *   borra el `queryClient.clear()` de cada cambio de sesión). Tras guardarlos,
   *   la respuesta del `PUT` sustituye a esta entrada sin volver a pedirla.
   * - `options`: los que se pueden elegir (`GET /api/users/me/genres/options`,
   *   los géneros con algún título visible). No dependen de lo que se elige, así
   *   que guardar no los invalida; un cambio del catálogo sí (un género se queda
   *   sin títulos o estrena el primero: ver {@link keysAffectedBy}).
   */
  favoriteGenres: {
    all: ['favoriteGenres'] as const,
    mine: ['favoriteGenres', 'mine'] as const,
    options: ['favoriteGenres', 'options'] as const,
  },
  /**
   * Recomendaciones del usuario de la sesión (`GET /api/users/me/recommendations`).
   * Dependen de sus géneros favoritos y de sus «Me gusta» (se descartan tras
   * cada cambio de cualquiera de los dos) y del catálogo
   * (un título editado o borrado). El `limit` va en la clave: con otro tamaño
   * sería otra respuesta. Dato PERSONAL, como `likes`.
   */
  recommendations: {
    all: ['recommendations'] as const,
    list: (limit: number) => ['recommendations', { limit }] as const,
  },
  /**
   * Un listado paginado del catálogo (`usePagedCatalog`): portada, `/peliculas`
   * y `/series` con o sin filtros, la fila «Series» y las comprobaciones de
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
 * Datos personales que guardan COPIAS del catálogo (título, portada, géneros,
 * número de episodios): «Mi lista», los «Me gusta», los géneros favoritos y las
 * recomendaciones. Se invalidan tras cualquier cambio del catálogo; las
 * recomendaciones, además, porque un título nuevo, editado (sus géneros) o
 * borrado cambia lo que se recomienda. Los géneros favoritos (`favoriteGenres`,
 * con sus opciones) porque un género renombrado o borrado cambia la lista, y
 * porque un alta o un borrado de títulos cambia qué géneros se pueden elegir.
 */
const PERSONAL_COPIES: QueryKey[] = [
  queryKeys.favorites.all,
  queryKeys.likes.all,
  queryKeys.favoriteGenres.all,
  queryKeys.recommendations.all,
];

/**
 * Raíces de la caché que deja anticuadas cada tipo de cambio. Los datos
 * personales con copias de títulos ({@link PERSONAL_COPIES}) van en todas. Un
 * género renombrado aparece dentro de cada película y serie, por eso arrastra
 * también sus listados.
 *
 * Los géneros que dependen de los TÍTULOS también:
 * - los de los filtros (`genres.withTitles`): una película nueva puede estrenar
 *   un género, una serie gana su primer episodio (se hace visible) o pierde el
 *   último, y un borrado deja un género sin títulos;
 * - los recuentos del panel (`genres.admin`): cuántas películas y series usan
 *   cada género, que deciden si se puede borrar.
 *
 * Tras un cambio de GÉNEROS no se invalidan ni la lista completa ni la del
 * panel: la pestaña Géneros las actualiza con la respuesta del servidor (ver
 * `AdminGenresPage`); sí las de los filtros, que llevan los nombres.
 *
 * @param change qué tipo de dato se ha escrito
 */
export function keysAffectedBy(change: CatalogChange): QueryKey[] {
  switch (change) {
    case 'movies':
      return [queryKeys.movies.all, queryKeys.genres.withTitles('movies'), queryKeys.genres.admin, ...PERSONAL_COPIES];
    case 'series':
      return [queryKeys.series.all, queryKeys.genres.withTitles('series'), queryKeys.genres.admin, ...PERSONAL_COPIES];
    case 'genres':
      return [queryKeys.movies.all, queryKeys.series.all, queryKeys.genres.withTitlesAll, ...PERSONAL_COPIES];
  }
}
