import { useCallback, useSyncExternalStore } from 'react';

/**
 * Indica si la ventana cumple ahora una consulta de medios
 * (`'(min-width: 48rem)'`...) y vuelve a pintar el componente cuando cambia.
 *
 * **Cuándo usarlo y cuándo no.** Casi siempre basta el CSS (las variantes `md:`
 * de Tailwind). Este gancho es para lo que el CSS no puede decidir: si un
 * elemento EXISTE. Una `<img>` oculta con `display: none` se descarga igual, así
 * que, para no bajar una imagen que no se va a ver (el póster del banner cuando
 * en escritorio hay imagen de fondo), hay que no montarla.
 *
 * Usa `useSyncExternalStore`, la forma que da React de leer un valor externo
 * que cambia: el primer pintado ya sale con el valor correcto (no hay un
 * primer pintado «de móvil» que luego salte) y no hay `useEffect` que
 * sincronice a mano. Sin `matchMedia` (un entorno sin ventana), `false`.
 *
 * @param query consulta de medios, con la misma sintaxis que en CSS
 * @returns `true` si la consulta se cumple ahora
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window === 'undefined' || !window.matchMedia) return () => {};
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    [query],
  );
  const getSnapshot = () => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query).matches : false);
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}

/**
 * Consulta del punto de corte `md` de Tailwind (48rem = 768 px con la letra por
 * defecto). Se escribe en `rem`, como lo genera Tailwind, para que coincida
 * exactamente con las clases `md:` aunque el usuario agrande la letra.
 */
export const MEDIUM_SCREEN_QUERY = '(min-width: 48rem)';
