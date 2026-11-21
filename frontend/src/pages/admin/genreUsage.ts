/**
 * Textos del uso de un género en el panel (pestaña Géneros, D-4), sin React:
 * así se prueban solos.
 */
import { pluralize } from '../../lib/series';
import type { AdminGenre } from '../../lib/types';

/** Títulos que usan un género (películas + series). */
export function usageTotal(genre: AdminGenre): number {
  return genre.movieCount + genre.seriesCount;
}

/**
 * Uso de un género en palabras: «3 películas y 2 series», «1 serie» o, sin
 * ninguno, «Sin películas ni series». Se escribe entero (no «3 · 2») para que el
 * lector de pantalla lo lea igual que se ve.
 */
export function genreUsageText(genre: AdminGenre): string {
  const parts = [
    genre.movieCount > 0 ? pluralize(genre.movieCount, 'película', 'películas') : null,
    genre.seriesCount > 0 ? pluralize(genre.seriesCount, 'serie', 'series') : null,
  ].filter((part): part is string => part !== null);
  return parts.length === 0 ? 'Sin películas ni series' : parts.join(' y ');
}

/**
 * Por qué no se puede borrar un género en uso (descripción accesible de su
 * «Borrar» desactivado). Dice qué lo impide y qué hacer, con el verbo en
 * singular o plural según cuántos títulos sean.
 */
export function genreInUseExplanation(genre: AdminGenre): string {
  const total = usageTotal(genre);
  return total === 1
    ? `No se puede borrar: lo usa ${genreUsageText(genre)}. Quítalo de ese título para poder borrarlo.`
    : `No se puede borrar: lo usan ${genreUsageText(genre)}. Quítalo de esos títulos para poder borrarlo.`;
}
