import type { ReactNode } from 'react';

/** Propiedades de {@link FormAlert}. */
interface FormAlertProps {
  /** Mensaje principal; si está vacío no se pinta nada. */
  message: string;
  /**
   * Detalle opcional bajo el mensaje (p. ej. los intentos de login que quedan).
   * Va DENTRO de la misma región para que se anuncie junto al mensaje, en un
   * solo aviso, en lugar de en dos alertas que se pisan.
   */
  children?: ReactNode;
}

/**
 * Aviso de error a nivel de formulario (credenciales incorrectas, 429, red
 * caída...). Usa `role="alert"` para que se anuncie al aparecer; no depende
 * solo del color porque incluye un texto explícito.
 */
export function FormAlert({ message, children }: FormAlertProps) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="mb-5 rounded-lg border border-red-500/40 bg-red-900/40 px-4 py-3 text-sm text-red-300"
    >
      {message}
      {children}
    </div>
  );
}
