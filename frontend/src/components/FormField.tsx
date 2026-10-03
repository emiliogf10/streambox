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
 * - `aria-invalid` marca el campo erróneo y `aria-describedby` apunta a lo que
 *   se ve debajo (la ayuda o el error), de modo que se lee al enfocar el campo.
 * - Con error, el error SUSTITUYE a la ayuda en lugar de sumarse: los mensajes
 *   ya incluyen el requisito ("debe tener entre 3 y 50 caracteres"), y ver
 *   "Entre 3 y 50 caracteres." justo encima del error era decir lo mismo dos
 *   veces. Al corregir el campo el error desaparece y la ayuda vuelve.
 * - El error va en `role="alert"` para anunciarse en cuanto aparece.
 * - El error no depende solo del color: lleva texto y borde, y el foco es visible.
 * - El marcador (`placeholder`) usa `text-muted` sin transparencia: con `/70` el
 *   contraste bajaba a 3.5:1 y no llegaba al 4.5:1 exigido.
 * - El borde usa el token `field-border` (3.9:1 sobre el fondo, ver `index.css`):
 *   WCAG 1.4.11 pide 3:1 para el contorno que permite identificar un control,
 *   y el anterior `white/15` se quedaba en 1.5:1. Al pasar el ratón se aclara.
 */
export function FormField({ id, label, error, hint, className = '', ...inputProps }: FormFieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const showHint = Boolean(hint) && !error;
  const describedBy = error ? errorId : showHint ? hintId : undefined;

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
          'focus-ring min-h-11 w-full rounded-lg border bg-surface px-4 py-2.5 text-sm text-white transition-colors ' +
          'placeholder:text-muted ' +
          (error ? 'border-danger ' : 'border-field-border hover:border-muted ') +
          className
        }
        {...inputProps}
      />
      {showHint && (
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
