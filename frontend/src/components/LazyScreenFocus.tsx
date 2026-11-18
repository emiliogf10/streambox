import { useLayoutEffect } from 'react';
import type { ReactNode } from 'react';
import { MAIN_CONTENT_ID } from './SkipLink';

/**
 * `true` cuando la espera de una pantalla diferida desapareció con el foco
 * dentro del contenido (`<main id="contenido">`). Lo anota
 * {@link FallbackFocusHandoff} y lo consume {@link RestoreContentFocus}.
 *
 * Es estado del módulo y no de un componente porque quien lo anota (la espera)
 * y quien lo usa (la pantalla o el aviso de error) son dos montajes distintos
 * que nunca existen a la vez. Solo hay un foco en la página: basta con uno.
 */
let contentHadFocus = false;

/**
 * Envuelve la espera («Cargando...») de una pantalla que se carga bajo demanda
 * y, al desaparecer, anota si el foco estaba en el contenido.
 *
 * **El problema.** Al navegar a una pantalla diferida por primera vez, React
 * pinta su espera y `RouteChangeFocus` lleva el foco al `<main>` de esa espera.
 * Si la pantalla trae su PROPIO `<main>` (las de cuenta, con `AuthLayout`) o
 * el aviso de error trae el suyo, el de la espera se quita del DOM con el foco
 * dentro y el foco cae en `<body>`: el lector de pantalla no anuncia nada y el
 * siguiente Tab empieza desde arriba. Con el trozo ya descargado no pasa (no
 * hay espera), así que solo le ocurría a quien entraba por primera vez.
 *
 * La anotación se hace en la limpieza de un efecto de diseño, que React ejecuta
 * ANTES de quitar del DOM los elementos de la espera: el foco aún está ahí.
 */
export function FallbackFocusHandoff({ children }: { children: ReactNode }) {
  useLayoutEffect(
    () => () => {
      const main = document.getElementById(MAIN_CONTENT_ID);
      const active = document.activeElement;
      contentHadFocus = main !== null && active !== null && main.contains(active);
    },
    [],
  );
  return children;
}

/**
 * Se pinta junto a la pantalla diferida (o en su aviso de error) y, al
 * montarse, devuelve el foco al `<main id="contenido">` nuevo si la espera se
 * lo llevó consigo (ver {@link FallbackFocusHandoff}).
 *
 * Solo actúa si el foco se ha PERDIDO (está en `<body>`): si la pantalla ya ha
 * enfocado algo al montarse, o el `<main>` es el mismo (el panel, dentro de
 * `AppShell`), no se toca. Tampoco al entrar directamente con la dirección (la
 * espera no tenía el foco): ahí el primer Tab debe seguir siendo «Saltar al
 * contenido», como en cualquier otra página. `preventScroll`: el scroll ya lo
 * ha decidido `RouteChangeFocus`.
 */
export function RestoreContentFocus() {
  useLayoutEffect(() => {
    const pending = contentHadFocus;
    contentHadFocus = false;
    if (!pending) return;
    const active = document.activeElement;
    if (active !== null && active !== document.body) return;
    document.getElementById(MAIN_CONTENT_ID)?.focus({ preventScroll: true });
  }, []);
  return null;
}
