import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { MetaTags } from './MetaTags';

/**
 * Año, duración y géneros de una película (banner y diálogo de detalles).
 * El marcado y los estilos son los de {@link MetaTags}, compartidos con las series.
 */
export function MovieMetaTags({ movie }: { movie: Movie }) {
  return (
    <MetaTags
      facts={[String(movie.releaseYear), formatDuration(movie.duration)]}
      genres={movie.genres}
      label="Datos de la película"
    />
  );
}
