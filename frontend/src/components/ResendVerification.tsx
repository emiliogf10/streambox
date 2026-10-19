import type { ResendNotice as ResendNoticeValue } from '../hooks/useResendVerification';
import { useResendVerification } from '../hooks/useResendVerification';
import { formatWaitTime } from '../lib/utils';
import { Button } from './Button';
import type { ButtonVariant } from './buttonStyles';

/**
 * Aviso del último «Reenviar el correo» (ver `useResendVerification`).
 *
 * La región `role="status"` existe SIEMPRE, aunque esté vacía: los lectores de
 * pantalla solo anuncian con fiabilidad los cambios de una región viva que ya
 * estaba en la página, y el «te llegará un enlace nuevo» es una confirmación
 * tranquila (cortés), no una alarma. Los fallos van aparte, en `role="alert"`,
 * que sí interrumpe. No depende solo del color: el texto dice lo que pasa.
 */
export function ResendNotice({ notice }: { notice: ResendNoticeValue }) {
  const done = notice?.kind === 'done' ? notice.text : '';
  const failure = notice && notice.kind !== 'done' ? notice.text : '';
  return (
    <>
      <div role="status">{done && <p className="mt-3 text-sm text-gray-200">{done}</p>}</div>
      {failure && (
        <p role="alert" className="mt-3 text-sm font-medium text-danger">
          {failure}
        </p>
      )}
    </>
  );
}

/** Propiedades de {@link ResendVerificationButton}. */
interface ResendVerificationButtonProps {
  /** Correo al que se pide el enlace (ya recortado). */
  email: string;
  /** Empieza en espera: para la pantalla de justo después del registro (ver `useResendVerification`). */
  startInCooldown?: boolean;
  /** Variante del botón; por defecto `outline` (acción secundaria de la pantalla). */
  variant?: ButtonVariant;
  /** Clases extra del botón (ancho, márgenes...). */
  className?: string;
}

/**
 * Botón «Reenviar el correo» de confirmación con su aviso debajo, para la
 * pantalla «Revisa tu correo» del registro, donde el correo ya se conoce (el que
 * se acaba de escribir). La pista del login usa el mismo hook y `ResendNotice`,
 * pero con su propio botón: su componente no se desmonta entre intentos y así
 * la espera del reenvío sobrevive a un nuevo envío del formulario.
 *
 * Mientras espera, el botón dice cuánto falta («Reenviar en 45 s»), igual que
 * el «Reintentar en…» del login. El botón no es una región viva, así que el
 * número que cambia cada segundo no se lee en bucle.
 */
export function ResendVerificationButton({
  email,
  startInCooldown = false,
  variant = 'outline',
  className = '',
}: ResendVerificationButtonProps) {
  const { resend, sending, remaining, notice } = useResendVerification({ startInCooldown });
  const waiting = remaining > 0;
  return (
    <div>
      <Button variant={variant} className={className} disabled={sending || waiting} onClick={() => void resend(email)}>
        {sending ? 'Enviando...' : waiting ? `Reenviar en ${formatWaitTime(remaining)}` : 'Reenviar el correo'}
      </Button>
      <ResendNotice notice={notice} />
    </div>
  );
}
