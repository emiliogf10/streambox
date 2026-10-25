import { RESEND_DONE_MESSAGE, requestVerificationEmail } from '../lib/emailVerification';
import { useEmailLinkRequest } from './useEmailLinkRequest';

export type { ResendNotice } from './useEmailLinkRequest';

/** Opciones de {@link useResendVerification}. */
interface UseResendVerificationOptions {
  /**
   * Empieza con el botón en espera (un minuto). Para la pantalla que sale justo
   * después del registro: el correo acaba de salir y reenviarlo al instante solo
   * invalidaría el que va de camino.
   */
  startInCooldown?: boolean;
}

/**
 * Lógica del «Reenviar el correo» de confirmación, compartida por la pantalla
 * «Revisa tu correo» del registro, la pista «¿aún no has confirmado tu correo?»
 * del login (tras un 401 o una cuenta bloqueada) y el formulario de «enlace no
 * válido» de `/verificar-email`.
 *
 * Es {@link useEmailLinkRequest} con la llamada y el aviso de la confirmación
 * del correo (`POST /api/auth/email-verification/resend`,
 * {@link RESEND_DONE_MESSAGE}); el enlace para restablecer la contraseña usa el
 * mismo hook con los suyos (`lib/passwordReset.ts`).
 *
 * @returns `resend(email)`, si está enviando, los segundos de espera que quedan
 *          (`remaining`, 0 si se puede pulsar) y el aviso del último intento
 */
export function useResendVerification({ startInCooldown = false }: UseResendVerificationOptions = {}) {
  const { request, ...state } = useEmailLinkRequest({
    send: requestVerificationEmail,
    doneMessage: RESEND_DONE_MESSAGE,
    startInCooldown,
  });
  return { resend: request, ...state };
}
