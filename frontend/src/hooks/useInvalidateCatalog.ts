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
 * **Por qué se cancela antes de invalidar.** Una lectura en vuelo salió antes de
 * que la escritura terminara y puede traer los datos de antes. `invalidateQueries`
 * solo la corta si la entrada ya tiene datos (su `cancelRefetch` no se aplica a
 * una primera carga, y no hay opción que cambie eso): en la primera carga de una
 * clave reutilizaría la promesa en curso y la copia vieja quedaría como buena
 * (p. ej. la fila de una serie recién borrada, hasta el siguiente refresco).
 * `cancelQueries` la descarta en todos los casos, y deja cada entrada como estaba
 * antes de esa lectura, sin perder lo que se ve en pantalla. Una entrada sin
 * datos queda un instante en reposo y sin petición, pero `invalidateQueries` la
 * vuelve a pedir enseguida si está en pantalla; si no lo está, se pedirá al
 * abrirla. Tampoco se usa `resetQueries`: vaciaría también lo que ya está en
 * pantalla y todos los listados volverían al esqueleto de carga.
 *
 * La promesa se puede ignorar: quien acaba de guardar no tiene que esperar a
 * que se refresquen los listados (el `void` del llamador lo deja dicho).
 */
export function useInvalidateCatalog(): (change: CatalogChange) => Promise<void> {
  const queryClient = useQueryClient();
  return useCallback(
    async (change: CatalogChange) => {
      const keys = keysAffectedBy(change);
      await Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
      await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
    },
    [queryClient],
  );
}
