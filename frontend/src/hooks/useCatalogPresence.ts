import { usePagedCatalog } from './usePagedCatalog';

/**
 * Lo que se sabe de si una sección del catálogo tiene algo visible:
 * - `checking`: se está preguntando.
 * - `some`: hay al menos un título.
 * - `none`: no hay ninguno (para series: ninguna con episodios).
 * - `unknown`: no se pudo preguntar (red, 5xx...).
 */
export type CatalogPresence = 'checking' | 'some' | 'none' | 'unknown';

/**
 * ¿Hay algo visible en `/movies` o en `/series`? Sirve para no ofrecer un enlace
 * que lleve a una página vacía (p. ej. «Explorar series» en «Mi lista» cuando
 * ninguna serie tiene episodios todavía).
 *
 * Pide UNA página de tamaño 1 con la lógica de siempre ({@link usePagedCatalog},
 * mismo endpoint y orden que la página de destino) y mira `totalElements`: es lo
 * mínimo que hay que traer para responder y no hace falta un endpoint nuevo.
 * No se usa "cargar más", así que su aviso de error nunca se llega a mostrar.
 *
 * @param path listado a consultar (relativo a `/api`)
 */
export function useCatalogPresence(path: '/movies' | '/series'): CatalogPresence {
  const { total, status } = usePagedCatalog<{ id: number }>(path, 1, '');
  if (status === 'loading') return 'checking';
  if (status === 'error') return 'unknown';
  return total > 0 ? 'some' : 'none';
}
