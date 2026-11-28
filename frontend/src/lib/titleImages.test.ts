import { describe, expect, it } from 'vitest';
import {
  episodePlayerPosterCandidates,
  episodeThumbnailUrl,
  moviePlayerPosterCandidates,
  seasonHasThumbnails,
} from './titleImages';

/**
 * Tests de `titleImages.ts`: qué imagen horizontal se usa en cada sitio. Lo
 * importante es el orden de preferencia y que un campo ausente (servidor
 * anterior a la V16: `undefined`) o vacío se trate como «no hay».
 */
describe('moviePlayerPosterCandidates', () => {
  it('imagen de fondo (16:9 como el vídeo) y después la portada; sin fondo, solo la portada', () => {
    expect(moviePlayerPosterCandidates({ imageUrl: '/covers/p.webp', backdropUrl: '/covers/p-fondo.webp' })).toEqual([
      '/covers/p-fondo.webp',
      '/covers/p.webp',
    ]);
    expect(moviePlayerPosterCandidates({ imageUrl: '/covers/p.webp', backdropUrl: null })).toEqual(['/covers/p.webp']);
    expect(moviePlayerPosterCandidates({ imageUrl: '/covers/p.webp', backdropUrl: '' })).toEqual(['/covers/p.webp']);
    expect(moviePlayerPosterCandidates({ imageUrl: '/covers/p.webp' } as never)).toEqual(['/covers/p.webp']);
  });

  it('sin repetidas: un fondo igual que la portada no se prueba dos veces', () => {
    expect(moviePlayerPosterCandidates({ imageUrl: '/covers/p.webp', backdropUrl: '/covers/p.webp' })).toEqual([
      '/covers/p.webp',
    ]);
  });
});

describe('episodePlayerPosterCandidates', () => {
  const series = { imageUrl: '/covers/s.webp', backdropUrl: '/covers/s-fondo.webp' };

  it('imagen del episodio → imagen de fondo de la serie → portada de la serie', () => {
    expect(episodePlayerPosterCandidates({ imageUrl: '/covers/e.webp' }, series)).toEqual([
      '/covers/e.webp',
      '/covers/s-fondo.webp',
      '/covers/s.webp',
    ]);
    expect(episodePlayerPosterCandidates({ imageUrl: null }, series)).toEqual(['/covers/s-fondo.webp', '/covers/s.webp']);
    expect(episodePlayerPosterCandidates({ imageUrl: null }, { ...series, backdropUrl: null })).toEqual([
      '/covers/s.webp',
    ]);
  });

  /**
   * Revisión de QA (V16): una cadena vacía o un campo ausente (servidor
   * anterior a la V16) en CUALQUIER eslabón no entra en la lista: nunca se
   * pasa `poster=""` al vídeo (pediría la propia página como imagen) mientras
   * haya una imagen detrás.
   */
  it('vacío o ausente en cualquier eslabón: no entra en la lista', () => {
    expect(episodePlayerPosterCandidates({ imageUrl: '' }, series)).toEqual(['/covers/s-fondo.webp', '/covers/s.webp']);
    expect(episodePlayerPosterCandidates({} as never, series)).toEqual(['/covers/s-fondo.webp', '/covers/s.webp']);
    expect(episodePlayerPosterCandidates({ imageUrl: '' }, { ...series, backdropUrl: '' })).toEqual(['/covers/s.webp']);
    expect(episodePlayerPosterCandidates({} as never, { imageUrl: '/covers/s.webp' } as never)).toEqual([
      '/covers/s.webp',
    ]);
  });

  it('sin repetidas: un episodio con la misma imagen que el fondo de la serie aparece una vez', () => {
    expect(episodePlayerPosterCandidates({ imageUrl: '/covers/s-fondo.webp' }, series)).toEqual([
      '/covers/s-fondo.webp',
      '/covers/s.webp',
    ]);
  });
});

describe('episodeThumbnailUrl y seasonHasThumbnails', () => {
  it('la miniatura nunca es la portada vertical: imagen propia, de fondo de la serie o null (respaldo)', () => {
    expect(episodeThumbnailUrl({ imageUrl: '/covers/e.webp' }, '/covers/s-fondo.webp')).toBe('/covers/e.webp');
    expect(episodeThumbnailUrl({ imageUrl: null }, '/covers/s-fondo.webp')).toBe('/covers/s-fondo.webp');
    expect(episodeThumbnailUrl({ imageUrl: null }, null)).toBeNull();
    expect(episodeThumbnailUrl({ imageUrl: '' }, undefined)).toBeNull();
  });

  it('una temporada lleva miniaturas solo si algún episodio tiene imagen propia', () => {
    expect(seasonHasThumbnails([{ imageUrl: null }, { imageUrl: '' }])).toBe(false);
    expect(seasonHasThumbnails([{ imageUrl: null }, { imageUrl: '/covers/e.webp' }])).toBe(true);
    expect(seasonHasThumbnails([])).toBe(false);
  });
});
