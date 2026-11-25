/**
 * Tests de `WatchProgressBar`: la barra de lo visto nunca es la única forma de
 * saberlo (el texto lo dice) y, cuando va sin texto, no estorba a los lectores
 * de pantalla.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WatchProgressBar } from './WatchProgressBar';

describe('WatchProgressBar', () => {
  it('es un <progress> nativo con su valor, que toma el nombre del texto de al lado', () => {
    render(<WatchProgressBar positionSeconds={600} durationSeconds={2700} />);

    const bar = screen.getByRole('progressbar', { name: 'Quedan 35 min' });
    expect(bar.tagName).toBe('PROGRESS');
    expect(bar).toHaveAttribute('value', '600');
    expect(bar).toHaveAttribute('max', '2700');
    expect(screen.getByText('Quedan 35 min')).toBeVisible();
  });

  it('terminado: barra llena y «Visto»', () => {
    render(<WatchProgressBar positionSeconds={2600} durationSeconds={2700} finished />);

    expect(screen.getByRole('progressbar', { name: 'Visto' })).toHaveAttribute('value', '2700');
  });

  it('visto y vuelto a empezar (`watched` sin `finished`): la barra en la posición actual y «Visto · Quedan…»', () => {
    render(<WatchProgressBar positionSeconds={600} durationSeconds={2700} watched />);

    const bar = screen.getByRole('progressbar', { name: 'Visto · Quedan 35 min' });
    expect(bar).toHaveAttribute('value', '600');
  });

  it('`finished` manda sobre `watched`: al final, «Visto» sin más y la barra llena', () => {
    render(<WatchProgressBar positionSeconds={2600} durationSeconds={2700} finished watched />);

    expect(screen.getByRole('progressbar', { name: 'Visto' })).toHaveAttribute('value', '2700');
  });

  it('sin texto (el texto ya está al lado, o va dentro de un enlace): oculta a los lectores de pantalla', () => {
    const { container } = render(<WatchProgressBar positionSeconds={600} durationSeconds={2700} withText={false} />);

    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(container.querySelector('progress')).toHaveAttribute('aria-hidden', 'true');
  });
});
