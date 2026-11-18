/**
 * Tests de `GenreLinks`: los géneros de una ficha como enlaces a su catálogo.
 *
 * Protegen el destino de cada enlace (el catálogo del mismo tipo, filtrado por
 * ese género), el nombre accesible que empieza por el texto visible (WCAG 2.5.3)
 * y la zona táctil de 44 px (WCAG 2.5.5; antes medían 36 px). jsdom no maqueta:
 * el tamaño se comprueba por la clase y la medida real, en el navegador.
 */
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { GenreLinks } from './GenreLinks';

const genres = [
  { id: 4, name: 'Drama' },
  { id: 9, name: 'Thriller' },
];

describe('GenreLinks', () => {
  it('cada género lleva a su catálogo filtrado, con un nombre que empieza por lo que se ve y 44 px de alto', () => {
    render(
      <MemoryRouter>
        <GenreLinks kind="series" genres={genres} />
      </MemoryRouter>,
    );

    const drama = screen.getByRole('link', { name: 'Drama, ver todas las series del género' });
    expect(drama).toHaveAttribute('href', '/series?genero=4');
    expect(drama).toHaveClass('min-h-11');
    expect(screen.getByRole('link', { name: 'Thriller, ver todas las series del género' })).toHaveAttribute(
      'href',
      '/series?genero=9',
    );
  });
});
