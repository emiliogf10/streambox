import type { ComponentProps } from 'react';

/** Propiedades de {@link FormField}: las de un `<input>` más etiqueta, ayuda y error. */
interface FormFieldProps extends ComponentProps<'input'> {
  /** `id` obligatorio: enlaza la etiqueta, la ayuda y el error con el campo. */
  id: string;
  /** Texto de la etiqueta (visible). */
  label: string;
  /** Mensaje de error del campo; si existe, el campo se marca como inválido. */
  error?: string;
  /** Texto de ayuda permanente (requisitos del campo). */
  hint?: string;
}

/**
 * Campo de formulario accesible: etiqueta + input + ayuda + error.
 *
 * - `<label htmlFor>` asocia el texto al campo (clic en la etiqueta y lectores de pantalla).
 * - `aria-invalid` marca el campo erróneo y `aria-describedby` apunta a la ayuda
 *   y al error, de modo que se leen al enfocar el campo.
 * - El error va en `role="alert"` para anunciarse en cuanto aparece.
 * - El error no depende solo del color: lleva texto y borde, y el foco es visible.
 * - El marcador (`placeholder`) usa `text-muted` sin transparencia: con `/70` el
 *   contraste bajaba a 3.5:1 y no llegaba al 4.5:1 exigido.
 */
export function FormField({ id, label, error, hint, className = '', ...inputProps }: FormFieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined;

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-gray-300">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={
          'focus-ring w-full rounded-lg border bg-canvas px-4 py-2.5 text-sm text-white placeholder:text-muted ' +
          (error ? 'border-danger ' : 'border-white/15 ') +
          className
        }
        {...inputProps}
      />
      {hint && (
        <p id={hintId} className="mt-1 text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="mt-1 text-xs font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
