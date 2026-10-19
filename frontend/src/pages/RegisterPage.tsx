import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { ResendVerificationButton } from '../components/ResendVerification';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { VERIFICATION_LINK_TTL_HOURS } from '../lib/emailVerification';
import type { AcceptedResponse, RegisterRequest } from '../lib/types';
import { PASSWORD_MAX, PASSWORD_MIN, USERNAME_MAX, USERNAME_MIN, validateRegistration } from '../lib/validation';
import type { RegisterErrors } from '../lib/validation';

/** Orden de los campos en el formulario (para llevar el foco al primero con error). */
const FIELD_ORDER: (keyof RegisterRequest)[] = ['username', 'email', 'password'];

/** `id` de cada input, también usados por las etiquetas y los mensajes de error. */
const FIELD_IDS: Record<keyof RegisterRequest, string> = {
  username: 'register-username',
  email: 'register-email',
  password: 'register-password',
};

/**
 * Pista que acompaña al 409 de nombre de usuario cuando el nombre es el mismo
 * del registro que se acaba de aceptar en esta pantalla (el usuario ha pulsado
 * «Corregir el correo» y lo ha vuelto a enviar con otro correo).
 *
 * Por qué pasa: aquel registro creó una cuenta pendiente que reserva el nombre
 * hasta que caduque su enlace. El servidor no lo libera antes porque, si lo
 * hiciera, cualquiera podría borrar el registro pendiente de otra persona
 * registrándose con su nombre. Sin esta pista, «ya está en uso» parecería
 * absurdo justo después de haberlo elegido.
 */
const USERNAME_RESERVED_HINT =
  'Si es el que usaste hace un momento, lo tiene reservado aquel registro hasta que caduque su enlace ' +
  `(${VERIFICATION_LINK_TTL_HOURS} h): elige otro.`;

/** Propiedades de {@link CheckYourEmail}. */
interface CheckYourEmailProps {
  /** Correo que se escribió en el formulario (ya recortado). */
  email: string;
  /** Vuelve al formulario, con lo escrito, para corregir el correo. */
  onEditEmail: () => void;
}

/**
 * Pantalla «Revisa tu correo», justo después de enviar el registro.
 *
 * **Lo que NO dice, a propósito.** El servidor responde lo mismo (202) tanto si
 * el correo es nuevo como si ya tenía cuenta: así nadie puede usar el registro
 * para averiguar quién está dado de alta. Por eso el texto no afirma «cuenta
 * creada» ni «te hemos enviado un correo»: dice qué pasará *si el correo es
 * correcto* y avisa de que, si ya había una cuenta, el mensaje lo recordará
 * (el backend envía entonces un «ya tienes una cuenta» en lugar del enlace).
 *
 * **Sin callejones sin salida:** reenviar el correo (en espera el primer minuto:
 * acaba de salir uno y reenviar lo invalidaría), volver al formulario si el
 * correo estaba mal escrito e ir a «Iniciar sesión» para quien ya lo confirmó.
 *
 * **La contraseña de una cuenta anterior.** Si el correo ya tenía una cuenta
 * (confirmada, pendiente con el enlace vigente o heredada de antes de que
 * existiera la verificación, que nunca recibió ningún enlace), el servidor no la
 * toca y CONSERVA su contraseña: si la cambiara, cualquiera podría cambiar la de
 * una cuenta ajena solo con conocer su correo. La pantalla lo dice para que nadie
 * intente entrar con la nueva. La única excepción es la regla del servidor para
 * sustituir un registro: pendiente, con su primer enlace enviado al darse de alta
 * y todos sus enlaces caducados; entonces vale la nueva. La frase la describe por
 * «el enlace que te enviamos al registrarte» a propósito: una cuenta heredada no
 * lo tuvo, así que el texto no le promete una sustitución que no ocurre.
 *
 * Al aparecer, el foco va al título: el formulario (y el botón pulsado) ya no
 * existen, y así el lector de pantalla anuncia «Revisa tu correo».
 */
function CheckYourEmail({ email, onEditEmail }: CheckYourEmailProps) {
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => titleRef.current?.focus(), []);

  return (
    <AuthLayout
      title="Revisa tu correo"
      subtitle="Falta un paso: confirmar que el correo es tuyo."
      titleRef={titleRef}
      footer={
        <>
          ¿Ya has confirmado tu correo?{' '}
          <Link to="/login" className="focus-ring font-semibold text-accent underline-offset-2 hover:underline">
            Inicia sesión
          </Link>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-sm leading-relaxed text-gray-300">
        <p>
          Si la dirección <strong className="font-semibold break-all text-white">{email}</strong> es correcta, en unos
          minutos te llegará un correo con un enlace para confirmar tu cuenta. El enlace caduca en{' '}
          {VERIFICATION_LINK_TTL_HOURS} horas.
        </p>
        <p>Si ya tenías una cuenta con ese correo, te lo recordaremos en ese mensaje: no hace falta crear otra.</p>
        <p>
          Si este correo ya tenía una cuenta, la contraseña que vale es la de esa cuenta, no la que acabas de escribir.
          Solo hay una excepción: si ya te habías registrado, nunca confirmaste el correo y el enlace que te enviamos al
          registrarte (y cualquier otro que pidieras) ya ha caducado, aquel registro se sustituye por este y vale la
          contraseña nueva.
        </p>
        <p>¿No lo encuentras? Mira en la carpeta de spam o de correo no deseado. Si no ha llegado, pide otro.</p>
      </div>

      <div className="mt-6 flex flex-col gap-3">
        <ResendVerificationButton email={email} startInCooldown className="w-full" />
        <Button variant="ghost" className="w-full" onClick={onEditEmail}>
          Corregir el correo
        </Button>
      </div>
    </AuthLayout>
  );
}

/**
 * Pantalla de registro (`POST /api/users`, público; el servidor siempre crea rol `USER`).
 *
 * - Valida en el cliente con las mismas reglas que el backend ({@link validateRegistration})
 *   y, además, pinta junto a cada campo los `validationErrors` que devuelva el servidor.
 *   Así llegan las reglas de contraseña que solo conoce el servidor (contraseña
 *   común, o que contiene el usuario o el correo): su motivo se muestra junto al
 *   campo y la ayuda las anuncia de antemano para no sorprender con el error.
 * - 409 `USER_ALREADY_EXISTS`: el nombre de usuario ya lo usa otra cuenta. Va
 *   junto al campo de nombre; si es el mismo nombre de un registro ya aceptado en
 *   esta pantalla (tras «Corregir el correo»), con {@link USERNAME_RESERVED_HINT}. (Un correo repetido ya NO da 409: el servidor
 *   contesta 202 igual que con uno nuevo, para no revelar quién tiene cuenta.)
 * - 429: bloquea el botón con cuenta atrás según `Retry-After`.
 * - Éxito (202): pantalla {@link CheckYourEmail}. La cuenta queda pendiente hasta
 *   que se pulse el enlace del correo (`/verificar-email`); no hay inicio de sesión automático.
 */
export function RegisterPage() {
  const [values, setValues] = useState<RegisterRequest>({ username: '', email: '', password: '' });
  const [fieldErrors, setFieldErrors] = useState<RegisterErrors>({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  /** Correo al que se ha enviado el registro; mientras exista se ve «Revisa tu correo». */
  const [sentTo, setSentTo] = useState<string | null>(null);
  /** Al volver de «Corregir el correo», el foco va al campo de correo (el botón pulsado ya no existe). */
  const focusEmailOnReturn = useRef(false);
  /** Nombre del último registro aceptado (202) en esta pantalla: ese registro lo tiene reservado. */
  const acceptedUsername = useRef<string | null>(null);
  const { remaining, start } = useCountdown(() => setFormError(''));
  // Aquí y no en `CheckYourEmail`: al volver al formulario el título también debe volver.
  useDocumentTitle(sentTo === null ? 'Crear cuenta' : 'Revisa tu correo');

  useEffect(() => {
    if (sentTo === null && focusEmailOnReturn.current) {
      focusEmailOnReturn.current = false;
      document.getElementById(FIELD_IDS.email)?.focus();
    }
  }, [sentTo]);

  /** Actualiza un campo y borra su error: ya está corrigiéndolo. */
  const setField = (field: keyof RegisterRequest, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
  };

  /** Lleva el foco al primer campo con error, para que quien usa teclado o lector lo encuentre. */
  const focusFirstInvalid = (errors: RegisterErrors) => {
    const first = FIELD_ORDER.find((field) => errors[field]);
    if (first) document.getElementById(FIELD_IDS[first])?.focus();
  };

  /**
   * Traduce un error del servidor a mensajes de campo y/o de formulario.
   *
   * @param error el error de `apiFetch`
   * @param submittedUsername nombre enviado (recortado), para reconocer el 409 de un nombre que reserva el registro anterior
   */
  const showServerError = (error: unknown, submittedUsername: string) => {
    if (error instanceof ApiError) {
      if (error.status === 429 && error.retryAfterSeconds) start(error.retryAfterSeconds);

      if (error.code === 'VALIDATION_ERROR' && error.validationErrors) {
        const perField: RegisterErrors = {};
        const unmatched: string[] = [];
        for (const [field, message] of Object.entries(error.validationErrors)) {
          if (Object.hasOwn(FIELD_IDS, field)) perField[field as keyof RegisterRequest] = message;
          else unmatched.push(message);
        }
        setFieldErrors(perField);
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Revisa los campos marcados.');
        focusFirstInvalid(perField);
        return;
      }

      // Con el contrato actual, el único duplicado que se comunica es el nombre de usuario.
      if (error.status === 409 && error.code === 'USER_ALREADY_EXISTS') {
        const reservedByPrevious = acceptedUsername.current === submittedUsername;
        const perField: RegisterErrors = {
          username: reservedByPrevious ? `${error.message.replace(/\.?$/, '.')} ${USERNAME_RESERVED_HINT}` : error.message,
        };
        setFieldErrors(perField);
        setFormError('Revisa los campos marcados.');
        focusFirstInvalid(perField);
        return;
      }
    }
    setFormError(getErrorMessage(error));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');

    const data: RegisterRequest = {
      username: values.username.trim(),
      email: values.email.trim(),
      password: values.password, // la contraseña nunca se recorta: los espacios cuentan
    };

    const errors = validateRegistration(data);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      focusFirstInvalid(errors);
      return;
    }

    setSubmitting(true);
    try {
      // 202 con un texto que es siempre el mismo: no dice nada de la cuenta y no se enseña.
      await apiFetch<AcceptedResponse>('/users', { method: 'POST', body: data, public: true });
      acceptedUsername.current = data.username;
      setSentTo(data.email);
    } catch (error) {
      showServerError(error, data.username);
    } finally {
      setSubmitting(false);
    }
  };

  const blocked = remaining > 0;

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

  return (
    <AuthLayout
      title="Crea tu cuenta"
      subtitle="Regístrate para guardar tu lista de películas."
      footer={
        <>
          ¿Ya tienes cuenta?{' '}
          <Link to="/login" className="focus-ring font-semibold text-accent underline-offset-2 hover:underline">
            Inicia sesión
          </Link>
        </>
      }
    >
      <FormAlert message={formError} />

      <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
        <FormField
          id={FIELD_IDS.username}
          label="Nombre de usuario"
          type="text"
          autoComplete="username"
          required
          hint={`Entre ${USERNAME_MIN} y ${USERNAME_MAX} caracteres.`}
          error={fieldErrors.username}
          value={values.username}
          onChange={(e) => setField('username', e.target.value)}
        />
        <FormField
          id={FIELD_IDS.email}
          label="Correo electrónico"
          type="email"
          autoComplete="email"
          placeholder="tu@correo.com"
          required
          error={fieldErrors.email}
          value={values.email}
          onChange={(e) => setField('email', e.target.value)}
        />
        <FormField
          id={FIELD_IDS.password}
          label="Contraseña"
          type="password"
          autoComplete="new-password"
          required
          hint={`Entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres. Evita contraseñas comunes y no incluyas tu usuario ni tu correo.`}
          error={fieldErrors.password}
          value={values.password}
          onChange={(e) => setField('password', e.target.value)}
        />
        <Button type="submit" disabled={submitting || blocked} className="mt-2 w-full">
          {submitting ? 'Creando cuenta...' : blocked ? `Reintentar en ${remaining} s` : 'Crear cuenta'}
        </Button>
      </form>
    </AuthLayout>
  );
}
