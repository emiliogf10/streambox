/**
 * Tests de `WatchButton`.
 *
 * `videoUrl` viene de la base de datos: un `javascript:` guardado ahí no debe
 * convertirse nunca en un enlace pulsable. También se comprueba que el enlace
 * válido se abre en pestaña nueva sin dar acceso a `window.opener` (`rel`) y
 * que, sin vídeo (es opcional), no queda ningún botón que prometa verlo.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WatchButton } from './WatchButton';

describe('WatchButton: URL válida', () => {
  it('es un enlace a la URL, en pestaña nueva y con rel="noopener noreferrer"', () => {
    render(<WatchButton videoUrl="https://video.example/matrix" title="Matrix" />);

    const link = screen.getByRole('link', { name: 'Ver ahora Matrix (se abre en una pestaña nueva)' });

    expect(link).toHaveAttribute('href', 'https://video.example/matrix');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('usa la URL normalizada (recortada y en minúsculas el esquema/host)', () => {
    render(<WatchButton videoUrl="  HTTPS://Video.Example/matrix  " title="Matrix" />);

    expect(screen.getByRole('link')).toHaveAttribute('href', 'https://video.example/matrix');
  });
});

describe('WatchButton: sin vídeo utilizable', () => {
  it.each([
    ['javascript:alert(document.cookie)'],
    ['JaVaScRiPt:alert(1)'],
    ['data:text/html,<script>alert(1)</script>'],
    ['https://netflix.com@evil.example/'],
    ['/ruta/relativa'],
    [''],
    [null],
    [undefined],
  ])('con %j no pinta nada: ni enlace ni un «Ver ahora» que no lleva a ningún vídeo', (videoUrl) => {
    const { container } = render(<WatchButton videoUrl={videoUrl} title="Matrix" />);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByText(/Ver ahora/)).not.toBeInTheDocument();
    // Ni siquiera un texto oculto para lectores de pantalla: no queda nada en el DOM.
    expect(container).toBeEmptyDOMElement();
  });
});

describe('WatchButton: variante de episodio («Ver»)', () => {
  const episodeProps = {
    title: 'El regreso',
    label: 'Ver',
    accessibleName: 'Ver T1:E3 El regreso',
    variant: 'outline' as const,
  };

  it('texto visible «Ver» y nombre accesible único (que empieza por el texto visible), igual de seguro', () => {
    render(<WatchButton videoUrl="https://video.example/s1e3" {...episodeProps} />);

    const link = screen.getByRole('link', { name: 'Ver T1:E3 El regreso (se abre en una pestaña nueva)' });
    expect(link).toHaveTextContent(/^Ver$/);
    expect(link).toHaveAttribute('href', 'https://video.example/s1e3');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    // Variante secundaria (borde), no la blanca de la acción principal.
    expect(link).toHaveClass('border');
    expect(link).not.toHaveClass('bg-white');
  });

  it('con URL insegura o sin vídeo, el episodio no tiene «Ver»', () => {
    const { container, rerender } = render(<WatchButton videoUrl="javascript:alert(1)" {...episodeProps} />);
    expect(container).toBeEmptyDOMElement();

    rerender(<WatchButton videoUrl={null} {...episodeProps} />);
    expect(container).toBeEmptyDOMElement();
  });
});
