import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { FormAlert } from '../components/FormAlert';
import { RequestLinkForm } from '../components/RequestLinkForm';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useHashLinkToken } from '../hooks/useHashLinkToken';
import { ApiError, getErrorMessage } from '../lib/api';
import {
  RESEND_DONE_MESSAGE,
  VERIFICATION_LINK_TTL_HOURS,
  confirmEmail,
  requestVerificationEmail,
} from '../lib/emailVerification';
import { formatWaitTime } from '../lib/utils';

/**
 * Estado de la página:
 * - `ready`: hay token; se espera a que el usuario pulse «Confirmar mi correo».
 * - `confirming`: petición en curso.
 * - `confirmed`: el servidor ha respondido 204.
 * - `invalid`: el token no vale (400) o el enlace no traía ninguno.
 */
type Status = 'ready' | 'confirming' | 'confirmed' | 'invalid';

/** Códigos de error que significan «este enlace no sirve» (no «inténtalo otra vez»). */
const INVALID_LINK_CODES = new Set(['INVALID_VERIFICATION_TOKEN', 'VALIDATION_ERROR']);

/** `id` del campo de correo del formulario «pedir un enlace nuevo». */
const RESEND_EMAIL_ID = 'verify-resend-email';

/** Clases de los enlaces de texto, iguales que los de login y registro. */
const TEXT_LINK = 'focus-ring font-semibold text-accent underline-offset-2 hover:underline';

/**
 * Página `/verificar-email`, a la que lleva el enlace del correo de
 * confirmación (`/verificar-email#token=…`). Es pública y funciona igual con o
 * sin sesión abierta (no tiene guarda: no redirige a nadie).
 *
 * El token se lee del fragmento y se quita de la barra de direcciones con
 * {@link useHashLinkToken} (común con `/restablecer-contrasena`), que también
 * reconoce un enlace nuevo abierto con la página ya en pantalla: cada uno monta
 * de cero {@link VerificationFlow} (con otra `key`), que vuelve a «Confirma tu
 * correo» con el token nuevo, sin errores ni cuentas atrás del enlace anterior.
 */
export function VerifyEmailPage() {
  const link = useHashLinkToken();
  return <VerificationFlow key={link.id} token={link.token} announce={link.id > 0} />;
}

/** Propiedades de {@link VerificationFlow}. */
interface VerificationFlowProps {
  /** Token del enlace, o `null` si no traía uno con formato válido. */
  token: string | null;
  /**
   * Si se monta por un enlace abierto con la página ya en pantalla: entonces el
   * foco va al título nuevo (el elemento que lo tenía ha desaparecido y así el
   * lector de pantalla anuncia la pantalla). Al cargar la página no hace falta.
   */
  announce: boolean;
}

/**
 * Confirmación de un enlace concreto (un token). Vive mientras no se abra otro:
 * {@link VerifyEmailPage} lo vuelve a montar con cada enlace nuevo.
 *
 * **No confirma al cargar; hace falta pulsar un botón.** Los antivirus y
 * filtros del correo abren los enlaces por su cuenta para revisarlos; si la
 * página confirmara al abrirse, ese robot gastaría el token (de un solo uso) y
 * al usuario le saldría «enlace no válido» sin haber hecho nada.
 *
 * Respuestas (por `status`/`code`, nunca por el texto):
 * - 204: «Correo confirmado», con el camino a «Iniciar sesión» (o al inicio si
 *   ya hay sesión en este navegador).
 * - 400 `INVALID_VERIFICATION_TOKEN` (inválido, caducado o ya usado: el mismo
 *   cuerpo en los tres casos) o `VALIDATION_ERROR` (token mal formado): «El
 *   enlace no es válido o ha caducado», que explica que quien ya confirmó solo
 *   tiene que iniciar sesión y ofrece pedir un enlace nuevo. Lo mismo si el
 *   enlace no trae token.
 * - 429: aviso y el botón en espera según `Retry-After`.
 * - Red caída, 5xx...: aviso y el mismo botón para reintentar (el token sigue en memoria).
 *
 * Al pasar a «confirmado» o «no válido», el foco va al título, que cambia: el
 * botón pulsado ya no existe y así el lector de pantalla anuncia el resultado.
 */
function VerificationFlow({ token, announce }: VerificationFlowProps) {
  const { isAuthenticated } = useAuth();
  const [status, setStatus] = useState<Status>(token ? 'ready' : 'invalid');
  const [error, setError] = useState('');
  const { remaining, start } = useCountdown(() => setError(''));
  const titleRef = useRef<HTMLHeadingElement>(null);
  const previousStatus = useRef(status);

  useDocumentTitle(status === 'confirmed' ? 'Correo confirmado' : 'Confirmar correo');

  // Enlace nuevo con la página abierta: el foco estaba en un elemento que ya no existe.
  useEffect(() => {
    if (announce) titleRef.current?.focus();
  }, [announce]);

  useEffect(() => {
    if (previousStatus.current === status) return;
    previousStatus.current = status;
    if (status === 'confirmed' || status === 'invalid') titleRef.current?.focus();
  }, [status]);

  const handleConfirm = async () => {
    if (!token) return;
    setError('');
    setStatus('confirming');
    try {
      await confirmEmail(token);
      setStatus('confirmed');
    } catch (err) {
      if (err instanceof ApiError && err.status === 400 && INVALID_LINK_CODES.has(err.code)) {
        setStatus('invalid');
        return;
      }
      if (err instanceof ApiError && err.status === 429 && err.retryAfterSeconds) start(err.retryAfterSeconds);
      setError(getErrorMessage(err));
      setStatus('ready');
    }
  };

  if (status === 'confirmed') {
    return (
      <AuthLayout title="Correo confirmado" subtitle="Tu cuenta ya está activa." titleRef={titleRef}>
        <p className="mb-6 text-sm leading-relaxed text-gray-300">
          {isAuthenticated
            ? 'Ya hay una sesión abierta en este navegador: puedes seguir donde estabas.'
            : 'Ya puedes iniciar sesión con tu correo y tu contraseña.'}
        </p>
        {isAuthenticated ? (
          <Link to="/" className={buttonClasses('primary', 'w-full')}>
            Ir al inicio
          </Link>
        ) : (
          <Link to="/login" className={buttonClasses('primary', 'w-full')}>
            Iniciar sesión
          </Link>
        )}
      </AuthLayout>
    );
  }

  if (status === 'invalid') {
    return (
      <AuthLayout
        title="El enlace no es válido o ha caducado"
        subtitle={`Cada enlace sirve una sola vez y caduca a las ${VERIFICATION_LINK_TTL_HOURS} horas.`}
        titleRef={titleRef}
        footer={
          isAuthenticated ? undefined : (
            <>
              ¿Aún no tienes cuenta?{' '}
              <Link to="/registro" className={TEXT_LINK}>
                Regístrate
              </Link>
            </>
          )
        }
      >
        <div className="mb-6 flex flex-col gap-3 text-sm leading-relaxed text-gray-300">
          {isAuthenticated ? (
            <p>
              Si ya habías confirmado tu correo, no tienes que hacer nada más: ya tienes la sesión iniciada.{' '}
              <Link to="/" className={TEXT_LINK}>
                Ir al inicio
              </Link>
            </p>
          ) : (
            <p>
              Si ya habías confirmado tu correo, no tienes que hacer nada más:{' '}
              <Link to="/login" className={TEXT_LINK}>
                inicia sesión
              </Link>
              .
            </p>
          )}
          <p>
            {/* Sin prometer el envío: el 202 es siempre igual y no se manda nada sin cuenta pendiente o tras el límite silencioso por cuenta. */}
            Si no, escribe tu correo y, si hay una cuenta pendiente de confirmar con él, te llegará un enlace nuevo. Si
            copiaste el enlace a mano, comprueba también que esté completo.
          </p>
        </div>
        <RequestLinkForm id={RESEND_EMAIL_ID} send={requestVerificationEmail} doneMessage={RESEND_DONE_MESSAGE} />
      </AuthLayout>
    );
  }

  const waiting = remaining > 0;
  return (
    <AuthLayout
      title="Confirma tu correo"
      subtitle="Pulsa el botón para activar tu cuenta y poder iniciar sesión."
      titleRef={titleRef}
    >
      <FormAlert message={error} />
      <Button className="w-full" disabled={status === 'confirming' || waiting} onClick={() => void handleConfirm()}>
        {status === 'confirming'
          ? 'Confirmando...'
          : waiting
            ? `Reintentar en ${formatWaitTime(remaining)}`
            : 'Confirmar mi correo'}
      </Button>
      <p className="mt-6 text-xs leading-relaxed text-muted">
        ¿Por qué hay que pulsar? Algunos programas de correo abren los enlaces por su cuenta para revisarlos. Así, la
        cuenta solo se confirma cuando tú lo decides.
      </p>
    </AuthLayout>
  );
}
