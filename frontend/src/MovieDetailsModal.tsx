import { useEffect, useRef, useState } from 'react';
import { API_URL, authHeader } from './api';
import type { Movie } from './types';
import { formatDuration, getMovieImage } from './utils';
import { Check, Play, Plus, X } from 'lucide-react';

/**
 * Propiedades para el componente MovieDetailsModal.
 */
interface Props {
  movie: Movie;
  onClose: () => void;
  onUpdate?: () => void;
}

/**
 * Renderiza un modal superpuesto con información detallada sobre una película específica.
 * Permite al usuario alternar el estado de favorito de la película.
 *
 * @param props Las propiedades, incluyendo la película a mostrar y las funciones de retorno (callbacks).
 */
export function MovieDetailsModal({ movie, onClose, onUpdate }: Props) {
  const [isFavorite, setIsFavorite] = useState(false);
  const [toggling, setToggling]     = useState(false);
  const overlayRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetch(`${API_URL}/users/me/favorites`, { headers: authHeader() })
      .then((res) => res.json())
      .then((data: Movie[]) => {
        setIsFavorite(Array.isArray(data) && data.some((m) => m.id === movie.id));
      })
      .catch(() => {});
  }, [movie.id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  const toggleFavorite = async () => {
    if (toggling) return;
    setToggling(true);
    try {
      const method = isFavorite ? 'DELETE' : 'POST';
      await fetch(`${API_URL}/users/me/favorites/${movie.id}`, { method, headers: authHeader() });
      setIsFavorite(!isFavorite);
      onUpdate?.();
    } finally {
      setToggling(false);
    }
  };

  return (
    <div
      ref={overlayRef}
      style={{ position: 'fixed', inset: 0, zIndex: 100, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '16px', backgroundColor: 'rgba(0,0,0,0.8)' }}
      onClick={(e) => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div style={{ position: 'relative', backgroundColor: '#161b27', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '16px', overflow: 'hidden', width: '100%', maxWidth: '672px', boxShadow: '0 25px 50px -12px rgba(0,0,0,0.5)' }}>
        
        <button onClick={onClose} style={{ position: 'absolute', top: '12px', right: '12px', zIndex: 20, padding: '8px', borderRadius: '50%', backgroundColor: 'rgba(14,17,23,0.8)', border: '1px solid rgba(255,255,255,0.1)', cursor: 'pointer', color: 'white' }}>
          <X style={{ width: '16px', height: '16px' }} />
        </button>

        <div style={{ position: 'relative', height: '288px', width: '100%' }}>
          <img src={getMovieImage(movie.title, movie.imageUrl)} alt={movie.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, #161b27 0%, rgba(22,27,39,0.25) 50%, transparent 100%)' }} />
          
          <div style={{ position: 'absolute', bottom: '16px', left: '24px', right: '40px' }}>
            <p style={{ color: '#e8a020', fontSize: '11px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.1em', margin: '0 0 4px' }}>Película</p>
            <h2 style={{ fontSize: '30px', fontWeight: 900, color: 'white', lineHeight: 1.2, margin: '0 0 8px' }}>{movie.title}</h2>
            <div style={{ display: 'flex', gap: '8px' }}>
              {[String(movie.releaseYear), formatDuration(movie.duration)].map(t => (
                <span key={t} style={{ padding: '2px 8px', borderRadius: '4px', backgroundColor: 'rgba(255,255,255,0.1)', border: '1px solid rgba(255,255,255,0.15)', color: 'rgba(255,255,255,0.9)', fontSize: '12px' }}>{t}</span>
              ))}
            </div>
          </div>
        </div>

        <div style={{ padding: '24px 24px 24px' }}>
          <div style={{ display: 'flex', gap: '12px', marginBottom: '20px' }}>
            <button style={{ display: 'flex', alignItems: 'center', gap: '8px', backgroundColor: 'white', color: 'black', fontWeight: 700, fontSize: '14px', padding: '8px 20px', borderRadius: '8px', border: 'none', cursor: 'pointer' }}>
              <Play style={{ width: '16px', height: '16px', fill: 'black' }} /> Ver ahora
            </button>
            <button onClick={toggleFavorite} disabled={toggling} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 600, fontSize: '14px', padding: '8px 16px', borderRadius: '8px', border: '1px solid', backgroundColor: isFavorite ? 'rgba(255,255,255,0.1)' : 'transparent', borderColor: isFavorite ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.25)', color: isFavorite ? 'white' : '#d1d5db', cursor: toggling ? 'not-allowed' : 'pointer', opacity: toggling ? 0.5 : 1 }}>
              {isFavorite ? <Check style={{ width: '16px', height: '16px', color: '#4ade80' }} /> : <Plus style={{ width: '16px', height: '16px' }} />}
              {isFavorite ? 'En mi lista' : 'Mi lista'}
            </button>
          </div>
          <div style={{ backgroundColor: 'rgba(14,17,23,0.6)', borderRadius: '12px', padding: '16px' }}>
            <p style={{ color: '#8892a4', fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.1em', fontWeight: 600, margin: '0 0 8px' }}>Sinopsis</p>
            <p style={{ color: '#d1d5db', fontSize: '14px', lineHeight: 1.6, margin: 0 }}>{movie.description || 'Sin descripción disponible.'}</p>
          </div>
        </div>
      </div>
    </div>
  );
}
