/** `id` del `<main>` de cada pantalla: destino del enlace "Saltar al contenido". */
export const MAIN_CONTENT_ID = 'contenido';

/**
 * Enlace "Saltar al contenido": el PRIMER elemento enfocable de la página.
 *
 * Quien navega con teclado o lector de pantalla tendría que recorrer el logo,
 * los enlaces y el buscador en cada página antes de llegar al contenido
 * (WCAG 2.4.1, "Evitar bloques"). Está oculto visualmente (`sr-only`) y se hace
 * visible, arriba a la izquierda, al recibir el foco.
 *
 * Se gestiona con `onClick` en lugar de dejar que el navegador siga el `#`
 * del enlace: en una SPA eso cambiaría la URL (`/#contenido`) y el enrutador
 * podría interferir. Se mueve el foco al `<main>` (que lleva `tabIndex={-1}`
 * para poder recibirlo) y el navegador lo desplaza a la vista.
 */
export function SkipLink() {
  return (
    <a
      href={`#${MAIN_CONTENT_ID}`}
      onClick={(event) => {
        event.preventDefault();
        document.getElementById(MAIN_CONTENT_ID)?.focus();
      }}
      className="focus-ring sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[300] focus:rounded-lg focus:bg-accent focus:px-4 focus:py-3 focus:text-sm focus:font-bold focus:text-black"
    >
      Saltar al contenido
    </a>
  );
}
