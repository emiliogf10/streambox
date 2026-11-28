/**
 * Tests de las miniaturas de `EpisodeList` (imagen propia del episodio, V16).
 *
 * Protegen la regla por temporada de «Miniaturas»: si ningún episodio tiene
 * imagen, la lista es solo texto como antes; si alguno la tiene, TODOS llevan
 * miniatura (los demás, la imagen de fondo de la serie o el respaldo, nunca la
 * portada vertical) para que las filas queden alineadas; y cada miniatura se
 * carga en diferido, es decorativa y tiene su hueco 16:9 reservado.
 */
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { Episode, Season } from '../lib/types';
import { makeEpisode } from '../test/helpers';
import { EpisodeList } from './EpisodeList';

function renderList(episodes: Episode[], seriesBackdropUrl: string | null = null) {
  const season: Season = { seasonNumber: 1, episodes };
  render(
    <MemoryRouter>
      <EpisodeList season={season} seriesId={3} seriesBackdropUrl={seriesBackdropUrl} />
    </MemoryRouter>,
  );
  const list = screen.getByRole('list', { name: 'Episodios de la temporada 1' });
  return { list, rows: within(list).getAllByRole('listitem') };
}

/** Lo que ocupa el hueco de la miniatura de una fila: la `<img>` o el respaldo (decorativo). */
function thumbnailOf(row: HTMLElement) {
  return row.querySelector('.aspect-video');
}

describe('EpisodeList: miniaturas', () => {
  it('si ningún episodio tiene imagen, la lista es solo texto como antes, aunque la serie tenga imagen de fondo', () => {
    const { rows } = renderList(
      [makeEpisode({ episodeNumber: 1 }), makeEpisode({ episodeNumber: 2 })],
      '/covers/serie-fondo.webp',
    );

    for (const row of rows) {
      expect(row.querySelector('img')).toBeNull();
      expect(thumbnailOf(row)).toBeNull();
    }
  });

  it('temporada mixta: todos llevan miniatura; sin imagen propia, la de fondo de la serie', () => {
    const { rows } = renderList(
      [
        makeEpisode({ episodeNumber: 1, imageUrl: 'https://img.example/e1.webp' }),
        makeEpisode({ episodeNumber: 2, imageUrl: null }),
      ],
      '/covers/serie-fondo.webp',
    );

    const [first, second] = rows.map((row) => row.querySelector('img'));
    expect(first).toHaveAttribute('src', 'https://img.example/e1.webp');
    expect(second).toHaveAttribute('src', '/covers/serie-fondo.webp');
    for (const img of [first, second]) {
      // Decorativa (el título está al lado), en diferido y con su proporción 16:9 declarada.
      expect(img).toHaveAttribute('alt', '');
      expect(img).toHaveAttribute('loading', 'lazy');
      expect(img).not.toHaveAttribute('fetchpriority');
      expect(img).toHaveAttribute('width', '1280');
      expect(img).toHaveAttribute('height', '720');
      expect(img?.parentElement).toHaveClass('aspect-video');
    }
  });

  it('temporada mixta sin imagen de fondo de la serie: el hueco es el respaldo, nunca otra imagen', () => {
    const { rows } = renderList([
      makeEpisode({ episodeNumber: 1, imageUrl: '/covers/e1.webp' }),
      makeEpisode({ episodeNumber: 2, imageUrl: null }),
    ]);

    expect(rows[0].querySelector('img')).toHaveAttribute('src', '/covers/e1.webp');
    expect(rows[1].querySelector('img')).toBeNull();
    // El respaldo ocupa el mismo hueco 16:9 (la fila queda alineada) y no se anuncia.
    expect(thumbnailOf(rows[1])).not.toBeNull();
    expect(within(rows[1]).queryByRole('img')).not.toBeInTheDocument();
  });

  it('con miniaturas siguen el título como encabezado, la sinopsis y «Ver» con su nombre único', () => {
    const { rows } = renderList([
      makeEpisode({ episodeNumber: 1, title: 'El faro', imageUrl: '/covers/e1.webp', description: 'Llega la tormenta.' }),
    ]);

    expect(within(rows[0]).getByRole('heading', { level: 3, name: '1. El faro' })).toBeInTheDocument();
    expect(within(rows[0]).getByText('Llega la tormenta.')).toBeInTheDocument();
    expect(within(rows[0]).getByRole('link', { name: /^Ver T1:E1 El faro/ })).toBeInTheDocument();
  });
});
