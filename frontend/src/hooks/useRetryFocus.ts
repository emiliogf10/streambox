import { useCallback, useLayoutEffect, useRef } from 'react';
import type { RefCallback } from 'react';

/**
 * Elemento estable al que se llevó el foco tras el último «Reintentar» que
 * desapareció, mientras lo siga teniendo. Si el reintento vuelve a fallar, el
 * nuevo «Reintentar» de esa misma zona lo recoge (ver {@link useRetryFocus}).
 *
 * Es estado del módulo y no de un componente porque el aviso que se pulsó ya no
 * existe cuando aparece el siguiente: son dos montajes distintos de `ErrorState`.
 * Solo hay un foco en la página, así que basta con uno.
 */
let parkedFocus: HTMLElement | null = null;

/**
 * `true` si el foco "se ha perdido": no está en ningún elemento (cae en
 * `<body>`, que es lo que pasa cuando se quita del DOM el elemento enfocado).
 * Si está en cualquier otro sitio, es que el usuario (u otro código, como el
 * cambio de página) ya lo ha movido y no hay que tocarlo.
 */
function focusIsLost(): boolean {
  const active = document.activeElement;
  return active === null || active === document.body;
}

/**
 * Antecesores de `element` enfocables por código (`tabindex="-1"`), del más
 * cercano al más lejano. El último suele ser el `<main id="contenido">` de
 * `AppShell`, que siempre lo es (destino de «Saltar al contenido»).
 */
function focusableAncestors(element: HTMLElement): HTMLElement[] {
  const found: HTMLElement[] = [];
  let current = element.parentElement?.closest<HTMLElement>('[tabindex="-1"]') ?? null;
  while (current) {
    found.push(current);
    current = current.parentElement?.closest<HTMLElement>('[tabindex="-1"]') ?? null;
  }
  return found;
}

/**
 * Destino del foco: el elemento que da quien usa el hook (si sigue en la
 * página) o, si no, el antecesor enfocable más cercano que siga en ella.
 */
function resolveTarget(explicit: HTMLElement | null | undefined, ancestors: HTMLElement[]): HTMLElement | null {
  if (explicit?.isConnected) return explicit;
  return ancestors.find((element) => element.isConnected) ?? null;
}

/**
 * Deja el foco "aparcado" en `target` hasta que salga de él: en cuanto el
 * usuario lo mueva (o lo recoja el nuevo «Reintentar»), se olvida.
 */
function park(target: HTMLElement) {
  parkedFocus = target;
  target.addEventListener(
    'blur',
    () => {
      if (parkedFocus === target) parkedFocus = null;
    },
    { once: true },
  );
}

/** Lo que devuelve {@link useRetryFocus}: la `ref` y el `onClick` del botón «Reintentar». */
export interface RetryFocus {
  /** `ref` del botón «Reintentar» (callback estable). */
  retryRef: RefCallback<HTMLButtonElement>;
  /** `onClick` del botón: anota de dónde se pulsó y llama al reintento. */
  handleRetry: () => void;
}

/**
 * Que el foco no se pierda al pulsar «Reintentar» (WCAG 2.4.3, orden del foco).
 *
 * **El problema.** Al reintentar, el aviso de error se sustituye por la silueta
 * de carga: el botón pulsado desaparece del DOM y el foco cae en `<body>`. Quien
 * usa teclado o lector de pantalla pierde su sitio: el siguiente Tab empieza
 * desde el principio de la página (o el lector no anuncia nada).
 *
 * **La solución, en dos pasos:**
 * 1. Cuando el botón desaparece tras pulsarlo, el foco va a un **elemento
 *    estable de la misma zona**: el que indique quien usa el hook (`getTarget`,
 *    p. ej. el encabezado de la sección) o, si no, el antecesor más cercano
 *    enfocable por código (`tabIndex={-1}`) que siga en la página; en último
 *    término, el `<main>`. Ese elemento sigue ahí mientras carga y cuando llega
 *    el contenido, así que el siguiente Tab continúa desde esa zona.
 * 2. Si el reintento **vuelve a fallar**, el nuevo «Reintentar» de esa misma
 *    zona recupera el foco: el usuario puede volver a pulsarlo sin buscarlo.
 *
 * **Sin robar el foco.** Los dos pasos solo actúan si el foco sigue perdido
 * (en `<body>`) o aparcado en ese elemento estable: si el usuario ya se ha ido
 * a otro sitio, o el cambio de página lo ha llevado al contenido, no se toca.
 *
 * **Por qué con una `ref` de callback y no con efectos.** React llama a la
 * limpieza de la `ref` justo cuando el botón sale del DOM, sea porque se
 * desmonta todo el aviso (`ErrorState`) o solo el botón (el de los géneros de
 * un formulario). En ese momento el botón aún está en el documento, así que la
 * comprobación se aplaza a una microtarea, que corre en cuanto React termina de
 * aplicar los cambios y antes de que el usuario pueda hacer nada. La `ref` es
 * estable (`useCallback` sin dependencias): una nueva en cada render haría que
 * React la soltara y la volviera a poner en cada pintado.
 *
 * El foco se mueve con `preventScroll`: la zona es la misma que el usuario
 * estaba viendo y la página no debe saltar.
 *
 * @param onRetry el reintento (vuelve a pedir los datos)
 * @param getTarget opcional: el elemento estable de la zona; debe ser enfocable
 *   (`tabIndex={-1}` si no es un control) y seguir en la página mientras carga
 */
export function useRetryFocus(onRetry: () => void, getTarget?: () => HTMLElement | null): RetryFocus {
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  /** Antecesores enfocables anotados al pulsar (`null` = no se ha pulsado). */
  const pressedFromRef = useRef<HTMLElement[] | null>(null);
  const getTargetRef = useRef(getTarget);

  // La `ref` de callback es estable, así que lee el `getTarget` más reciente de aquí.
  useLayoutEffect(() => {
    getTargetRef.current = getTarget;
  });

  const retryRef = useCallback<RefCallback<HTMLButtonElement>>((button) => {
    if (!button) return;
    buttonRef.current = button;

    // Paso 2: el reintento ha vuelto a fallar y este es el nuevo «Reintentar» de la misma zona.
    const parked = parkedFocus;
    if (
      parked &&
      (document.activeElement === parked || focusIsLost()) &&
      resolveTarget(getTargetRef.current?.(), focusableAncestors(button)) === parked
    ) {
      parkedFocus = null;
      button.focus();
    }

    return () => {
      buttonRef.current = null;
      const pressedFrom = pressedFromRef.current;
      pressedFromRef.current = null;
      if (!pressedFrom) return;
      // Paso 1: el botón pulsado ha desaparecido. Se mira cuando React ya lo ha quitado del documento.
      queueMicrotask(() => {
        if (button.isConnected || !focusIsLost()) return;
        const target = resolveTarget(getTargetRef.current?.(), pressedFrom);
        if (!target) return;
        target.focus({ preventScroll: true });
        if (document.activeElement === target) park(target);
      });
    };
  }, []);

  const handleRetry = useCallback(() => {
    const button = buttonRef.current;
    // Los antecesores se anotan ahora: cuando el botón desaparezca ya no se podrán recorrer.
    pressedFromRef.current = button ? focusableAncestors(button) : [];
    onRetry();
  }, [onRetry]);

  return { retryRef, handleRetry };
}
