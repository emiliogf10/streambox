import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { ResendNotice } from '../components/ResendVerification';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useEmailLinkRequest } from '../hooks/useEmailLinkRequest';
import { ApiError, getErrorMessage } from '../lib/api';
import { RESEND_RATE_LIMITED_MESSAGE } from '../lib/emailVerification';
import {
  RESET_LINK_TTL_HOURS,
  RESET_REQUEST_DONE_MESSAGE,
  emailFromLocationState,
  requestPasswordReset,
} from '../lib/passwordReset';
import { formatWaitTime } from '../lib/utils';
import { emailError } from '../lib/validation';

/** `id` del campo de correo (también lo usan la etiqueta y el mensaje de error). */
const EMAIL_ID = 'forgot-email';

/** Clases de los enlaces de texto, iguales que los de login y registro. */
const TEXT_LINK = 'focus-ring font-semibold text-accent underline-offset-2 hover:underline';

/**
 * Pie de las dos pantallas: volver a «Iniciar sesión» o, si ya hay una sesión
 * abierta en este navegador, al inicio (el login redirigiría allí de todos modos).
 */
function BackToLogin({ isAuthenticated }: { isAuthenticated: boolean }) {
  return isAuthenticated ? (
    <Link to="/" className={TEXT_LINK}>
      Ir al inicio
    </Link>
  ) : (
    <>
      ¿Te has acordado de la contraseña?{' '}
      <Link to="/login" className={TEXT_LINK}>
        Inicia sesión
      </Link>
    </>
  );
}

/** Propiedades de {@link CheckYourEmail}. */
interface CheckYourEmailProps {
  /** Correo al que se pidió el enlace (ya recortado). */
  email: string;
  /** Vuelve al formulario, con lo escrito, para corregir el correo. */
  onEditEmail: () => void;
}

/**
 * Pantalla «Revisa tu correo», justo después de pedir el enlace.
 *
 * **Lo que NO dice, a propósito.** El servidor responde lo mismo (202) exista o
 * no una cuenta con ese correo, así que el texto no afirma «te hemos enviado un
 * correo»: dice qué pasará *si hay una cuenta*. Tampoco promete que llegue uno
 * nuevo si ya se pidieron varios (el límite por cuenta del servidor es
 * silencioso: el mismo 202 sin enviar nada) y por eso remite al más reciente.
 *
 * **Para cualquier cuenta.** Vale igual para una cuenta con el correo
 * confirmado, una pendiente de confirmar o una antigua de antes de la
 * confirmación (que nunca recibió ningún enlace): al elegir la contraseña nueva,
 * el correo queda confirmado (lo acaba de demostrar), y la pantalla lo dice
 * porque es justo la salida de quien no puede entrar por no haberlo confirmado.
 * Y para un correo sin cuenta: no le llega nada, y el texto lo deja ver sin
 * afirmarlo, con la salida de crear una.
 *
 * **Sin callejones sin salida:** pedir otro enlace (en espera el primer minuto:
 * acaba de salir uno y otro lo invalidaría), corregir el correo, registrarse y
 * volver a «Iniciar sesión».
 *
 * Al aparecer, el foco va al título: el formulario (y el botón pulsado) ya no
 * existen, y así el lector de pantalla anuncia «Revisa tu correo».
 */
function CheckYourEmail({ email, onEditEmail }: CheckYourEmailProps) {
  const { isAuthenticated } = useAuth();
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => titleRef.current?.focus(), []);
  const { request, sending, remaining, notice } = useEmailLinkRequest({
    send: requestPasswordReset,
    doneMessage: RESET_REQUEST_DONE_MESSAGE,
    startInCooldown: true,
  });
  const waiting = remaining > 0;

  return (
    <AuthLayout
      title="Revisa tu correo"
      subtitle="Busca el enlace para elegir una contraseña nueva."
      titleRef={titleRef}
      footer={<BackToLogin isAuthenticated={isAuthenticated} />}
    >
      <div className="flex flex-col gap-4 text-sm leading-relaxed text-gray-300">
        <p>
          Si hay una cuenta con <strong className="font-semibold break-all text-white">{email}</strong>, en unos
          minutos te llegará un correo con un enlace para elegir una contraseña nueva. El enlace caduca en{' '}
          {RESET_LINK_TTL_HOURS} hora y solo sirve una vez. Si ya habías pedido varios hace poco, puede que no llegue
          otro: usa el del correo más reciente.
        </p>
        <p>Si aún no habías confirmado tu correo, al elegir la contraseña nueva quedará confirmado y podrás entrar.</p>
        <p>
          ¿No lo encuentras? Mira en la carpeta de spam o de correo no deseado y comprueba que la dirección esté bien
          escrita. Si no tienes ninguna cuenta con ella, no te llegará nada
          {isAuthenticated ? '.' : (
            <>
              : puedes{' '}
              <Link to="/registro" className={TEXT_LINK}>
                crear una cuenta
              </Link>
              .
            </>
          )}
        </p>
      </div>

      <div className="mt-6 flex flex-col gap-3">
        <div>
          <Button variant="outline" className="w-full" disabled={sending || waiting} onClick={() => void request(email)}>
            {sending ? 'Enviando...' : waiting ? `Reenviar en ${formatWaitTime(remaining)}` : 'Reenviar el enlace'}
          </Button>
          <ResendNotice notice={notice} />
        </div>
        <Button variant="ghost" className="w-full" onClick={onEditEmail}>
          Corregir el correo
        </Button>
      </div>
    </AuthLayout>
  );
}

/**
 * Página `/recuperar-contrasena` («He olvidado mi contraseña»): se escribe el
 * correo y el servidor envía, si hay una cuenta con él, un enlace a
 * `/restablecer-contrasena#token=…` (`POST /api/auth/password-reset`).
 *
 * Es pública y **sin guarda** (como `/verificar-email`): quien tiene la sesión
 * abierta pero no recuerda la contraseña también puede usarla.
 *
 * - El correo puede venir ya escrito desde el login, en el `state` de la
 *   navegación (nunca en la URL; ver `ForgotPasswordLocationState`).
 * - Se valida en el cliente (vacío o mal formado no se envía; el foco va al campo).
 * - 202: pantalla {@link CheckYourEmail}. El texto del servidor no se enseña
 *   (siempre es el mismo): la pantalla pone el suyo.
 * - 400 `VALIDATION_ERROR`: el motivo junto al campo.
 * - 429: aviso y el botón bloqueado con la cuenta atrás de `Retry-After`.
 * - Red caída, 5xx...: aviso, y el mismo botón para reintentar.
 */
export function ForgotPasswordPage() {
  const location = useLocation();
  const { isAuthenticated } = useAuth();
  const [email, setEmail] = useState(() => emailFromLocationState(location.state));
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  /** Correo al que se ha pedido el enlace; mientras exista se ve «Revisa tu correo». */
  const [sentTo, setSentTo] = useState<string | null>(null);
  /** Al volver de «Corregir el correo», el foco va al campo (el botón pulsado ya no existe). */
  const focusEmailOnReturn = useRef(false);
  const { remaining, start } = useCountdown(() => setFormError(''));
  useDocumentTitle(sentTo === null ? 'Recuperar la contraseña' : 'Revisa tu correo');

  useEffect(() => {
    if (sentTo === null && focusEmailOnReturn.current) {
      focusEmailOnReturn.current = false;
      document.getElementById(EMAIL_ID)?.focus();
    }
  }, [sentTo]);

  /** Pinta un error del campo y lleva allí el foco, para que quien usa teclado o lector lo encuentre. */
  const showFieldError = (message: string) => {
    setFieldError(message);
    document.getElementById(EMAIL_ID)?.focus();
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');
    const value = email.trim();
    const problem = emailError(value);
    if (problem) {
      showFieldError(problem);
      return;
    }
    setFieldError(undefined);
    setSubmitting(true);
    try {
      await requestPasswordReset(value);
      setSentTo(value);
    } catch (error) {
      if (error instanceof ApiError && error.status === 429) {
        const wait = error.retryAfterSeconds;
        if (wait) start(wait);
        setFormError(RESEND_RATE_LIMITED_MESSAGE(wait ? formatWaitTime(wait) : undefined));
      } else if (error instanceof ApiError && error.code === 'VALIDATION_ERROR') {
        showFieldError(error.validationErrors?.email ?? error.message);
      } else {
        setFormError(getErrorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (sentTo !== null) {
    return (
      <CheckYourEmail
        email={sentTo}
        onEditEmail={() => {
          focusEmailOnReturn.current = true;
          setSentTo(null);
        }}
      />
    );
  }

  const blocked = remaining > 0;
  return (
    <AuthLayout
      title="¿Has olvidado tu contraseña?"
      subtitle="Escribe el correo de tu cuenta y te llegará un enlace para elegir una nueva."
      footer={<BackToLogin isAuthenticated={isAuthenticated} />}
    >
      <FormAlert message={formError} />
      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          id={EMAIL_ID}
          label="Correo electrónico"
          type="email"
          autoComplete="email"
          placeholder="tu@correo.com"
          required
          error={fieldError}
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            setFieldError(undefined);
          }}
        />
        <Button type="submit" disabled={submitting || blocked} className="mt-2 w-full">
          {submitting ? 'Enviando...' : blocked ? `Reintentar en ${formatWaitTime(remaining)}` : 'Enviar el enlace'}
        </Button>
      </form>
    </AuthLayout>
  );
}
