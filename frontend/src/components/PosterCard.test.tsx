/**
 * Tests de `PosterCard` y de sus dos usos, `MovieCard` y `SeriesCard`.
 *
 * Protegen la generalización: la misma tarjeta es un BOTÓN que abre un diálogo
 * (película) o un ENLACE que cambia de página (serie), con el mismo contenido
 * (póster decorativo, título y dato secundario) y el mismo nombre accesible.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { makeMovie, makeSeries } from '../test/helpers';
import { MovieCard } from './MovieCard';
import { PosterCard } from './PosterCard';
import { SeriesCard } from './SeriesCard';

describe('PosterCard', () => {
  it('con onClick es un botón que avisa de que abre un diálogo', async () => {
    const onClick = vi.fn();
    render(<PosterCard title="Dune" imageUrl="https://img.example/dune.webp" meta="2021 · 2h 35m" onClick={onClick} />);

    const card = screen.getByRole('button', { name: 'Dune 2021 · 2h 35m' });
    expect(card).toHaveAttribute('aria-haspopup', 'dialog');
    await userEvent.setup().click(card);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('con to es un enlace (sin aria-haspopup) y el póster es decorativo', () => {
    render(
      <MemoryRouter>
        <PosterCard title="Dark" imageUrl="https://img.example/dark.webp" meta="2017–2020 · 3 temporadas" to="/series/4" />
      </MemoryRouter>,
    );

    const card = screen.getByRole('link', { name: 'Dark 2017–2020 · 3 temporadas' });
    expect(card).toHaveAttribute('href', '/series/4');
    expect(card).not.toHaveAttribute('aria-haspopup');
    expect(card.querySelector('img')).toHaveAttribute('alt', '');
  });

  it('el ancho por defecto es el de una fila; se puede cambiar (rejilla)', () => {
    const { rerender } = render(<PosterCard title="A" imageUrl="" meta="m" onClick={() => {}} />);
    expect(screen.getByRole('button')).toHaveClass('w-36');

    rerender(<PosterCard title="A" imageUrl="" meta="m" onClick={() => {}} className="w-full" />);
    expect(screen.getByRole('button')).toHaveClass('w-full');
    expect(screen.getByRole('button')).not.toHaveClass('w-36');
  });
});

describe('PosterCard: movimiento reducido', () => {
  it('el zoom de la imagen y el hundido al pulsar se desactivan con `prefers-reduced-motion`', () => {
    render(<PosterCard title="A" imageUrl="https://img.example/a.webp" meta="m" onClick={() => {}} />);

    const card = screen.getByRole('button');
    expect(card).toHaveClass('active:scale-98', 'motion-reduce:active:scale-100');
    expect(card.querySelector('img')).toHaveClass('group-hover:scale-105', 'motion-reduce:group-hover:scale-100');
  });
});

describe('MovieCard y SeriesCard', () => {
  it('MovieCard: año y duración, y pulsar entrega la película', async () => {
    const movie = makeMovie({ id: 5, title: 'Arrival', releaseYear: 2016, duration: 116 });
    const onClick = vi.fn();
    render(<MovieCard movie={movie} onClick={onClick} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Arrival 2016 · 1h 56m' }));

    expect(onClick).toHaveBeenCalledWith(movie);
  });

  it('SeriesCard: años y temporadas, enlace a /series/:id', () => {
    render(
      <MemoryRouter>
        <SeriesCard series={makeSeries({ id: 9, title: 'Dark', releaseYear: 2017, endYear: null, seasonCount: 1 })} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: 'Dark 2017– · 1 temporada' })).toHaveAttribute('href', '/series/9');
  });
});
