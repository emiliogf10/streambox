/**
 * Tests de `FormField` y `TextAreaField`: cómo exponen el estado de error.
 *
 * Protegen tres decisiones de accesibilidad:
 * - `aria-invalid` siempre presente: `"false"` sin error (si faltara, Firefox y
 *   Safari dirían «no válido» de un campo `required` vacío nada más cargar).
 * - El error describe el campo (`aria-describedby`) pero NO es una región viva:
 *   el formulario lleva el foco al campo tras un envío fallido, y varias
 *   alertas a la vez se pisaban entre sí.
 * - 16 px en móvil (`text-base`, 14 px desde `sm`): con menos, Safari en iOS
 *   amplía la página al enfocar el campo.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FormField } from './FormField';
import { TextAreaField } from './TextAreaField';

describe('FormField', () => {
  it('sin error: aria-invalid="false" (no ausente) y descrito por la ayuda', () => {
    render(<FormField id="correo" label="Correo" required hint="El de tu cuenta." />);

    const input = screen.getByRole('textbox', { name: 'Correo' });
    expect(input).toHaveAttribute('aria-invalid', 'false');
    expect(input).toHaveAccessibleDescription('El de tu cuenta.');
  });

  it('con error: aria-invalid="true", el error describe el campo y no es una región viva', () => {
    render(<FormField id="correo" label="Correo" required hint="El de tu cuenta." error="Falta el correo." />);

    const input = screen.getByRole('textbox', { name: 'Correo' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Falta el correo.');
    expect(screen.getByText('Falta el correo.')).not.toHaveAttribute('role');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('16 px en móvil y 14 px desde `sm` (Safari en iOS no amplía la página al enfocarlo)', () => {
    render(<FormField id="correo" label="Correo" />);

    expect(screen.getByRole('textbox', { name: 'Correo' })).toHaveClass('text-base', 'sm:text-sm');
  });

  it('un correo no pasa por el corrector ortográfico (es un identificador); el resto de campos, sí', () => {
    render(
      <>
        <FormField id="correo" label="Correo" type="email" />
        <FormField id="titulo" label="Título" />
      </>,
    );

    expect(screen.getByRole('textbox', { name: 'Correo' })).toHaveAttribute('spellcheck', 'false');
    expect(screen.getByRole('textbox', { name: 'Título' })).not.toHaveAttribute('spellcheck');
  });
});

describe('FormField: hueco reservado del mensaje (D-11)', () => {
  it('con `messageSlotClassName`, sin error pinta un hueco vacío (oculto a los lectores) con esas clases', () => {
    const { container } = render(<FormField id="anio" label="Año" messageSlotClassName="sm:min-h-4" />);

    const slot = container.querySelector('p');
    expect(slot).toBeEmptyDOMElement();
    expect(slot).toHaveAttribute('aria-hidden', 'true');
    expect(slot).toHaveClass('mt-1', 'text-xs', 'sm:min-h-4');
    expect(screen.getByRole('textbox', { name: 'Año' })).not.toHaveAttribute('aria-describedby');
  });

  it('con error, el error ocupa ese mismo hueco (mismas clases) y describe el campo', () => {
    const { container } = render(
      <FormField id="anio" label="Año" error="Escribe un año entre 1888 y 2100." messageSlotClassName="sm:min-h-4" />,
    );

    expect(container.querySelectorAll('p')).toHaveLength(1);
    expect(screen.getByText('Escribe un año entre 1888 y 2100.')).toHaveClass('mt-1', 'text-xs', 'sm:min-h-4');
    expect(screen.getByRole('textbox', { name: 'Año' })).toHaveAccessibleDescription('Escribe un año entre 1888 y 2100.');
  });

  it('sin la prop, sin error no hay hueco (como siempre)', () => {
    const { container } = render(<FormField id="nombre" label="Nombre" />);
    expect(container.querySelector('p')).toBeNull();
  });
});

describe('TextAreaField', () => {
  it('sigue las mismas reglas: aria-invalid siempre, error sin región viva y 16 px en móvil', () => {
    const { rerender } = render(<TextAreaField id="sinopsis" label="Sinopsis" value="" maxChars={10} onChange={() => {}} />);

    const area = screen.getByRole('textbox', { name: 'Sinopsis' });
    expect(area).toHaveAttribute('aria-invalid', 'false');
    expect(area).toHaveClass('text-base', 'sm:text-sm');

    rerender(
      <TextAreaField id="sinopsis" label="Sinopsis" value="" maxChars={10} onChange={() => {}} error="Escribe una sinopsis." />,
    );

    expect(area).toHaveAttribute('aria-invalid', 'true');
    expect(area).toHaveAccessibleDescription(/^Escribe una sinopsis\./);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
