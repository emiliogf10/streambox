/**
 * Tests de `validateRegistration`.
 *
 * Espejo de las restricciones de `CreateUserRequest` del backend. Se prueban los
 * valores LÍMITE (justo dentro y justo fuera de cada rango): son los que se
 * rompen al tocar un `<` por un `<=`. Los datos llegan ya recortados (el recorte
 * lo hace `RegisterPage`, que tiene su propio test).
 */
import { describe, expect, it } from 'vitest';
import { PASSWORD_MAX, PASSWORD_MIN, USERNAME_MAX, USERNAME_MIN, validateRegistration } from './validation';

/** Datos válidos de partida; cada test cambia solo un campo. */
const valid = { username: 'ana', email: 'ana@example.com', password: 'Faro-nube-2026' };

describe('validateRegistration', () => {
  it('unos datos correctos no producen errores', () => {
    expect(validateRegistration(valid)).toEqual({});
  });

  it('los límites coinciden con los del backend (usuario 3-50, contraseña 12-64)', () => {
    expect([USERNAME_MIN, USERNAME_MAX, PASSWORD_MIN, PASSWORD_MAX]).toEqual([3, 50, 12, 64]);
  });

  describe('nombre de usuario', () => {
    it.each([
      [USERNAME_MIN - 1, true],
      [USERNAME_MIN, false],
      [USERNAME_MAX, false],
      [USERNAME_MAX + 1, true],
    ])('con %i caracteres, ¿error? %s', (length, hasError) => {
      const errors = validateRegistration({ ...valid, username: 'u'.repeat(length) });
      expect(errors.username !== undefined).toBe(hasError);
    });

    it('el mensaje indica el rango permitido', () => {
      expect(validateRegistration({ ...valid, username: '' }).username).toBe(
        'El nombre de usuario debe tener entre 3 y 50 caracteres.',
      );
    });
  });

  describe('contraseña', () => {
    it.each([
      [PASSWORD_MIN - 1, true],
      [PASSWORD_MIN, false],
      [PASSWORD_MAX, false],
      [PASSWORD_MAX + 1, true],
    ])('con %i caracteres, ¿error? %s', (length, hasError) => {
      const errors = validateRegistration({ ...valid, password: 'p'.repeat(length) });
      expect(errors.password !== undefined).toBe(hasError);
    });

    it('el mensaje indica el rango permitido', () => {
      expect(validateRegistration({ ...valid, password: '' }).password).toBe(
        'La contraseña debe tener entre 12 y 64 caracteres.',
      );
    });

    it('los espacios cuentan como caracteres (no se recorta la contraseña)', () => {
      expect(validateRegistration({ ...valid, password: ' '.repeat(PASSWORD_MIN) }).password).toBeUndefined();
    });
  });

  describe('correo', () => {
    it('vacío pide introducirlo', () => {
      expect(validateRegistration({ ...valid, email: '' }).email).toBe('Introduce tu correo electrónico.');
    });

    it.each(['sin-arroba', '@dominio.com', 'usuario@', 'con espacio@dominio.com', 'a@b c', 'a@@b'])(
      'rechaza %j',
      (email) => {
        expect(validateRegistration({ ...valid, email }).email).toBe('Introduce un correo electrónico válido.');
      },
    );

    // El formato es permisivo a propósito: la regla definitiva es la del servidor.
    it.each(['a@b', 'ana.garcia+tag@sub.example.com'])('acepta %j', (email) => {
      expect(validateRegistration({ ...valid, email }).email).toBeUndefined();
    });
  });

  it('informa de todos los campos erróneos a la vez, no solo del primero', () => {
    const errors = validateRegistration({ username: 'a', email: 'x', password: '1' });

    expect(Object.keys(errors).sort()).toEqual(['email', 'password', 'username']);
  });
});
