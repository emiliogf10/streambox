import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { TriangleAlert } from 'lucide-react';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { ResendNotice } from '../components/ResendVerification';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useResendVerification } from '../hooks/useResendVerification';
import { ApiError, getErrorMessage } from '../lib/api';
import { forgotPasswordState } from '../lib/passwordReset';
import { formatWaitTime } from '../lib/utils';
import { emailError } from '../lib/validation';

/**
 * Minutos que dura el bloqueo de una cuenta tras demasiados fallos.
 *
 * Es la ventana del backend (`streambox.security.rate-limit.lockout.window=15m`).
 * El servidor no la envía en el 401 (sí envía cuántos intentos quedan), así que
 * el aviso previo la menciona de forma fija; cuando el bloqueo ya ha ocurrido,
 * la espera exacta sale de la cabecera `Retry-After`.
 */
const LOCKOUT_MINUTES = 15;

/** Ruta de «He olvidado mi contraseña». */
const FORGOT_PASSWORD_PATH = '/recuperar-contrasena';

/** Clases de los enlaces de texto, iguales que los de registro y `/verificar-email`. */
const TEXT_LINK = 'focus-ring font-semibold text-accent underline-offset-2 hover:underline';

/**
 * Texto del aviso de intentos restantes, con singular y plural.
 *
 * @param remaining intentos que quedan (≥ 1)
 */
function remainingAttemptsMessage(remaining: number): string {
  return remaining === 1
    ? `Te queda 1 intento antes de que la cuenta se bloquee ${LOCKOUT_MINUTES} minutos.`
    : `Te quedan ${remaining} intentos antes de que la cuenta se bloquee ${LOCKOUT_MINUTES} minutos.`;
}

/**
 * Mensaje de cuenta bloqueada (429 `ACCOUNT_LOCKED`).
 *
 * Se calcula UNA vez, al recibir el error, y no se actualiza cada segundo: va
 * dentro de una región `role="alert"`, y si cambiara con la cuenta atrás el
 * lector de pantalla lo volvería a leer entero cada segundo. La cuenta atrás
 * "viva" está en el botón, que no es una región viva.
 *
 * @param seconds espera indicada por `Retry-After`, si se conoce
 */
function accountLockedMessage(seconds?: number): string {
  const intro = 'Tu cuenta está bloqueada temporalmente por demasiados intentos fallidos.';
  return seconds !== undefined
    ? `${intro} Podrás volver a intentarlo en ${formatWaitTime(seconds)}.`
    : `${intro} Inténtalo de nuevo más tarde.`;
}

/**
 * Aviso de intentos restantes, dentro del aviso de credenciales incorrectas.
 *
 * Con un solo intento es más visible (icono, negrita, recuadro con el color de
 * acento): es el último aviso antes de quedarse 15 minutos fuera. La diferencia
 * no depende solo del color: cambia el peso, aparece el icono y el recuadro.
 * Contraste del texto de acento sobre el recuadro (acento al 10 % encima del
 * fondo rojo del aviso): 6,0:1, por encima del 4,5:1 de WCAG AA.
 */
function RemainingAttemptsNotice({ remaining }: { remaining: number }) {
  const text = remainingAttemptsMessage(remaining);
  if (remaining === 1) {
    return (
      <p className="mt-3 flex items-start gap-2 rounded-md border border-accent/50 bg-accent/10 px-3 py-2 font-semibold text-accent">
        <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        {text}
      </p>
    );
  }
  return <p className="mt-1.5 text-red-200">{text}</p>;
}

/**
 * Pista «¿aún no has confirmado tu correo?» bajo el aviso de credenciales
 * incorrectas (401) o de cuenta bloqueada (429 `ACCOUNT_LOCKED`), con el botón
 * para pedir el correo de confirmación a la dirección con la que se intentó entrar.
 *
 * **Por qué sale en TODOS esos errores.** Para no revelar qué correos tienen
 * cuenta, el servidor responde a una cuenta pendiente de confirmar, aunque la
 * contraseña sea correcta, exactamente lo mismo que a una contraseña incorrecta
 * (el mismo 401, que además cuenta como intento). La interfaz no puede saber
 * cuál de los dos casos es, así que la pista va siempre y está escrita para ser
 * verdad en los tres: contraseña incorrecta de una cuenta confirmada, cuenta
 * pendiente y correo sin cuenta. Por eso es una pregunta («¿Te acabas de
 * registrar…?») y nunca afirma que la cuenta exista ni que se haya enviado
 * nada; el aviso del reenvío tampoco (el 202 es igual siempre).
 *
 * **Es una pista, no un error:** tono neutro (superficie y borde suaves, sin el
 * rojo del aviso ni el ámbar del acento) y DEBAJO del aviso, para que el orden
 * de lectura sea error (con los intentos que quedan) → pista → botón y no tape
 * ni compita con `remainingAttempts`.
 *
 * **Accesibilidad.** El texto va en una región `role="status"` (cortés): el
 * lector lee primero la alerta del error y después la pista, sin interrumpirla.
 * Esa región y el contenedor se pintan SIEMPRE (vacíos si no hay pista), porque
 * los lectores solo anuncian con fiabilidad los cambios de una región viva que
 * ya estaba en la página; el botón queda fuera (un control dentro de una región
 * viva se leería como parte del mensaje). Contraste: blanco y `gray-300` sobre
 * `surface`, más de 12:1.
 *
 * **Por qué el componente no se desmonta.** El estado del reenvío
 * (`useResendVerification`: la espera de un minuto y su aviso) vive aquí. Si se
 * desmontara en cada envío del formulario, volver a intentar entrar reactivaría
 * el botón al momento, y otro reenvío invalidaría el enlace que acaba de salir.
 *
 * El texto no da por hecho que exista un correo: las cuentas heredadas (de antes
 * de la verificación) están pendientes y nunca recibieron ninguno. Por eso la
 * salida es «si no tienes ninguno o ha caducado», no «si no lo encuentras».
 *
 * **La otra salida: restablecer la contraseña.** Debajo del botón, un enlace a
 * `/recuperar-contrasena` con el correo del intento (en el `state` de la
 * navegación, nunca en la URL). Es verdad en los tres casos: a quien se equivocó
 * de contraseña le sirve para elegir otra, y a una cuenta pendiente (o heredada)
 * también le confirma el correo, porque quien abre el enlace demuestra que el
 * buzón es suyo. Va fuera de la región `role="status"`, como el botón.
 *
 * @param email correo (recortado y con formato válido) con el que se intentó
 *              entrar, o `null` para no mostrar la pista
 */
function UnconfirmedEmailHint({ email }: { email: string | null }) {
  const { resend, sending, remaining, notice } = useResendVerification();
  const waiting = remaining > 0;
  return (
    <div className={email ? 'mb-5 rounded-lg border border-line bg-surface px-4 py-3 text-sm text-gray-300' : undefined}>
      <div role="status">
        {email && (
          <>
            <p className="font-medium text-white">¿Te acabas de registrar o aún no has confirmado tu correo?</p>
            <p className="mt-1">
              Hasta que lo confirmes no podrás entrar, aunque la contraseña sea correcta. Pulsa el enlace del correo
              de confirmación de StreamBox (mira también en spam); si no tienes ninguno o ha caducado, pide uno nuevo
              para <span className="break-all text-white">{email}</span>.
            </p>
          </>
        )}
      </div>
      {email && (
        <div>
          <Button
            variant="outline"
            className="mt-3 w-full"
            disabled={sending || waiting}
            onClick={() => void resend(email)}
          >
            {sending
              ? 'Enviando...'
              : waiting
                ? `Reenviar en ${formatWaitTime(remaining)}`
                : 'Reenviar el correo de confirmación'}
          </Button>
          <ResendNotice notice={notice} />
          <p className="mt-3">
            ¿No recuerdas la contraseña?{' '}
            <Link to={FORGOT_PASSWORD_PATH} state={forgotPasswordState(email)} className={TEXT_LINK}>
              Restablécela
            </Link>
            : al hacerlo, tu correo también queda confirmado.
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * Correo para la pista de {@link UnconfirmedEmailHint}, o `null` si no tiene
 * sentido ofrecerla: con un correo vacío o mal formado no puede haber ninguna
 * cuenta que confirmar, y el botón no debe enviar nada.
 *
 * @param email correo ya recortado con el que se intentó entrar
 */
function hintEmailFor(email: string): string | null {
  return emailError(email) ? null : email;
}

/**
 * Pantalla de inicio de sesión (`POST /api/auth/login`).
 *
 * Errores tratados de forma distinta según `status`, `code` y campos del cuerpo
 * (nunca según el texto del mensaje):
 * - 401: credenciales incorrectas (mensaje genérico: no revela si falla el
 *   correo o la contraseña). Si el servidor envía `remainingAttempts`, se avisa
 *   de cuántos intentos quedan antes del bloqueo de la cuenta. Una cuenta que
 *   aún no ha confirmado su correo recibe ESTE MISMO 401 aunque la contraseña
 *   sea correcta (así el login no revela qué correos tienen cuenta), de modo que
 *   debajo siempre va la pista {@link UnconfirmedEmailHint}.
 * - 429 `ACCOUNT_LOCKED`: la cuenta está bloqueada (también con la contraseña
 *   correcta). Mensaje específico, el botón bloqueado con una cuenta atrás en
 *   minutos y segundos (`Retry-After`) y la misma pista: una cuenta pendiente
 *   que reintenta con la contraseña correcta acaba aquí.
 * - 429 por otro motivo (`RATE_LIMIT_EXCEEDED`, límite por IP): "demasiados
 *   intentos" y cuenta atrás en el botón (sin pista: no dice nada de la cuenta).
 * - 403: el mensaje genérico de «sin permisos» de `apiFetch` (p. ej.
 *   `CSRF_REJECTED`); el login ya no tiene ningún 403 propio.
 * - Red caída / 5xx / otros: el mensaje que ya prepara `apiFetch`, sin pista
 *   (el fallo no tiene que ver con la cuenta).
 *
 * `login()` del contexto hace la llamada y abre la sesión: `RedirectIfAuthenticated`
 * (en `App.tsx`) se encarga de llevar al usuario a la portada.
 *
 * Bajo el formulario, «¿Has olvidado tu contraseña?» lleva a `/recuperar-contrasena`
 * con el correo ya escrito si tiene buen formato (en el `state` de la navegación,
 * nunca en la URL: ver `forgotPasswordState`), para no tener que reescribirlo.
 */
export function LoginPage() {
  useDocumentTitle('Iniciar sesión');
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [remainingAttempts, setRemainingAttempts] = useState<number | undefined>(undefined);
  /**
   * Correo de la pista «¿aún no has confirmado tu correo?» (el del intento, no
   * el que haya ahora en el campo, para que el texto diga a quién se enviaría).
   */
  const [hintEmail, setHintEmail] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Al acabar la espera se retira el aviso de "demasiados intentos" o de cuenta bloqueada (y su pista).
  const { remaining, start } = useCountdown(() => {
    setError('');
    setHintEmail(null);
  });

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setRemainingAttempts(undefined);
    setHintEmail(null);
    const trimmedEmail = email.trim();
    if (!trimmedEmail || !password) {
      setError('Introduce tu correo electrónico y tu contraseña.');
      return;
    }
    setLoading(true);
    try {
      // El servidor fija la cookie de sesión (HttpOnly); no hay token que guardar.
      await login(trimmedEmail, password);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Correo o contraseña incorrectos.');
        setRemainingAttempts(err.remainingAttempts);
        setHintEmail(hintEmailFor(trimmedEmail));
      } else if (err instanceof ApiError && err.status === 429) {
        if (err.retryAfterSeconds) start(err.retryAfterSeconds);
        const locked = err.code === 'ACCOUNT_LOCKED';
        setError(locked ? accountLockedMessage(err.retryAfterSeconds) : err.message);
        if (locked) setHintEmail(hintEmailFor(trimmedEmail));
      } else {
        setError(getErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  };

  const blocked = remaining > 0;

  return (
    <AuthLayout
      title="Bienvenido de nuevo"
      subtitle="Accede a tu cuenta para continuar."
      footer={
        <>
          ¿No tienes cuenta?{' '}
          <Link to="/registro" className={TEXT_LINK}>
            Regístrate
          </Link>
        </>
      }
    >
      <FormAlert message={error}>
        {remainingAttempts !== undefined && <RemainingAttemptsNotice remaining={remainingAttempts} />}
      </FormAlert>
      <UnconfirmedEmailHint email={error ? hintEmail : null} />

      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          id="login-email"
          label="Correo electrónico"
          type="email"
          autoComplete="email"
          placeholder="tu@correo.com"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <FormField
          id="login-password"
          label="Contraseña"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" disabled={loading || blocked} className="mt-2 w-full">
          {loading ? 'Entrando...' : blocked ? `Reintentar en ${formatWaitTime(remaining)}` : 'Iniciar sesión'}
        </Button>
      </form>
      {/* Tras el botón y no entre la contraseña y el botón: así el Tab va de la contraseña a «Iniciar sesión». */}
      <p className="mt-5 text-sm">
        <Link to={FORGOT_PASSWORD_PATH} state={forgotPasswordState(email)} className={TEXT_LINK}>
          ¿Has olvidado tu contraseña?
        </Link>
      </p>
    </AuthLayout>
  );
}
