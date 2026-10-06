/**
 * Tests de `WatchButton`.
 *
 * `videoUrl` viene de la base de datos: un `javascript:` guardado ahí no debe
 * convertirse nunca en un enlace pulsable. También se comprueba que el enlace
 * válido se abre en pestaña nueva sin dar acceso a `window.opener` (`rel`).
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

describe('WatchButton: URL no utilizable', () => {
  it.each([
    ['javascript:alert(document.cookie)'],
    ['JaVaScRiPt:alert(1)'],
    ['data:text/html,<script>alert(1)</script>'],
    ['https://netflix.com@evil.example/'],
    ['/ruta/relativa'],
    [''],
    [null],
    [undefined],
  ])('con %j no hay enlace: solo un botón aria-disabled sin href', (videoUrl) => {
    render(<WatchButton videoUrl={videoUrl} title="Matrix" />);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    const button = screen.getByRole('button', { name: /Ver ahora/ });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toHaveAttribute('href');
  });

  it('explica el motivo a los lectores de pantalla y sigue siendo enfocable con el teclado', async () => {
    const user = userEvent.setup();
    render(<WatchButton videoUrl={null} title="Matrix" />);

    const button = screen.getByRole('button', { name: /Ver ahora/ });
    expect(button).toHaveAccessibleDescription(/No disponible: esta película no tiene un vídeo/);

    // `aria-disabled` (a diferencia de `disabled`) no lo saca del orden de tabulación.
    expect(button).toBeEnabled();
    await user.tab();
    expect(button).toHaveFocus();
  });
});

describe('WatchButton: variante de episodio («Ver»)', () => {
  const episodeProps = {
    title: 'El regreso',
    label: 'Ver',
    accessibleName: 'Ver T1:E3 El regreso',
    unavailableSubject: 'este episodio',
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

  it('con URL insegura: botón desactivado con el nombre del episodio y el motivo referido al episodio', () => {
    render(<WatchButton videoUrl="javascript:alert(1)" {...episodeProps} />);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Ver T1:E3 El regreso' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAccessibleDescription('No disponible: este episodio no tiene un vídeo al que se pueda acceder.');
    expect(button).toHaveAttribute('title', 'Este episodio no tiene un vídeo disponible');
  });
});
