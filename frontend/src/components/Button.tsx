import type { ComponentProps, MouseEvent } from 'react';
import { buttonClasses } from './buttonStyles';
import type { ButtonVariant } from './buttonStyles';

/**
 * Propiedades de {@link Button}: las de un `<button>` (incluido `ref`, que en
 * React 19 es una prop más) más la variante visual y los dos estados «sin
 * soltar el foco».
 */
interface ButtonProps extends ComponentProps<'button'> {
  variant?: ButtonVariant;
  /**
   * Hay una petición en curso lanzada por este botón (enviar un formulario,
   * guardar, añadir a la lista...). El botón se ve y se anuncia desactivado
   * (`aria-disabled`, `aria-busy`) e ignora los clics y los envíos, pero
   * **conserva el foco**. Ver la explicación del componente.
   */
  pending?: boolean;
  /**
   * El botón no se puede usar por ahora, aunque no haya nada en curso (p. ej.
   * la espera de `Retry-After` tras un 429 o el minuto entre dos reenvíos de un
   * correo). Igual que `pending`, pero sin `aria-busy`.
   */
  inactive?: boolean;
}

/**
 * Botón con el aspecto de la aplicación. Por defecto `type="button"` para que
 * un botón dentro de un formulario no lo envíe sin querer.
 *
 * **`pending` / `inactive` en lugar de `disabled`.** Un botón que pasa a
 * `disabled` mientras tiene el foco lo suelta: el navegador lo manda al
 * `<body>` y quien usa teclado o lector de pantalla pierde su sitio (al pulsar
 * Tab vuelve a empezar por «Saltar al contenido»). Pasaba al enviar cualquier
 * formulario pulsando su botón y con «Mi lista». Con estas props el botón:
 * - se marca con `aria-disabled="true"` (el lector lo anuncia como no
 *   disponible y `buttonStyles` lo atenúa igual que a uno desactivado) y, si
 *   hay algo en curso, con `aria-busy="true"`;
 * - sigue en el orden de tabulación y **no pierde el foco**;
 * - **anula el clic** (`preventDefault`): ni se llama a `onClick` ni, en un
 *   botón `submit`, se envía el formulario. Intro en un campo de texto también
 *   pasa por aquí, porque el envío implícito del navegador «pulsa» el botón de
 *   envío; así no hay dobles envíos aunque el formulario no lo compruebe.
 *
 * `disabled` sigue disponible para botones que nunca tienen el foco en ese
 * momento (p. ej. «Cancelar» mientras se guarda con el otro botón).
 */
export function Button({
  variant = 'primary',
  className = '',
  type = 'button',
  pending = false,
  inactive = false,
  onClick,
  ...rest
}: ButtonProps) {
  const blocked = pending || inactive;

  const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
    if (blocked) {
      event.preventDefault();
      return;
    }
    onClick?.(event);
  };

  return (
    <button
      type={type}
      className={buttonClasses(variant, className)}
      {...rest}
      {...(blocked ? { 'aria-disabled': true } : {})}
      {...(pending ? { 'aria-busy': true } : {})}
      onClick={handleClick}
    />
  );
}
