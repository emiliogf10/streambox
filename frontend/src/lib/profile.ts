/**
 * Funciones puras de la página de perfil (`/perfil`): recuento de géneros, qué
 * títulos de la lista se enseñan, fecha de alta, inicial del avatar y etiqueta
 * del rol. Separadas del componente para probarlas sin renderizar nada (igual
 * que `catalog.ts` y `series.ts`).
 *
 * Todo sale de datos que la aplicación ya tiene (`GET /api/users/me` y las
 * listas de favoritos): no existe historial de reproducción ni nada parecido,
 * así que el perfil describe lo que de verdad se sabe del usuario.
 */
import type { CatalogItem, Movie, Role, Series } from './types';

/** Un género y cuántos títulos de la lista lo tienen. */
export interface GenreCount {
  id: number;
  name: string;
  count: number;
}

/**
 * Cuenta cuántos títulos de `items` tienen cada género y los ordena de más a
 * menos frecuente. Los empates se resuelven por nombre (A–Z, con las reglas del
 * español: «Épico» va junto a «Epopeya», no después de «Western») y, en último
 * término, por id, para que el orden sea siempre el mismo.
 *
 * Se agrupa por `id` y no por nombre: el id es la identidad del género (el
 * nombre se puede renombrar en el panel). Un título cuenta una sola vez por
 * género aunque el servidor lo repitiera.
 *
 * @param items películas y/o series de la lista (solo importan sus géneros)
 */
export function countGenres(items: readonly Pick<CatalogItem, 'genres'>[]): GenreCount[] {
  const byId = new Map<number, GenreCount>();
  for (const item of items) {
    for (const genre of new Map(item.genres.map((g) => [g.id, g])).values()) {
      const entry = byId.get(genre.id);
      if (entry) entry.count += 1;
      else byId.set(genre.id, { id: genre.id, name: genre.name, count: 1 });
    }
  }
  return [...byId.values()].sort(
    (a, b) => b.count - a.count || a.name.localeCompare(b.name, 'es') || a.id - b.id,
  );
}

/** Un título de la lista con su tipo (la tarjeta y el destino al pulsar son distintos). */
export type ListPreviewItem = { kind: 'movie'; item: Movie } | { kind: 'series'; item: Series };

/** Cuántos títulos de la lista enseña el perfil (una fila de pósters en escritorio). */
export const LIST_PREVIEW_SIZE = 5;

/**
 * Elige los títulos de la lista que enseña el perfil: primero las películas
 * (que llegan de la más recién añadida a la más antigua) y, si sobran huecos,
 * las series. No hay "fecha de alta en la lista" que permita mezclarlas por
 * recencia, así que se evita fingir un orden que no existe.
 *
 * @param movies películas de la lista, en el orden del servidor
 * @param series series de la lista, en el orden del servidor
 * @param limit máximo de títulos
 */
export function pickListPreview(
  movies: readonly Movie[],
  series: readonly Series[],
  limit: number = LIST_PREVIEW_SIZE,
): ListPreviewItem[] {
  const picked: ListPreviewItem[] = movies.slice(0, limit).map((item) => ({ kind: 'movie', item }));
  const rest = limit - picked.length;
  if (rest > 0) picked.push(...series.slice(0, rest).map((item): ListPreviewItem => ({ kind: 'series', item })));
  return picked;
}

/**
 * Mes y año de alta en español ("octubre de 2026") a partir del instante ISO
 * que da la API. Se formatea en UTC a propósito: la API entrega un instante UTC
 * y, sin fijar la zona, quien se registró a las 23:30 del 31 de octubre vería
 * «noviembre» o «octubre» según el huso de su navegador.
 *
 * @param createdAt instante ISO-8601 (`User.createdAt`)
 * @returns el texto, o `null` si la fecha no es válida (así no se pinta «Invalid Date»)
 */
export function formatMemberSince(createdAt: string): string | null {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat('es-ES', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(date);
}

/**
 * Inicial en mayúscula del nombre para el avatar. Toma el primer carácter
 * Unicode completo (`Array.from`), no la primera unidad UTF-16: con un nombre
 * que empieza por un emoji, `charAt(0)` devolvería media letra rota.
 *
 * @returns la inicial, o «?» si el nombre está vacío
 */
export function avatarInitial(username: string): string {
  const first = Array.from(username.trim())[0];
  return first ? first.toLocaleUpperCase('es') : '?';
}

/** Nombre del rol para mostrar. En pantalla se pone en mayúsculas con CSS, no en el texto. */
export function roleLabel(role: Role): string {
  return role === 'ADMIN' ? 'Administrador' : 'Usuario';
}
