/**
 * «He olvidado mi contraseña»: llamadas a la API, lectura del token del enlace y
 * textos compartidos por el login, `/recuperar-contrasena` y `/restablecer-contrasena`.
 *
 * **Por qué existe.** Sin esto, quien olvidaba su contraseña perdía la cuenta, y
 * quien registraba primero el correo de otra persona (con su propia contraseña)
 * podía retenerlo para siempre. Ahora quien controla el buzón elige una
 * contraseña nueva con un enlace del correo y, al hacerlo, la cuenta queda con
 * el correo confirmado (lo acaba de demostrar).
 *
 * **Consecuencia para los textos** (igual que en la confirmación del correo): la
 * petición responde SIEMPRE lo mismo (202), exista o no la cuenta, para no
 * revelar quién está dado de alta. Ningún texto puede afirmar que «te hemos
 * enviado» un correo: se dice «si hay una cuenta con X, te llegará…».
 *
 * Los textos viven aquí (y no en los componentes) para que los tests los usen
 * sin copiarlos y porque un archivo de componentes no debe exportar constantes
 * (rompe el *fast refresh* de Vite).
 */
import { apiFetch } from './api';
import { readLinkToken } from './linkToken';
import type { AcceptedResponse, PasswordResetConfirmRequest, PasswordResetRequest } from './types';
import { emailError } from './validation';

/**
 * Horas que dura el enlace para restablecer la contraseña. Copia a mano de
 * `streambox.email.password-reset-ttl` (variable `STREAMBOX_EMAIL_PASSWORD_RESET_TTL`,
 * 1 h por defecto): si se cambia allí, hay que cambiarla aquí también o los
 * textos de `/recuperar-contrasena` y `/restablecer-contrasena` mentirán.
 */
export const RESET_LINK_TTL_HOURS = 1;

/**
 * Lee el token del fragmento del enlace (`/restablecer-contrasena#token=…`). Es
 * {@link readLinkToken}: el mismo formato y las mismas reglas que el enlace de
 * confirmación del correo.
 */
export const readResetToken = readLinkToken;

/**
 * Pide el correo con el enlace para elegir una contraseña nueva
 * (`POST /api/auth/password-reset`).
 *
 * Pública (`public: true`): no necesita sesión y un 401 no debe tocar la de
 * nadie. Responde 202 con el mismo cuerpo exista o no la cuenta; 400
 * `VALIDATION_ERROR` si el correo está mal formado; 429 `RATE_LIMIT_EXCEEDED`
 * con `Retry-After` si se abusa desde la misma IP.
 *
 * @param email correo ya recortado
 * @throws {ApiError} si la petición falla
 */
export function requestPasswordReset(email: string): Promise<AcceptedResponse> {
  const body: PasswordResetRequest = { email };
  return apiFetch<AcceptedResponse>('/auth/password-reset', { method: 'POST', body, public: true });
}

/**
 * Cambia la contraseña con el token del enlace (`POST /api/auth/password-reset/confirm`).
 *
 * Pública. Al responder 204 el servidor ya ha cambiado la contraseña, ha dado el
 * correo por confirmado y ha cerrado TODAS las sesiones de esa cuenta; no abre
 * ninguna nueva.
 *
 * @param token token del enlace (ver {@link readResetToken})
 * @param newPassword contraseña nueva, tal cual (nunca se recorta: los espacios cuentan)
 * @throws {ApiError} 400 `INVALID_RESET_TOKEN` (enlace inválido, caducado o ya
 *   usado: el mismo cuerpo en los tres casos), 400 `VALIDATION_ERROR` (la
 *   contraseña no cumple la política; el token sigue valiendo), 429...
 */
export function confirmPasswordReset(token: string, newPassword: string): Promise<void> {
  const body: PasswordResetConfirmRequest = { token, newPassword };
  return apiFetch('/auth/password-reset/confirm', { method: 'POST', body, public: true });
}

/**
 * Estado de navegación con el que el login abre `/recuperar-contrasena`: el
 * correo que ya estaba escrito, para no obligar a teclearlo otra vez.
 *
 * Va en el `state` de la navegación (el historial del navegador) y **nunca en la
 * URL**: una dirección de correo en la barra acabaría en el historial, en los
 * marcadores, en los logs del servidor y en el `Referer`.
 */
export interface ForgotPasswordLocationState {
  email: string;
}

/**
 * Estado de navegación para ir a `/recuperar-contrasena` con el correo escrito,
 * o `undefined` si no lo merece (vacío o mal formado: no se arrastra basura).
 *
 * @param email correo tal cual está en el campo
 */
export function forgotPasswordState(email: string): ForgotPasswordLocationState | undefined {
  const trimmed = email.trim();
  return emailError(trimmed) ? undefined : { email: trimmed };
}

/**
 * Correo que llega en el `state` de la navegación (ver
 * {@link ForgotPasswordLocationState}). Cualquier otra cosa (sin estado, de otra
 * forma, un correo mal formado) da cadena vacía: el `state` lo puede escribir
 * cualquier código de la página, así que se comprueba en lugar de fiarse.
 *
 * @param state `location.state`
 */
export function emailFromLocationState(state: unknown): string {
  if (typeof state !== 'object' || state === null || !('email' in state)) return '';
  const { email } = state as { email: unknown };
  return typeof email === 'string' && !emailError(email.trim()) ? email.trim() : '';
}

/**
 * Aviso tras pedir otro enlace desde «Revisa tu correo» o desde «enlace no
 * válido». No afirma que se haya enviado nada: el 202 es igual siempre, y el
 * servidor solo envía si hay una cuenta con ese correo **y** no ha saltado su
 * límite silencioso por cuenta (3 por hora, 1 por minuto), que no da 429 a
 * propósito (revelaría que la cuenta existe). Por eso «salvo que ya hayas pedido
 * varios hace poco».
 *
 * «Usa siempre el del correo más reciente» es verdad en los dos casos: si salió
 * uno nuevo, invalida los anteriores; si el límite lo frenó, el último que llegó
 * sigue siendo el bueno.
 *
 * @param email correo al que se pidió el enlace
 */
export const RESET_REQUEST_DONE_MESSAGE = (email: string): string =>
  `Si hay una cuenta con ${email}, te llegará un enlace nuevo en unos minutos, ` +
  'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.';

/**
 * Aviso tras cambiar la contraseña: lo que el servidor ha hecho con las
 * sesiones. Las de los demás dispositivos tienen el refresh token revocado al
 * momento, pero el JWT de acceso que ya tengan no se puede revocar (es
 * *stateless*) y vale hasta 15 minutos más: se dice, como en «Cerrar sesión en
 * todos los dispositivos».
 */
export const RESET_SESSIONS_CLOSED =
  'Por seguridad, se han cerrado todas las sesiones abiertas con tu cuenta. En otros dispositivos puede tardar hasta 15 minutos.';
