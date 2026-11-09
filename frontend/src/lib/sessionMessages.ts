/**
 * Textos de los avisos de sesión que da la interfaz: el de una sesión que el
 * servidor ya no acepta ({@link SESSION_ENDED}) y los que da `AuthProvider`
 * cuando la sesión cambia en OTRA pestaña del mismo navegador (ver
 * `onSessionChangedElsewhere` en `AuthContext.tsx`).
 *
 * Viven aparte para que los tests los usen sin copiarlos, para que el mismo aviso
 * no quede escrito en dos sitios (`lib/api.ts` y `AuthContext.tsx`) y porque un
 * archivo de componentes no debe exportar constantes (rompe el *fast refresh* de Vite).
 *
 * Ninguno dice «tu sesión ha caducado»: muchas veces sería falso. Lo que pasó
 * puede ser que alguien cerró sesión o entró con otra cuenta en otra pestaña, o
 * que el titular cerró todas sus sesiones o cambió su contraseña.
 */

/**
 * Aviso cuando la API rechaza la sesión y ya no se puede renovar (401 de una ruta
 * protegida tras un refresh fallido, código `SESSION_EXPIRED`). Se muestra una
 * sola vez y la app lleva al login.
 *
 * Dice «ha terminado» y no «ha caducado» porque el servidor responde lo mismo en
 * dos casos que el navegador no puede distinguir: la sesión caducó de verdad, o
 * el titular la cerró desde otro dispositivo («Cerrar sesión en todos los
 * dispositivos», cambiar o restablecer la contraseña revocan los tokens al
 * momento). «Terminado» es verdad en los dos, para USER y para ADMIN.
 */
export const SESSION_ENDED = 'Tu sesión ha terminado. Inicia sesión de nuevo.';

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
 * pulsó «Cerrar sesión» o porque allí se descubrió que había terminado y se
 * cerró: en los dos casos se cerró allí). Esta pestaña vuelve al login.
 */
export const SESSION_CLOSED_ELSEWHERE = 'Se ha cerrado la sesión en otra pestaña. Inicia sesión de nuevo.';
