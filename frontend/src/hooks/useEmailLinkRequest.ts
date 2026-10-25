import { useCallback, useState } from 'react';
import { ApiError, getErrorMessage } from '../lib/api';
import { RESEND_COOLDOWN_SECONDS, RESEND_RATE_LIMITED_MESSAGE } from '../lib/emailVerification';
import { formatWaitTime } from '../lib/utils';
import { useCountdown } from './useCountdown';

/**
 * Resultado de la última petición de un correo con enlace:
 * - `done`: el servidor la ha aceptado (202). Se anuncia con `role="status"`.
 * - `limited`: 429; desaparece solo al acabar la espera.
 * - `error`: red caída, 5xx, correo rechazado por el servidor...
 */
export type ResendNotice = { kind: 'done' | 'limited' | 'error'; text: string } | null;

/** Opciones de {@link useEmailLinkRequest}. */
export interface UseEmailLinkRequestOptions {
  /**
   * Llamada a la API que pide el correo (`POST …`, pública, con 202 siempre
   * igual). Debe ser estable (una función de módulo): forma parte de las
   * dependencias de `request`.
   */
  send: (email: string) => Promise<unknown>;
  /** Aviso tras el 202; también estable. No debe afirmar que exista la cuenta ni que haya salido nada. */
  doneMessage: (email: string) => string;
  /**
   * Empieza con el botón en espera ({@link RESEND_COOLDOWN_SECONDS}). Para las
   * pantallas «Revisa tu correo», que salen justo después de pedir un correo:
   * pedir otro al instante solo invalidaría el que va de camino.
   */
  startInCooldown?: boolean;
}

/**
 * Lógica común de los botones que piden un correo con un enlace de un solo uso:
 * el reenvío de la confirmación del correo (`useResendVerification`) y el
 * enlace para restablecer la contraseña («Revisa tu correo» de
 * `/recuperar-contrasena`), además de `RequestLinkForm` (los estados «enlace no
 * válido» de las dos páginas de los enlaces).
 *
 * Los dos endpoints se comportan igual a propósito: responden 202 con el mismo
 * cuerpo exista o no la cuenta (no revelan quién está dado de alta), cada correo
 * nuevo invalida el enlace anterior, tienen un límite por IP (429 con
 * `Retry-After`) y otro por cuenta que es SILENCIOSO (el mismo 202 sin enviar
 * nada; un 429 delataría que la cuenta existe).
 *
 * - Éxito (202): el aviso `doneMessage` y una espera de un minuto antes de poder
 *   pedir otro.
 * - 429: aviso con la espera de `Retry-After` y el botón bloqueado hasta entonces.
 * - Otros errores: el mensaje que ya prepara `apiFetch`, y se puede reintentar.
 *
 * No usa TanStack Query porque no hay datos que guardar en caché: es la acción
 * de un formulario.
 *
 * @returns `request(email)`, si está enviando, los segundos de espera que quedan
 *          (`remaining`, 0 si se puede pulsar) y el aviso del último intento
 */
export function useEmailLinkRequest({ send, doneMessage, startInCooldown = false }: UseEmailLinkRequestOptions) {
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<ResendNotice>(null);
  // Al acabar la espera de un 429 se retira su aviso; el de «enviado» se queda (sigue siendo verdad).
  const { remaining, start } = useCountdown(
    () => setNotice((current) => (current?.kind === 'limited' ? null : current)),
    startInCooldown ? RESEND_COOLDOWN_SECONDS : 0,
  );

  const request = useCallback(
    async (email: string) => {
      setSending(true);
      setNotice(null);
      try {
        await send(email);
        setNotice({ kind: 'done', text: doneMessage(email) });
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
    [send, doneMessage, start],
  );

  return { request, sending, remaining, notice };
}
