import type { Movie } from './types';
import { MovieCard } from './MovieCard';

/**
 * Propiedades para el componente MovieRow.
 */
interface Props {
  title: string;
  movies: Movie[];
  onSelectMovie: (movie: Movie) => void;
}

/**
 * Renderiza una fila desplazable horizontalmente de tarjetas de películas con un título dado.
 *
 * @param props Las propiedades del componente que contienen el título, la lista de películas y el manejador de clics.
 */
export function MovieRow({ title, movies, onSelectMovie }: Props) {
  if (movies.length === 0) return null;
  return (
    <section style={{ marginBottom: '32px', padding: '0 24px' }}>
      <h2 style={{ fontSize: '16px', fontWeight: 700, color: 'white', marginBottom: '16px', marginTop: 0 }}>{title}</h2>
      <div className="scrollbar-hide" style={{ display: 'flex', gap: '16px', overflowX: 'auto', paddingBottom: '8px' }}>
        {movies.map((m) => (
          <MovieCard key={m.id} movie={m} onClick={onSelectMovie} />
        ))}
      </div>
    </section>
  );
}
