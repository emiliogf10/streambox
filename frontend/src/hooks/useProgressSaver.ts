import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { ApiError, apiFetch, currentSessionKey } from '../lib/api';
import { isFinishedAt, MIN_WATCHED_SECONDS, playbackStep, SAVE_INTERVAL_MS, toProgressPayload } from '../lib/playback';
import { queryKeys } from '../lib/queryKeys';
import type { WatchProgress, WatchProgressRequest } from '../lib/types';

/**
 * Espera tras el último salto con la barra (`seeked`) antes de guardar la
 * posición nueva: al arrastrar el control, el navegador lanza un `seeked` por
 * cada punto por el que pasa y basta con guardar dónde se suelta.
 */
export const SEEK_SAVE_DELAY_MS = 1_000;

/** Qué se está viendo: una película o un episodio (con su serie, para poner al día su página). */
export type ProgressTarget = { kind: 'movie'; id: number } | { kind: 'episode'; id: number; seriesId: number };

/** Ruta del `PUT` de progreso del título. */
function progressPath(target: ProgressTarget): string {
  return target.kind === 'movie' ? `/users/me/progress/movies/${target.id}` : `/users/me/progress/episodes/${target.id}`;
}

/**
 * Errores tras los que no se vuelve a intentar guardar mientras siga abierto el
 * reproductor, porque repetir no los arreglaría:
 * - 409 `NO_VIDEO_AVAILABLE`: el título ya no tiene vídeo (lo han quitado
 *   mientras se veía);
 * - 404: el título ya no existe; 400: la API rechaza los datos (no cambiarán);
 *   403: no se tiene permiso;
 * - la sesión ha terminado (401 sin arreglo): de eso se encarga `AuthContext`,
 *   que lleva al login.
 *
 * Todo lo demás (red caída, 5xx, 429) es pasajero: se reintenta en el siguiente
 * guardado, sin avisos. Un aviso cada 15 s por un corte de red sería peor que
 * perder unos segundos de progreso.
 */
function isPermanentFailure(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return error.sessionExpired || [400, 403, 404, 409].includes(error.status);
}

/**
 * Pone la caché al día tras un guardado correcto:
 * - el progreso del título se ESCRIBE con lo enviado (sin volver a pedirlo: el
 *   reproductor lo tiene en pantalla y pedirlo cada 15 s no aportaría nada);
 * - «Seguir viendo» y, en un episodio, el progreso de su serie se DESCARTAN:
 *   los vuelve a pedir quien los enseñe (la portada, la página de la serie).
 *
 * Antes se cancelan las lecturas en vuelo de esas claves: una empezada antes de
 * guardar traería el progreso de antes y lo dejaría como bueno. Esto importa al
 * cerrar el reproductor: el último guardado sale al desmontarse y la portada se
 * monta a la vez y pide «Seguir viendo»; si esa lectura llega antes que el
 * guardado, la invalidación de aquí la repite con el dato ya escrito.
 */
async function applySaved(queryClient: QueryClient, target: ProgressTarget, saved: WatchProgressRequest) {
  const titleKey = target.kind === 'movie' ? queryKeys.progress.movie(target.id) : queryKeys.progress.episode(target.id);
  const staleKeys = [
    queryKeys.progress.continueWatchingAll,
    ...(target.kind === 'episode' ? [queryKeys.progress.series(target.seriesId)] : []),
  ];
  await Promise.all([titleKey, ...staleKeys].map((queryKey) => queryClient.cancelQueries({ queryKey })));
  const progress: WatchProgress = {
    ...saved,
    finished: isFinishedAt(saved.positionSeconds, saved.durationSeconds),
    updatedAt: new Date().toISOString(),
  };
  queryClient.setQueryData<WatchProgress | null>(titleKey, progress);
  await Promise.all(staleKeys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

/**
 * Guarda el progreso del `<video>` del reproductor
 * (`PUT /api/users/me/progress/movies|episodes/{id}`, segundos enteros).
 *
 * **Cuándo guarda** (lo que se ve y se oye no cambia: es todo en segundo plano):
 * - cada {@link SAVE_INTERVAL_MS} mientras se reproduce;
 * - al pausar y al terminar (`pause`, que el navegador lanza también al llegar
 *   al final; terminado = posición igual a la duración, ver `toProgressPayload`);
 * - al saltar con la barra, {@link SEEK_SAVE_DELAY_MS} después del último salto
 *   (quien adelanta hasta el final de una escena y cierra, sigue desde ahí);
 * - al cerrar el reproductor (el componente se desmonta: «Volver», Escape,
 *   «Atrás» del navegador...);
 * - al ocultarse la pestaña (`visibilitychange` → `hidden`: cambiar de pestaña,
 *   bloquear el móvil, pasar a otra aplicación) y al salir de la página
 *   (`pagehide`: cerrarla o recargarla). En el móvil, `hidden` puede ser lo
 *   último que llega antes de que el sistema mate la pestaña.
 *
 * **`keepalive` en los guardados de salida.** Una petición normal se cancela al
 * cerrar la página; con `keepalive: true`, el navegador la termina por su
 * cuenta. Va con `fetch` (a través de `apiFetch`) y no con `navigator.sendBeacon`,
 * que es lo habitual para esto, porque `sendBeacon` no puede añadir cabeceras y
 * el backend exige `X-Requested-With` en todo `PUT` (defensa CSRF).
 *
 * **Cuándo NO guarda.**
 * - Antes de {@link MIN_WATCHED_SECONDS} de reproducción REAL (sumando los
 *   avances de `timeupdate`; los saltos con la barra no cuentan, ver
 *   `playbackStep`): abrir un título por error y cerrarlo no debe llenar «Seguir
 *   viendo».
 * - Si no ha cambiado nada desde el último guardado (pausar y cerrar enseguida
 *   enviaría lo mismo dos veces).
 * - Sin duración conocida (el vídeo aún no ha cargado).
 * - Tras un fallo definitivo (ver {@link isPermanentFailure}); los pasajeros
 *   se reintentan en el siguiente guardado, sin avisos.
 * - Si ha cambiado la sesión desde que se abrió (otra persona ha entrado en
 *   otra pestaña): el progreso no es suyo.
 *
 * Recibe el elemento (y no una `ref`) para engancharse a él en cuanto existe:
 * el reproductor lo monta cuando ha cargado el título, y lo vuelve a montar al
 * pulsar «Reintentar» tras un error del vídeo. Quien lo usa debe dar a cada
 * título su propio `<video>` (`key`): si el mismo elemento cambiara de `src`,
 * el guardado de despedida del título anterior leería el tiempo del nuevo.
 *
 * @param video el `<video>` del reproductor, o `null` mientras no existe
 * @param target qué se está viendo, o `null` si no se puede guardar (vídeo que no se abre aquí)
 */
export function useProgressSaver(video: HTMLVideoElement | null, target: ProgressTarget | null): void {
  const queryClient = useQueryClient();
  const kind = target?.kind;
  const id = target?.id;
  const seriesId = target?.kind === 'episode' ? target.seriesId : undefined;

  useEffect(() => {
    if (!video || kind === undefined || id === undefined) return;
    const current: ProgressTarget =
      kind === 'movie' ? { kind, id } : { kind: 'episode', id, seriesId: seriesId ?? 0 };
    const path = progressPath(current);
    const sessionKey = currentSessionKey();
    let watchedSeconds = 0;
    let lastTime = video.currentTime;
    /** «posición/duración» del último guardado enviado (o en vuelo), para no repetirlo. */
    let lastSent: string | null = null;
    let stopped = false;

    const save = (keepalive: boolean) => {
      if (stopped || watchedSeconds < MIN_WATCHED_SECONDS) return;
      if (currentSessionKey() !== sessionKey) {
        stopped = true;
        return;
      }
      const payload = toProgressPayload(video.currentTime, video.duration, video.ended);
      if (!payload) return;
      const signature = `${payload.positionSeconds}/${payload.durationSeconds}`;
      if (signature === lastSent) return;
      lastSent = signature;
      apiFetch(path, { method: 'PUT', body: payload, keepalive }).then(
        () => {
          if (currentSessionKey() === sessionKey) void applySaved(queryClient, current, payload);
        },
        (error: unknown) => {
          // Pasajero: se olvida para que el siguiente guardado lo vuelva a intentar.
          if (lastSent === signature) lastSent = null;
          if (isPermanentFailure(error)) stopped = true;
        },
      );
    };

    const onTimeUpdate = () => {
      if (!video.seeking) watchedSeconds += playbackStep(lastTime, video.currentTime);
      lastTime = video.currentTime;
    };
    // Tras un salto con la barra se guarda la posición nueva, pero un segundo después del último: arrastrar el
    // control lanza un `seeked` por cada punto por el que pasa, y solo importa dónde se suelta.
    let seekTimer: ReturnType<typeof setTimeout> | undefined;
    const onSeeked = () => {
      lastTime = video.currentTime;
      clearTimeout(seekTimer);
      seekTimer = setTimeout(() => save(false), SEEK_SAVE_DELAY_MS);
    };
    const onPause = () => save(false);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') save(true);
    };
    const onPageHide = () => save(true);
    const timer = setInterval(() => {
      if (!video.paused && !video.ended) save(false);
    }, SAVE_INTERVAL_MS);

    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('seeked', onSeeked);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onPause);
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      clearInterval(timer);
      clearTimeout(seekTimer);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('seeked', onSeeked);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onPause);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('pagehide', onPageHide);
      // Despedida: se cierra el reproductor (o cambia de título). `keepalive` por si es porque se cierra la página.
      save(true);
    };
  }, [video, kind, id, seriesId, queryClient]);
}
