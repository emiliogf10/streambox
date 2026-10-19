/**
 * Confirmación del correo al registrarse: llamadas a la API, lectura del token
 * del enlace y textos compartidos por el registro, el login y `/verificar-email`.
 *
 * **Por qué existe.** Sin confirmar el correo, cualquiera podía registrarse con
 * la dirección de otra persona, y el registro respondía distinto si el correo
 * ya tenía cuenta (revelaba quién está dado de alta). Ahora el registro y el
 * reenvío responden SIEMPRE lo mismo (202) y la cuenta no puede iniciar sesión
 * hasta que alguien con acceso a ese buzón pulsa el enlace.
 *
 * **Consecuencia para los textos:** como la respuesta es igual exista o no la
 * cuenta, ningún texto puede afirmar que «se ha creado la cuenta» ni que «te
 * hemos enviado» un correo: se dice «si el correo es correcto, te llegará…».
 *
 * Los textos viven aquí (y no en los componentes) para que los tests los usen
 * sin copiarlos y porque un archivo de componentes no debe exportar constantes
 * (rompe el *fast refresh* de Vite).
 */
import { apiFetch } from './api';
import type { AcceptedResponse, EmailVerificationRequest, ResendVerificationRequest } from './types';

/** Horas que dura el enlace de confirmación (`streambox.email.verification-ttl` del backend, 24 h). */
export const VERIFICATION_LINK_TTL_HOURS = 24;

/**
 * Segundos que el botón «Reenviar el correo» queda desactivado tras usarlo (o
 * tras el registro, que acaba de enviar uno).
 *
 * Cada correo nuevo invalida el enlace anterior: pulsar varias veces seguidas solo
 * conseguiría que el primer correo, que quizá ya va de camino, deje de servir.
 * Un minuto da tiempo a que llegue. El servidor tiene además su propio límite
 * (429 con `Retry-After`), que manda si es mayor.
 */
export const RESEND_COOLDOWN_SECONDS = 60;

/**
 * Longitud máxima de un token que merece la pena enviar. Los del backend son 32
 * bytes en Base64URL (43 caracteres); el margen es por si cambia el formato.
 */
const MAX_TOKEN_LENGTH = 512;

/** Caracteres de Base64URL sin relleno, el formato de los tokens del backend. */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]+$/;

/**
 * Lee el token del fragmento del enlace (`#token=…`).
 *
 * El token viaja en el **fragmento** (lo que va tras `#`) porque el navegador
 * nunca lo envía al servidor: no queda en los logs de nginx ni en la cabecera
 * `Referer` de ninguna petición.
 *
 * Un valor con caracteres que un token nuestro no puede tener (un enlace que el
 * cliente de correo ha partido o «adornado») se trata como ausente: la página
 * enseña directamente «enlace no válido» sin gastar una petición.
 *
 * @param hash `location.hash`, con o sin la `#` inicial
 * @returns el token, o `null` si no hay uno con formato válido
 */
export function readVerificationToken(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token')?.trim() ?? '';
  return token.length > 0 && token.length <= MAX_TOKEN_LENGTH && TOKEN_PATTERN.test(token) ? token : null;
}

/**
 * Confirma el correo con el token del enlace (`POST /api/auth/email-verification`).
 *
 * Pública (`public: true`): no necesita sesión y un 401 no debe tocar la de
 * nadie. Responde 204; un token inválido, caducado o ya usado da 400
 * `INVALID_VERIFICATION_TOKEN` (el mismo cuerpo en los tres casos, a propósito).
 *
 * @param token token del enlace (ver {@link readVerificationToken})
 * @throws {ApiError} si el servidor no confirma el correo
 */
export function confirmEmail(token: string): Promise<void> {
  const body: EmailVerificationRequest = { token };
  return apiFetch('/auth/email-verification', { method: 'POST', body, public: true });
}

/**
 * Pide otro correo de confirmación (`POST /api/auth/email-verification/resend`).
 *
 * Pública. Responde 202 con el mismo cuerpo exista o no una cuenta pendiente
 * con ese correo; si se abusa, 429 `RATE_LIMIT_EXCEEDED` con `Retry-After`.
 *
 * @param email correo ya recortado
 * @throws {ApiError} si la petición falla
 */
export function requestVerificationEmail(email: string): Promise<AcceptedResponse> {
  const body: ResendVerificationRequest = { email };
  return apiFetch<AcceptedResponse>('/auth/email-verification/resend', { method: 'POST', body, public: true });
}

/**
 * Aviso tras pedir un enlace nuevo. No afirma que se haya enviado nada: el 202
 * es igual en todos los casos y el servidor solo envía si hay una cuenta
 * pendiente con ese correo **y** no ha saltado su límite silencioso por cuenta
 * (3 correos por hora contando el del registro, 1 por minuto). Ese límite no da
 * 429 a propósito (revelaría que la cuenta existe), así que el aviso lo menciona
 * («salvo que ya hayas pedido varios hace poco»): recargar la página reinicia la
 * espera del cliente y es fácil toparse con él.
 *
 * Tampoco dice que los enlaces anteriores ya no sirvan: si el límite frenó el
 * envío, el último enlace recibido sigue valiendo. «Usa siempre el del correo
 * más reciente» es verdad en los dos casos (si salió uno nuevo, es el único que
 * vale; si no, el más reciente sigue siendo el bueno).
 *
 * @param email correo al que se pidió el enlace
 */
export const RESEND_DONE_MESSAGE = (email: string): string =>
  `Si hay una cuenta pendiente de confirmar con ${email}, te llegará un enlace nuevo en unos minutos, ` +
  'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.';

/**
 * Aviso cuando el servidor frena los reenvíos (429).
 *
 * @param waitText espera ya formateada («45 s», «5 min»), si se conoce
 */
export const RESEND_RATE_LIMITED_MESSAGE = (waitText?: string): string =>
  waitText
    ? `Has pedido demasiados correos seguidos. Podrás pedir otro en ${waitText}.`
    : 'Has pedido demasiados correos seguidos. Inténtalo de nuevo más tarde.';

