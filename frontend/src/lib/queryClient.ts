/**
 * Configuración de la caché de datos del servidor (TanStack Query).
 *
 * **Qué resuelve.** Antes cada hook de datos reimplementaba la carga, el error,
 * la cancelación de respuestas viejas y «cargar más», y no había caché: volver a
 * la portada repetía todas las peticiones y los géneros se pedían en cada página.
 * Ahora el `QueryClient` guarda cada respuesta con su clave (`lib/queryKeys.ts`),
 * la comparte entre pantallas y la invalida tras las escrituras del panel.
 *
 * **Lo que NO cambia:** toda petición sigue pasando por `apiFetch` (`lib/api.ts`),
 * que es quien maneja la sesión (cookie, refresh, 401/403/429, red caída). Las
 * `queryFn` solo le pasan el `signal` de TanStack para que cancele lo que ya no
 * se va a pintar.
 */
import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './api';

/**
 * Tiempo durante el que un dato se considera «fresco»: volver a una pantalla
 * dentro de ese plazo la pinta desde la caché SIN pedir nada al servidor.
 *
 * Un minuto porque el catálogo cambia poco y los cambios del propio panel ya
 * invalidan la caché al momento; lo que haga OTRA persona se ve, como mucho, un
 * minuto después (al volver a la pantalla, al recuperar el foco de la pestaña o
 * al recargar). Con 0 (el valor por defecto de TanStack) cada navegación
 * volvería a pedirlo todo, que es justo lo que se quería evitar.
 */
export const STALE_TIME_MS = 60_000;

/**
 * Tiempo que se guarda un dato que ya no usa ninguna pantalla (p. ej. la página
 * de una serie de la que se ha salido). Cinco minutos (el valor por defecto,
 * escrito para que se vea): «Atrás» enseña lo visitado al instante, y pasado ese
 * rato se libera la memoria.
 */
export const GC_TIME_MS = 5 * 60_000;

/** Reintentos automáticos como máximo (solo en fallos pasajeros, ver {@link isTransientError}). */
const MAX_RETRIES = 1;

/**
 * ¿Merece la pena reintentar este error? Solo los pasajeros: sin respuesta
 * (status 0: red caída, backend apagado, tiempo agotado) o 5xx (incluidos los
 * 502/503/504 del proxy con el backend reiniciándose).
 *
 * NUNCA un 4xx: un 401 ya pasó por la renovación de la sesión dentro de
 * `apiFetch` (repetirlo pediría otro refresh condenado a fallar), un 403 o un
 * 404 no van a cambiar en un segundo, y repetir un 429 solo alarga el bloqueo.
 * Tampoco errores que no son `ApiError` (un fallo de programación).
 *
 * @param error lo que lanzó la `queryFn`
 */
export function isTransientError(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.sessionExpired) return false;
  return error.status === 0 || error.status >= 500;
}

/**
 * Política de reintentos de las consultas: UNO, y solo en fallos pasajeros. El
 * valor por defecto de TanStack (3 reintentos ante cualquier error) haría
 * esperar unos 7 s antes de enseñar el error de un 404 y repetiría los 401.
 *
 * @param failureCount fallos anteriores a este (0 en el primero)
 * @param error el error de este intento
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  return failureCount < MAX_RETRIES && isTransientError(error);
}

/**
 * Crea el `QueryClient` de la aplicación (uno por montaje de `App`; los tests
 * crean el suyo con `src/test/queryClient.tsx`).
 *
 * - `staleTime` / `gcTime`: ver {@link STALE_TIME_MS} y {@link GC_TIME_MS}.
 * - `retry`: ver {@link shouldRetryQuery}. Las mutaciones nunca se reintentan
 *   solas: un `POST` repetido a ciegas podría duplicar una escritura.
 * - `refetchOnWindowFocus` (activo, el valor por defecto): al volver a la
 *   pestaña se refrescan SOLO los datos ya anticuados (más de un minuto). Así
 *   «Mi lista» se pone al día si se cambió en otra pestaña (las pestañas solo se
 *   avisan de los cambios de SESIÓN, no de los datos) sin una petición en cada
 *   cambio de foco.
 * - `networkMode: 'always'`: la petición se lanza aunque el navegador crea que
 *   no hay red. Con el modo por defecto (`online`), sin conexión las consultas
 *   quedarían «en pausa» —un esqueleto de carga sin fin— y los favoritos se
 *   quedarían esperando sin aviso; así `apiFetch` devuelve su error de red de
 *   siempre y la pantalla enseña «No se pudo conectar» con «Reintentar».
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: STALE_TIME_MS,
        gcTime: GC_TIME_MS,
        retry: shouldRetryQuery,
        refetchOnWindowFocus: true,
        networkMode: 'always',
      },
      mutations: {
        retry: false,
        networkMode: 'always',
      },
    },
  });
}

/** Lo mínimo de un resultado de TanStack que hace falta para {@link loadStatusOf}. */
interface QueryStateLike {
  data: unknown;
  isError: boolean;
  isFetching: boolean;
}

/**
 * Traduce el estado de una consulta a los tres estados que pintan las
 * pantallas, con las mismas reglas que tenían los hooks antes de TanStack:
 * - `ready` si hay datos, aunque un refresco en segundo plano haya fallado
 *   (mejor seguir enseñando lo cargado que tirarlo por un fallo pasajero);
 * - `error` si no hay datos y la última carga falló;
 * - `loading` en otro caso, también mientras se REINTENTA tras un error: al
 *   pulsar «Reintentar» la pantalla vuelve al esqueleto de carga, como antes.
 *
 * @param query resultado de `useQuery`/`useInfiniteQuery`
 */
export function loadStatusOf(query: QueryStateLike): 'loading' | 'ready' | 'error' {
  if (query.data !== undefined) return 'ready';
  if (query.isError && !query.isFetching) return 'error';
  return 'loading';
}
