import type { Movie } from '../lib/types';
import { MovieCard } from './MovieCard';
import { PosterRow } from './PosterRow';

/**
 * Propiedades para el componente MovieRow.
 */
interface Props {
  title: string;
  movies: Movie[];
  onSelectMovie: (movie: Movie) => void;
}

/**
 * Fila de películas: una {@link PosterRow} (scroll horizontal, flechas, título
 * como `<h2>`) con una {@link MovieCard} por película. Pulsar una tarjeta llama
 * a `onSelectMovie`, que en la portada abre el diálogo de detalles.
 *
 * @param props el título, la lista de películas y el manejador de clics
 */
export function MovieRow({ title, movies, onSelectMovie }: Props) {
  return (
    <PosterRow title={title} items={movies} renderItem={(movie) => <MovieCard movie={movie} onClick={onSelectMovie} />} />
  );
}
