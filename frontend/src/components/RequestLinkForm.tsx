import { useState } from 'react';
import type { FormEvent } from 'react';
import { useEmailLinkRequest } from '../hooks/useEmailLinkRequest';
import type { UseEmailLinkRequestOptions } from '../hooks/useEmailLinkRequest';
import { formatWaitTime } from '../lib/utils';
import { emailError } from '../lib/validation';
import { Button } from './Button';
import { FormField } from './FormField';
import { ResendNotice } from './ResendVerification';

/** Propiedades de {@link RequestLinkForm}. */
interface RequestLinkFormProps extends Pick<UseEmailLinkRequestOptions, 'send' | 'doneMessage'> {
  /** `id` del campo de correo (único en la página). */
  id: string;
}

/**
 * Formulario «pedir un enlace nuevo» de los estados «enlace no válido» de
 * `/verificar-email` (otro correo de confirmación) y `/restablecer-contrasena`
 * (otro enlace para la contraseña). Allí no se conoce el correo (el enlace solo
 * trae el token), así que hay que escribirlo.
 *
 * El correo se valida en el cliente (vacío o sin `@` no se envía y el foco
 * vuelve al campo) y se envía recortado. El aviso del resultado va debajo del
 * botón, en la misma página (`ResendNotice`), con las reglas de espera de
 * {@link useEmailLinkRequest}: un minuto tras cada envío y la de `Retry-After` en un 429.
 */
export function RequestLinkForm({ id, send, doneMessage }: RequestLinkFormProps) {
  const [email, setEmail] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>(undefined);
  const { request, sending, remaining, notice } = useEmailLinkRequest({ send, doneMessage });
  const waiting = remaining > 0;

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const value = email.trim();
    const problem = emailError(value);
    setFieldError(problem);
    if (problem) {
      document.getElementById(id)?.focus();
      return;
    }
    void request(value);
  };

  return (
    <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
      <FormField
        id={id}
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
      <div>
        <Button type="submit" variant="outline" disabled={sending || waiting} className="w-full">
          {sending ? 'Enviando...' : waiting ? `Pedir otro en ${formatWaitTime(remaining)}` : 'Enviar un enlace nuevo'}
        </Button>
        <ResendNotice notice={notice} />
      </div>
    </form>
  );
}
