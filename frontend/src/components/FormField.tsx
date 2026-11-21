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
  /**
   * Oculta la etiqueta a la vista (sigue existiendo para los lectores de
   * pantalla). Solo para campos cuyo propósito ya es evidente por el contexto,
   * como renombrar en línea un elemento de una lista.
   */
  hideLabel?: boolean;
  /**
   * Si se da, el hueco del mensaje (el error) existe SIEMPRE, vacío cuando no
   * hay error, con estas clases añadidas: así la aparición del error no empuja
   * lo que hay debajo (salto de maquetación). Se combina con variantes de
   * Tailwind para decidir dónde se reserva: p. ej. `max-sm:empty:hidden
   * sm:min-h-4` lo reserva solo desde `sm` (ver el campo «Año» de
   * `CatalogFilterBar`, D-11). Sin la prop, el error aparece y empuja, como siempre.
   */
  messageSlotClassName?: string;
}

/**
 * Campo de formulario accesible: etiqueta + input + ayuda + error.
 *
 * - `<label htmlFor>` asocia el texto al campo (clic en la etiqueta y lectores de pantalla).
 * - `aria-invalid` marca el campo erróneo (`"true"`) y vale `"false"` mientras no
 *   hay error: si faltara, Firefox y Safari expondrían un campo `required` vacío
 *   como inválido desde que carga la página, y un lector de pantalla diría «no
 *   válido» antes de que el usuario escriba nada. `aria-describedby` apunta a lo
 *   que se ve debajo (la ayuda o el error), de modo que se lee al enfocar el campo.
 * - Con error, el error SUSTITUYE a la ayuda en lugar de sumarse: los mensajes
 *   ya incluyen el requisito ("debe tener entre 3 y 50 caracteres"), y ver
 *   "Entre 3 y 50 caracteres." justo encima del error era decir lo mismo dos
 *   veces. Al corregir el campo el error desaparece y la ayuda vuelve.
 * - **El error NO es una región viva** (`role="alert"`). Quien muestra el error
 *   se encarga de que se oiga UNA vez: tras un envío fallido, el formulario
 *   lleva el foco al primer campo con error (que lee su error por
 *   `aria-describedby`) y, si hace falta, un único aviso general (`FormAlert`).
 *   Con un `role="alert"` por campo, varios errores a la vez se interrumpían
 *   entre sí y el campo enfocado los repetía. Un error que aparece sin envío
 *   (p. ej. al salir del campo) lo anuncia la página con una región cortés.
 * - **Texto de 16 px en móvil** (`text-base`, 14 px desde `sm`): con menos de
 *   16 px, Safari en iOS amplía la página al enfocar el campo y el usuario
 *   tiene que pellizcar para volver a ver el formulario.
 * - Con `type="email"`, `spellcheck="false"` por defecto: el correo es un
 *   identificador y el corrector lo subrayaría como falta.
 * - El error no depende solo del color: lleva texto y borde, y el foco es visible.
 * - El marcador (`placeholder`) usa `text-muted` sin transparencia: con `/70` el
 *   contraste bajaba a 3.5:1 y no llegaba al 4.5:1 exigido.
 * - El borde usa el token `field-border` (3.9:1 sobre el fondo, ver `index.css`):
 *   WCAG 1.4.11 pide 3:1 para el contorno que permite identificar un control,
 *   y el anterior `white/15` se quedaba en 1.5:1. Al pasar el ratón se aclara.
 */
export function FormField({
  id,
  label,
  error,
  hint,
  hideLabel = false,
  messageSlotClassName,
  className = '',
  ...inputProps
}: FormFieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const showHint = Boolean(hint) && !error;
  const describedBy = error ? errorId : showHint ? hintId : undefined;

  return (
    <div>
      <label htmlFor={id} className={hideLabel ? 'sr-only' : 'mb-1.5 block text-sm font-medium text-gray-300'}>
        {label}
      </label>
      <input
        id={id}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
        // Un correo es un identificador, no texto: sin corrector ortográfico que lo subraye o lo «arregle»
        // (el navegador ya quita la mayúscula inicial y la autocorrección en `type="email"`). Se puede sobrescribir.
        spellCheck={inputProps.type === 'email' ? false : undefined}
        className={
          'focus-ring min-h-11 w-full rounded-lg border bg-surface px-4 py-2.5 text-base text-white transition-colors sm:text-sm ' +
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
        <p id={errorId} className={`mt-1 text-xs font-medium text-danger ${messageSlotClassName ?? ''}`.trim()}>
          {error}
        </p>
      )}
      {/* Hueco reservado del mensaje (vacío): mide lo mismo que el error, que así no desplaza nada al aparecer. */}
      {!error && !showHint && messageSlotClassName !== undefined && (
        <p aria-hidden="true" className={`mt-1 text-xs ${messageSlotClassName}`} />
      )}
    </div>
  );
}
