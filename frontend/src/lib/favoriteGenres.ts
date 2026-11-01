/**
 * Reglas y textos de los géneros favoritos («Tus gustos») y de la pregunta de
 * bienvenida, sin React: así se prueban solos y la página `/bienvenida`, la
 * sección del perfil y la portada usan exactamente las mismas.
 *
 * El servidor es quien manda (`PUT /api/users/me/genres` valida otra vez y
 * responde 400 `VALIDATION_ERROR` en `validationErrors.genreIds`); aquí se
 * repiten los límites solo para no ofrecer «Guardar» con una selección que va a
 * rechazar y para que el contador diga la verdad.
 */
import type { Location } from 'react-router-dom';
import type { Genre, RecommendationBasis, User } from './types';

/** Mínimo de géneros favoritos que se pueden guardar (decisión del autor: con menos no hay base). */
export const MIN_FAVORITE_GENRES = 3;

/** Máximo de géneros favoritos (decisión del autor: más ya no dirían qué te gusta de verdad). */
export const MAX_FAVORITE_GENRES = 5;

/** Ruta de la pregunta de bienvenida. */
export const WELCOME_PATH = '/bienvenida';

/** `id` de la sección «Tus gustos» del perfil (destino del ancla de la tarjeta de la portada). */
export const TASTES_SECTION_ID = 'tus-gustos';

/** Enlace a «Tus gustos» del perfil: la página lleva el foco a la sección al llegar con este fragmento. */
export const PROFILE_TASTES_PATH = `/perfil#${TASTES_SECTION_ID}`;

/**
 * `true` si con `count` géneros marcados se puede pulsar «Guardar» (de 3 a 5).
 * La lista vacía también es válida para el servidor, pero no se envía con
 * «Guardar»: es «Ahora no» o «Quitar mis gustos», cada uno con su botón.
 */
export function canSaveGenreSelection(count: number): boolean {
  return count >= MIN_FAVORITE_GENRES && count <= MAX_FAVORITE_GENRES;
}

/**
 * `true` si con `count` géneros elegibles se puede llegar al mínimo. Con menos
 * (catálogo pequeño: p. ej. solo hay títulos de Drama y Comedia) no se ofrece el
 * selector: su «Guardar» nunca se habilitaría y el contador pediría «Elige al
 * menos 1 más» sin que quedara ninguno (revisión de `qa`). Las pantallas lo
 * dicen con {@link genreShortageTitle} y {@link genreShortageMessage}, y la
 * tarjeta de la portada no invita a un selector imposible.
 */
export function hasEnoughGenreOptions(count: number): boolean {
  return count >= MIN_FAVORITE_GENRES;
}

/**
 * Título del aviso «no se pueden elegir géneros todavía»: distinto si no hay
 * ninguno o si hay alguno pero no bastan, para que diga exactamente lo que pasa.
 *
 * @param available géneros elegibles que hay (menos de {@link MIN_FAVORITE_GENRES})
 */
export function genreShortageTitle(available: Genre[]): string {
  return available.length === 0 ? 'Todavía no hay géneros que elegir' : 'Todavía no hay géneros suficientes';
}

/**
 * Explicación del aviso de {@link genreShortageTitle}: POR QUÉ no se puede
 * elegir, en palabras verdaderas para USER y ADMIN.
 *
 * Habla de títulos **visibles** a propósito: un ADMIN puede tener series con
 * género pero sin episodios (las ve en el panel) y «no hay títulos con género»
 * sería falso para él; esas series no las ve nadie más, así que no cuentan.
 *
 * Con alguno (1 o 2) los nombra; cada pantalla añade después lo que proceda
 * (cuándo podrá elegirlos y {@link GENRE_SHORTAGE_LIKES_HINT}).
 *
 * @param available géneros elegibles que hay (menos de {@link MIN_FAVORITE_GENRES}), por nombre
 */
export function genreShortageMessage(available: Genre[]): string {
  if (available.length === 0) return 'Ningún título visible tiene género todavía.';
  const names = formatGenreNames(available.map((genre) => `«${genre.name}»`));
  return `Hacen falta títulos visibles de al menos ${MIN_FAVORITE_GENRES} géneros y por ahora solo los hay de ${names}.`;
}

/**
 * La otra vía que sí funciona cuando hay géneros con títulos pero no bastan para
 * elegir: los «Me gusta» ya sirven para recomendar títulos de esos géneros. Sin
 * ningún género con títulos no se dice, porque no habría nada que recomendar.
 */
export const GENRE_SHORTAGE_LIKES_HINT =
  'Mientras tanto, los títulos que marques con «Me gusta» también sirven para recomendarte otros parecidos.';

/**
 * Texto del contador del selector. Siempre dice cuántos van de cuántos y qué
 * falta, para que el estado de «Guardar» no sea un misterio:
 * - menos de 3: cuántos faltan;
 * - de 3 a 4: que ya se puede guardar (y que caben más);
 * - 5: que es el máximo y cómo cambiar uno por otro (las demás casillas no se
 *   pueden marcar).
 */
export function genreCounterMessage(count: number): string {
  const base = `Has elegido ${count} de ${MAX_FAVORITE_GENRES}.`;
  if (count < MIN_FAVORITE_GENRES) {
    const missing = MIN_FAVORITE_GENRES - count;
    return `${base} Elige al menos ${missing} más.`;
  }
  if (count < MAX_FAVORITE_GENRES) return `${base} Ya puedes guardar.`;
  return `${base} Es el máximo: quita uno si quieres elegir otro.`;
}

/**
 * Une nombres como se diría en español: «Acción», «Acción y Drama»,
 * «Acción, Drama y Comedia». Con `Intl.ListFormat` (conjunción en español).
 */
export function formatGenreNames(names: string[]): string {
  return new Intl.ListFormat('es', { style: 'long', type: 'conjunction' }).format(names);
}

/**
 * Subtítulo de las filas de recomendaciones de la portada: POR QUÉ se
 * recomiendan esos títulos, según en qué se basó el servidor (`basis`).
 *
 * Con géneros favoritos los nombra si ya se conocen (`genreNames`); mientras no
 * han llegado (o si su petición falló) usa una frase igual de verdadera que no
 * los nombra, en lugar de inventarlos o de no decir nada.
 *
 * Con `NONE` no hay filas, así que no hay subtítulo (`null`).
 *
 * @param basis en qué se basan las recomendaciones
 * @param genreNames nombres de los géneros favoritos, si se conocen
 */
export function recommendationsReason(basis: RecommendationBasis, genreNames?: string[]): string | null {
  const genres = genreNames && genreNames.length > 0 ? formatGenreNames(genreNames) : null;
  switch (basis) {
    case 'NONE':
      return null;
    case 'GENRES':
      return genres ? `Porque te gustan ${genres}.` : 'Según los géneros que has elegido como favoritos.';
    case 'LIKES':
      return 'Según los títulos que has marcado con «Me gusta».';
    case 'GENRES_AND_LIKES':
      return genres
        ? `Porque te gustan ${genres} y según los títulos que has marcado con «Me gusta».`
        : 'Según tus géneros favoritos y los títulos que has marcado con «Me gusta».';
  }
}

/**
 * `true` si hay que llevar a este usuario a la pregunta de bienvenida: solo un
 * `USER` con `genresOnboardingPending` (el servidor nunca lo marca en un ADMIN,
 * pero la interfaz tampoco se lo pregunta aunque llegara: decisión del autor).
 */
export function needsGenresOnboarding(user: User | null): boolean {
  return user?.role === 'USER' && user.genresOnboardingPending === true;
}

/** Estado de navegación con el que se llega a `/bienvenida`: a dónde iba el usuario. */
export interface WelcomeState {
  from: string;
}

/**
 * Ruta completa (con búsqueda y fragmento) de una ubicación del enrutador: lo
 * que se guarda como destino al desviar a alguien a `/bienvenida`.
 */
export function locationPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  return `${location.pathname}${location.search}${location.hash}`;
}

/**
 * A dónde ir al terminar la bienvenida: el destino guardado en el estado de
 * navegación si es una ruta interna de la aplicación y, si no, el inicio.
 *
 * Se valida aunque el estado lo ponga la propia aplicación: `history.state`
 * puede llegar de una entrada antigua del historial. Solo rutas que empiezan por
 * una única `/` (no `//otro-sitio`, que el navegador leería como otro dominio) y
 * nunca la propia `/bienvenida` (sería un bucle).
 */
export function welcomeDestination(state: unknown): string {
  const from = (state as Partial<WelcomeState> | null)?.from;
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) return '/';
  if (from === WELCOME_PATH || from.startsWith(`${WELCOME_PATH}?`) || from.startsWith(`${WELCOME_PATH}#`)) return '/';
  return from;
}
