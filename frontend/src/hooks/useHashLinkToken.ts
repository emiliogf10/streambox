import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { readLinkToken } from '../lib/linkToken';

/** Enlace leído por {@link useHashLinkToken}. */
export interface HashLink {
  /** Token del último enlace, o `null` si no traía uno con formato válido. */
  token: string | null;
  /**
   * Cuántos enlaces se han abierto con la página montada (0 = el de la carga).
   * Sirve de `key` del componente que usa el token: cada enlace nuevo lo monta
   * de cero, sin el estado (errores, cuentas atrás, respuestas tardías) del anterior.
   */
  id: number;
}

/**
 * Token de un enlace de los correos (`…#token=…`), común a `/verificar-email` y
 * `/restablecer-contrasena`.
 *
 * **El token, en el fragmento y fuera de la barra de direcciones.** Se lee de
 * `location.hash` y enseguida se quita de la URL (`navigate(..., { replace:
 * true })`, que en el navegador es un `history.replaceState`): así no queda en
 * el historial ni a la vista de quien mire la pantalla. Se usa el enrutador y no
 * `window.history` directamente para que React Router sepa que la URL ha
 * cambiado. El fragmento nunca viaja al servidor, así que tampoco acaba en los
 * logs ni en el `Referer`.
 *
 * **Un enlace nuevo en la misma pestaña cuenta como un enlace nuevo.** Si el
 * usuario pega otro `…#token=…` con la página ya abierta (lo normal tras pedir
 * uno desde «enlace no válido»), el navegador no recarga: solo cambia el
 * fragmento (navegación dentro del mismo documento, que React Router recibe
 * como un `popstate`). Por eso el fragmento se vigila en cada render y no solo
 * al montar: cada fragmento no vacío distinto del anterior es un enlace nuevo
 * (otro `id`). Quitar el fragmento de la URL también lo cambia (a vacío), y eso
 * **no** es un enlace nuevo: el token ya leído se conserva.
 *
 * @returns el token del último enlace y su número de orden (ver {@link HashLink})
 */
export function useHashLinkToken(): HashLink {
  const location = useLocation();
  const navigate = useNavigate();
  // `hash`: último fragmento visto; `token`: el del último enlace; `id`: cuántos enlaces van.
  const [link, setLink] = useState(() => ({ hash: location.hash, token: readLinkToken(location.hash), id: 0 }));

  // Ajuste durante el render (patrón recomendado por React para derivar estado
  // de un cambio de props/contexto): sin un efecto intermedio, el formulario
  // del enlace anterior no llega a pintarse con el token nuevo.
  if (location.hash !== link.hash) {
    setLink(
      location.hash
        ? { hash: location.hash, token: readLinkToken(location.hash), id: link.id + 1 }
        : { ...link, hash: '' },
    );
  }

  // Quita el `#token=…` de la barra de direcciones (y del historial) en cuanto se ha leído.
  useEffect(() => {
    if (location.hash) {
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: location.state });
    }
  }, [location, navigate]);

  return { token: link.token, id: link.id };
}
