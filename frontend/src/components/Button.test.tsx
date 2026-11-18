/**
 * Tests de `Button`: los estados `pending` e `inactive`, que bloquean el botón
 * sin soltar el foco (en lugar de `disabled`) y sin permitir dobles envíos.
 *
 * jsdom no quita el foco a un botón que pasa a `disabled` (el navegador sí), así
 * que aquí se protege el mecanismo: que el botón NO lleva `disabled`, que se
 * anuncia como no disponible y que no ejecuta su acción ni envía el formulario,
 * tampoco con Intro desde un campo (envío implícito). La pérdida real del foco
 * la vigilan los E2E.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { expectButtonAvailable, expectButtonUnavailable } from '../test/buttonState';
import { Button } from './Button';

/** Formulario que pasa a «Enviando...» al enviarse y se queda así hasta que el test lo resuelva. */
function SlowForm({ onSubmit }: { onSubmit: () => void }) {
  const [sending, setSending] = useState(false);
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
    setSending(true);
  };
  return (
    <form onSubmit={handleSubmit} aria-label="Formulario">
      <label htmlFor="campo">Campo</label>
      <input id="campo" />
      <Button type="submit" pending={sending}>
        {sending ? 'Enviando...' : 'Enviar'}
      </Button>
    </form>
  );
}

describe('Button', () => {
  it('sin estados especiales es un botón normal (type="button" por defecto)', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Pulsar</Button>);
    const button = screen.getByRole('button', { name: 'Pulsar' });
    expect(button).toHaveAttribute('type', 'button');
    expectButtonAvailable(button);
    await userEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('`pending`: se anuncia ocupado y no disponible, conserva el foco y no ejecuta la acción', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(
      <Button pending onClick={onClick}>
        Guardando...
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Guardando...' });
    expectButtonUnavailable(button, { busy: true });
    await user.tab();
    expect(button).toHaveFocus();
    await user.click(button);
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onClick).not.toHaveBeenCalled();
    expect(button).toHaveFocus();
  });

  it('`inactive`: no disponible pero no ocupado, y tampoco ejecuta la acción', async () => {
    const onClick = vi.fn();
    render(
      <Button inactive onClick={onClick}>
        Reintentar en 5 s
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Reintentar en 5 s' });
    expectButtonUnavailable(button);
    expect(button).not.toHaveAttribute('aria-busy');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('un formulario enviado con su botón no se vuelve a enviar: ni con otro clic ni con Intro en un campo', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<SlowForm onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'Enviar' }));
    const busy = screen.getByRole('button', { name: 'Enviando...' });
    expectButtonUnavailable(busy, { busy: true });
    // El foco sigue en el botón pulsado (con `disabled` el navegador lo mandaría al <body>).
    expect(busy).toHaveFocus();

    await user.click(busy);
    await user.type(screen.getByLabelText('Campo'), 'x{Enter}');
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('respeta un `aria-disabled` propio cuando no hay `pending` ni `inactive`', () => {
    render(<Button aria-disabled>Con aria propio</Button>);
    expect(screen.getByRole('button', { name: 'Con aria propio' })).toHaveAttribute('aria-disabled', 'true');
  });
});
