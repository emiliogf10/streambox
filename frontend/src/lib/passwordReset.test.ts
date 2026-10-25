/**
 * Tests de `lib/passwordReset.ts` y de `validatePasswordReset` (`lib/validation.ts`):
 * la lectura del token del enlace, el correo que el login pasa a
 * `/recuperar-contrasena` (en el `state` de la navegación, nunca en la URL), las
 * reglas del formulario «Elige una contraseña nueva» y los textos que no deben
 * afirmar que la cuenta exista.
 */
import { describe, expect, it } from 'vitest';
import { readVerificationToken } from './emailVerification';
import {
  RESET_REQUEST_DONE_MESSAGE,
  emailFromLocationState,
  forgotPasswordState,
  readResetToken,
} from './passwordReset';
import { PASSWORD_MAX, PASSWORD_MIN, validatePasswordReset } from './validation';

describe('readResetToken', () => {
  it('lee el token de `#token=…` con las mismas reglas que el enlace de confirmación', () => {
    const token = 'dGhpcy1pcy1hLXRlc3QtdG9rZW4tb2YtMzItYnl0ZXM';
    expect(readResetToken(`#token=${token}`)).toBe(token);
    expect(readResetToken('#token=abc%3Cscript%3E')).toBeNull();
    expect(readResetToken('')).toBeNull();
    expect(readResetToken).toBe(readVerificationToken);
  });
});

describe('forgotPasswordState (el correo que el login pasa a «He olvidado mi contraseña»)', () => {
  it('pasa el correo recortado si tiene buen formato', () => {
    expect(forgotPasswordState('  ana@example.com ')).toEqual({ email: 'ana@example.com' });
  });

  it.each([
    ['vacío', ''],
    ['solo espacios', '   '],
    ['sin @', 'ana.example.com'],
  ])('no pasa nada si el correo está %s', (_case, email) => {
    expect(forgotPasswordState(email)).toBeUndefined();
  });
});

describe('emailFromLocationState', () => {
  it('devuelve el correo del estado de la navegación', () => {
    expect(emailFromLocationState({ email: 'ana@example.com' })).toBe('ana@example.com');
  });

  it.each([
    ['sin estado', undefined],
    ['null', null],
    ['otra forma', { from: '/login' }],
    ['correo que no es texto', { email: 42 }],
    ['correo mal formado', { email: 'no-es-un-correo' }],
    ['un texto suelto', 'ana@example.com'],
  ])('devuelve cadena vacía con %s (el estado no es de fiar)', (_case, state) => {
    expect(emailFromLocationState(state)).toBe('');
  });
});

describe('validatePasswordReset', () => {
  const valid = 'a'.repeat(PASSWORD_MIN);

  it('acepta una contraseña de longitud válida repetida igual', () => {
    expect(validatePasswordReset({ newPassword: valid, confirmPassword: valid })).toEqual({});
    const longest = 'b'.repeat(PASSWORD_MAX);
    expect(validatePasswordReset({ newPassword: longest, confirmPassword: longest })).toEqual({});
  });

  it('rechaza las longitudes fuera del rango (justo por debajo y por encima)', () => {
    const short = 'a'.repeat(PASSWORD_MIN - 1);
    const long = 'a'.repeat(PASSWORD_MAX + 1);
    const message = `La contraseña debe tener entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres.`;
    expect(validatePasswordReset({ newPassword: short, confirmPassword: short }).newPassword).toBe(message);
    expect(validatePasswordReset({ newPassword: long, confirmPassword: long }).newPassword).toBe(message);
  });

  it('pide repetirla y que coincida', () => {
    expect(validatePasswordReset({ newPassword: valid, confirmPassword: '' })).toEqual({
      confirmPassword: 'Repite la contraseña nueva.',
    });
    expect(validatePasswordReset({ newPassword: valid, confirmPassword: `${valid}x` })).toEqual({
      confirmPassword: 'Las contraseñas no coinciden.',
    });
  });

  it('no recorta: un espacio al final cuenta (no coincide con la misma sin él)', () => {
    expect(validatePasswordReset({ newPassword: `${valid} `, confirmPassword: valid }).confirmPassword).toBe(
      'Las contraseñas no coinciden.',
    );
  });
});

describe('RESET_REQUEST_DONE_MESSAGE', () => {
  it('no afirma que exista la cuenta ni que haya salido un correo, y es verdad con el límite silencioso', () => {
    const text = RESET_REQUEST_DONE_MESSAGE('ana@example.com');
    expect(text).toBe(
      'Si hay una cuenta con ana@example.com, te llegará un enlace nuevo en unos minutos, salvo que ya hayas ' +
        'pedido varios hace poco. Usa siempre el del correo más reciente.',
    );
    expect(text).not.toMatch(/te hemos enviado/i);
  });
});
