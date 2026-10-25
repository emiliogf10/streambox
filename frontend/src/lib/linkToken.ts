/**
 * Lectura del token de los enlaces que el backend manda por correo: la
 * confirmación del correo (`/verificar-email#token=…`) y el restablecimiento de
 * la contraseña (`/restablecer-contrasena#token=…`). Los dos tokens tienen el
 * mismo formato (32 bytes aleatorios en Base64URL sin relleno, 43 caracteres) y
 * viajan igual, así que se leen con la misma función.
 */

/**
 * Longitud máxima de un token que merece la pena enviar. Los del backend tienen
 * 43 caracteres; el margen es por si cambia el formato (un token más largo en el
 * futuro no debe dejar los enlaces sin funcionar hasta que se actualice esto).
 */
const MAX_TOKEN_LENGTH = 512;

/** Caracteres de Base64URL sin relleno, el formato de los tokens del backend. */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]+$/;

/**
 * Lee el token del fragmento de un enlace (`#token=…`).
 *
 * El token viaja en el **fragmento** (lo que va tras `#`) porque el navegador
 * nunca lo envía al servidor: no queda en los logs de nginx ni en la cabecera
 * `Referer` de ninguna petición.
 *
 * Un valor con caracteres que un token nuestro no puede tener (un enlace que el
 * cliente de correo ha partido o «adornado») se trata como ausente: la página
 * enseña directamente «enlace no válido» sin gastar una petición.
 *
 * @param hash `location.hash`, con o sin la `#` inicial
 * @returns el token, o `null` si no hay uno con formato válido
 */
export function readLinkToken(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token')?.trim() ?? '';
  return token.length > 0 && token.length <= MAX_TOKEN_LENGTH && TOKEN_PATTERN.test(token) ? token : null;
}
