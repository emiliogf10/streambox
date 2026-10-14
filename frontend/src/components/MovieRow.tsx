import type { Movie } from '../lib/types';
import { MovieCard } from './MovieCard';
import { PosterRow } from './PosterRow';

/**
 * Propiedades para el componente MovieRow.
 */
interface Props {
  title: string;
  movies: Movie[];
}

/**
 * Fila de películas: una {@link PosterRow} (scroll horizontal, flechas, título
 * como `<h2>`) con una {@link MovieCard} por película. Cada tarjeta enlaza a la
 * página de su película.
 *
 * @param props el título y la lista de películas
 */
export function MovieRow({ title, movies }: Props) {
  return <PosterRow title={title} items={movies} renderItem={(movie) => <MovieCard movie={movie} />} />;
}
