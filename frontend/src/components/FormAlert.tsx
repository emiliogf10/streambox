import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';

/** Propiedades de {@link FormAlert}. */
interface FormAlertProps {
  /** Mensaje principal; si está vacío no se pinta nada. */
  message: string;
  /**
   * Detalle opcional bajo el mensaje (p. ej. los intentos de login que quedan).
   * Va DENTRO del mismo aviso para que se lea junto al mensaje, de una vez, y no
   * en dos avisos que se pisan.
   */
  children?: ReactNode;
  /**
   * Si el aviso se lleva el foco al aparecer (por defecto, sí).
   *
   * Pásalo a `false` cuando el mismo envío también ha marcado campos con error:
   * entonces el foco va al primer campo con error (lo mueve el formulario, que
   * es donde hay que corregir) y el aviso se anuncia como región viva
   * (`role="alert"`), como antes. Ver {@link FormAlert}.
   */
  takeFocus?: boolean;
}

/**
 * Aviso de error a nivel de formulario (credenciales incorrectas, 429, red
 * caída...). No depende solo del color: el texto dice qué ha pasado.
 *
 * **El foco va al aviso (decisión del autor, revisión de 2026-11).** Tras un
 * error que no es de ningún campo (un 401 en el login, la red caída al guardar),
 * el foco salta al aviso: quien usa teclado o lector de pantalla lo oye y sigue
 * desde ahí (el siguiente Tab lleva al primer campo), y en móvil el navegador lo
 * desplaza a la vista. Antes el foco se quedaba en el botón y el aviso, arriba,
 * podía quedar fuera de la pantalla.
 *
 * **Un solo anuncio.** Un aviso que es región viva (`role="alert"`) y además
 * recibe el foco se lee DOS veces: una al aparecer (la región viva) y otra al
 * enfocarse. Por eso hay dos modos, que se eligen al aparecer cada mensaje y no
 * cambian mientras se ve (ver `takeFocus`):
 * - **Con foco** (lo normal): un grupo enfocable por código (`tabIndex={-1}`, no
 *   es una parada del tabulador) cuyo nombre accesible es el propio texto
 *   (`aria-labelledby`), sin región viva. Se oye una vez: al recibir el foco.
 *   El anillo de foco es el de siempre (`focus-ring`).
 * - **Sin foco**, cuando el mismo envío ha marcado campos: el foco va al primer
 *   campo con error y el aviso general se anuncia con `role="alert"`.
 *
 * Cada mensaje nuevo monta el aviso de nuevo (`key`), así que un error distinto
 * vuelve a llevarse el foco. Los formularios vacían el aviso al enviar, de modo
 * que un mismo error que se repite tras otro envío también vuelve a enfocarse.
 */
export function FormAlert({ message, children, takeFocus = true }: FormAlertProps) {
  if (!message) return null;
  return (
    <FormAlertBox key={message} message={message} takeFocus={takeFocus}>
      {children}
    </FormAlertBox>
  );
}

/** El aviso ya con mensaje; ver {@link FormAlert}. */
function FormAlertBox({ message, children, takeFocus }: Required<Pick<FormAlertProps, 'message' | 'takeFocus'>> & {
  children?: ReactNode;
}) {
  // El modo se fija al aparecer: si al corregir un campo dejara de haber errores de campo, el aviso
  // no debe quitarle el foco a quien está escribiendo ni dejar de ser (o pasar a ser) una región viva.
  const [focusMode] = useState(takeFocus);
  const ref = useRef<HTMLDivElement>(null);
  const contentId = useId();

  useEffect(() => {
    if (focusMode) ref.current?.focus();
  }, [focusMode]);

  const className = 'mb-5 rounded-lg border border-red-500/40 bg-red-900/40 px-4 py-3 text-sm text-red-300';
  if (!focusMode) {
    return (
      <div role="alert" className={className}>
        {message}
        {children}
      </div>
    );
  }
  return (
    <div ref={ref} tabIndex={-1} role="group" aria-labelledby={contentId} className={`focus-ring ${className}`}>
      <div id={contentId}>
        {message}
        {children}
      </div>
    </div>
  );
}
