import { useLayoutEffect } from 'react';
import type { RefObject } from 'react';

/**
 * Publica la altura real de un elemento como variable CSS en `<html>` y la
 * mantiene al día mientras el elemento esté montado.
 *
 * **Para qué.** CSS no puede leer la altura de OTRO elemento, y hay reglas que
 * la necesitan: `index.css` usa `--navbar-height` para que, al desplazar la
 * página hasta el elemento enfocado, el navegador lo deje por debajo de la
 * barra superior pegada arriba (WCAG 2.2 · 2.4.11, el foco no puede quedar
 * tapado). La barra no tiene una altura única: 57 px en una fila desde 768 px,
 * 165 px en tres filas por debajo, y más aún si la navegación se parte en dos
 * líneas (320 px, el ancho de referencia de WCAG 1.4.10, o con el texto
 * ampliado). Copiar esos números de las clases de Tailwind a la hoja de estilos
 * se desfasaría con el primer cambio en la barra; medirla no.
 *
 * **Cómo.**
 * - Un `ResizeObserver` sobre la caja de borde (incluye relleno y borde) avisa
 *   de cada cambio de alto: otro ancho de ventana, zoom, fuentes o el enlace
 *   «Administrar» que aparece al confirmarse el rol.
 * - Se mide en `useLayoutEffect`, antes de que el navegador pinte, para que la
 *   variable exista desde el primer fotograma.
 * - Al desmontar se borra: en las pantallas sin barra (login, registro) la
 *   variable no existe y la regla de CSS usa su valor por defecto.
 *
 * El estilo sigue viviendo en CSS: JavaScript solo aporta un dato medido (no es
 * un `style={{}}` que decida el aspecto de un componente).
 *
 * @param ref elemento que se mide
 * @param name nombre de la variable CSS, con los dos guiones (p. ej. `--navbar-height`)
 */
export function useHeightCssVariable(ref: RefObject<HTMLElement | null>, name: `--${string}`): void {
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const root = document.documentElement;
    // Hacia arriba: con alturas fraccionarias (zoom), redondear hacia abajo dejaría
    // el borde superior del elemento enfocado un píxel por debajo de la barra.
    const publish = () => root.style.setProperty(name, `${Math.ceil(element.getBoundingClientRect().height)}px`);

    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(element, { box: 'border-box' });
    return () => {
      observer.disconnect();
      root.style.removeProperty(name);
    };
  }, [ref, name]);
}
