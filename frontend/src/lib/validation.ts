/**
 * Validación del formulario de registro en el cliente.
 *
 * Replica las restricciones de `CreateUserRequest` (backend) para dar
 * respuesta inmediata sin ir al servidor. Es solo comodidad: la validación del
 * servidor es la que manda y sus errores (`validationErrors`) también se muestran.
 */
import type { RegisterRequest } from './types';

/** Límites de `CreateUserRequest` (`@Size`). */
export const USERNAME_MIN = 3;
export const USERNAME_MAX = 50;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 100;

/** Errores por campo; un campo sin error no aparece. */
export type RegisterErrors = Partial<Record<keyof RegisterRequest, string>>;

/**
 * Comprueba los datos de registro.
 *
 * El formato de correo es deliberadamente permisivo (algo antes y después de
 * una `@`): el backend aplica la regla definitiva y no conviene rechazar aquí
 * direcciones que él aceptaría.
 *
 * @param values datos ya recortados (`trim`) de usuario y correo
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validateRegistration(values: RegisterRequest): RegisterErrors {
  const errors: RegisterErrors = {};

  if (values.username.length < USERNAME_MIN || values.username.length > USERNAME_MAX) {
    errors.username = `El nombre de usuario debe tener entre ${USERNAME_MIN} y ${USERNAME_MAX} caracteres.`;
  }

  if (!values.email) {
    errors.email = 'Introduce tu correo electrónico.';
  } else if (!/^[^\s@]+@[^\s@]+$/.test(values.email)) {
    errors.email = 'Introduce un correo electrónico válido.';
  }

  if (values.password.length < PASSWORD_MIN || values.password.length > PASSWORD_MAX) {
    errors.password = `La contraseña debe tener entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres.`;
  }

  return errors;
}
