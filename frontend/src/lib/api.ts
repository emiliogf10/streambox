/**
 * Cliente HTTP central del frontend.
 *
 * Toda llamada a la API pasa por {@link apiFetch}. Así el manejo de la sesión
 * (cookie HttpOnly, cabecera anti-CSRF, 401, renovación con el refresh token) y
 * de los errores (403, 429, red caída,
 * respuestas que no son JSON...) vive en UN solo sitio y las pantallas solo
 * tienen que capturar un {@link ApiError} con un mensaje ya listo para el usuario.
 */
import type { ApiErrorBody } from './types';

/** Prefijo común de todos los endpoints (el proxy de Vite lo envía al backend). */
const API_URL = '/api';

/** Datos con los que se construye un {@link ApiError}. */
interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  retryAfterSeconds?: number;
  validationErrors?: Record<string, string>;
  remainingAttempts?: number;
  sessionExpired?: boolean;
}

/**
 * Error uniforme de cualquier llamada a la API.
 *
 * Las pantallas deciden por `status` y `code` (contrato estable del backend),
 * nunca por el texto de `message`, que solo se usa para mostrar.
 * Códigos generados por el propio cliente: `NETWORK_ERROR` (status 0, no hubo
 * respuesta) e `INVALID_RESPONSE` (la respuesta no era JSON válido).
 */
export class ApiError extends Error {
  /** Código HTTP, o 0 si no hubo respuesta (red caída, backend apagado). */
  readonly status: number;
  /** Código funcional estable (`VALIDATION_ERROR`, `USER_ALREADY_EXISTS`...). */
  readonly code: string;
  /** Segundos que indica la cabecera `Retry-After` (típicamente en un 429). */
  readonly retryAfterSeconds?: number;
  /** Errores de validación por campo (`campo → mensaje`), si el servidor los envió. */
  readonly validationErrors?: Record<string, string>;
  /**
   * Intentos de login que quedan antes del bloqueo de la cuenta (solo en el 401
   * de `POST /api/auth/login`). `undefined` si el servidor no lo envió o el
   * valor no es un entero ≥ 1: mejor no avisar que avisar con un número inventado.
   */
  readonly remainingAttempts?: number;
  /**
   * `true` si este error fue un 401 que ya provocó el cierre de sesión. La UI
   * lo ignora en silencio: el aviso "sesión caducada" se muestra una sola vez.
   */
  readonly sessionExpired: boolean;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.retryAfterSeconds = init.retryAfterSeconds;
    this.validationErrors = init.validationErrors;
    this.remainingAttempts = init.remainingAttempts;
    this.sessionExpired = init.sessionExpired ?? false;
  }
}

/**
 * Puente con el contexto de autenticación.
 *
 * `api.ts` no puede importar `AuthContext` (sería una dependencia circular),
 * así que es el proveedor quien se registra aquí con {@link configureAuth}.
 *
 * El JWT ya NO existe para JavaScript: viaja en una cookie `HttpOnly` que el
 * navegador adjunta solo (por eso un XSS no puede leerlo). Lo que el puente
 * comparte es una "clave de sesión" opaca (un contador) que sirve para saber a
 * QUÉ sesión pertenece una respuesta y descartar los 401 tardíos de una sesión
 * anterior.
 */
interface AuthBridge {
  /** Clave de la sesión actual; cambia en cada inicio o cierre de sesión. */
  getSessionKey: () => number;
  /**
   * Se invoca cuando un 401 ya no tiene arreglo (el refresh tampoco pudo renovar
   * la sesión); recibe la clave con la que se hizo la petición.
   */
  onUnauthorized: (usedKey: number) => void;
  /**
   * ¿Merece la pena intentar renovar la sesión con la que se hizo la petición?
   * `false` si ya no hay sesión o si la petición es de una sesión ANTERIOR: un
   * refresh ahí podría resucitar una sesión que el usuario acaba de cerrar.
   */
  canRefresh: (usedKey: number) => boolean;
  /**
   * Se invoca cuando OTRA pestaña anuncia que la sesión ha cambiado (ha entrado,
   * ha salido o le ha caducado; ver {@link announceSessionChange}). La cookie es
   * común a todas las pestañas, así que lo que esta pestaña cree saber de la
   * sesión (usuario, rol, caché) ya no vale: debe volver a preguntar al servidor.
   */
  onSessionChangedElsewhere: () => void;
}

let authBridge: AuthBridge | null = null;

/**
 * Registra (o elimina, con `null`) el puente con la sesión. Lo llama `AuthProvider`.
 *
 * Mientras hay puente, esta pestaña escucha el canal entre pestañas
 * ({@link SESSION_CHANNEL_NAME}) para enterarse de las renovaciones que hagan las
 * demás; al quitarlo, lo cierra.
 *
 * @param bridge funciones para leer la clave de sesión, avisar de un 401 y saber si se puede renovar
 */
export function configureAuth(bridge: AuthBridge | null): void {
  authBridge = bridge;
  if (bridge) {
    openSessionChannel();
    return;
  }
  closeSessionChannel();
  // Sin proveedor no hay sesión que coordinar: se olvidan la cola local y el
  // refresh en curso (lo pendiente sigue su curso, pero lo nuevo ya no lo espera).
  // En la app el proveedor no se desmonta nunca; en los tests, sí (uno por test),
  // y así una petición que un test deja colgada no bloquea la cola del siguiente.
  localSessionQueue = null;
  refreshInFlight = null;
}

// ---------------------------------------------------------------------------
// Renovación de la sesión (refresh token)
//
// El JWT de acceso (`streambox_token`) vive 15 minutos; el refresh token
// (`streambox_refresh`, Path=/api/auth) permite pedir otro sin contraseña con
// `POST /api/auth/refresh`. Ninguno de los dos es visible para JavaScript: aquí
// solo se decide CUÁNDO pedir la renovación y qué hacer con la respuesta.
//
// Tres reglas que explican todo el código de abajo:
// 1. **Un solo refresh a la vez** (*single-flight*): si varias peticiones reciben
//    401 a la vez, todas esperan la MISMA promesa. Cada refresh rota el token; si
//    cada petición pidiera el suyo, se gastarían refrescos (y el límite de 30 por
//    minuto) sin motivo.
// 2. **No renovar dos veces la misma caducidad.** Cada renovación correcta
//    incrementa {@link sessionGeneration}. Una petición recuerda la generación con
//    la que SALIÓ; si al recibir su 401 la generación ya es otra, es que alguien
//    renovó mientras viajaba (salió con la cookie vieja): basta con repetirla. Las
//    demás pestañas avisan de sus renovaciones por un `BroadcastChannel`.
// 3. **Las operaciones que cambian las cookies van en fila** (login, logout,
//    refresh, cambio de contraseña y logout global, con el Web Lock
//    {@link SESSION_LOCK_NAME}, común a todas las pestañas). Así un refresh no se cruza con un logout: si la respuesta del
//    refresh llegara después de la del logout, volvería a dejar en el navegador
//    una cookie de acceso válida y, al recargar, se «resucitaría» la sesión.
// ---------------------------------------------------------------------------

/**
 * Rutas que fijan o borran las cookies de sesión. Nunca disparan un refresh (un
 * 401 ahí significa otra cosa: credenciales malas, o que la renovación no es
 * posible) y se ejecutan dentro del lock de sesión.
 */
const SESSION_COOKIE_PATHS = new Set(['/auth/login', '/auth/logout', '/auth/refresh']);

/**
 * Rutas AUTENTICADAS que también cambian las cookies de sesión: el cambio de
 * contraseña (entrega cookies de una sesión nueva y revoca la anterior) y
 * «Cerrar sesión en todos los dispositivos» (revoca todas y las borra).
 *
 * Van dentro del lock de sesión por el mismo motivo que login y logout: si un
 * refresh (de esta u otra pestaña) saliera con el refresh token viejo y su
 * respuesta llegara DESPUÉS, el servidor lo rechazaría como revocado y su
 * `Set-Cookie` de borrado se llevaría por delante las cookies nuevas (o, en el
 * logout global, podría devolver unas válidas). A diferencia de
 * {@link SESSION_COOKIE_PATHS}, un 401 aquí sí se arregla renovando: la
 * petición necesita un JWT de acceso válido, y tras 15 minutos en el perfil ya
 * habrá caducado. No hay bloqueo mutuo: el lock solo abarca cada envío, se
 * suelta antes de renovar y la repetición vuelve a cogerlo.
 */
const SESSION_ROTATING_PATHS = new Set(['/users/me/password', '/auth/logout-all']);

/** Ruta de la renovación de la sesión. */
const REFRESH_PATH = '/auth/refresh';

/**
 * Nombre del Web Lock que ponen en fila login, logout y refresh. Lo comparten
 * todas las pestañas del mismo origen: es lo que evita que dos pestañas renueven
 * a la vez con el mismo refresh token.
 */
export const SESSION_LOCK_NAME = 'streambox-session';

/** Canal por el que una pestaña avisa a las demás de que ha renovado la sesión. */
export const SESSION_CHANNEL_NAME = 'streambox-session';

/** Mensaje que se publica en {@link SESSION_CHANNEL_NAME} tras una renovación correcta. */
const SESSION_RENEWED_MESSAGE = 'session-renewed';

/**
 * Mensaje que se publica en {@link SESSION_CHANNEL_NAME} cuando esta pestaña
 * inicia sesión, la cierra o descubre que ha caducado (ver {@link announceSessionChange}).
 */
export const SESSION_CHANGED_MESSAGE = 'session-changed';

/**
 * Tiempo máximo de las peticiones que van dentro del lock de sesión (login,
 * logout y refresh); ver {@link lockedRequestSignal}. Holgado: un login normal
 * (BCrypt) tarda menos de un segundo.
 */
const SESSION_REQUEST_TIMEOUT_MS = 15_000;

/**
 * Contador de renovaciones conocidas por esta pestaña (las suyas y las que le
 * anuncian las demás). Solo se compara consigo mismo: no es una hora, así que no
 * le afectan los cambios del reloj del sistema.
 */
let sessionGeneration = 0;

/** Canal abierto mientras hay `AuthProvider` (ver {@link configureAuth}). */
let sessionChannel: BroadcastChannel | null = null;

/** Abre el canal entre pestañas si el navegador lo admite (si no, se sigue sin él). */
function openSessionChannel(): void {
  if (sessionChannel || typeof BroadcastChannel === 'undefined') return;
  sessionChannel = new BroadcastChannel(SESSION_CHANNEL_NAME);
  sessionChannel.onmessage = (event: MessageEvent<unknown>) => {
    if (!isRecord(event.data)) return;
    if (event.data.type === SESSION_RENEWED_MESSAGE) sessionGeneration += 1;
    else if (event.data.type === SESSION_CHANGED_MESSAGE) authBridge?.onSessionChangedElsewhere();
  };
}

/**
 * Avisa a las DEMÁS pestañas de que la sesión ha cambiado (esta pestaña ha
 * iniciado sesión, la ha cerrado o ha descubierto que caducó). Lo llama
 * `AuthProvider`; el navegador nunca entrega el mensaje a quien lo envía.
 *
 * **Por qué hace falta.** La cookie de sesión es del navegador, común a todas
 * las pestañas; lo que cada una sabe de la sesión (usuario, rol, «Mi lista» en
 * caché) es suyo. Sin este aviso, una pestaña abierta con la sesión de A seguía
 * enseñando a A (su nombre, su rol, su lista) mientras sus peticiones ya salían
 * con la cookie de C, que acababa de entrar en otra pestaña: al volver a ella
 * vería «Mi lista» de C con el nombre de A, y un clic en un corazón cambiaría la
 * cuenta de C.
 *
 * **Solo el tipo, sin datos.** El mensaje no lleva quién ha entrado ni nada de
 * la cuenta: cualquier script del mismo origen puede escuchar el canal, y la
 * pestaña que lo recibe no necesita más; pregunta al servidor (`GET /users/me`).
 * Tampoco hay bucles: la pestaña que lo recibe vuelve a preguntar, pero nunca
 * lo reenvía.
 */
export function announceSessionChange(): void {
  sessionChannel?.postMessage({ type: SESSION_CHANGED_MESSAGE });
}

/**
 * Clave opaca de la sesión actual (la misma que usa {@link apiFetch} para
 * descartar respuestas de una sesión anterior); 0 si no hay `AuthProvider`.
 * Sirve para que quien lanza una operación larga (p. ej. añadir a «Mi lista»)
 * sepa al terminar si la sesión sigue siendo la misma y no avise a otra persona.
 */
export function currentSessionKey(): number {
  return authBridge?.getSessionKey() ?? 0;
}

/** Cierra el canal entre pestañas (al desmontarse `AuthProvider`). */
function closeSessionChannel(): void {
  sessionChannel?.close();
  sessionChannel = null;
}

/**
 * Cola local de reserva para cuando no existe `navigator.locks` (navegadores
 * antiguos o contextos no seguros: http fuera de `localhost`). Ordena las
 * operaciones de ESTA pestaña; entre pestañas queda la gracia de 10 s del
 * servidor, que acepta el refresh simultáneo de otra pestaña sin cerrar la sesión.
 * `null` = no hay nada en curso.
 */
let localSessionQueue: Promise<void> | null = null;

/** No hace nada; sirve para que la cola espere a una tarea sin heredar su error. */
function ignore(): void {}

/**
 * Ejecuta `task` en exclusiva respecto a las demás operaciones de sesión (de
 * esta y, con Web Locks, de todas las pestañas). No es reentrante: `task` no
 * debe esperar a otra operación que también pase por aquí.
 *
 * Sin Web Locks y con la cola vacía, `task` empieza en el acto (sin esperar a
 * otra vuelta del bucle de eventos): así la petición sale igual que sin cola.
 */
function withSessionLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  if (locks && typeof locks.request === 'function') {
    return locks.request(SESSION_LOCK_NAME, () => task()) as Promise<T>;
  }
  const run = localSessionQueue ? localSessionQueue.then(task) : task();
  const tail = run.then(ignore, ignore);
  localSessionQueue = tail;
  void tail.then(() => {
    if (localSessionQueue === tail) localSessionQueue = null;
  });
  return run;
}

/**
 * Señal para una petición que va dentro del lock de sesión: la del llamador (si
 * la hay) más un tiempo máximo de {@link SESSION_REQUEST_TIMEOUT_MS}. Mientras la
 * petición dura, el lock está cogido y con él el login, el logout y los refresh
 * de TODAS las pestañas: una petición colgada no debe bloquearlas para siempre.
 * Al agotarse el tiempo, el error es `TimeoutError` (no `AbortError`), así que se
 * trata como red caída.
 */
function lockedRequestSignal(signal: AbortSignal | null | undefined): AbortSignal | undefined {
  const timeout =
    typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(SESSION_REQUEST_TIMEOUT_MS) : undefined;
  if (!signal) return timeout;
  if (!timeout || typeof AbortSignal.any !== 'function') return signal;
  return AbortSignal.any([signal, timeout]);
}

/** Resultado de intentar renovar la sesión. */
type RefreshOutcome =
  /** Hay cookies nuevas (esta pestaña u otra las ha renovado): se puede repetir la petición. */
  | { kind: 'renewed' }
  /** El servidor dice que no hay sesión renovable (401 `SESSION_EXPIRED`): sesión caducada. */
  | { kind: 'expired' }
  /** No se sabe (red caída, 5xx, 429...): la sesión NO se cierra; la pantalla muestra este error. */
  | { kind: 'failed'; error: ApiError };

const RENEWED: RefreshOutcome = { kind: 'renewed' };

/** Refresh en curso en esta pestaña; las peticiones que reciben 401 a la vez lo comparten. */
let refreshInFlight: Promise<RefreshOutcome> | null = null;

/**
 * Consigue una sesión renovada para una petición que ha recibido 401.
 *
 * @param generationAtSend valor de {@link sessionGeneration} cuando salió la petición
 */
function renewSession(generationAtSend: number): Promise<RefreshOutcome> {
  // Alguien renovó mientras la petición viajaba (con la cookie vieja): no hace falta otro refresh.
  if (sessionGeneration !== generationAtSend) return Promise.resolve(RENEWED);
  if (refreshInFlight) return refreshInFlight;
  const startGeneration = sessionGeneration;
  const refresh: Promise<RefreshOutcome> = withSessionLock(async () => {
    // Mientras esperaba el lock, otra pestaña pudo renovar (y avisarlo por el canal).
    // Repetir el refresh no rompería nada (las cookies son comunes y llevaría ya el
    // token nuevo), pero gastaría una rotación y una petición del límite.
    if (sessionGeneration !== startGeneration) return RENEWED;
    return requestRefresh();
  })
    // `requestRefresh` no lanza; esto cubre un fallo del propio lock: se trata como
    // «no se sabe» (sin cerrar la sesión), nunca como una excepción cruda.
    .catch((): RefreshOutcome => ({ kind: 'failed', error: networkError() }))
    .finally(() => {
    if (refreshInFlight === refresh) refreshInFlight = null;
  });
  refreshInFlight = refresh;
  return refresh;
}

/**
 * Hace el `POST /api/auth/refresh` (sin cuerpo; la cookie la pone el navegador).
 * Va con `fetch` directo y no con {@link apiFetch} porque ya se ejecuta dentro del
 * lock de sesión (que no es reentrante) y porque su 401 no debe disparar otro refresh.
 * Nunca se reintenta: un 401 aquí es definitivo y repetirlo no lo arreglaría.
 */
async function requestRefresh(): Promise<RefreshOutcome> {
  let res: Response;
  let text: string;
  try {
    res = await fetch(`${API_URL}${REFRESH_PATH}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', [CSRF_HEADER]: CSRF_VALUE },
      signal: lockedRequestSignal(undefined),
    });
    text = await res.text();
  } catch {
    return { kind: 'failed', error: networkError() };
  }
  if (res.ok) {
    sessionGeneration += 1;
    sessionChannel?.postMessage({ type: SESSION_RENEWED_MESSAGE });
    return RENEWED;
  }
  // Cualquier 401 (el contrato dice `SESSION_EXPIRED`) es «no hay sesión que renovar».
  if (res.status === 401) return { kind: 'expired' };
  const parsed = text.trim() === '' ? undefined : tryParseJson(text);
  return { kind: 'failed', error: buildApiError(res, parsed, true) };
}

/**
 * Cabecera que el backend exige en toda petición no segura (defensa CSRF). Un
 * formulario de otra web no puede añadirla sin pasar por CORS, que el backend no
 * concede; así la cookie sola (que el navegador enviaría) no basta para actuar.
 */
const CSRF_HEADER = 'X-Requested-With';
const CSRF_VALUE = 'StreamBox';

/** Métodos que no modifican datos y por tanto no llevan la cabecera anti-CSRF. */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/** Valores admitidos como parámetros de consulta; `undefined`, `null` y `''` se omiten. */
export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** Opciones de {@link apiFetch}: las de `fetch`, con `body` ya como objeto. */
export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  /** Cuerpo de la petición; se serializa a JSON automáticamente. */
  body?: unknown;
  /** Parámetros de la URL (`?page=0&size=20`), ya codificados. */
  params?: QueryParams;
  /**
   * Endpoint público (login, registro, logout): un 401 NO cierra la sesión ni
   * intenta renovarla, porque ahí significa "credenciales incorrectas", no
   * "sesión caducada". (La cookie, al ser del navegador, se envía igualmente; el
   * servidor la ignora.)
   */
  public?: boolean;
}

/** Construye la cadena `?a=1&b=2` omitiendo valores vacíos. */
function buildQuery(params?: QueryParams): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/**
 * Mensaje de "demasiados intentos" (429).
 *
 * @param seconds segundos de espera indicados por `Retry-After`, si se conocen
 */
export function rateLimitMessage(seconds?: number): string {
  return seconds !== undefined
    ? `Demasiados intentos. Inténtalo de nuevo en ${seconds} s.`
    : 'Demasiados intentos. Inténtalo de nuevo en unos instantes.';
}

/** Interpreta `Retry-After` en su forma de segundos; ignora la forma de fecha HTTP. */
function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number.parseInt(value, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

/**
 * Interpreta `remainingAttempts` del cuerpo de error. Solo vale un entero ≥ 1
 * (el contrato del backend); cualquier otra cosa (texto, decimal, 0, negativo)
 * se descarta para no mostrar un aviso con un número sin sentido.
 */
function parseRemainingAttempts(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 ? value : undefined;
}

/** Intenta interpretar un texto como JSON; devuelve `undefined` si no lo es. */
function tryParseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Indica si un valor desconocido tiene forma de objeto (y no es `null` ni un array). */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Indica si el error procede de cancelar la petición con un `AbortController`.
 * Las cancelaciones son intencionadas (p. ej. un componente que se desmonta) y
 * no deben mostrarse al usuario.
 */
export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * Estados con los que un proxy (Vite en desarrollo, nginx en Docker) dice «no he
 * podido hablar con el backend»: 502, 503 y 504. Con el backend parado o
 * reiniciándose no hay «red caída» (status 0) sino uno de estos, con la página
 * HTML del proxy en lugar del JSON de la API.
 */
const UNREACHABLE_STATUSES = new Set([502, 503, 504]);

/**
 * Convierte una respuesta de error en un {@link ApiError} con mensaje en español.
 *
 * Para 401, 403, 429 y 5xx el mensaje lo fija el cliente (es lo que el usuario
 * debe entender); para el resto se usa el `message` del servidor, que ya está en
 * español y es específico (validaciones, duplicados...).
 *
 * Un 502/503/504 SIN `code` en el cuerpo es la página del proxy: el backend no
 * llegó a contestar, así que se dice «No se pudo conectar con el servidor» (como
 * con la red caída y como el aviso del cierre de sesión, `lib/logout.ts`) y no
 * «el servidor ha tenido un problema», que hace pensar en un fallo de la
 * operación. Si el cuerpo trae `code`, sí respondió la API (p. ej. un 503 propio)
 * y se trata como cualquier otro 5xx. Se decide por `status` y `code`, nunca por el texto.
 */
function buildApiError(res: Response, body: unknown, isPublic: boolean): ApiError {
  const errorBody: Partial<ApiErrorBody> = isRecord(body) ? (body as Partial<ApiErrorBody>) : {};
  const status = res.status;
  const retryAfterSeconds = parseRetryAfter(res.headers.get('Retry-After'));
  const serverMessage =
    typeof errorBody.message === 'string' && errorBody.message.trim() ? errorBody.message : null;

  let message: string;
  if (status === 401) {
    message = isPublic
      ? (serverMessage ?? 'Credenciales incorrectas.')
      : 'Tu sesión ha caducado. Inicia sesión de nuevo.';
  } else if (status === 403) {
    message = 'No tienes permisos para realizar esta acción.';
  } else if (status === 429) {
    message = rateLimitMessage(retryAfterSeconds);
  } else if (UNREACHABLE_STATUSES.has(status) && typeof errorBody.code !== 'string') {
    // La página de error de un proxy (Vite, nginx): no se llegó al backend.
    message = 'No se pudo conectar con el servidor. Inténtalo de nuevo en unos instantes.';
  } else if (status >= 500) {
    message = 'El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.';
  } else {
    message = serverMessage ?? `No se pudo completar la operación (error ${status}).`;
  }

  return new ApiError({
    status,
    code: typeof errorBody.code === 'string' ? errorBody.code : `HTTP_${status}`,
    message,
    retryAfterSeconds,
    validationErrors: isRecord(errorBody.validationErrors)
      ? (errorBody.validationErrors as Record<string, string>)
      : undefined,
    remainingAttempts: parseRemainingAttempts(errorBody.remainingAttempts),
    sessionExpired: status === 401 && !isPublic,
  });
}

/**
 * Hace una petición a la API y devuelve el cuerpo JSON ya tipado.
 *
 * Responsabilidades centralizadas aquí:
 * - La sesión va en una cookie HttpOnly: `credentials: 'same-origin'` hace que el
 *   navegador la envíe (mismo origen gracias al proxy de Vite/nginx). No hay
 *   cabecera `Authorization` ni token en JavaScript.
 * - Añade `X-Requested-With: StreamBox` a toda petición que no sea GET/HEAD/OPTIONS
 *   (el backend responde 403 `CSRF_REJECTED` sin ella).
 * - Serializa `body` a JSON y construye la query a partir de `params`.
 * - Comprueba SIEMPRE `res.ok`: nada se da por bueno sin mirar el estado.
 * - 204 / cuerpo vacío: devuelve `undefined` sin llamar a `res.json()`.
 * - 401 en endpoint autenticado: primero intenta RENOVAR la sesión (un único
 *   `POST /auth/refresh` compartido, ver {@link renewSession}) y repite la
 *   petición UNA vez. Un 401 significa que el servidor no llegó a ejecutarla
 *   (la autenticación va antes), así que repetirla es seguro también en un POST.
 *   - Renovada: devuelve lo que diga la repetición (si vuelve a dar 401, sesión caducada; no hay bucle).
 *   - El servidor no la renueva (401 `SESSION_EXPIRED`): avisa a `AuthProvider`
 *     para cerrar la sesión. No navega: al quedar sin sesión, la ruta protegida
 *     redirige a `/login` una sola vez aunque fallen varias peticiones a la vez.
 *   - No se pudo saber (red, 5xx, 429...): lanza ESE error, sin cerrar la sesión,
 *     para que la pantalla lo enseñe con «Reintentar».
 *   No se renueva en rutas públicas ni de sesión (login, registro, refresh,
 *   logout), en peticiones canceladas ni en las de una sesión ya cerrada.
 * - Login, logout y refresh se ejecutan en fila (Web Lock común a las pestañas).
 * - 403 NO cierra la sesión (el usuario está autenticado, solo le faltan permisos).
 * - 429 lee `Retry-After` y lo expone en `retryAfterSeconds`.
 * - Red caída, backend apagado o respuesta que no es JSON: `ApiError` con
 *   status 0 (`NETWORK_ERROR`) o `INVALID_RESPONSE`, nunca una excepción cruda.
 * - Las cancelaciones (`AbortController`) se relanzan tal cual; ver {@link isAbortError}.
 *
 * @typeParam T tipo del cuerpo de la respuesta (`void` si no hay cuerpo)
 * @param path ruta relativa a `/api`, p. ej. `/movies`
 * @param options método, cuerpo, parámetros, señal de cancelación...
 * @returns el cuerpo JSON de la respuesta
 * @throws {ApiError} si la petición falla por cualquier motivo
 */
export async function apiFetch<T = void>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { body, params, public: isPublic = false, headers: extraHeaders, ...init } = options;

  const sessionKey = authBridge?.getSessionKey() ?? 0;
  // Generación con la que SALE la petición: si al volver con 401 ya es otra,
  // alguien renovó mientras tanto y no hace falta otro refresh (ver `renewSession`).
  const generationAtSend = sessionGeneration;
  const headers = new Headers(extraHeaders);
  headers.set('Accept', 'application/json');
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  if (!SAFE_METHODS.has((init.method ?? 'GET').toUpperCase())) headers.set(CSRF_HEADER, CSRF_VALUE);

  const url = `${API_URL}${path}${buildQuery(params)}`;
  const requestInit: RequestInit = {
    ...init,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  };
  const send =
    SESSION_COOKIE_PATHS.has(path) || SESSION_ROTATING_PATHS.has(path)
      ? () => withSessionLock(() => sendRequest(url, { ...requestInit, signal: lockedRequestSignal(init.signal) }))
      : () => sendRequest(url, requestInit);

  const first = await send();

  if (first.res.status === 401 && mayRenewSession(path, isPublic, sessionKey)) {
    // Cancelada: nadie espera ya la respuesta; no se gasta un refresh por ella.
    throwIfAborted(init.signal);
    const outcome = await renewSession(generationAtSend);
    if (outcome.kind === 'expired') {
      // El 401 es definitivo. Se avisa a `AuthProvider` AUNQUE esta petición se haya
      // cancelado mientras tanto (la pantalla cambió): la caducidad es de la sesión,
      // no de la petición, y si nadie la cerrara, las peticiones que sigan llegando
      // con 401 pedirían otro refresh condenado a la misma respuesta.
      const error = failureFrom(first, isPublic, sessionKey);
      throwIfAborted(init.signal);
      throw error;
    }
    throwIfAborted(init.signal);
    if (outcome.kind === 'failed') throw outcome.error;
    // Si mientras se renovaba la sesión cambió (logout, otro login), no se repite:
    // el 401 original se trata como de una sesión anterior (se ignora en silencio).
    if (authBridge?.getSessionKey() === sessionKey) {
      return readResponse<T>(await send(), isPublic, sessionKey);
    }
  }
  return readResponse<T>(first, isPublic, sessionKey);
}

/** Respuesta ya leída: el `Response` y su cuerpo como texto. */
interface RawResponse {
  res: Response;
  text: string;
}

/** Error de «no hubo respuesta» (red caída, backend apagado, tiempo agotado). */
function networkError(): ApiError {
  return new ApiError({
    status: 0,
    code: 'NETWORK_ERROR',
    message: 'No se pudo conectar con el servidor. Comprueba tu conexión e inténtalo de nuevo.',
  });
}

/**
 * Lanza un `AbortError` si la petición se canceló. Se crea uno nuevo en lugar de
 * relanzar `signal.reason`, que puede ser cualquier valor: así {@link isAbortError}
 * siempre lo reconoce.
 */
function throwIfAborted(signal: AbortSignal | null | undefined): void {
  if (signal?.aborted) throw new DOMException('La petición se ha cancelado.', 'AbortError');
}

/** Envía la petición y lee su cuerpo; los fallos de red se convierten en `NETWORK_ERROR`. */
async function sendRequest(url: string, init: RequestInit): Promise<RawResponse> {
  try {
    const res = await fetch(url, init);
    return { res, text: await res.text() };
  } catch (error) {
    if (isAbortError(error)) throw error;
    throw networkError();
  }
}

/**
 * ¿Puede un 401 de esta petición arreglarse renovando la sesión? Solo en rutas
 * autenticadas, con `AuthProvider` montado y si la petición es de la sesión actual.
 */
function mayRenewSession(path: string, isPublic: boolean, sessionKey: number): boolean {
  return !isPublic && !SESSION_COOKIE_PATHS.has(path) && authBridge !== null && authBridge.canRefresh(sessionKey);
}

/**
 * Convierte la respuesta en el resultado de {@link apiFetch}: el cuerpo si fue
 * bien o un {@link ApiError} si no. Un 401 de ruta autenticada que llega aquí ya
 * no tiene arreglo: avisa a `AuthProvider` para cerrar la sesión.
 */
function readResponse<T>(raw: RawResponse, isPublic: boolean, sessionKey: number): T {
  const { res, text } = raw;
  if (!res.ok) throw failureFrom(raw, isPublic, sessionKey);
  if (text.trim() === '') return undefined as T; // 204 u otro éxito sin cuerpo
  const parsed = tryParseJson(text);
  if (parsed === undefined) {
    throw new ApiError({
      status: res.status,
      code: 'INVALID_RESPONSE',
      message: 'El servidor ha enviado una respuesta que no se puede interpretar.',
    });
  }
  return parsed as T;
}

/**
 * Construye el {@link ApiError} de una respuesta de error y, si es un 401 de ruta
 * autenticada (sesión caducada sin arreglo), avisa a `AuthProvider`.
 */
function failureFrom({ res, text }: RawResponse, isPublic: boolean, sessionKey: number): ApiError {
  const parsed = text.trim() === '' ? undefined : tryParseJson(text);
  const error = buildApiError(res, parsed, isPublic);
  if (error.sessionExpired) authBridge?.onUnauthorized(sessionKey);
  return error;
}

/**
 * Mensaje legible para mostrar al usuario a partir de cualquier error capturado.
 *
 * @param error valor capturado en un `catch`
 * @param fallback texto si el error no es un {@link ApiError}
 */
export function getErrorMessage(
  error: unknown,
  fallback = 'Ha ocurrido un error inesperado. Inténtalo de nuevo.',
): string {
  return error instanceof ApiError ? error.message : fallback;
}
