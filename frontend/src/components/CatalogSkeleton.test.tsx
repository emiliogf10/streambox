/**
 * Tests de `CatalogSkeleton`: se anuncia como estado de carga con su texto y
 * las formas grises son decorativas (no llegan a los lectores de pantalla).
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CatalogSkeleton } from './CatalogSkeleton';

describe('CatalogSkeleton', () => {
  it('es un `role="status"` cuyo único texto es la etiqueta de carga', () => {
    render(<CatalogSkeleton label="Cargando catálogo..." />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent(/^Cargando catálogo\.\.\.$/);
  });

  it('las siluetas están ocultas a la accesibilidad y su pulso se detiene con movimiento reducido', () => {
    render(<CatalogSkeleton />);

    const shapes = screen.getByRole('status').querySelector('[aria-hidden="true"]');
    expect(shapes).not.toBeNull();
    expect(shapes).toHaveClass('animate-pulse', 'motion-reduce:animate-none');
  });
});
