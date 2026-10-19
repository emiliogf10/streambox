import { useCallback, useState } from 'react';
import { ApiError, getErrorMessage } from '../lib/api';
import { RESEND_COOLDOWN_SECONDS, RESEND_DONE_MESSAGE, RESEND_RATE_LIMITED_MESSAGE, requestVerificationEmail } from '../lib/emailVerification';
import { formatWaitTime } from '../lib/utils';
import { useCountdown } from './useCountdown';

/**
 * Resultado del último reenvío:
 * - `done`: el servidor lo ha aceptado (202). Se anuncia con `role="status"`.
 * - `limited`: 429; desaparece solo al acabar la espera.
 * - `error`: red caída, 5xx, correo rechazado por el servidor...
 */
export type ResendNotice = { kind: 'done' | 'limited' | 'error'; text: string } | null;

/** Opciones de {@link useResendVerification}. */
interface UseResendVerificationOptions {
  /**
   * Empieza con el botón en espera ({@link RESEND_COOLDOWN_SECONDS}). Para la
   * pantalla que sale justo después del registro: el correo acaba de salir y
   * reenviarlo al instante solo invalidaría el que va de camino.
   */
  startInCooldown?: boolean;
}

/**
 * Lógica del «Reenviar el correo» de confirmación, compartida por la pantalla
 * «Revisa tu correo» del registro, la pista «¿aún no has confirmado tu correo?»
 * del login (tras un 401 o una cuenta bloqueada) y el formulario de «enlace no
 * válido» de `/verificar-email`.
 *
 * - Éxito (202): aviso que no afirma que se haya enviado nada (el servidor
 *   contesta igual exista o no la cuenta) y espera de un minuto antes de poder
 *   pedir otro: cada reenvío invalida el enlace anterior.
 * - 429: aviso con la espera de `Retry-After` y el botón bloqueado hasta entonces.
 * - Otros errores: el mensaje que ya prepara `apiFetch`, y se puede reintentar.
 *
 * No usa TanStack Query porque no hay datos que guardar en caché: es una acción
 * de un formulario, como el registro o el cambio de contraseña.
 *
 * @returns `resend(email)`, si está enviando, los segundos de espera que quedan
 *          (`remaining`, 0 si se puede pulsar) y el aviso del último intento
 */
export function useResendVerification({ startInCooldown = false }: UseResendVerificationOptions = {}) {
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<ResendNotice>(null);
  // Al acabar la espera de un 429 se retira su aviso; el de «enviado» se queda (sigue siendo verdad).
  const { remaining, start } = useCountdown(
    () => setNotice((current) => (current?.kind === 'limited' ? null : current)),
    startInCooldown ? RESEND_COOLDOWN_SECONDS : 0,
  );

  const resend = useCallback(
    async (email: string) => {
      setSending(true);
      setNotice(null);
      try {
        await requestVerificationEmail(email);
        setNotice({ kind: 'done', text: RESEND_DONE_MESSAGE(email) });
        start(RESEND_COOLDOWN_SECONDS);
      } catch (error) {
        if (error instanceof ApiError && error.status === 429) {
          const wait = error.retryAfterSeconds;
          if (wait) start(wait);
          setNotice({ kind: 'limited', text: RESEND_RATE_LIMITED_MESSAGE(wait ? formatWaitTime(wait) : undefined) });
        } else {
          setNotice({ kind: 'error', text: getErrorMessage(error) });
        }
      } finally {
        setSending(false);
      }
    },
    [start],
  );

  return { resend, sending, remaining, notice };
}
