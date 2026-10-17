import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Check } from 'lucide-react';
import { Button } from '../Button';
import { FormAlert } from '../FormAlert';
import { UserAvatar } from '../UserAvatar';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { ApiError, apiFetch, getErrorMessage } from '../../lib/api';
import { AVATARS, INITIAL_AVATAR_LABEL, avatarChangedMessage, avatarLabel, isAvatarKey } from '../../lib/avatars';
import type { AvatarKey } from '../../lib/avatars';
import type { UpdateProfileRequest, User } from '../../lib/types';

/** Propiedades de {@link AvatarForm}. */
interface AvatarFormProps {
  /** `id` del formulario (único en la página: `useId`). */
  id: string;
  /** Nombre del usuario: la opción «Tu inicial» y la vista previa enseñan su inicial. */
  username: string;
  /** Avatar actual (`User.avatar`): opción marcada al abrir y referencia para saber si ha cambiado. */
  currentAvatar: string | null | undefined;
  /** Cierra el formulario (al guardar o al cancelar); quien lo abrió devuelve el foco. */
  onClose: () => void;
}

/** Una opción del selector: un avatar o `null` («Tu inicial»). */
type Choice = AvatarKey | null;

/** Lleva el foco a la opción marcada del grupo: desde ahí las flechas lo recorren. */
function focusCheckedOption(group: HTMLFieldSetElement | null) {
  group?.querySelector<HTMLInputElement>('input:checked')?.focus();
}

/**
 * Selector del icono de perfil (`PATCH /api/users/me` con solo `avatar`).
 *
 * **Grupo de radio nativo.** `fieldset` + `legend` y un `<input type="radio">`
 * por opción, oculto a la vista (`sr-only`) pero no al teclado ni al lector de
 * pantalla: el navegador ya da gratis lo que pide el patrón (Tab entra en el
 * grupo por la opción marcada, las flechas cambian de opción y la marcan, y se
 * anuncia «Gato, botón de opción, marcado, 4 de 13»). Lo visible es la etiqueta:
 * el avatar y su nombre. La opción marcada no se distingue solo por el color:
 * lleva además fondo propio, una marca ✓ y el nombre en negrita. El foco
 * se dibuja en la etiqueta (`has-focus-visible`) con el mismo contorno que `focus-ring`.
 *
 * **Opciones.** «Tu inicial» (`null`, lo de siempre) y los 12 avatares de
 * `lib/avatars.ts`. Si la clave guardada no la conoce esta versión de la
 * interfaz, se marca «Tu inicial», que es lo que se está viendo.
 *
 * **Guardar.** Si lo marcado es lo que ya se ve (con una clave desconocida,
 * «Tu inicial»), se cierra sin petición: así no se borra en el servidor una
 * clave que quizá conozca otra versión de la interfaz. Si no,
 * `updateUser` cambia la sesión con la respuesta del servidor y la barra y la
 * cabecera del perfil lo enseñan al instante (como el cambio de nombre). Un 400
 * con `validationErrors.avatar` se muestra junto al grupo (y el foco vuelve a la
 * opción marcada, que lo tiene en su descripción); el resto de errores (red,
 * 429, 5xx...) en un aviso del formulario, que sigue abierto para reintentar. El
 * 401 lo resuelve `apiFetch` (sesión caducada → login).
 */
export function AvatarForm({ id, username, currentAvatar, onClose }: AvatarFormProps) {
  const { updateUser } = useAuth();
  const toast = useToast();
  const fieldsetRef = useRef<HTMLFieldSetElement>(null);
  const current: string | null = currentAvatar ?? null;
  /**
   * Lo que el usuario está viendo ahora: la clave guardada si la interfaz la
   * conoce y, si no, `null` («Tu inicial», que es como se pinta). Es la
   * referencia para saber si ha cambiado algo: comparar con `current` haría que
   * «Guardar icono» sin tocar nada enviara `{ avatar: null }` con una clave
   * desconocida, borrándola y avisando de un cambio que no se ve.
   */
  const shown: Choice = isAvatarKey(current) ? current : null;
  const [selected, setSelected] = useState<Choice>(shown);
  const [fieldError, setFieldError] = useState('');
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const errorId = `${id}-error`;
  const name = `${id}-avatar`;

  // Al abrirse, el foco va a la opción marcada (como el campo del nombre al abrir su formulario).
  useEffect(() => {
    focusCheckedOption(fieldsetRef.current);
  }, []);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');
    setFieldError('');
    if (selected === shown) {
      onClose();
      return;
    }

    setSubmitting(true);
    try {
      const body: UpdateProfileRequest = { avatar: selected };
      const updated = await apiFetch<User>('/users/me', { method: 'PATCH', body });
      updateUser(updated);
      toast.success(avatarChangedMessage(updated.avatar));
      onClose();
    } catch (error) {
      if (error instanceof ApiError && error.sessionExpired) return;
      if (error instanceof ApiError && error.validationErrors?.avatar) {
        setFieldError(error.validationErrors.avatar);
        focusCheckedOption(fieldsetRef.current);
      } else {
        setFormError(getErrorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const choices: { value: Choice; label: string }[] = [
    { value: null, label: INITIAL_AVATAR_LABEL },
    ...AVATARS.map((avatar) => ({ value: avatar.key, label: avatar.label })),
  ];

  return (
    <form id={id} onSubmit={handleSubmit} noValidate aria-label="Cambiar icono de perfil" className="mt-3">
      <FormAlert message={formError} />
      <fieldset ref={fieldsetRef} className="min-w-0">
        <legend className="mb-1 text-sm font-medium text-white">Elige tu icono</legend>
        <p className="mb-3 text-xs leading-relaxed text-muted">
          Se muestra en la barra superior y en tu perfil. «Tu inicial» usa la primera letra de tu nombre.
        </p>
        {fieldError && (
          <p id={errorId} role="alert" className="mb-3 text-xs font-medium text-danger">
            {fieldError}
          </p>
        )}
        {/* Rejilla que se adapta al ancho: 4 columnas a 375 px y más cuanto más espacio, sin desbordar nunca. */}
        <ul role="list" className="grid grid-cols-[repeat(auto-fill,minmax(4.25rem,1fr))] gap-2">
          {choices.map(({ value, label }) => {
            const checked = selected === value;
            return (
              <li key={value ?? 'initial'}>
                <label
                  className={`relative flex min-h-11 cursor-pointer flex-col items-center gap-1.5 rounded-lg border-2 px-1 py-2 text-center text-xs transition-colors has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent ${
                    checked
                      ? 'border-accent bg-surface-raised font-semibold text-white'
                      : 'border-line text-gray-300 hover:border-field-border hover:text-white'
                  }`}
                >
                  <input
                    type="radio"
                    name={name}
                    value={value ?? ''}
                    checked={checked}
                    onChange={() => {
                      setSelected(value);
                      setFieldError('');
                    }}
                    aria-describedby={fieldError ? errorId : undefined}
                    className="sr-only"
                  />
                  <UserAvatar username={username} avatar={value} size="md" />
                  <span className="break-words">{label}</span>
                  {checked && (
                    <span
                      aria-hidden="true"
                      className="absolute top-1 right-1 inline-flex size-5 items-center justify-center rounded-full bg-accent text-black"
                    >
                      <Check className="size-3.5" strokeWidth={3} />
                    </span>
                  )}
                </label>
              </li>
            );
          })}
        </ul>
      </fieldset>

      <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        {/* Vista previa al tamaño de la cabecera del perfil: el nombre va en texto, el dibujo es decorativo. */}
        <div className="flex items-center gap-3">
          <UserAvatar username={username} avatar={selected} size="lg" />
          <p className="text-sm text-muted">
            Vista previa: <span className="font-semibold text-white">{avatarLabel(selected)}</span>
          </p>
        </div>
        <div className="flex flex-wrap gap-3">
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Guardando...' : 'Guardar icono'}
          </Button>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancelar
          </Button>
        </div>
      </div>
    </form>
  );
}
