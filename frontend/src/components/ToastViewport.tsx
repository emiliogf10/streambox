import { useCallback, useLayoutEffect, useRef } from 'react';
import type { FocusEvent, RefObject } from 'react';
import type { ToastItem } from '../context/ToastContext';
import { useHeightCssVariable } from '../hooks/useHeightCssVariable';
import { MAIN_CONTENT_ID } from './SkipLink';
import { Toast } from './Toast';

/** Propiedades de {@link ToastViewport}. */
interface ToastViewportProps {
  toasts: ToastItem[];
  onDismiss: (id: number) => void;
  /**
   * `true` para la zona que vive dentro de un diálogo abierto (ver `Modal`). Allí
   * la barra superior queda tapada por el fondo del diálogo, así que en móvil los
   * avisos van arriba del todo y su altura no se publica (solo cuenta la zona de la página).
   */
  inDialog?: boolean;
}

/** Variable CSS con la altura de la zona de avisos de la página: la usa `index.css` para que el foco no quede debajo. */
export const TOASTS_HEIGHT_VARIABLE = '--toasts-height';

/** `ref` vacía y estable: la zona de un diálogo no publica su altura (ver `inDialog`). */
const NO_ELEMENT: RefObject<HTMLElement | null> = { current: null };

/**
 * Posición en móvil de la zona de la página: justo debajo de la barra (con el
 * mismo respiro de 0.75rem de siempre) y, mientras la barra está apartada, subida
 * su misma altura. El desplazamiento usa `translate` (como la barra, sin
 * recolocar nada) y solo por debajo de `sm`: desde ahí la zona va abajo.
 */
const PLACEMENT_ON_PAGE =
  'top-[calc(var(--navbar-height,0px)+0.75rem)] max-sm:navbar-away:-translate-y-(--navbar-height) ' +
  'max-sm:motion-safe:transition-[translate] max-sm:motion-safe:duration-200 max-sm:motion-safe:ease-out-strong';

/** Posición en móvil de la zona de un diálogo: arriba del todo (la barra está tras el fondo). */
const PLACEMENT_IN_DIALOG = 'top-3';

/** Elemento que puede recibir el foco ahora mismo: sigue en la página y no está dentro de una zona inerte. */
function canTakeFocus(element: HTMLElement | null | undefined): element is HTMLElement {
  return !!element && element.isConnected && !element.closest('[inert]');
}

/**
 * Zona fija de la pantalla donde se apilan los avisos.
 *
 * **Regiones.** Hay DOS regiones `aria-live` permanentes (no se crean al
 * aparecer el primer aviso, porque los lectores de pantalla solo anuncian
 * cambios en regiones que ya existían): una `polite` para éxito/información,
 * que espera a que el lector termine de hablar, y una `assertive` para
 * errores, que interrumpe. Las dos van dentro de una región con nombre
 * («Avisos», `<section aria-label>`), siempre en el mismo sitio: quien usa
 * lector de pantalla la encuentra en la lista de zonas de la página para releer
 * un error (B5; guía «persistent-toast-notifications»: «make the container a
 * region, with an accessible name»).
 *
 * **Dónde.** Abajo a la derecha desde `sm`; en móvil, ARRIBA y a todo el ancho
 * (E-10). Abajo, en un teléfono, el aviso tapaba justo la zona en la que se
 * está trabajando: tras guardar el icono del perfil, «Icono de perfil
 * cambiado…» cubría el botón «Cambiar» de la contraseña durante 5 s.
 *
 * Arriba, pero **justo DEBAJO de la barra superior**, no encima de ella. Al
 * principio iba pegada al borde (`top-3`) y tapaba la barra entera: como los
 * errores no se cierran solos (B5), un error de red dejaba el logo, las
 * pestañas, la lupa y el menú inaccesibles hasta cerrarlo a mano, y el foco de
 * teclado podía quedar debajo (WCAG 2.2 · 2.4.11). Ahora:
 * - La posición sale de `--navbar-height`, que publica `Navbar` con su altura
 *   real (dos filas en móvil, más con el buscador abierto). Sin barra (login,
 *   registro) la variable no existe y queda a 0.75rem del borde, como antes.
 * - **Cuando la barra se aparta al bajar** (variante `navbar-away`), la zona
 *   sube con ella, a la vez y con la misma curva, para no dejar un hueco vacío
 *   arriba; al volver la barra, baja otra vez. Con movimiento reducido salta
 *   sin deslizarse, igual que la barra.
 * - Publica su altura en `--toasts-height` ({@link useHeightCssVariable}) e
 *   `index.css` la suma, en móvil, al `scroll-margin-top` del contenido: lo que
 *   se enfoca queda por debajo de la barra Y de los avisos.
 * - Dentro de un diálogo (`inDialog`) la barra no se ve (está tras el fondo),
 *   así que allí los avisos siguen arriba del todo.
 *
 * **Foco al cerrar uno a mano** (B4; misma guía: «move focus to the next
 * remaining toast in the stack, or—if no toasts remain—return focus to the
 * user's prior focus location»). Si el aviso que se cierra tenía el foco, al
 * desaparecer su botón el foco caía en `<body>` y el siguiente Tab empezaba
 * desde un punto imprevisible. Ahora va:
 * 1. al botón «Cerrar aviso» del siguiente aviso de la pila (o del anterior,
 *    si era el último), para poder cerrarlos seguidos;
 * 2. si no queda ninguno, al elemento que tenía el foco ANTES de entrar en la
 *    zona de avisos (se anota en `onFocus` con `relatedTarget`), si sigue en la
 *    página y no está inerte;
 * 3. si tampoco, al contenido principal (`<main>`), destino de «Saltar al contenido».
 *
 * El foco se mueve ANTES de quitar el aviso, mientras su botón aún existe: así
 * nunca pasa por `<body>` (el lector de pantalla no anuncia un vacío).
 */
export function ToastViewport({ toasts, onDismiss, inDialog = false }: ToastViewportProps) {
  const polite = toasts.filter((t) => t.type !== 'error');
  const assertive = toasts.filter((t) => t.type === 'error');
  /** Orden de los avisos en el documento (el de tabulación): primero los corteses, luego los de error. */
  const ordered = [...polite, ...assertive];

  const regionRef = useRef<HTMLElement>(null);
  useHeightCssVariable(inDialog ? NO_ELEMENT : regionRef, TOASTS_HEIGHT_VARIABLE);
  const closeButtons = useRef(new Map<number, HTMLButtonElement>());
  /** Último elemento de FUERA de la zona que tenía el foco antes de entrar en ella. */
  const focusBeforeToasts = useRef<HTMLElement | null>(null);

  const rememberFocusOrigin = (event: FocusEvent<HTMLElement>) => {
    const from = event.relatedTarget;
    if (from instanceof HTMLElement && !regionRef.current?.contains(from)) focusBeforeToasts.current = from;
  };

  /** `ref` de cada botón «Cerrar aviso», por id de aviso (se borra al desmontarse). */
  const closeRefFor = useCallback(
    (id: number) => (button: HTMLButtonElement | null) => {
      if (button) closeButtons.current.set(id, button);
      else closeButtons.current.delete(id);
    },
    [],
  );

  // `dismiss` lee el orden de aquí para ser estable: si cambiara en cada render, cada aviso reiniciaría su
  // temporizador (el efecto de `Toast` depende de `onDismiss`).
  const orderedRef = useRef(ordered);
  useLayoutEffect(() => {
    orderedRef.current = ordered;
  });

  const dismiss = useCallback(
    (id: number) => {
      // El único elemento enfocable de un aviso es su botón «Cerrar aviso».
      if (document.activeElement === closeButtons.current.get(id)) {
        const stack = orderedRef.current;
        const index = stack.findIndex((t) => t.id === id);
        const neighbour = stack[index + 1] ?? stack[index - 1];
        const target =
          (neighbour && closeButtons.current.get(neighbour.id)) ||
          (canTakeFocus(focusBeforeToasts.current) ? focusBeforeToasts.current : null) ||
          document.getElementById(MAIN_CONTENT_ID);
        target?.focus({ preventScroll: true });
      }
      onDismiss(id);
    },
    [onDismiss],
  );

  return (
    <section
      ref={regionRef}
      aria-label="Avisos"
      onFocus={rememberFocusOrigin}
      className={`pointer-events-none fixed inset-x-4 z-[200] flex flex-col items-stretch gap-2 sm:top-auto sm:bottom-4 sm:left-auto sm:w-96 ${
        inDialog ? PLACEMENT_IN_DIALOG : PLACEMENT_ON_PAGE
      }`}
    >
      <div role="status" aria-live="polite" className="flex flex-col gap-2">
        {polite.map((t) => (
          <Toast key={t.id} toast={t} onDismiss={dismiss} closeRef={closeRefFor(t.id)} />
        ))}
      </div>
      <div role="alert" aria-live="assertive" className="flex flex-col gap-2">
        {assertive.map((t) => (
          <Toast key={t.id} toast={t} onDismiss={dismiss} closeRef={closeRefFor(t.id)} />
        ))}
      </div>
    </section>
  );
}
