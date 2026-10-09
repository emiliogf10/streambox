import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../Button';
import { FormAlert } from '../FormAlert';
import { FormField } from '../FormField';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { ApiError, apiFetch, getErrorMessage } from '../../lib/api';
import { usernameChangedMessage } from '../../lib/profile';
import type { UpdateProfileRequest, User } from '../../lib/types';
import { USERNAME_MAX, USERNAME_MIN, normalizeUsername, usernameError } from '../../lib/validation';

/** Propiedades de {@link UsernameForm}. */
interface UsernameFormProps {
  /** `id` del formulario (único en la página: `useId`). */
  id: string;
  /** Nombre actual: valor inicial del campo y referencia para saber si ha cambiado. */
  currentUsername: string;
  /** Cierra el formulario (al guardar o al cancelar); quien lo abrió devuelve el foco. */
  onClose: () => void;
}

/**
 * Formulario en línea para cambiar el nombre de usuario (`PATCH /api/users/me`).
 *
 * - Envía SOLO `username`: el servidor rechaza con 400 un `email`, `role` o
 *   `password` (el correo no se puede cambiar; ver la tarjeta «Cuenta»).
 * - Normaliza como el servidor ({@link normalizeUsername}) y valida la misma
 *   longitud (3–50) antes de enviar. Si el nombre normalizado es el actual, no
 *   hay nada que guardar: se cierra sin petición (el servidor respondería 200 sin cambios).
 * - Al guardar, `updateUser` cambia el nombre en la sesión con la respuesta del
 *   servidor: la cabecera del perfil y la barra lo enseñan al instante, sin
 *   recargar ni volver a pedir `/users/me`.
 * - Errores junto al campo (y foco en él): 409 `USER_ALREADY_EXISTS` («ya está
 *   en uso») y 400 `VALIDATION_ERROR` con `validationErrors.username`. El resto
 *   (red, 5xx...) en un aviso del formulario, que sigue abierto para reintentar.
 *   Un 400 NUNCA cierra la sesión; un 401 sin arreglo ya lo gestiona `apiFetch`
 *   (sesión caducada → login), así que aquí no se enseña nada más.
 */
export function UsernameForm({ id, currentUsername, onClose }: UsernameFormProps) {
  const { updateUser } = useAuth();
  const toast = useToast();
  const inputId = `${id}-input`;
  const inputRef = useRef<HTMLInputElement>(null);
  const [value, setValue] = useState(currentUsername);
  const [fieldError, setFieldError] = useState('');
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Al abrirse, el foco va al campo con el texto seleccionado: lo normal es reescribirlo entero.
  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  /** Muestra un error del campo y lleva allí el foco (teclado y lector de pantalla lo encuentran). */
  const showFieldError = (message: string) => {
    setFieldError(message);
    inputRef.current?.focus();
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');
    const username = normalizeUsername(value);
    const problem = usernameError(username);
    if (problem) {
      showFieldError(problem);
      return;
    }
    if (username === currentUsername) {
      onClose();
      return;
    }

    setSubmitting(true);
    try {
      const body: UpdateProfileRequest = { username };
      const updated = await apiFetch<User>('/users/me', { method: 'PATCH', body });
      updateUser(updated);
      toast.success(usernameChangedMessage(updated.username));
      onClose();
    } catch (error) {
      if (error instanceof ApiError && error.sessionExpired) return;
      if (error instanceof ApiError && error.code === 'USER_ALREADY_EXISTS') {
        showFieldError(error.message);
      } else if (error instanceof ApiError && error.validationErrors?.username) {
        showFieldError(error.validationErrors.username);
      } else {
        setFormError(getErrorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form id={id} onSubmit={handleSubmit} noValidate aria-label="Cambiar nombre de usuario" className="mt-3">
      <FormAlert message={formError} />
      <FormField
        ref={inputRef}
        id={inputId}
        label="Nuevo nombre de usuario"
        type="text"
        autoComplete="username"
        required
        hint={`Entre ${USERNAME_MIN} y ${USERNAME_MAX} caracteres.`}
        error={fieldError}
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          setFieldError('');
        }}
      />
      <div className="mt-4 flex flex-wrap gap-3">
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Guardando...' : 'Guardar'}
        </Button>
        <Button variant="outline" onClick={onClose} disabled={submitting}>
          Cancelar
        </Button>
      </div>
    </form>
  );
}
