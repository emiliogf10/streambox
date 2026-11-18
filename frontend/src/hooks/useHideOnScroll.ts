import { useEffect, useState } from 'react';
import type { RefObject } from 'react';

/**
 * Dice si una barra pegada arriba debe apartarse porque el usuario está
 * BAJANDO por la página, y vuelve a `false` en cuanto sube (revisión de
 * 2026-11, D-1: en móvil la barra ocupaba el 20 % de la pantalla siempre).
 *
 * **Cuándo se aparta.** Solo al desplazarse hacia abajo y ya por debajo de la
 * altura de la propia barra (cerca del principio no parpadea). Cualquier
 * desplazamiento hacia arriba la devuelve: está «a un gesto». El valor se
 * recorta a los límites de la página porque el rebote elástico de iOS en los
 * extremos cambiaría el sentido sin que el usuario haya hecho nada.
 *
 * **Por qué JavaScript y no una consulta de contenedor `scroll-state`.** La
 * receta moderna (`container-type: scroll-state` + `@container
 * scroll-state(scrolled: bottom)`) solo existe hoy en Chrome y Edge; en Safari
 * de iOS, el móvil al que más le importa, la barra no se ocultaría nunca. Con
 * un solo camino en JavaScript el comportamiento es el mismo en todos los
 * navegadores y se puede probar en Vitest. Lo que decide el ASPECTO sigue en CSS
 * (`Navbar`): este hook solo da un dato.
 *
 * El oyente es pasivo (no retrasa el desplazamiento) y solo cambia el estado
 * cuando cambia el sentido, así que no provoca un renderizado por evento.
 *
 * @param barRef la barra (se usa su altura como umbral)
 * @param enabled si es `false` no escucha nada y devuelve siempre `false`
 * @returns `true` mientras la barra deba estar apartada
 */
export function useHideOnScroll(barRef: RefObject<HTMLElement | null>, enabled = true): boolean {
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    let lastY = window.scrollY;
    const handleScroll = () => {
      const maxY = document.documentElement.scrollHeight - window.innerHeight;
      const y = Math.max(0, Math.min(window.scrollY, maxY));
      if (y === lastY) return;
      const barHeight = barRef.current?.offsetHeight ?? 0;
      setHidden(y > lastY && y > barHeight);
      lastY = y;
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, [barRef, enabled]);

  return enabled && hidden;
}
