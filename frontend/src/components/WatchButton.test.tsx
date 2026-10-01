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
