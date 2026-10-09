import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../Button';
import { FormAlert } from '../FormAlert';
import { FormField } from '../FormField';
import { useToast } from '../../context/ToastContext';
import { useCountdown } from '../../hooks/useCountdown';
import { ApiError, apiFetch, getErrorMessage } from '../../lib/api';
import { PASSWORD_CHANGED, passwordRateLimitMessage } from '../../lib/profile';
import type { ChangePasswordRequest } from '../../lib/types';
import { formatWaitTime } from '../../lib/utils';
import { PASSWORD_MAX, PASSWORD_MIN, validatePasswordChange } from '../../lib/validation';
import type { PasswordChangeErrors, PasswordChangeValues } from '../../lib/validation';
import { focusFirstInvalidField, splitValidationErrors } from '../../pages/admin/formErrors';

/** Campos en el orden en que se ven (para llevar el foco al primero con error). */
const FIELD_ORDER: (keyof PasswordChangeValues)[] = ['currentPassword', 'newPassword', 'confirmPassword'];

/** Valores iniciales: todo vacío (el navegador o el gestor de contraseñas rellenan la actual). */
const EMPTY: PasswordChangeValues = { currentPassword: '', newPassword: '', confirmPassword: '' };

/** Propiedades de {@link PasswordForm}. */
interface PasswordFormProps {
  /** `id` del formulario (único en la página: `useId`); prefijo de los campos. */
  id: string;
  /** Cierra el formulario (al terminar o al cancelar); quien lo abrió devuelve el foco. */
  onClose: () => void;
}

/**
 * Formulario «Cambiar contraseña» (`PUT /api/users/me/password`).
 *
 * **Qué pasa al cambiarla** (decisión del autor): se cierran todas las DEMÁS
 * sesiones y esta sigue abierta. El servidor responde 204 con cookies nuevas
 * (una sesión nueva), así que aquí no hay que hacer nada con la sesión: el
 * aviso de éxito ({@link PASSWORD_CHANGED}) cuenta lo que pasa en los demás
 * dispositivos, incluido que puede tardar hasta 15 minutos (su JWT de acceso no
 * se puede revocar). La petición va en el lock de sesión de `apiFetch` para no
 * cruzarse con una renovación (ver `SESSION_ROTATING_PATHS` en `lib/api.ts`).
 *
 * **Errores** (por `status`/`code`, nunca por el texto):
 * - Del cliente ({@link validatePasswordChange}): actual vacía, nueva de longitud
 *   no válida o igual a la actual, repetición distinta. No gastan intentos.
 * - 400 `CURRENT_PASSWORD_INCORRECT`: junto a «Contraseña actual» (gasta uno de
 *   los 5 intentos cada 15 minutos).
 * - 400 `VALIDATION_ERROR`: cada mensaje junto a su campo (contraseña común, con
 *   tu usuario o correo...: reglas que solo conoce el servidor).
 * - 429: aviso con la espera y el botón bloqueado con la cuenta atrás (`Retry-After`), como el login.
 * - Red, 5xx...: aviso del formulario, que sigue abierto para reintentar.
 * Ninguno cierra la sesión: solo un 401 sin arreglo, que gestiona `apiFetch`.
 */
export function PasswordForm({ id, onClose }: PasswordFormProps) {
  const toast = useToast();
  const [values, setValues] = useState<PasswordChangeValues>(EMPTY);
  const [fieldErrors, setFieldErrors] = useState<PasswordChangeErrors>({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // Al acabar la espera del 429 se retira su aviso.
  const { remaining, start } = useCountdown(() => setFormError(''));

  const ids: Record<keyof PasswordChangeValues, string> = {
    currentPassword: `${id}-current`,
    newPassword: `${id}-new`,
    confirmPassword: `${id}-confirm`,
  };

  // Al abrirse, el foco va al primer campo.
  useEffect(() => {
    document.getElementById(`${id}-current`)?.focus();
  }, [id]);

  /** Actualiza un campo y borra su error: ya está corrigiéndolo. */
  const setField = (field: keyof PasswordChangeValues, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setFieldErrors((current) => ({ ...current, [field]: undefined }));
  };

  /** Pinta los errores por campo y lleva el foco al primero. */
  const showFieldErrors = (errors: PasswordChangeErrors) => {
    setFieldErrors(errors);
    focusFirstInvalidField(errors, FIELD_ORDER, ids);
  };

  /** Traduce un error del servidor a mensajes de campo o de formulario. */
  const showServerError = (error: unknown) => {
    if (!(error instanceof ApiError)) {
      setFormError(getErrorMessage(error));
      return;
    }
    // Sesión caducada sin arreglo: `apiFetch` ya la cerró y avisó; la ruta lleva al login.
    if (error.sessionExpired) return;
    if (error.status === 429) {
      if (error.retryAfterSeconds) start(error.retryAfterSeconds);
      setFormError(
        passwordRateLimitMessage(
          error.retryAfterSeconds !== undefined ? formatWaitTime(error.retryAfterSeconds) : undefined,
        ),
      );
      return;
    }
    if (error.code === 'CURRENT_PASSWORD_INCORRECT') {
      showFieldErrors({ currentPassword: error.validationErrors?.currentPassword ?? error.message });
      return;
    }
    if (error.code === 'VALIDATION_ERROR' && error.validationErrors) {
      const { perField, unmatched } = splitValidationErrors(error.validationErrors, ids);
      if (unmatched.length > 0) setFormError(unmatched.join(' '));
      showFieldErrors(perField);
      return;
    }
    setFormError(getErrorMessage(error));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');
    const errors = validatePasswordChange(values);
    if (Object.keys(errors).length > 0) {
      showFieldErrors(errors);
      return;
    }

    setSubmitting(true);
    try {
      const body: ChangePasswordRequest = { currentPassword: values.currentPassword, newPassword: values.newPassword };
      await apiFetch('/users/me/password', { method: 'PUT', body });
      toast.success(PASSWORD_CHANGED);
      onClose();
    } catch (error) {
      showServerError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const blocked = remaining > 0;
  let submitLabel = 'Cambiar contraseña';
  if (submitting) submitLabel = 'Cambiando contraseña...';
  else if (blocked) submitLabel = `Reintentar en ${formatWaitTime(remaining)}`;

  return (
    <form id={id} onSubmit={handleSubmit} noValidate aria-label="Cambiar contraseña" className="mt-3">
      <FormAlert message={formError} />
      <div className="flex flex-col gap-4">
        <FormField
          id={ids.currentPassword}
          label="Contraseña actual"
          type="password"
          autoComplete="current-password"
          required
          error={fieldErrors.currentPassword}
          value={values.currentPassword}
          onChange={(e) => setField('currentPassword', e.target.value)}
        />
        <FormField
          id={ids.newPassword}
          label="Nueva contraseña"
          type="password"
          autoComplete="new-password"
          required
          hint={`Entre ${PASSWORD_MIN} y ${PASSWORD_MAX} caracteres. Evita contraseñas comunes y no incluyas tu usuario ni tu correo.`}
          error={fieldErrors.newPassword}
          value={values.newPassword}
          onChange={(e) => setField('newPassword', e.target.value)}
        />
        <FormField
          id={ids.confirmPassword}
          label="Repite la nueva contraseña"
          type="password"
          autoComplete="new-password"
          required
          error={fieldErrors.confirmPassword}
          value={values.confirmPassword}
          onChange={(e) => setField('confirmPassword', e.target.value)}
        />
      </div>
      <p className="mt-4 text-xs leading-relaxed text-muted">
        Se cerrarán tus otras sesiones; en este dispositivo seguirás dentro.
      </p>
      <div className="mt-4 flex flex-wrap gap-3">
        <Button type="submit" disabled={submitting || blocked}>
          {submitLabel}
        </Button>
        <Button variant="outline" onClick={onClose} disabled={submitting}>
          Cancelar
        </Button>
      </div>
    </form>
  );
}
