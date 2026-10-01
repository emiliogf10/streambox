import { useEffect } from 'react';

/** Nombre de la aplicación, que acompaña al título de cada página. */
const APP_NAME = 'StreamBox';

/**
 * Pone el título de la pestaña ("Mi lista — StreamBox").
 *
 * En una SPA el `<title>` no cambia al navegar si nadie lo hace: los lectores
 * de pantalla anuncian el título al cambiar de página y es lo primero que ve
 * quien tiene muchas pestañas (WCAG 2.4.2, "Página con título").
 *
 * @param title título de la página actual
 */
export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = `${title} — ${APP_NAME}`;
  }, [title]);
}
