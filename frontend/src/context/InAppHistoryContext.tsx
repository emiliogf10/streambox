import { createContext, useCallback, useContext, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigationType } from 'react-router-dom';

/**
 * Pregunta «¿hay una pantalla de StreamBox detrás de esta entrada del historial?»
 * para la entrada con esa `location.key`.
 *
 * Sin {@link InAppHistoryProvider} por encima responde siempre `false`: quien
 * la usa irá a su página de respaldo, que es la respuesta segura (nunca saca al
 * usuario de la aplicación).
 */
type HasAppEntryBehind = (locationKey: string) => boolean;

const InAppHistoryContext = createContext<HasAppEntryBehind>(() => false);

/**
 * Lleva la cuenta de cuántas pantallas de StreamBox hay DETRÁS de cada entrada
 * del historial en esta visita, para que «Volver» pueda hacer `navigate(-1)`
 * solo cuando eso no saque al usuario de la aplicación.
 *
 * **El problema que resuelve.** `navigate(-1)` es el «Atrás» del navegador: si
 * la entrada actual es la primera de la visita (se pegó un enlace, se recargó),
 * lleva a la web de antes o a una pestaña en blanco. Mirar si
 * `location.key === 'default'` (la clave de la primera entrada) no basta: una
 * navegación con `replace` («Ver el siguiente» en el reproductor, el
 * `<Navigate replace>` de la bienvenida) da una clave NUEVA a una entrada que
 * sigue siendo la primera.
 *
 * **Cómo cuenta.** Por cada cambio de ruta, según cómo se ha llegado
 * (`useNavigationType`):
 * - `PUSH` (un enlace normal): una más que la entrada de la que se viene;
 * - `REPLACE`: las mismas que la entrada sustituida (ocupa su sitio);
 * - `POP` («Atrás»/«Adelante» o la carga inicial): lo que ya se sabía de esa
 *   entrada; si no se sabe nada (la carga inicial, o una entrada de antes de
 *   recargar la página), 0. **Ante la duda, 0**: lo peor que pasa es que
 *   «Volver» lleve a la página del título en vez de a la pantalla de antes; al
 *   revés, sacaría al usuario de StreamBox.
 *
 * **Por qué así y no con `window.history.state.idx`.** Ese índice lo guarda
 * `BrowserRouter` por dentro, pero no es parte de su API documentada (podría
 * cambiar en una versión) y no existe con `MemoryRouter`, el de los tests: con
 * él, el caso «hay pantalla anterior» no se podría probar. Esto solo usa
 * `useLocation` y `useNavigationType`, que funcionan igual con los dos.
 *
 * Va dentro del router (lo necesita) y por encima de las rutas: tiene que ver
 * TODAS las navegaciones de la visita, no solo las de una página.
 */
export function InAppHistoryProvider({ children }: { children: ReactNode }) {
  const location = useLocation();
  const navigationType = useNavigationType();
  /** Pantallas de StreamBox detrás de cada entrada conocida, por `location.key`. */
  const depthByKeyRef = useRef(new Map<string, number>());
  /** Clave de la última entrada contada: de ella «se viene» en un `PUSH` o un `REPLACE`. */
  const currentKeyRef = useRef<string | null>(null);

  useEffect(() => {
    // En modo estricto el efecto se ejecuta dos veces con la misma entrada: contarla dos veces sumaría una de más.
    if (currentKeyRef.current === location.key) return;
    const depthByKey = depthByKeyRef.current;
    const previous = currentKeyRef.current === null ? 0 : (depthByKey.get(currentKeyRef.current) ?? 0);
    let depth: number;
    if (navigationType === 'PUSH') depth = previous + 1;
    else if (navigationType === 'REPLACE') depth = previous;
    else depth = depthByKey.get(location.key) ?? 0;
    depthByKey.set(location.key, depth);
    currentKeyRef.current = location.key;
  }, [location.key, navigationType]);

  const hasAppEntryBehind = useCallback<HasAppEntryBehind>(
    (locationKey) => (depthByKeyRef.current.get(locationKey) ?? 0) > 0,
    [],
  );

  return <InAppHistoryContext.Provider value={hasAppEntryBehind}>{children}</InAppHistoryContext.Provider>;
}

/**
 * Función que dice si detrás de una entrada (por su `location.key`) hay otra
 * pantalla de StreamBox; ver {@link InAppHistoryProvider}. Se llama al pulsar
 * (no al pintar): así siempre ve la cuenta ya puesta al día.
 */
// oxlint-disable-next-line react/only-export-components -- patrón habitual: proveedor y hook comparten archivo
export function useHasAppEntryBehind(): HasAppEntryBehind {
  return useContext(InAppHistoryContext);
}
