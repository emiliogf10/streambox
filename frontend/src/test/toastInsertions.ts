/**
 * Vigila CUÁNTAS veces y DÓNDE se inserta en el documento un aviso con un texto dado.
 *
 * Sirve para probar que un aviso lanzado desde un diálogo modal nace una sola
 * vez y en la página (ver `useToastAfterDialog`). Mirar el resultado final no
 * basta: un aviso que nace dentro del diálogo y se vuelve a crear en la página un
 * instante después acaba en el mismo sitio, pero un lector de pantalla puede
 * haberlo leído dos veces. Un `MutationObserver` ve cada inserción, también las
 * que solo duran un render.
 *
 * **Cómo sabe dónde.** Al empezar a vigilar se anota la zona de avisos de la
 * PÁGINA (la región «Avisos» que no está dentro de un `<dialog>`; hay que llamar
 * antes de abrir el diálogo). Cada inserción se atribuye a la zona que la
 * contiene: la de la página o cualquier otra (la de un diálogo). No se mira
 * `closest('dialog')` al recibir la mutación porque, para entonces, la zona del
 * diálogo puede haberse quitado ya del documento.
 *
 * Uso: `const toasts = watchToastInsertions('Borrado.')` antes de la acción y,
 * al final, `expect(toasts.places()).toEqual(['page'])`; `toasts.stop()` al acabar.
 *
 * @param text texto (o parte) del aviso
 */
export function watchToastInsertions(text: string) {
  const regions = [...document.querySelectorAll('section[aria-label="Avisos"]')];
  const pageRegion = regions.find((region) => !region.closest('dialog'));
  if (!pageRegion) throw new Error('No hay zona de avisos en la página: ¿falta ToastProvider?');

  const places: Array<'page' | 'dialog'> = [];
  const collect = (records: MutationRecord[]) => {
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (!(node instanceof HTMLElement) || !node.textContent?.includes(text)) continue;
        const region = node.closest('section[aria-label="Avisos"]');
        places.push(region === pageRegion ? 'page' : 'dialog');
      }
    }
  };
  const observer = new MutationObserver(collect);
  observer.observe(document.body, { childList: true, subtree: true });
  return {
    /** Dónde se ha insertado cada vez, en orden (incluye las mutaciones aún no entregadas). */
    places: () => {
      collect(observer.takeRecords());
      return [...places];
    },
    stop: () => observer.disconnect(),
  };
}
