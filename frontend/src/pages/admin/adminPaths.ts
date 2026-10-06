/**
 * Rutas del panel de administración y la "vuelta al listado".
 *
 * Están en un archivo sin componentes para que las compartan el listado, el
 * formulario y los tests sin repetir cadenas (y sin romper el "fast refresh" de
 * Vite, que exige que un archivo de componentes solo exporte componentes).
 */

/** Listado de películas del panel. */
export const ADMIN_MOVIES_PATH = '/admin/peliculas';
/** Alta de una película. */
export const ADMIN_NEW_MOVIE_PATH = `${ADMIN_MOVIES_PATH}/nueva`;
/** Listado de series del panel (incluye las que aún no tienen episodios). */
export const ADMIN_SERIES_PATH = '/admin/series';
/** Alta de una serie. */
export const ADMIN_NEW_SERIES_PATH = `${ADMIN_SERIES_PATH}/nueva`;
/** Pestaña de géneros. */
export const ADMIN_GENRES_PATH = '/admin/generos';

/** Edición de la película `id`. */
export function adminEditMoviePath(id: number): string {
  return `${ADMIN_MOVIES_PATH}/${id}/editar`;
}

/** Edición de la serie `id` (datos de la serie y, debajo, sus episodios). */
export function adminEditSeriesPath(id: number): string {
  return `${ADMIN_SERIES_PATH}/${id}/editar`;
}

/** Estado de navegación que el listado pasa al formulario (ver {@link getReturnTo}). */
export interface ReturnToState {
  returnTo: string;
}

/**
 * Adónde vuelve el formulario al guardar o cancelar.
 *
 * El listado pasa en el estado de la navegación su URL con búsqueda y página
 * (`/admin/peliculas?q=blade&page=2`), para que al terminar de editar el
 * administrador siga donde estaba. Solo se acepta una ruta del propio listado:
 * el estado no lo controla nadie de fuera, pero validar lo que se usa para
 * navegar es una costumbre barata que evita redirecciones a cualquier sitio.
 *
 * @param state `location.state` (puede ser cualquier cosa, o nada si se entró escribiendo la URL)
 * @param listPath listado al que pertenece el formulario (por defecto, el de películas); es
 *   también el destino si el estado no sirve
 */
export function getReturnTo(state: unknown, listPath: string = ADMIN_MOVIES_PATH): string {
  if (typeof state === 'object' && state !== null && 'returnTo' in state) {
    const { returnTo } = state as { returnTo: unknown };
    if (typeof returnTo === 'string' && (returnTo === listPath || returnTo.startsWith(`${listPath}?`))) {
      return returnTo;
    }
  }
  return listPath;
}
