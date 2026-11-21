/**
 * Tests de `StableLabel` (D-11): el texto de un botón cambia sin que cambie su
 * ancho. jsdom no mide, así que se comprueba la estructura que lo garantiza en
 * el navegador: todas las etiquetas en la MISMA celda de la rejilla, solo la
 * actual visible, y el nombre accesible solo con la actual.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StableLabel } from './StableLabel';

const OPTIONS = ['Mi lista', 'En mi lista'] as const;

describe('StableLabel', () => {
  it('apila todas las etiquetas en la misma celda (la más ancha fija el ancho) y solo se ve la actual', () => {
    render(
      <button type="button">
        <StableLabel current="Mi lista" options={OPTIONS} />
      </button>,
    );

    const current = screen.getByText('Mi lista');
    const other = screen.getByText('En mi lista');
    expect(current.parentElement).toHaveClass('inline-grid');
    for (const label of [current, other]) expect(label).toHaveClass('col-start-1', 'row-start-1');
    expect(current).not.toHaveClass('invisible');
    expect(other).toHaveClass('invisible');
  });

  it('el nombre accesible del botón es solo la etiqueta actual', () => {
    const { rerender } = render(
      <button type="button">
        <StableLabel current="Mi lista" options={OPTIONS} />
      </button>,
    );
    expect(screen.getByRole('button')).toHaveAccessibleName('Mi lista');

    rerender(
      <button type="button">
        <StableLabel current="En mi lista" options={OPTIONS} />
      </button>,
    );
    expect(screen.getByRole('button')).toHaveAccessibleName('En mi lista');
  });
});
