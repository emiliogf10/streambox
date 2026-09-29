import type { Movie } from './types';
import { formatDuration } from './utils';

interface Props {
  movie: Movie;
  onClick: (movie: Movie) => void;
}

export function MovieCard({ movie, onClick }: Props) {
  const meta = `${movie.releaseYear} · ${formatDuration(movie.duration)}`;
  return (
    <button
      onClick={() => onClick(movie)}
      style={{ width: '160px', flexShrink: 0, textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}
      className="group"
    >
      <div style={{ position: 'relative', width: '100%', aspectRatio: '2/3', borderRadius: '12px', overflow: 'hidden', backgroundColor: '#161b27', marginBottom: '8px' }}>
        <img src={movie.imageUrl} alt={movie.title} style={{ width: '100%', height: '100%', objectFit: 'cover', transition: 'transform 0.3s' }} className="group-hover:scale-105" onError={(e) => { (e.target as HTMLImageElement).style.opacity = '0'; }} />
        <div style={{ position: 'absolute', inset: 0, backgroundColor: 'black', opacity: 0, transition: 'opacity 0.3s' }} className="group-hover:opacity-25" />
      </div>
      <div style={{ padding: '0 2px' }}>
        <p style={{ color: 'white', fontSize: '14px', fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', margin: '0 0 2px' }}>{movie.title}</p>
        <p style={{ color: '#8892a4', fontSize: '12px', margin: 0 }}>{meta}</p>
      </div>
    </button>
  );
}
