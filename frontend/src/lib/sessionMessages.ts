/**
 * Textos de los avisos que da `AuthProvider` cuando la sesión cambia en OTRA
 * pestaña del mismo navegador (ver `onSessionChangedElsewhere` en `AuthContext.tsx`).
 *
 * Viven aparte para que los tests los usen sin copiarlos y porque un archivo de
 * componentes no debe exportar constantes (rompe el *fast refresh* de Vite).
 *
 * Ninguno dice «tu sesión ha caducado»: para quien mira esta pestaña sería falso
 * cuando lo que pasó es que alguien cerró sesión o entró con otra cuenta en otra.
 */

/**
 * Aviso cuando, tras un cambio hecho en otra pestaña, esta comprueba que ahora
 * hay OTRA cuenta dentro (o que antes no había ninguna). El nombre sale de la
 * respuesta del servidor (`GET /users/me`), nunca del mensaje entre pestañas, que
 * no lleva datos. Si la cuenta es la misma no se avisa: no ha cambiado nada.
 *
 * @param username nombre del usuario que devuelve el servidor
 */
export const SESSION_SWITCHED_ELSEWHERE = (username: string): string =>
  `Se ha iniciado sesión en otra pestaña como «${username}».`;

/**
 * Aviso cuando otra pestaña cierra la sesión que esta mostraba (porque alguien
 * pulsó «Cerrar sesión» o porque allí se descubrió que había caducado y se
 * cerró: en los dos casos se cerró allí). Esta pestaña vuelve al login.
 */
export const SESSION_CLOSED_ELSEWHERE = 'Se ha cerrado la sesión en otra pestaña. Inicia sesión de nuevo.';
