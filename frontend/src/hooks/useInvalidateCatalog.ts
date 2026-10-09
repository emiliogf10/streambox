import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { keysAffectedBy } from '../lib/queryKeys';
import type { CatalogChange } from '../lib/queryKeys';

/**
 * Devuelve la función que avisa a la caché de que el panel de administración ha
 * escrito en el catálogo (ver `keysAffectedBy` en `lib/queryKeys.ts`).
 *
 * `invalidateQueries` marca como anticuado todo lo afectado: lo que está en
 * pantalla se vuelve a pedir al momento y lo demás, la próxima vez que se abra.
 * Sin esto, tras editar una película la portada y `/peliculas` seguirían
 * enseñando la versión vieja hasta recargar la página.
 *
 * Se llama también cuando el servidor dice que algo ya no existía (404): es la
 * prueba de que la copia local estaba desfasada.
 *
 * La promesa se puede ignorar: quien acaba de guardar no tiene que esperar a
 * que se refresquen los listados (el `void` del llamador lo deja dicho).
 */
export function useInvalidateCatalog(): (change: CatalogChange) => Promise<void> {
  const queryClient = useQueryClient();
  return useCallback(
    async (change: CatalogChange) => {
      await Promise.all(keysAffectedBy(change).map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    },
    [queryClient],
  );
}
