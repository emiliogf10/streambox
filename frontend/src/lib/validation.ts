/**
 * Validación en el cliente de los formularios de cuenta: registro y «Editar
 * perfil» (nombre y contraseña).
 *
 * Replica las restricciones de `CreateUserRequest` (backend) para dar
 * respuesta inmediata sin ir al servidor. Es solo comodidad: la validación del
 * servidor es la que manda y sus errores (`validationErrors`) también se muestran.
 */
import type { RegisterRequest } from './types';

/** Límites de `CreateUserRequest` (`@Size`). */
export const USERNAME_MIN = 3;
export const USERNAME_MAX = 50;
/**
 * Política de contraseñas del registro: de 12 a 64 caracteres.
 *
 * - **Mínimo 12**: la longitud es lo que más dificulta adivinar una contraseña
 *   por fuerza bruta; con 8 caracteres caben demasiadas contraseñas débiles.
 * - **Máximo 64**: suficiente para frases de contraseña y para los gestores de
 *   contraseñas. Un tope evita textos enormes y queda por debajo del límite de
 *   BCrypt (el algoritmo con el que el backend las guarda solo tiene en cuenta
 *   los primeros 72 bytes).
 *
 * El servidor rechaza además las contraseñas comunes y las que contienen el
 * nombre de usuario o el correo. Esas reglas NO se copian aquí: la lista de
 * contraseñas comunes vive en el backend (duplicarla en el cliente la haría
 * pública y la desincronizaría); su motivo llega en `validationErrors.password`
 * y la pantalla lo pinta junto al campo.
 */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 64;

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

  const usernameProblem = usernameError(values.username);
  if (usernameProblem) errors.username = usernameProblem;

  if (!values.email) {
    errors.email = 'Introduce tu correo electrónico.';
  } else if (!/^[^\s@]+@[^\s@]+$/.test(values.email)) {
    errors.email = 'Introduce un correo electrónico válido.';
  }

  const passwordProblem = newPasswordLengthError(values.password);
  if (passwordProblem) errors.password = passwordProblem;

  return errors;
}

/**
 * Error de longitud de un nombre de usuario (registro y «Editar perfil»), o
 * `undefined` si es válido. Recibe el nombre ya normalizado.
 *
 * @param username nombre ya recortado (ver {@link normalizeUsername})
 */
export function usernameError(username: string): string | undefined {
  return username.length < USERNAME_MIN || username.length > USERNAME_MAX
    ? `El nombre de usuario debe tener entre ${USERNAME_MIN} y ${USERNAME_MAX} caracteres.`
    : undefined;
}

/** Error de longitud de una contraseña NUEVA (registro y cambio de contraseña), o `undefined` si es válida. */
export function newPasswordLengthError(password: string): string | undefined {
  return password.length < PASSWORD_MIN || password.length > PASSWORD_MAX
    ? `La contraseña debe tener entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres.`
    : undefined;
}

/**
 * Normaliza un nombre de usuario como lo hará el servidor en `PATCH /api/users/me`:
 * sin espacios en los extremos y con los interiores repetidos reducidos a uno.
 * Así la longitud que se comprueba aquí es la que medirá él, y «ana  » no cuenta
 * como un cambio respecto a «ana». (El servidor quita además caracteres
 * invisibles; ese caso raro lo resuelve su propio error.)
 *
 * @param username texto tal cual lo escribió el usuario
 */
export function normalizeUsername(username: string): string {
  return username.trim().replace(/\s+/g, ' ');
}

/** Datos del formulario «Cambiar contraseña»: los del cuerpo más la repetición, que no se envía. */
export interface PasswordChangeValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** Errores por campo del formulario «Cambiar contraseña»; un campo sin error no aparece. */
export type PasswordChangeErrors = Partial<Record<keyof PasswordChangeValues, string>>;

/**
 * Comprueba el formulario «Cambiar contraseña» antes de enviarlo.
 *
 * Replica lo que el servidor puede saber sin consultar nada: que haya
 * contraseña actual, la longitud de la nueva (la misma regla que el registro) y
 * que sea distinta de la actual. Lo que solo sabe él (que la actual sea la
 * correcta, que la nueva no sea común ni contenga tu usuario o correo) llega en
 * `validationErrors` y se pinta junto al campo. Comprobarlo aquí además ahorra
 * intentos: el servidor solo deja 5 fallos de la contraseña actual cada 15 minutos.
 *
 * Las contraseñas nunca se recortan: los espacios cuentan.
 *
 * @param values los tres campos del formulario
 * @returns los errores encontrados; objeto vacío si todo es válido
 */
export function validatePasswordChange(values: PasswordChangeValues): PasswordChangeErrors {
  const errors: PasswordChangeErrors = {};
  if (!values.currentPassword) errors.currentPassword = 'Introduce tu contraseña actual.';

  const lengthProblem = newPasswordLengthError(values.newPassword);
  if (lengthProblem) errors.newPassword = lengthProblem;
  else if (values.newPassword === values.currentPassword) {
    errors.newPassword = 'La nueva contraseña debe ser distinta de la actual.';
  }

  if (!values.confirmPassword) errors.confirmPassword = 'Repite la nueva contraseña.';
  else if (values.confirmPassword !== values.newPassword) errors.confirmPassword = 'Las contraseñas no coinciden.';

  return errors;
}
