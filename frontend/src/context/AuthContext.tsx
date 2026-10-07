import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, configureAuth, isAbortError } from '../lib/api';
import type { User } from '../lib/types';
import { useToast } from './ToastContext';

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
  /** Cierra la sesión: el estado se limpia al instante y se pide al servidor borrar la cookie. */
  logout: () => Promise<void>;
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
 * Se registra en `apiFetch` con `configureAuth`: ante un 401 en un endpoint
 * autenticado con la sesión abierta se cierra la sesión UNA vez y se avisa. No
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
 * **Limitación conocida:** al no haber `localStorage`, las pestañas ya no se
 * sincronizan al instante. Si cierras sesión en una, la otra lo descubre en su
 * siguiente petición (401, con su aviso). La cookie sí es común a todas.
 *
 * Debe ir dentro de `ToastProvider` (muestra el aviso de sesión caducada).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
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

  /** Guarda el resultado en el estado y en su copia síncrona. */
  const storeResult = useCallback((result: UserResult | null) => {
    resultRef.current = result;
    setUserResult(result);
  }, []);

  /** Cambia la sesión en memoria y empieza una época nueva (olvida el usuario anterior). */
  const applySession = useCallback(
    (next: SessionState) => {
      sessionRef.current = next;
      epochRef.current += 1;
      setSession(next);
      setEpoch(epochRef.current);
      storeResult(null);
    },
    [storeResult],
  );

  const logout = useCallback(async () => {
    if (sessionRef.current === 'none') return; // idempotente
    applySession('none');
    try {
      // `public`: un 401 aquí (cookie ya caducada) no debe disparar otro cierre.
      await apiFetch('/auth/logout', { method: 'POST', public: true });
    } catch {
      // Best-effort: la interfaz ya está sin sesión. Si el servidor no respondió,
      // la cookie caducará sola (y un 401 posterior se tratará como sin sesión).
    }
  }, [applySession]);

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
      onUnauthorized: (usedKey) => {
        // Ignora el 401 si ya no hay sesión o si es de una época anterior
        // (respuesta tardía tras volver a entrar).
        if (sessionRef.current === 'none' || usedKey !== epochRef.current) return;
        if (sessionRef.current === 'unknown') {
          // Primer chequeo: un 401 solo significa «no hay sesión». Sin aviso.
          applySession('none');
          return;
        }
        applySession('none');
        toast.info('Tu sesión ha caducado. Inicia sesión de nuevo.');
        // Limpia la cookie caducada o revocada (best-effort, sin bucle: es `public`).
        apiFetch('/auth/logout', { method: 'POST', public: true }).catch(() => undefined);
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
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        // Un 401 ya lo gestionó `apiFetch` (sesión cerrada, aviso único si procede).
        if (sessionRef.current === 'none' || epochRef.current !== epoch) return;
        // Red caída, 5xx...: se puede reintentar con `refreshUser()`. Con la
        // sesión ya confirmada se mantiene (sin rol: `isAdmin = false`); en el
        // arranque queda «sin saber» (ver `sessionCheckFailed`).
        storeResult({ epoch, attempt, status: 'error', user: null });
      });
    return () => controller.abort();
    // `session` solo pasa de `unknown` a `active` dentro de esta misma petición
    // (la época no cambia) y no debe relanzarla: por eso la dependencia es
    // `noSession` y no `session`.
  }, [noSession, epoch, attempt, storeResult]);

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
    }),
    [session, current, user, userStatus, refreshUser, login, logout],
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
