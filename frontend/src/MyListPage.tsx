import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { API_URL, authHeader } from './api';
import type { Movie } from './types';
import { MovieCard } from './MovieCard';
import { MovieDetailsModal } from './MovieDetailsModal';
import { ListX } from 'lucide-react';

export function MyListPage() {
  const [movies, setMovies]             = useState<Movie[]>([]);
  const [loading, setLoading]           = useState(true);
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const navigate = useNavigate();

  const fetchFavorites = useCallback(() => {
    fetch(`${API_URL}/users/me/favorites`, { headers: authHeader() })
      .then((res) => {
        if (res.status === 401 || res.status === 403) { 
          localStorage.removeItem('token');
          navigate('/login'); 
          throw new Error('auth'); 
        }
        if (!res.ok) throw new Error();
        return res.json();
      })
      .then((data: any) => {
        setMovies(Array.isArray(data) ? data : (data.content || []));
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, [navigate]);

  useEffect(() => { fetchFavorites(); }, [fetchFavorites]);

  const handleEmpty = async () => {
    await fetch(`${API_URL}/users/me/favorites`, { method: 'DELETE', headers: authHeader() });
    setMovies([]);
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '60vh' }}>
        <div style={{ width: '40px', height: '40px', border: '4px solid rgba(255,255,255,0.1)', borderTopColor: '#e8a020', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
      </div>
    );
  }

  return (
    <div style={{ padding: '24px', minHeight: '100vh' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <h1 style={{ fontSize: '24px', fontWeight: 700, margin: 0 }}>Mi lista</h1>
        {movies.length > 0 && (
          <button onClick={handleEmpty} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '14px', color: '#8892a4', backgroundColor: 'transparent', border: '1px solid rgba(255,255,255,0.1)', padding: '8px 16px', borderRadius: '8px', cursor: 'pointer' }}>
            <ListX style={{ width: '16px', height: '16px' }} /> Vaciar lista
          </button>
        )}
      </div>

      {movies.length === 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '96px 0', textAlign: 'center', gap: '20px' }}>
          <div style={{ width: '64px', height: '64px', borderRadius: '50%', backgroundColor: '#161b27', border: '1px solid rgba(255,255,255,0.1)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <ListX style={{ width: '32px', height: '32px', color: '#8892a4' }} />
          </div>
          <div>
            <p style={{ fontWeight: 600, margin: '0 0 4px', fontSize: '16px' }}>Tu lista está vacía</p>
            <p style={{ color: '#8892a4', fontSize: '14px', margin: 0 }}>Agrega películas desde el catálogo para encontrarlas aquí.</p>
          </div>
          <Link to="/" style={{ backgroundColor: 'white', color: 'black', fontWeight: 700, padding: '10px 28px', borderRadius: '8px', textDecoration: 'none', fontSize: '14px' }}>Explorar catálogo</Link>
        </div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '16px' }}>
          {movies.map((m) => (
            <MovieCard key={m.id} movie={m} onClick={setSelectedMovie} />
          ))}
        </div>
      )}

      {selectedMovie && (
        <MovieDetailsModal
          movie={selectedMovie}
          onClose={() => setSelectedMovie(null)}
          onUpdate={fetchFavorites}
        />
      )}
    </div>
  );
}


