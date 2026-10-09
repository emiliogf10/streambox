/**
 * Cierre de sesión EN EL SERVIDOR (`POST /api/auth/logout`).
 *
 * **Por qué el cierre es del servidor y no del cliente.** La sesión vive en la
 * cookie HttpOnly `streambox_token`, que JavaScript no puede leer ni borrar: solo
 * la borra el `Set-Cookie` de la respuesta del logout. Si la interfaz dijera
 * «sesión cerrada» sin esa respuesta (red caída, 502, backend reiniciándose), la
 * cookie seguiría valiendo y al recargar `GET /api/users/me` devolvería la sesión:
 * en un equipo compartido, la siguiente persona entraría en la cuenta. Por eso
 * `AuthContext` solo cierra la sesión en pantalla cuando {@link logoutOnServer}
 * confirma que el servidor la ha cerrado. Esta petición es además la que revoca
 * el *refresh token* (`streambox_refresh`): sin ella, la sesión podría renovarse
 * durante días; otro motivo para que mande el servidor. `apiFetch` la pone en fila
 * con los refresh (también los de otras pestañas): así una renovación en vuelo no
 * puede devolver al navegador cookies válidas justo después del cierre.
 *
 * Vive aquí, y no en `AuthContext.tsx`, para poder probar la política de
 * reintento y los textos sin montar el proveedor, y porque un archivo de
 * componentes no debe exportar constantes (rompe el *fast refresh*).
 */
import { ApiError, apiFetch } from './api';

/**
 * Espera antes del ÚNICO reintento automático. Corta a propósito: un corte de
 * red de un instante o un reinicio del backend se salvan solos, y quien ha pulsado
 * «Cerrar sesión» no se queda mucho rato esperando.
 */
export const LOGOUT_RETRY_DELAY_MS = 1000;

/**
 * Aviso si no se llegó al servidor: sin respuesta (red caída) o un 502/503/504
 * del proxy (Vite en desarrollo, nginx en Docker), que es lo que se recibe cuando
 * el backend está parado o reiniciándose. Dice el motivo y que la sesión SIGUE abierta.
 */
export const LOGOUT_FAILED_NETWORK =
  'No se ha podido cerrar la sesión: no hay conexión con el servidor. Tu sesión sigue abierta; inténtalo de nuevo.';

/** Aviso si el servidor respondió con otro 5xx (sí se llegó a él y falló). */
export const LOGOUT_FAILED_SERVER =
  'No se ha podido cerrar la sesión: el servidor ha tenido un problema. Tu sesión sigue abierta; inténtalo de nuevo.';

/** Aviso para cualquier otro rechazo (403, 429...): no se inventa un motivo que no se conoce. */
export const LOGOUT_FAILED_OTHER = 'No se ha podido cerrar la sesión. Tu sesión sigue abierta; inténtalo de nuevo.';

/** Resultado de {@link logoutOnServer}. */
export type LogoutResult = { closed: true } | { closed: false; message: string };

/**
 * ¿Significa este error que la sesión ya no existe en el servidor?
 * - 2xx: la cerró. Incluye un 2xx con cuerpo ilegible, que `apiFetch` convierte
 *   en `INVALID_RESPONSE`: el servidor contestó con éxito y el `Set-Cookie` ya llegó.
 * - 401: la cookie ya no era válida (caducada o revocada); no hay nada que cerrar.
 */
function sessionIsGone(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || (error.status >= 200 && error.status < 300));
}

/**
 * ¿Es un fallo pasajero que merece el reintento? Solo «sin respuesta» (status 0)
 * y 5xx. Un 403 o un 429 no van a cambiar en un segundo: reintentar solo retrasaría el aviso.
 */
function isTransientFailure(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}

/**
 * Códigos con los que un proxy dice «no he podido hablar con el backend»: 502
 * (Bad Gateway), 503 (Service Unavailable) y 504 (Gateway Timeout). Con el
 * backend parado, detrás de Vite o nginx no hay «red caída» (status 0) sino uno
 * de estos; decir «el servidor ha tenido un problema» haría pensar en un fallo
 * al cerrar la sesión cuando en realidad no se llegó a él.
 */
const UNREACHABLE_STATUSES = new Set([502, 503, 504]);

/** Texto del aviso según por qué falló (por `status`, nunca por el texto del mensaje). */
function failureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return LOGOUT_FAILED_OTHER;
  if (error.status === 0 || UNREACHABLE_STATUSES.has(error.status)) return LOGOUT_FAILED_NETWORK;
  if (error.status >= 500) return LOGOUT_FAILED_SERVER;
  return LOGOUT_FAILED_OTHER;
}

/** Promesa que se resuelve pasados `ms` milisegundos. */
function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Pide al servidor que cierre la sesión, con UN reintento tras
 * {@link LOGOUT_RETRY_DELAY_MS} si el primer fallo es pasajero (red o 5xx).
 *
 * Va como `public`: un 401 aquí no debe disparar el cierre por «sesión caducada»
 * de `apiFetch` (con su aviso) ni un refresh; se trata como «ya estaba cerrada».
 *
 * Nunca lanza: devuelve si la sesión quedó cerrada y, si no, el aviso que hay que mostrar.
 *
 * @param canRetry se consulta antes y después de la espera; si devuelve `false`
 *   (la sesión ya cambió por otro motivo, p. ej. un 401 la cerró), no se reintenta:
 *   sería una petición inútil cuyo resultado nadie va a usar
 */
export async function logoutOnServer(canRetry: () => boolean = () => true): Promise<LogoutResult> {
  for (let attempt = 1; ; attempt += 1) {
    try {
      await apiFetch('/auth/logout', { method: 'POST', public: true });
      return { closed: true };
    } catch (error) {
      if (sessionIsGone(error)) return { closed: true };
      const failure: LogoutResult = { closed: false, message: failureMessage(error) };
      if (attempt >= 2 || !isTransientFailure(error) || !canRetry()) return failure;
      await wait(LOGOUT_RETRY_DELAY_MS);
      if (!canRetry()) return failure;
    }
  }
}
