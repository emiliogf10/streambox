import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { TriangleAlert } from 'lucide-react';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import type { LoginResponse } from '../lib/types';
import { formatWaitTime } from '../lib/utils';

/**
 * Minutos que dura el bloqueo de una cuenta tras demasiados fallos.
 *
 * Es la ventana del backend (`streambox.security.rate-limit.lockout.window=15m`).
 * El servidor no la envía en el 401 (sí envía cuántos intentos quedan), así que
 * el aviso previo la menciona de forma fija; cuando el bloqueo ya ha ocurrido,
 * la espera exacta sale de la cabecera `Retry-After`.
 */
const LOCKOUT_MINUTES = 15;

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
 * Pantalla de inicio de sesión (`POST /api/auth/login`).
 *
 * Errores tratados de forma distinta según `status`, `code` y campos del cuerpo
 * (nunca según el texto del mensaje):
 * - 401: credenciales incorrectas (mensaje genérico: no revela si falla el
 *   correo o la contraseña). Si el servidor envía `remainingAttempts`, se avisa
 *   de cuántos intentos quedan antes del bloqueo de la cuenta.
 * - 429 `ACCOUNT_LOCKED`: la cuenta está bloqueada (también con la contraseña
 *   correcta). Mensaje específico y el botón bloqueado con una cuenta atrás en
 *   minutos y segundos (`Retry-After`).
 * - 429 por otro motivo (`RATE_LIMIT_EXCEEDED`, límite por IP): "demasiados
 *   intentos" y cuenta atrás en el botón.
 * - Red caída / 5xx / otros: el mensaje que ya prepara `apiFetch`.
 *
 * Al iniciar sesión solo se guarda el token con `login()`: `RedirectIfAuthenticated`
 * (en `App.tsx`) se encarga de llevar al usuario a la portada.
 */
export function LoginPage() {
  useDocumentTitle('Iniciar sesión');
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [remainingAttempts, setRemainingAttempts] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  // Al acabar la espera se retira el aviso de "demasiados intentos" o de cuenta bloqueada.
  const { remaining, start } = useCountdown(() => setError(''));

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setRemainingAttempts(undefined);
    if (!email.trim() || !password) {
      setError('Introduce tu correo electrónico y tu contraseña.');
      return;
    }
    setLoading(true);
    try {
      const { token } = await apiFetch<LoginResponse>('/auth/login', {
        method: 'POST',
        body: { email: email.trim(), password },
        public: true, // un 401 aquí significa "credenciales incorrectas", no "sesión caducada"
      });
      login(token);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Correo o contraseña incorrectos.');
        setRemainingAttempts(err.remainingAttempts);
      } else if (err instanceof ApiError && err.status === 429) {
        if (err.retryAfterSeconds) start(err.retryAfterSeconds);
        setError(err.code === 'ACCOUNT_LOCKED' ? accountLockedMessage(err.retryAfterSeconds) : err.message);
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
          <Link to="/registro" className="focus-ring font-semibold text-accent underline-offset-2 hover:underline">
            Regístrate
          </Link>
        </>
      }
    >
      <FormAlert message={error}>
        {remainingAttempts !== undefined && <RemainingAttemptsNotice remaining={remainingAttempts} />}
      </FormAlert>

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
    </AuthLayout>
  );
}
