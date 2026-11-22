/**
 * `<video>` simulado para los tests del reproductor y del guardado del progreso.
 *
 * jsdom crea el elemento pero no reproduce nada: `currentTime` no avanza,
 * `duration` es siempre `NaN`, `paused` siempre `true` y `play()` no está
 * implementado (escribe un error en la consola). Aquí se sustituyen esas
 * propiedades del elemento concreto por valores que el test controla, y se
 * lanzan los mismos eventos, en el mismo orden, que un navegador real:
 * - reproducir: `play` y un `timeupdate` cada 250 ms de vídeo;
 * - saltar con la barra: `seeking`, `timeupdate` (ya con el tiempo nuevo) y `seeked`;
 * - llegar al final: `timeupdate`, `pause` (con `ended` ya a `true`) y `ended`.
 */
import { act } from '@testing-library/react';

/** Estado del vídeo que controla el test. */
export interface FakeVideoState {
  currentTime: number;
  duration: number;
  paused: boolean;
  ended: boolean;
  seeking: boolean;
}

/** Controles del vídeo simulado. */
export interface FakeVideo {
  element: HTMLVideoElement;
  state: FakeVideoState;
  /** Carga los metadatos: fija la duración y lanza `loadedmetadata` y `loadeddata`. */
  loadMetadata: (duration: number) => void;
  /** Reproduce `seconds` segundos de vídeo (un `timeupdate` cada 250 ms). */
  play: (seconds: number) => void;
  /** Pausa (`pause`). */
  pause: () => void;
  /** Salta a `time` con la barra. */
  seek: (time: number) => void;
  /** Llega al final del vídeo. */
  end: () => void;
}

/**
 * Convierte un `<video>` (uno nuevo o el que ha pintado el componente) en un
 * vídeo controlable por el test.
 *
 * @param element el elemento; por defecto, uno nuevo sin montar
 * @param options `seekable: false` = el vídeo no deja saltar (servidor sin `Range`): cambiar `currentTime` no lo mueve
 */
export function fakeVideo(
  element: HTMLVideoElement = document.createElement('video'),
  options: { seekable?: boolean } = {},
): FakeVideo {
  const state: FakeVideoState = { currentTime: 0, duration: Number.NaN, paused: true, ended: false, seeking: false };
  Object.defineProperty(element, 'currentTime', {
    configurable: true,
    get: () => state.currentTime,
    // Como en un navegador, mover `currentTime` desde el código es un salto: `seeking` y, al llegar, `seeked`.
    // Con `seekable: false` (un servidor sin peticiones parciales) el navegador no salta y se queda donde estaba.
    set: (value: number) => {
      state.seeking = true;
      element.dispatchEvent(new Event('seeking'));
      if (options.seekable !== false) state.currentTime = value;
      state.seeking = false;
      element.dispatchEvent(new Event('seeked'));
    },
  });
  for (const key of ['duration', 'paused', 'ended', 'seeking'] as const) {
    Object.defineProperty(element, key, { configurable: true, get: () => state[key] });
  }
  element.play = () => {
    state.paused = false;
    state.ended = false;
    element.dispatchEvent(new Event('play'));
    return Promise.resolve();
  };
  element.pause = () => {
    if (state.paused) return;
    state.paused = true;
    element.dispatchEvent(new Event('pause'));
  };
  element.load = () => {};

  const fire = (type: string) => element.dispatchEvent(new Event(type));

  return {
    element,
    state,
    loadMetadata: (duration) =>
      act(() => {
        state.duration = duration;
        fire('loadedmetadata');
        fire('loadeddata');
        fire('canplay');
      }),
    play: (seconds) =>
      act(() => {
        if (state.paused) void element.play();
        const steps = Math.round(seconds / 0.25);
        for (let i = 0; i < steps; i++) {
          state.currentTime = Math.min(state.currentTime + 0.25, state.duration);
          fire('timeupdate');
        }
      }),
    pause: () => act(() => element.pause()),
    seek: (time) =>
      act(() => {
        state.seeking = true;
        fire('seeking');
        state.currentTime = time;
        fire('timeupdate');
        state.seeking = false;
        fire('seeked');
      }),
    end: () =>
      act(() => {
        state.currentTime = state.duration;
        state.ended = true;
        fire('timeupdate');
        state.paused = true;
        fire('pause');
        fire('ended');
      }),
  };
}
