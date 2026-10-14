import { useLayoutEffect, useRef } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';
import { MAIN_CONTENT_ID } from './SkipLink';

/**
 * Lo que haría el navegador al cargar una página nueva y una SPA no hace sola:
 * al cambiar de página, **sube el scroll** y **lleva el foco al contenido**.
 *
 * **El problema.** Con `BrowserRouter` cambiar de ruta solo cambia lo que se
 * pinta: la ventana se queda con el scroll de la página anterior y el foco, en
 * `<body>` (si el enlace pulsado desaparece) o en la barra. Quien bajaba por
 * `/peliculas` y pulsaba una tarjeta entraba, en el móvil, a mitad de la
 * película, sin ver su título; y un lector de pantalla no anunciaba nada, así
 * que no sabía que estaba en otra página.
 *
 * **Scroll.** `window.scrollTo(0, 0)` en cada cambio de `pathname`, salvo en
 * las navegaciones `POP` («Atrás»/«Adelante»): ahí el navegador restaura la
 * posición que tenía esa entrada del historial y subir lo estropearía. Los
 * cambios solo de la búsqueda (`?genero=` de `/peliculas`, `?temporada=` de
 * una serie, `?page=` del panel) no cuentan: son la misma página con otro
 * filtro, y saltar arriba al elegir uno sería un estorbo.
 *
 * **Foco: al `<main id="contenido">` y no al `<h1>`.** El `<main>` es el mismo
 * elemento mientras dura el diseño (`AppShell`, `AuthLayout`) y ya es enfocable
 * por código (`tabIndex={-1}`, destino de «Saltar al contenido»). El `<h1>`, en
 * cambio, no existe todavía o es otro mientras la página carga (las de detalle
 * pintan un `<h1>` oculto «Película» que luego sustituye el título): el foco
 * puesto en él se perdería de nuevo en `<body>` al llegar los datos. Desde el
 * `<main>`, el lector de pantalla anuncia que se está en el contenido principal
 * y el siguiente Tab recorre la página nueva desde el principio, sin pasar
 * otra vez por toda la barra. No se ve ningún anillo: el `<main>` lleva
 * `outline-hidden` porque no es un control (el foco no lo ha puesto el usuario).
 * Se usa `preventScroll` para que enfocar no mueva la página (ya lo decide el
 * apartado anterior; en `POP`, el navegador).
 *
 * **Sin pisar el foco que ya gestionan las páginas.** Si, tras el cambio, el
 * foco ya está DENTRO del `<main>` (lo ha puesto la propia página al montarse,
 * o es un control que sigue ahí, como las pestañas del panel), se respeta. Los
 * diálogos y los formularios (foco al primer error) actúan después, por su
 * cuenta, y nada de aquí los toca: esto solo corre al cambiar de página.
 *
 * Nada al montarse (la primera página de la visita): el navegador ya empieza
 * arriba y el primer Tab debe seguir siendo «Saltar al contenido».
 *
 * `useLayoutEffect` y no `useEffect`: corre antes de pintar, así que no llega a
 * verse un fotograma de la página nueva con el scroll de la anterior. Va en
 * `App`, junto a las rutas (no dentro de `AppShell`), para cubrir también el
 * paso entre «Iniciar sesión» y «Crear cuenta», y la llegada al login.
 */
export function RouteChangeFocus() {
  const { pathname } = useLocation();
  const navigationType = useNavigationType();
  const previousPathname = useRef(pathname);

  useLayoutEffect(() => {
    if (previousPathname.current === pathname) return;
    previousPathname.current = pathname;

    if (navigationType !== 'POP') window.scrollTo(0, 0);

    const main = document.getElementById(MAIN_CONTENT_ID);
    if (!main) return;
    const active = document.activeElement;
    const pageAlreadyFocusedSomething = active !== null && active !== main && main.contains(active);
    if (!pageAlreadyFocusedSomething) main.focus({ preventScroll: true });
  }, [pathname, navigationType]);

  return null;
}
