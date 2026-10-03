/**
 * Tests de `MoviePoster`.
 *
 * Protegen tres decisiones: la imagen siempre sale de `imageUrl` y no filtra el
 * Referer a terceros, nunca se muestra una imagen rota (hueco con el título en su
 * lugar, sin bucles de `onerror`), y un lector de pantalla no repite el título
 * cuando ya está escrito junto a la imagen.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { posterFallbackGradient } from '../lib/posterFallback';
import { MoviePoster } from './MoviePoster';

describe('MoviePoster: con imagen', () => {
  it('pinta la imagen de la API con referrerpolicy="no-referrer" y carga diferida', () => {
    render(<MoviePoster title="Matrix" src="https://img.example/matrix.webp" alt="Cartel de Matrix" />);

    const img = screen.getByRole('img', { name: 'Cartel de Matrix' });

    expect(img).toHaveAttribute('src', 'https://img.example/matrix.webp');
    expect(img).toHaveAttribute('referrerpolicy', 'no-referrer');
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('decoding', 'async');
    expect(img).not.toHaveAttribute('fetchpriority');
    // Dimensiones explícitas: el navegador reserva el hueco y no hay saltos de diseño.
    expect(img).toHaveAttribute('width', '600');
    expect(img).toHaveAttribute('height', '900');
  });

  it('por defecto es decorativa (alt vacío): el lector de pantalla no repite el título', () => {
    render(<MoviePoster title="Matrix" src="https://img.example/matrix.webp" />);

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByRole('presentation')).toHaveAttribute('alt', '');
  });

  it('con priority (el banner) se descarga ya y con prioridad alta', () => {
    render(<MoviePoster title="Matrix" src="https://img.example/matrix.webp" alt="Cartel" priority />);

    const img = screen.getByRole('img', { name: 'Cartel' });

    expect(img).toHaveAttribute('loading', 'eager');
    expect(img).toHaveAttribute('fetchpriority', 'high');
  });
});

describe('MoviePoster: respaldo sin imagen', () => {
  it.each([[undefined], [null], ['']])('con src %j muestra el título en lugar de una imagen', (src) => {
    render(<MoviePoster title="Matrix" src={src} />);

    expect(screen.queryByRole('presentation')).not.toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText('Matrix')).toBeInTheDocument();
  });

  it('si la imagen da error se sustituye por el hueco con el título (sin otra imagen)', () => {
    render(<MoviePoster title="Matrix" src="https://img.example/rota.webp" alt="Cartel de Matrix" />);

    fireEvent.error(screen.getByRole('img', { name: 'Cartel de Matrix' }));

    expect(document.querySelector('img')).toBeNull();
    // Con `alt` el hueco hace de imagen con ese nombre: el contenido sigue disponible.
    expect(screen.getByRole('img', { name: 'Cartel de Matrix' })).toBeInTheDocument();
    expect(screen.getByText('Matrix')).toBeInTheDocument();
  });

  it('el hueco decorativo (sin alt) se oculta a los lectores de pantalla', () => {
    render(<MoviePoster title="Matrix" src={null} />);

    expect(screen.getByText('Matrix').parentElement).toHaveAttribute('aria-hidden', 'true');
  });

  it('si cambia la imagen tras un fallo, vuelve a intentarlo con la nueva', () => {
    const { rerender } = render(<MoviePoster title="Matrix" src="https://img.example/rota.webp" alt="Cartel" />);
    fireEvent.error(screen.getByRole('img', { name: 'Cartel' }));
    expect(document.querySelector('img')).toBeNull();

    rerender(<MoviePoster title="Matrix" src="https://img.example/buena.webp" alt="Cartel" />);

    expect(document.querySelector('img')).toHaveAttribute('src', 'https://img.example/buena.webp');
  });

  it('la versión compacta no escribe el título (no cabe)', () => {
    render(<MoviePoster title="Matrix" src={null} compact />);

    expect(screen.queryByText('Matrix')).not.toBeInTheDocument();
  });

  it('el hueco usa el degradado propio del título: el mismo en todas partes', () => {
    render(
      <>
        <MoviePoster title="Puerto Seco" src={null} />
        <MoviePoster title="Puerto Seco" src="https://img.example/rota.webp" />
      </>,
    );
    fireEvent.error(document.querySelector('img')!);

    const [first, second] = screen.getAllByText('Puerto Seco').map((title) => title.parentElement!);
    for (const gradientClass of posterFallbackGradient('Puerto Seco').split(' ')) {
      expect(first).toHaveClass(gradientClass);
      expect(second).toHaveClass(gradientClass);
    }
  });
});

describe('MoviePoster: como fondo decorativo (backdrop)', () => {
  it('con imagen, es siempre decorativa aunque reciba alt', () => {
    render(<MoviePoster title="Matrix" src="https://img.example/matrix.webp" alt="Cartel" backdrop />);

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(document.querySelector('img')).toHaveAttribute('alt', '');
  });

  it('sin imagen, el hueco es solo el degradado: sin título ni icono y oculto a lectores de pantalla', () => {
    const { container } = render(<MoviePoster title="Matrix" src={null} alt="Cartel" backdrop />);

    expect(screen.queryByText('Matrix')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toBeNull();
    const fallback = container.firstElementChild!.firstElementChild!;
    expect(fallback).toHaveAttribute('aria-hidden', 'true');
    expect(fallback).not.toHaveAttribute('role');
    expect(fallback).toHaveClass(posterFallbackGradient('Matrix').split(' ')[1]);
  });
});
