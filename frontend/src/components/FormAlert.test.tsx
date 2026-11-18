/**
 * Tests de `FormAlert`: tras un error que no es de ningún campo, el foco va al
 * aviso (decisión del autor, revisión de 2026-11) y el aviso se lee UNA vez: sin
 * región viva cuando recibe el foco (una región viva enfocada se leería dos
 * veces). Con errores de campo en el mismo envío (`takeFocus={false}`), el foco
 * es cosa del formulario y el aviso se anuncia con `role="alert"`, como antes.
 */
import { render, screen } from '@testing-library/react';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { expectAnnouncedFormAlert, expectFocusedFormAlert } from '../test/formAlert';
import { FormAlert } from './FormAlert';

describe('FormAlert', () => {
  it('sin mensaje no pinta nada (ni un contenedor vacío que se pueda enfocar)', () => {
    const { container } = render(<FormAlert message="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('al aparecer se lleva el foco: grupo nombrado por su texto, fuera del tabulador y sin región viva', () => {
    render(
      <FormAlert message="Correo o contraseña incorrectos.">
        <p>Te quedan 3 intentos.</p>
      </FormAlert>,
    );

    const alert = expectFocusedFormAlert(screen.getByText('Correo o contraseña incorrectos.'));
    expect(alert).toHaveAttribute('tabindex', '-1');
    expect(alert).toHaveAccessibleName(expect.stringContaining('Te quedan 3 intentos.'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // Foco visible con el anillo común del proyecto.
    expect(alert).toHaveClass('focus-ring');
  });

  it('con takeFocus={false} (hay errores de campo) no toca el foco y se anuncia con role="alert"', () => {
    render(
      <>
        <input aria-label="Título" autoFocus />
        <FormAlert message="Revisa los campos marcados." takeFocus={false} />
      </>,
    );

    expectAnnouncedFormAlert(screen.getByText('Revisa los campos marcados.'));
    expect(screen.getByLabelText('Título')).toHaveFocus();
  });

  it('un mensaje nuevo vuelve a llevarse el foco; el modo no cambia mientras se ve el mismo mensaje', async () => {
    /** Formulario mínimo: «Fallar» pone un error; «Otro» cambia de error; «Campo» simula corregir un campo. */
    function Harness() {
      const [message, setMessage] = useState('');
      const [fieldErrors, setFieldErrors] = useState(true);
      return (
        <>
          <button type="button" onClick={() => setMessage('Primer error.')}>
            Fallar
          </button>
          <button type="button" onClick={() => setMessage('Segundo error.')}>
            Otro
          </button>
          <button type="button" onClick={() => setFieldErrors(false)}>
            Campo
          </button>
          <FormAlert message={message} takeFocus={!fieldErrors} />
        </>
      );
    }
    const user = userEvent.setup();
    render(<Harness />);

    // Con errores de campo: región viva, el foco se queda donde estaba.
    await user.click(screen.getByRole('button', { name: 'Fallar' }));
    expectAnnouncedFormAlert(screen.getByText('Primer error.'));
    // Corregir el campo no convierte el aviso en otro ni le quita el foco a quien escribe.
    await user.click(screen.getByRole('button', { name: 'Campo' }));
    expectAnnouncedFormAlert(screen.getByText('Primer error.'));
    expect(screen.getByRole('button', { name: 'Campo' })).toHaveFocus();

    // Un error distinto (ya sin errores de campo) sí se lleva el foco.
    await user.click(screen.getByRole('button', { name: 'Otro' }));
    expectFocusedFormAlert(screen.getByText('Segundo error.'));
  });
});
