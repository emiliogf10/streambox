import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AuthLayout } from '../components/AuthLayout';
import { Button } from '../components/Button';
import { FormAlert } from '../components/FormAlert';
import { FormField } from '../components/FormField';
import { useToast } from '../context/ToastContext';
import { useCountdown } from '../hooks/useCountdown';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import type { RegisterRequest, User } from '../lib/types';
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
 * Pantalla de registro (`POST /api/users`, público; el servidor siempre crea rol `USER`).
 *
 * - Valida en el cliente con las mismas reglas que el backend ({@link validateRegistration})
 *   y, además, pinta junto a cada campo los `validationErrors` que devuelva el servidor.
 *   Así llegan las reglas de contraseña que solo conoce el servidor (contraseña
 *   común, o que contiene el usuario o el correo): su motivo se muestra junto al
 *   campo y la ayuda las anuncia de antemano para no sorprender con el error.
 * - 409 (correo o usuario ya registrado): muestra el mensaje del servidor, que dice cuál es.
 * - 429: bloquea el botón con cuenta atrás según `Retry-After`.
 * - Éxito: lleva a `/login` con un toast. No hay inicio de sesión automático.
 */
export function RegisterPage() {
  useDocumentTitle('Crear cuenta');
  const navigate = useNavigate();
  const toast = useToast();
  const [values, setValues] = useState<RegisterRequest>({ username: '', email: '', password: '' });
  const [fieldErrors, setFieldErrors] = useState<RegisterErrors>({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const { remaining, start } = useCountdown(() => setFormError(''));

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

  /** Traduce un error del servidor a mensajes de campo y/o de formulario. */
  const showServerError = (error: unknown) => {
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
      await apiFetch<User>('/users', { method: 'POST', body: data, public: true });
      toast.success('Cuenta creada, ya puedes iniciar sesión.');
      navigate('/login', { replace: true });
    } catch (error) {
      showServerError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const blocked = remaining > 0;

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
