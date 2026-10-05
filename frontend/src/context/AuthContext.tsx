import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch, configureAuth, isAbortError } from '../lib/api';
import type { User } from '../lib/types';
import { useToast } from './ToastContext';

/** Clave de `localStorage` donde se guarda el JWT. */
const TOKEN_KEY = 'token';

/**
 * Lee el token guardado. `localStorage` puede lanzar excepciones (modo privado
 * de algunos navegadores, almacenamiento bloqueado): en ese caso se trata como
 * "sin sesión" en lugar de romper la aplicación.
 */
function readStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Guarda o borra (con `null`) el token; ignora los fallos de almacenamiento. */
function writeStoredToken(token: string | null): void {
  try {
    if (token === null) localStorage.removeItem(TOKEN_KEY);
    else localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Sin almacenamiento: la sesión vivirá solo mientras no se recargue la página.
  }
}

/**
 * Estado de la carga del usuario actual (`GET /api/users/me`):
 * - `idle`: no hay sesión, no hay nada que cargar.
 * - `loading`: hay sesión y se está preguntando al servidor quién es.
 * - `ready`: `user` ya tiene los datos de la sesión actual.
 * - `error`: no se pudo cargar (red, 5xx...). La sesión sigue abierta, pero
 *   `isAdmin` es `false` hasta que un reintento salga bien.
 */
export type UserStatus = 'idle' | 'loading' | 'ready' | 'error';

/** Valor que expone {@link useAuth}. */
interface AuthContextValue {
  /** JWT actual, o `null` si no hay sesión. */
  token: string | null;
  /** `true` si hay una sesión iniciada. */
  isAuthenticated: boolean;
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
  /** Vuelve a pedir el usuario actual (p. ej. el botón "Reintentar" tras un fallo de red). */
  refreshUser: () => void;
  /** Guarda el token recibido del login y abre la sesión. */
  login: (token: string) => void;
  /** Cierra la sesión (idempotente). */
  logout: () => void;
}

/**
 * Resultado de una petición a `/users/me`, junto con el token y el intento que
 * la originaron. Guardar a qué sesión pertenece es lo que impide que el usuario
 * de una sesión anterior aparezca en la nueva (ver {@link AuthProvider}).
 */
interface UserResult {
  token: string;
  attempt: number;
  status: 'ready' | 'error';
  user: User | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Proveedor de la sesión: ÚNICA fuente de verdad del token y del usuario actual.
 *
 * Es el único sitio (junto con el puente de `api.ts`) que toca `localStorage`.
 * Se registra en `apiFetch` con `configureAuth`, de modo que:
 * - `apiFetch` lee el token de aquí al enviar cada petición.
 * - Ante un 401 en un endpoint autenticado, `apiFetch` avisa y aquí se cierra
 *   la sesión. No se navega desde aquí: al quedar `isAuthenticated = false`, la
 *   ruta protegida redirige a `/login` por sí sola, UNA vez, aunque fallen
 *   varias peticiones a la vez (el segundo aviso ya encuentra la sesión cerrada
 *   y no hace nada).
 *
 * **Usuario actual y rol.** Cada vez que cambia el token (login, arranque con un
 * token guardado, otra pestaña) se pide `GET /api/users/me`. El rol NO se saca
 * del JWT por dos motivos: el token no lo lleva (solo `sub` = email e `iss`), y
 * aunque lo llevara quedaría desfasado hasta que caducase (24 h), mientras que el
 * backend lee el usuario de la base de datos en cada petición y un cambio de rol
 * es inmediato. Preguntar al servidor da siempre su verdad. Aun así, el rol en el
 * cliente solo decide qué se muestra: quien proteja los datos es el backend (403).
 *
 * **Respuestas tardías.** El resultado se guarda junto con el token (y el
 * intento) que lo pidió, y `user`/`userStatus` se DERIVAN comparándolo con la
 * sesión actual. Si el token ya cambió, ese resultado simplemente no cuenta: un
 * usuario de la sesión anterior nunca se asigna a la nueva, ni siquiera durante
 * el render que hay entre `login()` y la siguiente petición. Además, cada cambio
 * cancela la petición en curso con `AbortController`.
 *
 * Debe ir dentro de `ToastProvider` (muestra el aviso de sesión caducada).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [token, setToken] = useState<string | null>(readStoredToken);
  // Copia síncrona del token para `apiFetch`: el estado de React se actualiza
  // tras el render, pero una petición lanzada justo después de `login` ya debe
  // llevar el token nuevo.
  const tokenRef = useRef<string | null>(token);
  // Contador de reintentos de `/users/me`: cambiarlo vuelve a lanzar la petición
  // con el mismo token y, como forma parte de la "clave" del resultado, el
  // estado pasa a `loading` mientras tanto en lugar de quedarse en `error`.
  const [attempt, setAttempt] = useState(0);
  const [userResult, setUserResult] = useState<UserResult | null>(null);

  /**
   * Cambia la sesión en memoria (sin tocar `localStorage`) y olvida el usuario
   * anterior. Lo comparten login, logout y el evento `storage` de otra pestaña.
   */
  const applyToken = useCallback((next: string | null) => {
    tokenRef.current = next;
    setToken(next);
    setUserResult(null);
  }, []);

  const login = useCallback(
    (newToken: string) => {
      writeStoredToken(newToken);
      applyToken(newToken);
    },
    [applyToken],
  );

  const logout = useCallback(() => {
    writeStoredToken(null);
    applyToken(null);
  }, [applyToken]);

  const refreshUser = useCallback(() => setAttempt((n) => n + 1), []);

  // `useLayoutEffect` (y no `useEffect`) porque los efectos de los componentes
  // hijos —que ya lanzan peticiones al montarse— se ejecutan ANTES que los del
  // padre; los layout effects, en cambio, corren todos antes que cualquier
  // `useEffect`, así que el puente está listo cuando llega la primera petición.
  useLayoutEffect(() => {
    configureAuth({
      getToken: () => tokenRef.current,
      onUnauthorized: (usedToken) => {
        // Ignora el 401 si ya no hay sesión o si pertenece a una petición hecha
        // con un token anterior (p. ej. respuesta tardía tras volver a entrar).
        if (tokenRef.current === null || usedToken !== tokenRef.current) return;
        logout();
        toast.info('Tu sesión ha caducado. Inicia sesión de nuevo.');
      },
    });
    return () => configureAuth(null);
  }, [logout, toast]);

  // Carga el usuario de la sesión actual cada vez que cambia el token (o se
  // pide un reintento). `login()` sigue siendo síncrono: esto va por detrás.
  useEffect(() => {
    if (token === null) return;
    const controller = new AbortController();
    apiFetch<User>('/users/me', { signal: controller.signal })
      .then((me) => {
        if (!controller.signal.aborted) setUserResult({ token, attempt, status: 'ready', user: me });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        // Un 401 ya lo ha gestionado `apiFetch` (cierre de sesión y aviso únicos):
        // la sesión de este token ya no existe y no hay nada que guardar.
        if (tokenRef.current !== token) return;
        // Red caída, 5xx...: la sesión se mantiene, pero sin rol confirmado
        // (`isAdmin = false`). Se puede reintentar con `refreshUser()`.
        setUserResult({ token, attempt, status: 'error', user: null });
      });
    return () => controller.abort();
  }, [token, attempt]);

  // Si se cierra o abre sesión en otra pestaña, esta se mantiene coherente.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      // `key === null` es lo que emite `localStorage.clear()`: se vació todo el
      // almacenamiento, así que equivale a que la clave del token pase a `null`.
      // Cualquier otra clave no nos afecta.
      if (event.key !== null && event.key !== TOKEN_KEY) return;
      // Solo una cadena no vacía cuenta como token nuevo; cualquier otra cosa
      // (null, cadena vacía) se trata como cierre de sesión.
      const next = event.key === null || !event.newValue ? null : event.newValue;
      applyToken(next);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [applyToken]);

  // El resultado guardado solo vale si es de ESTA sesión y de ESTE intento.
  const current =
    token !== null && userResult?.token === token && userResult.attempt === attempt ? userResult : null;
  const user = current?.user ?? null;
  const userStatus: UserStatus = token === null ? 'idle' : (current?.status ?? 'loading');

  const value = useMemo<AuthContextValue>(
    () => ({
      token,
      isAuthenticated: token !== null,
      user,
      isAdmin: user?.role === 'ADMIN',
      userStatus,
      refreshUser,
      login,
      logout,
    }),
    [token, user, userStatus, refreshUser, login, logout],
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
