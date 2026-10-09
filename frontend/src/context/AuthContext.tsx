import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { announceSessionChange, apiFetch, configureAuth, isAbortError } from '../lib/api';
import { logoutOnServer } from '../lib/logout';
import type { User } from '../lib/types';
import { useToast } from './ToastContext';
import { SESSION_CLOSED_ELSEWHERE, SESSION_SWITCHED_ELSEWHERE } from '../lib/sessionMessages';

/**
 * Clave de `localStorage` donde versiones anteriores guardaban el JWT. Ya no se
 * escribe nunca: solo se BORRA al arrancar, para que una sesión antigua no deje
 * un token válido al alcance de cualquier script (XSS) en ese navegador.
 */
const LEGACY_TOKEN_KEY = 'token';

/** Borra el token heredado; ignora los fallos de almacenamiento (modo privado...). */
function removeLegacyToken(): void {
  try {
    localStorage.removeItem(LEGACY_TOKEN_KEY);
  } catch {
    // Sin almacenamiento no hay nada que limpiar.
  }
}

/**
 * Estado de la carga del usuario actual (`GET /api/users/me`):
 * - `idle`: no hay sesión, no hay nada que cargar.
 * - `loading`: se está preguntando al servidor quién es (también al arrancar).
 * - `ready`: `user` ya tiene los datos de la sesión actual.
 * - `error`: no se pudo cargar (red, 5xx...). Si ya había sesión, sigue abierta
 *   pero `isAdmin` es `false` hasta que un reintento salga bien.
 */
export type UserStatus = 'idle' | 'loading' | 'ready' | 'error';

/** Valor que expone {@link useAuth}. */
interface AuthContextValue {
  /** `true` solo cuando el servidor ha confirmado una sesión (nunca mientras se comprueba). */
  isAuthenticated: boolean;
  /**
   * `true` mientras el arranque aún averigua si hay sesión (`GET /users/me`
   * pendiente). Las rutas esperan en lugar de redirigir a `/login`, que echaría
   * a quien recarga la página con la sesión abierta.
   */
  isCheckingSession: boolean;
  /**
   * `true` si el chequeo inicial falló por red o 5xx: no se sabe si hay sesión,
   * así que ni se muestra la app ni se manda al login; se ofrece reintentar.
   */
  sessionCheckFailed: boolean;
  /** Usuario de la sesión actual, o `null` si no hay sesión o aún no se ha cargado. */
  user: User | null;
  /**
   * `true` solo si el servidor ha confirmado que el usuario de ESTA sesión es
   * `ADMIN`. Mientras carga o si la carga falla vale `false` (falla cerrado).
   * Sirve únicamente para decidir qué se PINTA: la seguridad real es el 403 del backend.
   */
  isAdmin: boolean;
  /** Estado de la carga de {@link AuthContextValue.user}; ver {@link UserStatus}. */
  userStatus: UserStatus;
  /** Vuelve a pedir el usuario actual (reintento tras un fallo de red, también en el arranque). */
  refreshUser: () => void;
  /**
   * Inicia sesión: `POST /auth/login` (el servidor fija la cookie HttpOnly) y
   * después carga el usuario. Lanza el `ApiError` del login (401, 429...) para
   * que el formulario lo muestre.
   */
  login: (email: string, password: string) => Promise<void>;
  /**
   * Cierra la sesión EN EL SERVIDOR (`POST /auth/logout`, que borra la cookie) y,
   * solo si este lo confirma, también en la interfaz. Si no se pudo (red caída,
   * 5xx... tras un reintento), la sesión sigue abierta y se avisa con un toast.
   * Nunca lanza: devuelve `true` si la sesión quedó cerrada. Mientras hay un
   * cierre en curso, otra llamada no hace nada (devuelve `false`).
   */
  logout: () => Promise<boolean>;
  /**
   * `true` mientras hay un cierre de sesión en curso (incluida la espera del
   * reintento): los botones «Cerrar sesión» lo usan para mostrar «Cerrando sesión...».
   */
  isLoggingOut: boolean;
}

/**
 * Qué se sabe de la sesión:
 * - `unknown`: recién arrancada la app, aún no se ha preguntado al servidor.
 * - `active`: el servidor la ha confirmado (o se acaba de iniciar).
 * - `none`: no hay sesión.
 */
type SessionState = 'unknown' | 'active' | 'none';

/**
 * Resultado de una petición a `/users/me`, junto con la sesión (`epoch`) y el
 * intento que la originaron. Guardar a qué sesión pertenece es lo que impide que
 * el usuario de una sesión anterior aparezca en la nueva (ver {@link AuthProvider}).
 */
interface UserResult {
  epoch: number;
  attempt: number;
  status: 'ready' | 'error';
  user: User | null;
}

/**
 * Comprobación de la sesión lanzada por un aviso de otra pestaña: con qué época
 * se lanzó y quién había antes (`id` del usuario, `null` sin sesión o
 * `undefined` si no se sabía), para avisar SOLO si de verdad cambió quién está dentro.
 */
interface ElsewhereCheck {
  epoch: number;
  previousUserId: number | null | undefined;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Proveedor de la sesión: ÚNICA fuente de verdad de quién está dentro.
 *
 * **Sin token en JavaScript.** El JWT viaja en la cookie `streambox_token`
 * (HttpOnly, SameSite=Strict, Path=/api) que fija el backend en el login: el
 * navegador la envía solo y ningún script, ni un XSS, puede leerla. Por eso aquí
 * no hay token ni `localStorage`; la sesión se DESCUBRE preguntando
 * `GET /api/users/me` al arrancar (200 = hay sesión, 401 = no la hay; ese primer
 * 401 es silencioso, sin aviso de «sesión caducada»).
 *
 * **Sesión renovable.** El JWT de acceso dura 15 minutos; el refresh token
 * (cookie `streambox_refresh`, 7 días) lo renueva sin contraseña. De eso se
 * ocupa `apiFetch`: ante un 401 pide `POST /auth/refresh` y repite la petición.
 * También en el arranque: si `/users/me` da 401 pero el refresh token sigue
 * valiendo (quien vuelve al día siguiente), se renueva y se entra sin login,
 * todo dentro de «Comprobando tu sesión...». Este proveedor solo le dice a
 * `apiFetch` cuándo NO renovar (`canRefresh`: sin sesión o petición de una
 * sesión anterior) y qué hacer cuando ya no hay arreglo (`onUnauthorized`).
 *
 * Se registra en `apiFetch` con `configureAuth`: cuando un 401 no se arregla
 * renovando, con la sesión abierta se cierra la sesión UNA vez y se avisa. No
 * se navega desde aquí: al quedar `isAuthenticated = false`, la ruta protegida
 * redirige a `/login` por sí sola aunque fallen varias peticiones a la vez.
 *
 * **Usuario y rol.** El rol no está en el JWT ni en JavaScript: se pregunta al
 * servidor, que lee la base de datos en cada petición (un cambio de rol es
 * inmediato). En el cliente solo decide qué se muestra; protege el backend (403).
 *
 * **Respuestas tardías.** Cada inicio o cierre de sesión incrementa `epoch`; el
 * resultado de `/users/me` se guarda con el `epoch` que lo pidió y `user` se
 * DERIVA comparándolo con el actual, así que el usuario de una sesión anterior
 * nunca se asigna a la nueva. Además, cada cambio cancela la petición en curso.
 *
 * **Cerrar sesión lo decide el servidor.** `logout` no limpia la interfaz hasta
 * que `POST /auth/logout` confirma el cierre (2xx, o 401 si ya no había sesión);
 * ante red caída o 5xx reintenta una vez y, si sigue fallando, mantiene la sesión
 * y avisa. Antes era optimista y se tragaba el error: la pantalla decía «sesión
 * cerrada» con la cookie aún válida, y al recargar se volvía a entrar.
 *
 * **Caché de datos.** El usuario actual NO va en la caché de TanStack Query:
 * `/users/me` no es un dato más, es cómo se descubre la sesión, y su lógica de
 * épocas y estados (`unknown`/`active`/`none`) es la que decide cuándo hay que
 * vaciar esa caché. Cada cambio de sesión (entrar, salir, sesión caducada) la
 * vacía (ver `applySession`).
 *
 * **Varias pestañas.** La cookie es común a todas, pero lo que cada una sabe
 * de la sesión (usuario, rol, caché) es suyo. Por eso, al iniciar sesión, al
 * cerrarla y al descubrir que caducó, esta pestaña lo anuncia por el canal entre
 * pestañas (`announceSessionChange`, solo el tipo de mensaje, sin datos de la
 * cuenta) y las demás vuelven a preguntar al servidor quién está dentro (ver
 * `onSessionChangedElsewhere`). Sin esto, una pestaña con la sesión de A seguía
 * mostrando a A (y su lista) mientras sus peticiones ya salían con la cookie de C.
 * Las renovaciones también se coordinan entre pestañas (`lib/api.ts`).
 *
 * Debe ir dentro de `ToastProvider` (muestra el aviso de sesión caducada) y de
 * `QueryClientProvider` (vacía la caché al cambiar de sesión).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [session, setSession] = useState<SessionState>('unknown');
  const [epoch, setEpoch] = useState(0);
  // Copias síncronas para `apiFetch`: el estado de React se actualiza tras el
  // render, pero una petición lanzada justo después ya debe ver la sesión nueva.
  const sessionRef = useRef<SessionState>('unknown');
  const epochRef = useRef(0);
  // Contador de reintentos de `/users/me`: cambiarlo vuelve a lanzar la petición
  // y, como forma parte de la "clave" del resultado, el estado pasa a `loading`
  // mientras tanto en lugar de quedarse en `error`.
  const [attempt, setAttempt] = useState(0);
  const [userResult, setUserResult] = useState<UserResult | null>(null);
  const resultRef = useRef<UserResult | null>(null);
  // Comprobación pendiente tras un aviso de otra pestaña (ver `onSessionChangedElsewhere`).
  const elsewhereRef = useRef<ElsewhereCheck | null>(null);

  /** Guarda el resultado en el estado y en su copia síncrona. */
  const storeResult = useCallback((result: UserResult | null) => {
    resultRef.current = result;
    setUserResult(result);
  }, []);

  /**
   * Cambia la sesión en memoria y empieza una época nueva (olvida el usuario anterior).
   *
   * También VACÍA la caché de datos del servidor (TanStack Query): ahí están «Mi
   * lista» y lo que se cargó con la sesión anterior. Sin esto, tras cerrar
   * sesión y entrar con otra cuenta en el mismo navegador se verían un instante
   * (o hasta cinco minutos, el `gcTime`) los favoritos de la persona anterior.
   * `clear()` además cancela las peticiones en vuelo de la sesión que se cierra.
   */
  const applySession = useCallback(
    (next: SessionState) => {
      sessionRef.current = next;
      epochRef.current += 1;
      // Una comprobación pedida por otra pestaña era de la época que acaba (quien
      // la necesite, `onSessionChangedElsewhere`, la vuelve a poner después).
      elsewhereRef.current = null;
      queryClient.clear();
      setSession(next);
      setEpoch(epochRef.current);
      storeResult(null);
    },
    [queryClient, storeResult],
  );

  // Cierre de sesión en curso: el estado pinta «Cerrando sesión...» y la copia
  // síncrona impide lanzar un segundo cierre con un doble clic (el estado de
  // React aún no se habría actualizado entre los dos clics).
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const loggingOutRef = useRef(false);

  /**
   * Cierre de sesión pedido por el usuario. NO es optimista: con la sesión en una
   * cookie HttpOnly, solo el servidor puede borrarla, así que la interfaz no dice
   * «sesión cerrada» hasta que él lo confirma (ver `lib/logout.ts`). Si no lo
   * confirma, la sesión sigue abierta en pantalla —porque lo sigue estando— y se
   * avisa. Mientras tanto el usuario sigue dentro; no se navega desde aquí: al
   * quedar sin sesión, la ruta protegida redirige a `/login` por sí sola.
   */
  const logout = useCallback(async () => {
    if (sessionRef.current === 'none' || loggingOutRef.current) return false;
    loggingOutRef.current = true;
    setIsLoggingOut(true);
    const startEpoch = epochRef.current;
    try {
      const result = await logoutOnServer(() => epochRef.current === startEpoch);
      // Si mientras tanto la sesión ya cambió (p. ej. un 401 de otra petición la
      // cerró con su aviso de «sesión caducada»), no hay nada más que hacer.
      // (El `as` evita que TypeScript arrastre el estrechamiento de antes del `await`: el ref sí pudo cambiar.)
      if (epochRef.current !== startEpoch) return (sessionRef.current as SessionState) === 'none';
      if (result.closed) {
        applySession('none');
        announceSessionChange();
        return true;
      }
      toast.error(result.message);
      return false;
    } finally {
      loggingOutRef.current = false;
      setIsLoggingOut(false);
    }
  }, [applySession, toast]);

  const login = useCallback(
    async (email: string, password: string) => {
      // `public`: un 401 es «credenciales incorrectas», no «sesión caducada».
      await apiFetch('/auth/login', { method: 'POST', body: { email, password }, public: true });
      // La cookie ya está en el navegador. Se carga el usuario antes de abrir la
      // sesión en la interfaz para no pintar un instante «sin rol».
      let me: User | null = null;
      try {
        me = await apiFetch<User>('/users/me', { public: true });
      } catch {
        // La sesión existe (el login salió bien) aunque no se pudo cargar el
        // usuario: se abre igualmente y el estado `error` ofrece reintentar.
      }
      applySession('active');
      storeResult({ epoch: epochRef.current, attempt, status: me ? 'ready' : 'error', user: me });
      // La cookie es de todo el navegador: las demás pestañas deben volver a preguntar quién está dentro.
      announceSessionChange();
    },
    [applySession, storeResult, attempt],
  );

  const refreshUser = useCallback(() => setAttempt((n) => n + 1), []);

  // Al arrancar se borra el token que versiones anteriores guardaron en localStorage.
  useEffect(removeLegacyToken, []);

  // `useLayoutEffect` (y no `useEffect`) porque los efectos de los componentes
  // hijos —que ya lanzan peticiones al montarse— se ejecutan ANTES que los del
  // padre; los layout effects, en cambio, corren todos antes que cualquier
  // `useEffect`, así que el puente está listo cuando llega la primera petición.
  useLayoutEffect(() => {
    configureAuth({
      getSessionKey: () => epochRef.current,
      // Se puede renovar mientras pueda haber sesión: abierta o aún sin comprobar
      // (en el arranque, un 401 de `/users/me` con un refresh token válido es
      // «vuelves al día siguiente», no «no hay sesión»). Nunca para una petición
      // de una época anterior: tras cerrar sesión, un refresh tardío podría
      // devolver al navegador cookies válidas de la sesión que se acaba de cerrar.
      canRefresh: (usedKey) => sessionRef.current !== 'none' && usedKey === epochRef.current,
      onUnauthorized: (usedKey) => {
        // Ignora el 401 si ya no hay sesión o si es de una época anterior
        // (respuesta tardía tras volver a entrar).
        if (sessionRef.current === 'none' || usedKey !== epochRef.current) return;
        if (sessionRef.current === 'unknown') {
          // Primer chequeo: un 401 solo significa «no hay sesión». Sin aviso de
          // «caducada»; solo si la comprobación la pidió otra pestaña que acaba de
          // cerrar la sesión que ESTA mostraba, se dice que se cerró allí.
          const check = elsewhereRef.current;
          elsewhereRef.current = null;
          applySession('none');
          if (check?.epoch === usedKey && typeof check.previousUserId === 'number') {
            toast.info(SESSION_CLOSED_ELSEWHERE);
          }
          return;
        }
        applySession('none');
        // Sin bucle: quien recibe el aviso vuelve a preguntar, pero nunca lo reenvía.
        announceSessionChange();
        toast.info('Tu sesión ha caducado. Inicia sesión de nuevo.');
        // Limpia las cookies y revoca en el servidor lo que quede de la sesión
        // (best-effort, sin bucle: es `public`). Si el 401 vino del refresh, el
        // servidor ya las borró; si vino de la repetición tras renovar, no.
        apiFetch('/auth/logout', { method: 'POST', public: true }).catch(() => undefined);
      },
      onSessionChangedElsewhere: () => {
        // Quién había antes, para avisar solo si de verdad cambia. Si ya había una
        // comprobación pendiente (dos avisos seguidos: salir y entrar con otra
        // cuenta), se conserva su «antes», que es lo que la persona veía.
        const pending = elsewhereRef.current;
        let previousUserId: number | null | undefined;
        if (pending) previousUserId = pending.previousUserId;
        else if (sessionRef.current === 'none') previousUserId = null;
        else if (sessionRef.current === 'active' && resultRef.current?.epoch === epochRef.current) {
          previousUserId = resultRef.current.user?.id;
        }
        // Lo que esta pestaña sabía (usuario, rol, caché) ya no vale: vuelve a
        // preguntar al servidor como en el arranque. `applySession` vacía la caché
        // y, al cambiar de época, cancela lo que estaba en vuelo; el 401 de una
        // petición vieja ya no cierra nada (es de otra época). Nunca se reenvía el
        // aviso: así no hay bucles entre pestañas.
        applySession('unknown');
        elsewhereRef.current = { epoch: epochRef.current, previousUserId };
      },
    });
    return () => configureAuth(null);
  }, [applySession, toast]);

  // Carga el usuario (y con ello descubre la sesión) mientras pueda haberla:
  // al arrancar, en cada reintento y cuando cambia la época.
  const noSession = session === 'none';
  useEffect(() => {
    if (noSession) return;
    // `login` ya dejó el usuario cargado: no se repite la petición.
    const existing = resultRef.current;
    if (existing?.epoch === epoch && existing.attempt === attempt && existing.status === 'ready') return;
    const controller = new AbortController();
    apiFetch<User>('/users/me', { signal: controller.signal })
      .then((me) => {
        if (controller.signal.aborted) return;
        if (sessionRef.current === 'unknown') {
          sessionRef.current = 'active'; // 200: hay sesión (la época no cambia)
          setSession('active');
        }
        storeResult({ epoch, attempt, status: 'ready', user: me });
        const check = elsewhereRef.current;
        if (check?.epoch === epoch) {
          elsewhereRef.current = null;
          // Solo si ahora hay OTRA persona dentro (o antes no había nadie): si es
          // la misma cuenta, para quien mira esta pestaña no ha cambiado nada.
          if (check.previousUserId !== undefined && check.previousUserId !== me.id) {
            toast.info(SESSION_SWITCHED_ELSEWHERE(me.username));
          }
        }
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        // Un 401 ya lo gestionó `apiFetch` (sesión cerrada, aviso único si procede).
        if (sessionRef.current === 'none' || epochRef.current !== epoch) return;
        if (elsewhereRef.current?.epoch === epoch) elsewhereRef.current = null;
        // Red caída, 5xx...: se puede reintentar con `refreshUser()`. Con la
        // sesión ya confirmada se mantiene (sin rol: `isAdmin = false`); en el
        // arranque queda «sin saber» (ver `sessionCheckFailed`).
        storeResult({ epoch, attempt, status: 'error', user: null });
      });
    return () => controller.abort();
    // `session` solo pasa de `unknown` a `active` dentro de esta misma petición
    // (la época no cambia) y no debe relanzarla: por eso la dependencia es
    // `noSession` y no `session`.
  }, [noSession, epoch, attempt, storeResult, toast]);

  // El resultado guardado solo vale si es de ESTA época y de ESTE intento.
  const current = session !== 'none' && userResult?.epoch === epoch && userResult.attempt === attempt ? userResult : null;
  const user = current?.user ?? null;
  const userStatus: UserStatus = session === 'none' ? 'idle' : (current?.status ?? 'loading');

  const value = useMemo<AuthContextValue>(
    () => ({
      isAuthenticated: session === 'active',
      isCheckingSession: session === 'unknown' && current === null,
      sessionCheckFailed: session === 'unknown' && current?.status === 'error',
      user,
      isAdmin: user?.role === 'ADMIN',
      userStatus,
      refreshUser,
      login,
      logout,
      isLoggingOut,
    }),
    [session, current, user, userStatus, refreshUser, login, logout, isLoggingOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * Acceso a la sesión: `const { isAuthenticated, user, isAdmin, login, logout } = useAuth()`.
 *
 * @throws si se usa fuera de {@link AuthProvider}
 */
// oxlint-disable-next-line react/only-export-components -- patrón habitual: proveedor y hook comparten archivo
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth debe usarse dentro de <AuthProvider>.');
  return ctx;
}
