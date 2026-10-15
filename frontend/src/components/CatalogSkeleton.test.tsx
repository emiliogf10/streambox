/**
 * Tests de `CatalogSkeleton` y `DetailSkeleton`: se anuncian como estado de
 * carga con su texto, las formas grises son decorativas (no llegan a los
 * lectores de pantalla) y miden lo mismo que lo que sustituyen.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BANNER_GRID_CLASS, DETAIL_BACK_ROW_CLASS, DETAIL_BANNER_GRID_CLASS, DETAIL_POSTER_SIZE_CLASS } from './bannerGridStyles';
import { CatalogSkeleton, DetailSkeleton } from './CatalogSkeleton';

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

  it('la silueta del banner usa la MISMA rejilla que el banner real (constante compartida)', () => {
    render(<CatalogSkeleton />);

    const grid = screen.getByRole('status').querySelector('[aria-hidden="true"] > div');
    expect(grid).toHaveClass(...BANNER_GRID_CLASS.split(' '));
  });
});

describe('DetailSkeleton', () => {
  it.each(['movie', 'series'] as const)('(%s) es un `role="status"` cuyo único texto es la etiqueta, con las formas ocultas', (kind) => {
    render(<DetailSkeleton label="Cargando película..." kind={kind} />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent(/^Cargando película\.\.\.$/);
    expect(status.querySelector('[aria-hidden="true"]')).toHaveClass('animate-pulse', 'motion-reduce:animate-none');
  });

  it('la cabecera usa la fila del enlace de vuelta, la rejilla centrada y el tamaño de póster de la página real (constantes compartidas)', () => {
    render(<DetailSkeleton label="Cargando película..." kind="movie" />);

    const shapes = screen.getByRole('status').querySelector('[aria-hidden="true"]');
    // Primero la fila de «Volver a películas»/«Volver a series» (misma altura que el enlace: 44 px)...
    const backRow = shapes?.children[0];
    expect(backRow).toHaveClass(...DETAIL_BACK_ROW_CLASS.split(' '));
    expect(backRow?.firstElementChild).toHaveClass('min-h-11');
    // ...y debajo, la rejilla del banner.
    const grid = shapes?.children[1];
    expect(grid).toHaveClass(...DETAIL_BANNER_GRID_CLASS.split(' '));
    expect(grid?.firstElementChild).toHaveClass(...DETAIL_POSTER_SIZE_CLASS.split(' '));
  });
});
