/**
 * Tests del degradado de respaldo de los pósters.
 *
 * Protegen las dos propiedades que justifican el diseño: que sea DETERMINISTA
 * (la misma película se ve igual en todas partes) y que de verdad VARÍE entre
 * películas (el defecto original era que todos los huecos eran idénticos).
 */
import { describe, expect, it } from 'vitest';
import { hashText, POSTER_FALLBACK_GRADIENTS, posterFallbackGradient } from './posterFallback';

describe('posterFallbackGradient', () => {
  it('el mismo título da siempre el mismo degradado', () => {
    expect(posterFallbackGradient('La Casa del Lago')).toBe(posterFallbackGradient('La Casa del Lago'));
    expect(hashText('Puerto Seco')).toBe(hashText('Puerto Seco'));
  });

  it('devuelve siempre una de las combinaciones de la lista (también con título vacío o muy largo)', () => {
    for (const title of ['', 'a', 'Ñandú', 'El increíble viaje de la nave perdida más allá de las estrellas olvidadas'.repeat(20)]) {
      expect(POSTER_FALLBACK_GRADIENTS).toContain(posterFallbackGradient(title));
    }
  });

  it('reparte títulos distintos entre varios degradados (no todos iguales)', () => {
    const titles = [
      'La Casa del Lago',
      'Fuego Cruzado',
      'Noches de Invierno',
      'Rutas de Hierro',
      'Puerto Seco',
      'Eco de Medianoche',
      'La Última Frontera',
      'Cielo de Ceniza',
    ];
    const used = new Set(titles.map(posterFallbackGradient));
    expect(used.size).toBeGreaterThanOrEqual(4);
  });

  it('el hash es un entero sin signo de 32 bits', () => {
    const hash = hashText('Dune: Parte Dos'.repeat(100));
    expect(Number.isInteger(hash)).toBe(true);
    expect(hash).toBeGreaterThanOrEqual(0);
    expect(hash).toBeLessThan(2 ** 32);
  });
});
