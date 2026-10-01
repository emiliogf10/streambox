import type { Movie } from './types';
import { formatDuration } from './utils';

/**
 * Propiedades para el componente HeroInfoPanels.
 */
interface Props {
  movie: Movie;
}

const panelStyle: React.CSSProperties = {
  backgroundColor: '#161b27',
  border: '1px solid rgba(255,255,255,0.1)',
  borderRadius: '12px',
  padding: '20px',
};

const labelStyle: React.CSSProperties = {
  color: '#8892a4',
  fontSize: '11px',
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.1em',
  marginBottom: '12px',
};

/**
 * Muestra paneles informativos debajo del banner principal.
 * Muestra la sinopsis de la película, el reparto (simulado) y los detalles técnicos.
 *
 * @param props Propiedades del componente que contienen los datos de la película.
 */
export function HeroInfoPanels({ movie }: Props) {
  return (
    <div style={{ padding: '0 24px 24px',
                  display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>

      {/* SINOPSIS */}
      <div style={panelStyle}>
        <p style={labelStyle}>Sinopsis</p>
        <p style={{ color: '#d1d5db', fontSize: '14px', lineHeight: 1.6 }}>
          {movie.description || 'Sin descripción disponible.'}
        </p>
      </div>

      {/* REPARTO — API no expone elenco, se muestra estado vacío honesto */}
      <div style={panelStyle}>
        <p style={labelStyle}>Reparto</p>
        <p style={{ color: '#8892a4', fontSize: '14px', fontStyle: 'italic' }}>
          Información no disponible en esta versión.
        </p>
      </div>

      {/* FICHA */}
      <div style={panelStyle}>
        <p style={labelStyle}>Ficha</p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
          {[
            { label: 'Año', value: String(movie.releaseYear) },
            { label: 'Duración', value: formatDuration(movie.duration) },
          ].map(({ label, value }) => (
            <div key={label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '14px' }}>
              <span style={{ color: '#8892a4' }}>{label}</span>
              <span style={{ color: '#d1d5db', fontWeight: 500 }}>{value}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
