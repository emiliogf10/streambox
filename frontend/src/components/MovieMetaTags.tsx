import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';

/**
 * Etiquetas con el año, la duración y los géneros de una película.
 *
 * Las usan el banner y el modal de detalles; extraerlas evita duplicar el
 * marcado. Es una lista (`<ul>`) para que un lector de pantalla diga cuántos
 * datos hay. La clave incluye la posición porque el texto puede repetirse
 * (p. ej. un género llamado igual que el año).
 */
export function MovieMetaTags({ movie }: { movie: Movie }) {
  const tags = [String(movie.releaseYear), formatDuration(movie.duration), ...movie.genres.map((g) => g.name)];
  return (
    <ul role="list" aria-label="Datos de la película" className="flex flex-wrap gap-2">
      {tags.map((tag, index) => (
        <li
          key={`${index}-${tag}`}
          className="inline-flex items-center rounded-md border border-white/20 bg-white/10 px-2.5 py-0.5 text-xs font-medium text-white/90"
        >
          {tag}
        </li>
      ))}
    </ul>
  );
}
