/**
 * Revisión independiente (QA) de `lib/playback.ts`.
 *
 * `isFinishedAt` es una copia en el cliente de la regla del servidor
 * (`WatchProgress.isFinished`: `posición × 100 >= duración × 95`, en enteros).
 * El cliente la calcula con decimales (`duración × 0.95`), y 0.95 no es exacto
 * en binario: si alguna duración diera un resultado por encima del entero,
 * la caché diría «a medias» lo que el servidor ya da por visto. Se comprueban
 * todas las duraciones admitidas (1 s a 24 h) en las posiciones alrededor del umbral.
 */
import { describe, expect, it } from 'vitest';
import { isFinishedAt, MAX_DURATION_SECONDS, toProgressPayload } from './playback';

/** La regla del servidor, en aritmética entera. */
function serverFinished(positionSeconds: number, durationSeconds: number): boolean {
  return positionSeconds * 100 >= durationSeconds * 95;
}

describe('isFinishedAt (QA): igual que el servidor en todas las duraciones', () => {
  it('de 1 s a 24 h, en las posiciones junto al 95 %, cliente y servidor coinciden', () => {
    const mismatches: string[] = [];
    for (let duration = 1; duration <= MAX_DURATION_SECONDS; duration++) {
      const threshold = Math.floor((duration * 95) / 100);
      for (const position of [threshold - 1, threshold, threshold + 1, threshold + 2]) {
        if (position < 0 || position > duration) continue;
        if (isFinishedAt(position, duration) !== serverFinished(position, duration)) {
          mismatches.push(`${position}/${duration}`);
        }
      }
    }
    expect(mismatches).toEqual([]);
  });
});

describe('toProgressPayload (QA): nunca algo que la API rechace', () => {
  it.each([
    ['tiempo negativo', -3, 100, false, { positionSeconds: 0, durationSeconds: 100 }],
    ['tiempo NaN', Number.NaN, 100, false, { positionSeconds: 0, durationSeconds: 100 }],
    ['duración por debajo de 1 s', 0.4, 0.9, false, null],
    ['duración de más de 24 h', 10, MAX_DURATION_SECONDS + 1, false, null],
    ['duración NaN (sin metadatos)', 10, Number.NaN, false, null],
    ['terminado con duración decimal', 99.9, 99.9, true, { positionSeconds: 99, durationSeconds: 99 }],
  ])('%s', (_name, currentTime, duration, ended, expected) => {
    expect(toProgressPayload(currentTime, duration, ended)).toEqual(expected);
  });
});
