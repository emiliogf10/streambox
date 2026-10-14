/**
 * Tests de `PosterCard` y de sus dos usos, `MovieCard` y `SeriesCard`.
 *
 * Protegen la generalización: la misma tarjeta es SIEMPRE un ENLACE a la página
 * del título (película o serie), con el mismo contenido (póster decorativo,
 * título y dato secundario) y el mismo nombre accesible. Antes la de película
 * era un botón que abría un diálogo; desde que cada película tiene su página
 * (`/peliculas/:id`) se comprueba que ya no queda rastro de eso (`aria-haspopup`).
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import { LocationProbe } from '../test/LocationProbe';
import { makeMovie, makeSeries } from '../test/helpers';
import { MovieCard } from './MovieCard';
import { PosterCard } from './PosterCard';
import { SeriesCard } from './SeriesCard';

/** Monta `ui` con un enrutador en memoria y la sonda de la ruta actual. */
function renderInRouter(ui: ReactElement) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="*" element={ui} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('PosterCard', () => {
  it('es un enlace (sin aria-haspopup) y el póster es decorativo', () => {
    renderInRouter(
      <PosterCard title="Dark" imageUrl="https://img.example/dark.webp" meta="2017–2020 · 3 temporadas" to="/series/4" />,
    );

    const card = screen.getByRole('link', { name: 'Dark 2017–2020 · 3 temporadas' });
    expect(card).toHaveAttribute('href', '/series/4');
    expect(card).not.toHaveAttribute('aria-haspopup');
    expect(card.querySelector('img')).toHaveAttribute('alt', '');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('el ancho por defecto es el de una fila; se puede cambiar (rejilla)', () => {
    const { rerender } = renderInRouter(<PosterCard title="A" imageUrl="" meta="m" to="/peliculas/1" />);
    expect(screen.getByRole('link')).toHaveClass('w-36');

    rerender(
      <MemoryRouter>
        <PosterCard title="A" imageUrl="" meta="m" to="/peliculas/1" className="w-full" />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link')).toHaveClass('w-full');
    expect(screen.getByRole('link')).not.toHaveClass('w-36');
  });
});

describe('PosterCard: movimiento reducido', () => {
  it('el zoom de la imagen y el hundido al pulsar se desactivan con `prefers-reduced-motion`', () => {
    renderInRouter(<PosterCard title="A" imageUrl="https://img.example/a.webp" meta="m" to="/peliculas/1" />);

    const card = screen.getByRole('link');
    expect(card).toHaveClass('active:scale-98', 'motion-reduce:active:scale-100');
    expect(card.querySelector('img')).toHaveClass('group-hover:scale-105', 'motion-reduce:group-hover:scale-100');
  });
});

describe('MovieCard y SeriesCard', () => {
  it('MovieCard: año y duración, enlace a /peliculas/:id, y pulsarla lleva a esa página', async () => {
    const movie = makeMovie({ id: 5, title: 'Arrival', releaseYear: 2016, duration: 116 });
    renderInRouter(<MovieCard movie={movie} />);

    const card = screen.getByRole('link', { name: 'Arrival 2016 · 1h 56m' });
    expect(card).toHaveAttribute('href', '/peliculas/5');
    expect(card).not.toHaveAttribute('aria-haspopup');
    await userEvent.setup().click(card);

    expect(screen.getByText('ruta:/peliculas/5')).toBeInTheDocument();
  });

  it('SeriesCard: años y temporadas, enlace a /series/:id', () => {
    renderInRouter(
      <SeriesCard series={makeSeries({ id: 9, title: 'Dark', releaseYear: 2017, endYear: null, seasonCount: 1 })} />,
    );

    expect(screen.getByRole('link', { name: 'Dark 2017– · 1 temporada' })).toHaveAttribute('href', '/series/9');
  });
});
