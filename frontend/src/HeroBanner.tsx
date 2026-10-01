import { Check, Play, Plus } from 'lucide-react';
import type { Movie } from './types';
import { formatDuration, getMovieImage } from './utils';

/**
 * Define las propiedades esperadas por el componente HeroBanner.
 */
interface Props {
  movie: Movie;
  isFavorite: boolean;
  onToggleFavorite: () => void;
  onDetails: (movie: Movie) => void;
}

/**
 * Renderiza el banner principal destacado para una película.
 * Incluye el póster de la película, título, metadatos y botones de acción.
 *
 * @param props Las propiedades del componente que contienen los datos de la película y los manejadores de eventos.
 */
export function HeroBanner({ movie, isFavorite, onToggleFavorite, onDetails }: Props) {
  return (
    <section style={{ padding: '16px 24px 20px' }}>
      <div style={{ position: 'relative', borderRadius: '16px', overflow: 'hidden', height: '460px' }}>

        {/* Imagen */}
        <img
          src={getMovieImage(movie.title, movie.imageUrl)}
          alt={movie.title}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }}
          onError={(e) => { (e.target as HTMLImageElement).style.opacity = '0'; }}
        />

        {/* Gradiente lateral */}
        <div style={{
          position: 'absolute', inset: 0,
          background: 'linear-gradient(to right, rgba(14,17,23,0.92) 0%, rgba(14,17,23,0.65) 40%, transparent 75%)',
        }} />
        {/* Gradiente inferior */}
        <div style={{
          position: 'absolute', inset: 0,
          background: 'linear-gradient(to top, rgba(14,17,23,0.75) 0%, transparent 45%)',
        }} />

        {/* Contenido */}
        <div style={{
          position: 'absolute', bottom: 0, left: 0,
          padding: '0 32px 32px',
          maxWidth: '580px',
          display: 'flex', flexDirection: 'column',
        }}>
          <p style={{ color: '#e8a020', fontSize: '11px', fontWeight: 700,
                      letterSpacing: '0.12em', textTransform: 'uppercase', marginBottom: '8px' }}>
            Película
          </p>

          <h1 style={{ color: 'white', fontSize: '48px', fontWeight: 900,
                       lineHeight: 1.1, margin: '0 0 12px' }}>
            {movie.title}
          </h1>

          {/* Badges */}
          <div style={{ display: 'flex', gap: '8px', marginBottom: '20px', flexWrap: 'wrap' }}>
            {[String(movie.releaseYear), formatDuration(movie.duration)].map((label) => (
              <span
                key={label}
                style={{
                  display: 'inline-flex', alignItems: 'center',
                  padding: '3px 10px',
                  borderRadius: '6px',
                  backgroundColor: 'rgba(255,255,255,0.12)',
                  border: '1px solid rgba(255,255,255,0.18)',
                  color: 'rgba(255,255,255,0.9)',
                  fontSize: '12px', fontWeight: 500,
                }}
              >
                {label}
              </span>
            ))}
          </div>

          {/* Botones */}
          <div style={{ display: 'flex', gap: '12px' }}>
            <button
              onClick={() => onDetails(movie)}
              style={{
                display: 'flex', alignItems: 'center', gap: '8px',
                backgroundColor: 'white', color: 'black',
                fontWeight: 700, fontSize: '14px',
                padding: '10px 20px', borderRadius: '8px',
                border: 'none', cursor: 'pointer',
              }}
            >
              <Play style={{ width: '16px', height: '16px', fill: 'black' }} />
              Ver ahora
            </button>

            <button
              onClick={onToggleFavorite}
              style={{
                display: 'flex', alignItems: 'center', gap: '8px',
                backgroundColor: isFavorite ? 'rgba(255,255,255,0.12)' : 'transparent',
                color: 'white',
                fontWeight: 600, fontSize: '14px',
                padding: '10px 20px', borderRadius: '8px',
                border: '1px solid rgba(255,255,255,0.4)',
                cursor: 'pointer',
              }}
            >
              {isFavorite
                ? <Check style={{ width: '16px', height: '16px', color: '#4ade80' }} />
                : <Plus style={{ width: '16px', height: '16px' }} />}
              {isFavorite ? 'En mi lista' : 'Mi lista'}
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
