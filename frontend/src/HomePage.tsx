import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { API_URL, authHeader } from './api';
import type { Movie } from './types';
import { HeroBanner } from './HeroBanner';
import { HeroInfoPanels } from './HeroInfoPanels';
import { MovieRow } from './MovieRow';
import { MovieDetailsModal } from './MovieDetailsModal';

function Spinner() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
                  flexDirection: 'column', gap: '16px', height: '60vh' }}>
      <div
        style={{
          width: '48px', height: '48px',
          border: '4px solid rgba(255,255,255,0.15)',
          borderTopColor: '#e8a020',
          borderRadius: '50%',
          animation: 'spin 0.8s linear infinite',
        }}
      />
      <p style={{ color: '#8892a4', fontSize: '14px' }}>Cargando catálogo...</p>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

function ErrorPanel({ message }: { message: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
                  height: '60vh', padding: '0 16px' }}>
      <div style={{
        backgroundColor: '#1e1a1a',
        border: '1px solid rgba(239,68,68,0.3)',
        borderRadius: '12px',
        padding: '24px 32px',
        textAlign: 'center',
        maxWidth: '400px',
      }}>
        <p style={{ color: '#f87171', fontWeight: 600, marginBottom: '8px' }}>
          Error al conectar con el servidor
        </p>
        <p style={{ color: '#8892a4', fontSize: '13px', lineHeight: '1.5' }}>
          {message}
        </p>
        <p style={{ color: '#8892a4', fontSize: '12px', marginTop: '12px' }}>
          Asegúrate de que el backend está corriendo en el puerto 8080.
        </p>
      </div>
    </div>
  );
}

function EmptyState() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
                  height: '60vh', padding: '0 16px' }}>
      <div style={{ textAlign: 'center', maxWidth: '400px' }}>
        <div style={{ fontSize: '48px', marginBottom: '16px' }}>🎬</div>
        <p style={{ color: 'white', fontWeight: 600, fontSize: '18px', marginBottom: '8px' }}>
          El catálogo está vacío
        </p>
        <p style={{ color: '#8892a4', fontSize: '14px', lineHeight: '1.5' }}>
          No hay películas en la base de datos todavía.
          Añade contenido desde el panel de administración.
        </p>
      </div>
    </div>
  );
}

export function HomePage() {
  const [movies, setMovies]       = useState<Movie[]>([]);
  const [favorites, setFavorites] = useState<Set<number>>(new Set());
  const [loading, setLoading]     = useState(true);
  const [error, setError]         = useState('');
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    fetch(`${API_URL}/movies`, { headers: authHeader() })
      .then((res) => {
        if (res.status === 401 || res.status === 403) {
          localStorage.removeItem('token');
          navigate('/login');
          throw new Error('auth');
        }
        if (!res.ok) throw new Error(`Error ${res.status}: No se pudo cargar el catálogo.`);
        return res.json();
      })
      .then((data: any) => {
        setMovies(Array.isArray(data) ? data : (data.content || []));
        setLoading(false);
      })
      .catch((err: Error) => {
        if (err.message !== 'auth') {
          setError(err.message || 'No se pudo conectar con el servidor.');
        }
        setLoading(false);
      });
  }, [navigate]);

  const fetchFavorites = useCallback(() => {
    fetch(`${API_URL}/users/me/favorites`, { headers: authHeader() })
      .then((res) => (res.ok ? res.json() : []))
      .then((data: any) => {
        setFavorites(new Set(Array.isArray(data) ? data.map((m) => m.id) : []));
      })
      .catch(() => {});
  }, []);

  useEffect(() => { fetchFavorites(); }, [fetchFavorites]);

  const toggleHeroFavorite = async () => {
    const hero = movies[0];
    if (!hero) return;
    const method = favorites.has(hero.id) ? 'DELETE' : 'POST';
    await fetch(`${API_URL}/users/me/favorites/${hero.id}`, { method, headers: authHeader() });
    fetchFavorites();
  };

  if (loading) return <Spinner />;
  if (error)   return <ErrorPanel message={error} />;
  if (movies.length === 0) return <EmptyState />;

  const hero = movies[0];
  const rest  = movies.slice(1);
  const mid   = Math.ceil(rest.length / 2);
  const row1  = rest.slice(0, mid);
  const row2  = rest.slice(mid);

  return (
    <div style={{ paddingBottom: '48px' }}>
      <HeroBanner
        movie={hero}
        isFavorite={favorites.has(hero.id)}
        onToggleFavorite={toggleHeroFavorite}
        onDetails={setSelectedMovie}
      />
      <HeroInfoPanels movie={hero} />

      {row1.length > 0 && (
        <MovieRow title="Tendencias ahora" movies={row1} onSelectMovie={setSelectedMovie} />
      )}
      {row2.length > 0 && (
        <MovieRow title="Más como esto" movies={row2} onSelectMovie={setSelectedMovie} />
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


