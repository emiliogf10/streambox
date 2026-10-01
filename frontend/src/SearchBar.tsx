import { useEffect, useRef, useState } from 'react';
import { API_URL, authHeader } from './api';
import type { Movie } from './types';
import { MovieDetailsModal } from './MovieDetailsModal';
import { Search } from 'lucide-react';
import { getMovieImage } from './utils';

/**
 * Un componente de barra de búsqueda que consulta a la API las películas que coinciden con el texto ingresado.
 * Muestra un menú desplegable con los resultados de búsqueda y abre un modal al seleccionar una.
 */
export function SearchBar() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Movie[]>([]);
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!query.trim()) { setResults([]); return; }
    const timer = setTimeout(() => {
      fetch(`${API_URL}/movies/search?title=${encodeURIComponent(query)}`, { headers: authHeader() })
        .then((res) => res.json())
        .then((data) => setResults(Array.isArray(data) ? data : (data.content || [])))
        .catch(() => {});
    }, 500);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const handleOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setResults([]);
        setQuery('');
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, []);

  const handleSelect = (m: Movie) => {
    setSelectedMovie(m);
    setQuery('');
    setResults([]);
  };

  return (
    <div style={{ position: 'relative' }} ref={containerRef}>
      <div style={{ display: 'flex', alignItems: 'center', backgroundColor: '#1a2035', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '9999px', padding: '6px 12px', gap: '8px', width: '192px' }}>
        <Search style={{ width: '14px', height: '14px', color: '#8892a4', flexShrink: 0 }} />
        <input
          type="search" placeholder="Buscar títulos..."
          value={query} onChange={(e) => setQuery(e.target.value)}
          style={{ backgroundColor: 'transparent', border: 'none', outline: 'none', color: 'white', fontSize: '14px', width: '100%' }}
        />
      </div>

      {results.length > 0 && (
        <ul style={{ position: 'absolute', right: 0, top: '100%', marginTop: '8px', width: '288px', backgroundColor: '#161b27', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '12px', boxShadow: '0 20px 40px rgba(0,0,0,0.5)', maxHeight: '320px', overflowY: 'auto', zIndex: 50, listStyle: 'none', padding: 0, margin: '8px 0 0 0' }}>
          {results.map((m) => (
            <li
              key={m.id}
              onClick={() => handleSelect(m)}
              style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px', cursor: 'pointer', borderBottom: '1px solid rgba(255,255,255,0.05)' }}
              onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.05)')}
              onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = 'transparent')}
            >
              <img src={getMovieImage(m.title, m.imageUrl)} alt={m.title} style={{ width: '36px', height: '48px', objectFit: 'cover', borderRadius: '6px', flexShrink: 0 }} />
              <div style={{ minWidth: 0 }}>
                <p style={{ color: 'white', fontSize: '14px', fontWeight: 500, margin: '0 0 2px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{m.title}</p>
                <p style={{ color: '#8892a4', fontSize: '12px', margin: 0 }}>{m.releaseYear}</p>
              </div>
            </li>
          ))}
        </ul>
      )}

      {selectedMovie && (
        <MovieDetailsModal
          movie={selectedMovie}
          onClose={() => setSelectedMovie(null)}
        />
      )}
    </div>
  );
}

