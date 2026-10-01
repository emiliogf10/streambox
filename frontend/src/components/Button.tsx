import type { ComponentProps } from 'react';
import { buttonClasses } from './buttonStyles';
import type { ButtonVariant } from './buttonStyles';

/**
 * Propiedades de {@link Button}: las de un `<button>` (incluido `ref`, que en
 * React 19 es una prop más) más la variante visual.
 */
interface ButtonProps extends ComponentProps<'button'> {
  variant?: ButtonVariant;
}

/**
 * Botón con el aspecto de la aplicación. Por defecto `type="button"` para que
 * un botón dentro de un formulario no lo envíe sin querer.
 */
export function Button({ variant = 'primary', className = '', type = 'button', ...rest }: ButtonProps) {
  return <button type={type} className={buttonClasses(variant, className)} {...rest} />;
}
