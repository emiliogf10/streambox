import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';

/**
 * Año, duración y géneros de una película.
 *
 * Las usan el banner y el modal de detalles; extraerlas evita duplicar el
 * marcado. Es una lista (`<ul>`) para que un lector de pantalla diga cuántos
 * datos hay. La clave incluye la posición porque el texto puede repetirse
 * (p. ej. un género llamado igual que el año).
 *
 * **Dos estilos para dos tipos de dato.** Antes los cinco elementos eran
 * etiquetas idénticas con borde y ninguno destacaba: el ojo tenía que leerlas
 * todas para separar "cuándo/cuánto dura" de "de qué va". Ahora año y duración
 * son texto plano con cifras tabulares (`tabular-nums`: los dígitos ocupan lo
 * mismo y no "bailan" de una película a otra) y solo los géneros, que son
 * categorías, llevan fondo de etiqueta.
 */
export function MovieMetaTags({ movie }: { movie: Movie }) {
  const facts = [String(movie.releaseYear), formatDuration(movie.duration)];
  const genres = movie.genres.map((g) => g.name);
  return (
    <ul role="list" aria-label="Datos de la película" className="flex flex-wrap items-center gap-x-3 gap-y-2">
      {facts.map((fact, index) => (
        <li key={`dato-${index}-${fact}`} className="text-sm font-semibold text-white/90 tabular-nums">
          {fact}
        </li>
      ))}
      {genres.map((genre, index) => (
        <li
          key={`genero-${index}-${genre}`}
          className="inline-flex items-center rounded-md bg-white/12 px-2 py-0.5 text-xs font-medium text-white/90"
        >
          {genre}
        </li>
      ))}
    </ul>
  );
}
