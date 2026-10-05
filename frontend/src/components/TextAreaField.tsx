import type { ComponentProps } from 'react';

/** Propiedades de {@link TextAreaField}: las de un `<textarea>` más etiqueta, ayuda, error y límite. */
interface TextAreaFieldProps extends Omit<ComponentProps<'textarea'>, 'value'> {
  /** `id` obligatorio: enlaza la etiqueta, la ayuda, el error y el contador con el campo. */
  id: string;
  label: string;
  /** Texto actual (controlado): el contador lo necesita. */
  value: string;
  /** Máximo de caracteres que admite el servidor (lo muestra el contador). */
  maxChars: number;
  error?: string;
  hint?: string;
}

/**
 * Área de texto accesible con contador de caracteres (p. ej. la sinopsis).
 *
 * Sigue las mismas reglas que {@link FormField} (etiqueta visible, error que
 * sustituye a la ayuda, `aria-invalid`, `role="alert"`) y añade un contador
 * "123 / 1000":
 * - **No se usa el atributo `maxLength`**: cortaría en silencio un texto pegado
 *   y el usuario no sabría qué ha perdido. Se deja escribir de más y el
 *   contador avisa ("sobran 12") en rojo y con texto, no solo con color.
 * - El contador forma parte de `aria-describedby`, así que el lector de
 *   pantalla lo lee al entrar en el campo. NO es una región viva: anunciarlo
 *   con cada tecla sería insoportable.
 */
export function TextAreaField({ id, label, value, maxChars, error, hint, className = '', ...rest }: TextAreaFieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const counterId = `${id}-counter`;
  const showHint = Boolean(hint) && !error;
  const over = value.length - maxChars;
  const describedBy = [error ? errorId : showHint ? hintId : null, counterId].filter(Boolean).join(' ');

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-gray-300">
        {label}
      </label>
      <textarea
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={
          'focus-ring block min-h-36 w-full resize-y rounded-lg border bg-surface px-4 py-2.5 text-sm leading-relaxed text-white transition-colors ' +
          'placeholder:text-muted ' +
          (error ? 'border-danger ' : 'border-field-border hover:border-muted ') +
          className
        }
        {...rest}
      />
      <div className="mt-1 flex items-start justify-between gap-3">
        {showHint && (
          <p id={hintId} className="text-xs text-muted">
            {hint}
          </p>
        )}
        {error && (
          <p id={errorId} role="alert" className="text-xs font-medium text-danger">
            {error}
          </p>
        )}
        <p
          id={counterId}
          className={`ml-auto shrink-0 text-xs tabular-nums ${over > 0 ? 'font-semibold text-danger' : 'text-muted'}`}
        >
          <span aria-hidden="true">
            {value.length} / {maxChars}
            {over > 0 && ` · sobran ${over}`}
          </span>
          <span className="sr-only">
            {over > 0
              ? `${value.length} de ${maxChars} caracteres: sobran ${over}`
              : `${value.length} de ${maxChars} caracteres`}
          </span>
        </p>
      </div>
    </div>
  );
}
