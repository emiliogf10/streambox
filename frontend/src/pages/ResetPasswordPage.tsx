import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { RequestLinkForm } from '../components/RequestLinkForm';
import { useAuth } from '../context/AuthContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useHashLinkToken } from '../hooks/useHashLinkToken';
import { ApiError, getErrorMessage } from '../lib/api';
import {
  RESET_LINK_TTL_HOURS,
  RESET_REQUEST_DONE_MESSAGE,
  RESET_SESSIONS_CLOSED,
  confirmPasswordReset,
  requestPasswordReset,
} from '../lib/passwordReset';
import { formatWaitTime } from '../lib/utils';
import { PASSWORD_MAX, PASSWORD_MIN, validatePasswordReset } from '../lib/validation';
import type { PasswordResetErrors, PasswordResetValues } from '../lib/validation';
import { focusFirstInvalidField } from './admin/formErrors';

/**
 * Estado de la página:
 * - `ready`: hay token; se espera a que el usuario escriba la contraseña nueva.
 * - `saving`: petición en curso.
 * - `done`: el servidor ha respondido 204 (contraseña cambiada).
 * - `invalid`: el token no vale (400 `INVALID_RESET_TOKEN`) o el enlace no traía ninguno.
 */
type Status = 'ready' | 'saving' | 'done' | 'invalid';

/**
 * Qué pasa con la sesión que hubiera abierta en ESTE navegador tras cambiar la contraseña:
 * - `none`: no había ninguna (o no se sabe que la hubiera: ver `closeSession`).
 * - `closing`: se está cerrando (`POST /auth/logout`).
 * - `closed`: cerrada.
 * - `failed`: el servidor no confirmó el cierre (red, 5xx...): se ofrece reintentar.
 */
type SessionCleanup = 'none' | 'closing' | 'closed' | 'failed';

/** Campos en el orden en que se ven (para llevar el foco al primero con error). */
const FIELD_ORDER: (keyof PasswordResetValues)[] = ['newPassword', 'confirmPassword'];

/** `id` de cada campo (también los usan las etiquetas y los mensajes de error). */
const FIELD_IDS: Record<keyof PasswordResetValues, string> = {
  newPassword: 'reset-new-password',
  confirmPassword: 'reset-confirm-password',
};

/** `id` del campo de correo del formulario «pedir un enlace nuevo». */
const REQUEST_EMAIL_ID = 'reset-request-email';

/** Clases de los enlaces de texto, iguales que los de login y registro. */
const TEXT_LINK = 'focus-ring font-semibold text-accent underline-offset-2 hover:underline';

/**
 * Página `/restablecer-contrasena`, a la que lleva el enlace del correo «Restablece
 * tu contraseña de StreamBox» (`/restablecer-contrasena#token=…`). Es pública y
 * **sin guarda**: el enlace funciona igual con o sin sesión abierta.
 *
 * El token se lee del fragmento y se quita de la barra de direcciones con
 * {@link useHashLinkToken} (común con `/verificar-email`), que también reconoce
 * un enlace nuevo abierto con la página ya en pantalla (lo normal tras pedir
 * otro desde «enlace no válido»): cada uno monta de cero {@link ResetFlow} (con
 * otra `key`), sin los errores, la cuenta atrás ni la respuesta tardía del anterior.
 */
export function ResetPasswordPage() {
  const link = useHashLinkToken();
  return <ResetFlow key={link.id} token={link.token} announce={link.id > 0} />;
}

/** Propiedades de {@link ResetFlow}. */
interface ResetFlowProps {
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
 * Restablecimiento con un enlace concreto (un token). Vive mientras no se abra
 * otro: {@link ResetPasswordPage} lo vuelve a montar con cada enlace nuevo.
 *
 * Abrir el enlace no gasta nada: hace falta escribir la contraseña (los
 * antivirus del correo que abren los enlaces no pueden cambiarla).
 *
 * Respuestas de `POST /api/auth/password-reset/confirm` (por `status`/`code`,
 * nunca por el texto):
 * - 204: «Contraseña cambiada», con «Iniciar sesión». El servidor ha cerrado
 *   todas las sesiones de la cuenta y no abre ninguna (ver «Sesión» abajo).
 * - 400 `INVALID_RESET_TOKEN` (inválido, caducado, ya usado o sustituido por
 *   otro: el mismo cuerpo siempre), o `VALIDATION_ERROR` del token: «El enlace no
 *   es válido o ha caducado», con el formulario para pedir otro. Lo mismo si el
 *   enlace no trae token.
 * - 400 `VALIDATION_ERROR` de `newPassword` (contraseña común, con el usuario o
 *   el correo de la cuenta...: reglas que solo conoce el servidor): el motivo
 *   junto al campo. **El token no se ha gastado**: se corrige y se reintenta.
 * - 429: aviso y el botón en espera según `Retry-After`.
 * - Red caída, 5xx...: aviso y el mismo botón para reintentar (el token sigue en memoria).
 *
 * **Sesión.** Si este navegador tenía una sesión abierta, se cierra al cambiar
 * la contraseña con el cierre normal (`logout` de `AuthContext`: borra las
 * cookies en el servidor y avisa a las demás pestañas), en silencio y sin el
 * aviso de «sesión caducada», que aquí sería falso. Si era de la misma cuenta,
 * el servidor ya la había revocado y, sin esto, seguiría en pantalla hasta que
 * caducara su JWT de acceso (hasta 15 minutos) y luego saltaría ese aviso; si
 * era de otra cuenta, cerrarla también es lo esperable: quien acaba de cambiar
 * la contraseña va a entrar con la suya. Solo cuenta la sesión que había al
 * cambiarla (si el arranque aún estaba comprobando si la había, se cierra por si
 * acaso): una que se abra después en otra pestaña no se toca.
 *
 * **Autocompletado:** `new-password` en los dos campos, para que el navegador
 * proponga una contraseña segura. No hay campo oculto de usuario (el del
 * perfil): el enlace solo trae el token y la página no sabe de qué cuenta es.
 *
 * Al pasar a «cambiada» o «no válido», el foco va al título, que cambia: el
 * formulario ya no existe y así el lector de pantalla anuncia el resultado.
 */
function ResetFlow({ token, announce }: ResetFlowProps) {
  const { isAuthenticated, isCheckingSession, logout } = useAuth();
  const [status, setStatus] = useState<Status>(token ? 'ready' : 'invalid');
  const [values, setValues] = useState<PasswordResetValues>({ newPassword: '', confirmPassword: '' });
  const [fieldErrors, setFieldErrors] = useState<PasswordResetErrors>({});
  const [formError, setFormError] = useState('');
  const [cleanup, setCleanup] = useState<SessionCleanup>('none');
  const { remaining, start } = useCountdown(() => setFormError(''));
  const titleRef = useRef<HTMLHeadingElement>(null);
  const previousStatus = useRef(status);

  useDocumentTitle(status === 'done' ? 'Contraseña cambiada' : 'Restablecer la contraseña');

  // Enlace nuevo con la página abierta: el foco estaba en un elemento que ya no existe.
  useEffect(() => {
    if (announce) titleRef.current?.focus();
  }, [announce]);

  useEffect(() => {
    if (previousStatus.current === status) return;
    previousStatus.current = status;
    if (status === 'done' || status === 'invalid') titleRef.current?.focus();
  }, [status]);

  /**
   * Cierra la sesión de este navegador (ver «Sesión» en {@link ResetFlow}) y
   * anota el resultado. Se llama desde el envío del formulario y desde el botón
   * de reintento, nunca desde un efecto: así solo se cierra la sesión que había
   * al cambiar la contraseña y nunca una que se abra después en otra pestaña.
   *
   * @param knownSession si se sabía que había sesión. Si solo se estaba
   *   comprobando (el arranque aún no había respondido), se cierra igual por si
   *   acaso, pero sin decir «se ha cerrado la sesión» (quizá no había ninguna)
   */
  const closeSession = (knownSession: boolean) => {
    setCleanup('closing');
    void logout().then((closed) => {
      if (!closed) setCleanup('failed');
      else setCleanup(knownSession ? 'closed' : 'none');
    });
  };

  /** Actualiza un campo y borra su error: ya está corrigiéndolo. */
  const setField = (field: keyof PasswordResetValues, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
  };

  /** Pinta los errores por campo y lleva el foco al primero. */
  const showFieldErrors = (errors: PasswordResetErrors) => {
    setFieldErrors(errors);
    focusFirstInvalidField(errors, FIELD_ORDER, FIELD_IDS);
  };

  /** Traduce un error del servidor a un estado de la página o a mensajes de campo. */
  const showServerError = (error: unknown) => {
    if (error instanceof ApiError && error.status === 400) {
      if (error.code === 'INVALID_RESET_TOKEN') {
        setStatus('invalid');
        return;
      }
      if (error.code === 'VALIDATION_ERROR') {
        const newPassword = error.validationErrors?.newPassword;
        if (newPassword) {
          setStatus('ready');
          showFieldErrors({ newPassword });
          return;
        }
        // Un token mal formado según el servidor es, para quien lo usa, un enlace que no vale.
        if (error.validationErrors?.token) {
          setStatus('invalid');
          return;
        }
      }
    }
    if (error instanceof ApiError && error.status === 429 && error.retryAfterSeconds) start(error.retryAfterSeconds);
    setFormError(getErrorMessage(error));
    setStatus('ready');
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!token) return;
    setFormError('');
    const errors = validatePasswordReset(values);
    if (Object.keys(errors).length > 0) {
      showFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    // Se decide AHORA qué sesión hay que cerrar después: la que hay (o la que se está comprobando).
    const knownSession = isAuthenticated;
    const maybeSession = isAuthenticated || isCheckingSession;
    setStatus('saving');
    try {
      await confirmPasswordReset(token, values.newPassword);
      setValues({ newPassword: '', confirmPassword: '' });
      setStatus('done');
      if (maybeSession) closeSession(knownSession);
    } catch (error) {
      showServerError(error);
    }
  };

  if (status === 'done') {
    const closingSession = cleanup === 'closing';
    // Si el cierre «falló» pero ya no hay sesión (otra vía la cerró mientras tanto), está cerrada.
    const sessionClosed = cleanup === 'closed' || (cleanup === 'failed' && !isAuthenticated);
    const sessionStuck = cleanup === 'failed' && isAuthenticated;
    return (
      <AuthLayout
        title="Contraseña cambiada"
        subtitle="Ya puedes iniciar sesión con tu contraseña nueva."
        titleRef={titleRef}
      >
        <div className="mb-6 flex flex-col gap-3 text-sm leading-relaxed text-gray-300">
          <p>{RESET_SESSIONS_CLOSED}</p>
          <div role="status">
            {closingSession && <p>Cerrando la sesión abierta en este navegador...</p>}
            {sessionClosed && <p>También se ha cerrado la sesión que había abierta en este navegador.</p>}
          </div>
          {sessionStuck && (
            <p role="alert" className="font-medium text-danger">
              No se ha podido cerrar la sesión abierta en este navegador. Ciérrala antes de entrar con tu contraseña
              nueva.
            </p>
          )}
        </div>
        {/* Mientras se cierra la sesión, «Iniciar sesión» no llevaría al login (con sesión, redirige al inicio). */}
        {closingSession && (
          <Button className="w-full" disabled>
            Cerrando la sesión...
          </Button>
        )}
        {sessionStuck && (
          <Button className="w-full" onClick={() => closeSession(true)}>
            Cerrar sesión
          </Button>
        )}
        {!closingSession && !sessionStuck && (
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
        subtitle={`Cada enlace sirve una sola vez y caduca en ${RESET_LINK_TTL_HOURS} hora.`}
        titleRef={titleRef}
        footer={
          isAuthenticated ? (
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
          )
        }
      >
        <div className="mb-6 flex flex-col gap-3 text-sm leading-relaxed text-gray-300">
          <p>
            Si ya cambiaste la contraseña con este enlace, no tienes que hacer nada más: entra con la nueva. Si pediste
            varios enlaces, solo vale el del correo más reciente.
          </p>
          <p>
            {/* Sin prometer el envío: el 202 es siempre igual y no se manda nada sin cuenta o tras el límite silencioso por cuenta. */}
            Si no, escribe tu correo y, si hay una cuenta con él, te llegará un enlace nuevo. Si copiaste el enlace a
            mano, comprueba también que esté completo.
          </p>
        </div>
        <RequestLinkForm id={REQUEST_EMAIL_ID} send={requestPasswordReset} doneMessage={RESET_REQUEST_DONE_MESSAGE} />
      </AuthLayout>
    );
  }

  const blocked = remaining > 0;
  let submitLabel = 'Cambiar la contraseña';
  if (status === 'saving') submitLabel = 'Cambiando la contraseña...';
  else if (blocked) submitLabel = `Reintentar en ${formatWaitTime(remaining)}`;

  return (
    <AuthLayout
      title="Elige una contraseña nueva"
      subtitle="Escríbela dos veces. Después podrás iniciar sesión con ella."
      titleRef={titleRef}
    >
      <FormAlert message={formError} />
      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          id={FIELD_IDS.newPassword}
          label="Contraseña nueva"
          type="password"
          autoComplete="new-password"
          required
          hint={`Entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres. Evita contraseñas comunes y no incluyas tu usuario ni tu correo.`}
          error={fieldErrors.newPassword}
          value={values.newPassword}
          onChange={(e) => setField('newPassword', e.target.value)}
        />
        <FormField
          id={FIELD_IDS.confirmPassword}
          label="Repite la contraseña nueva"
          type="password"
          autoComplete="new-password"
          required
          error={fieldErrors.confirmPassword}
          value={values.confirmPassword}
          onChange={(e) => setField('confirmPassword', e.target.value)}
        />
        <p className="text-xs leading-relaxed text-muted">
          Al cambiarla se cerrarán todas las sesiones abiertas con tu cuenta, también en otros dispositivos. Si aún no
          habías confirmado tu correo, quedará confirmado.
        </p>
        <Button type="submit" disabled={status === 'saving' || blocked} className="w-full">
          {submitLabel}
        </Button>
      </form>
    </AuthLayout>
  );
}
