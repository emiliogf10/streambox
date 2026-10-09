/**
 * Reloj falso MANUAL para los tests que dependen de un `setTimeout`: los
 * *debounce* del buscador de la barra, del buscador del panel y de la vista
 * previa de la portada.
 *
 * **Por qué.** Con el reloj real, un test que escribe y después espera con
 * `waitFor`/`findBy*` (1 s por defecto) a que pase un debounce de 300-400 ms solo
 * deja unos 600 ms de margen para todo lo demás (navegación, petición simulada,
 * repintado). En solitario sobra; con la suite completa en paralelo la máquina va
 * varias veces más lenta, el margen se agota y el test falla "a veces" sin que el
 * código haya cambiado. Con este reloj el tiempo SOLO avanza cuando el test lo
 * pide ({@link passTime}): el resultado no depende de lo cargada que esté la
 * máquina, y además se puede comprobar el retraso exacto (nada a los 299 ms, la
 * petición a los 300).
 *
 * Solo se falsean `setTimeout` y `clearTimeout`, que es lo que usan los debounce.
 * El planificador de React y las promesas del `fetch` simulado siguen siendo
 * reales, así que lo demás funciona igual que sin reloj falso.
 *
 * **Uso:** `installManualTimers()` en `beforeEach`, `vi.useRealTimers()` en
 * `afterEach` (el global `jest` del puente lo retira solo `unstubGlobals: true`
 * de `vite.config.ts`) y `userEvent.setup({ delay: null })`, porque las pausas
 * propias de user-event también usarían el reloj falso.
 */
import { act } from '@testing-library/react';
import { vi } from 'vitest';

/**
 * Activa el reloj falso manual.
 *
 * Testing Library solo sabe convivir con temporizadores falsos si existe un
 * global `jest` con `advanceTimersByTime` (su detección es específica de Jest).
 * Sin él, `findBy*`, `waitFor` y user-event se quedarían esperando un
 * `setTimeout` que nunca vence y el test se colgaría. Este puente, recomendado
 * por la documentación de Vitest, hace que avancen el reloj falso en su lugar.
 *
 * @param options.withDate falsea también `Date` (avanza solo con {@link passTime}).
 *        Hace falta cuando el código MIDE el tiempo transcurrido con `Date.now()`,
 *        como la pausa de los avisos al ocultar la pestaña (`components/Toast`). No
 *        es el valor por defecto para no cambiar la fecha que ven los demás tests.
 */
export function installManualTimers({ withDate = false }: { withDate?: boolean } = {}): void {
  vi.useFakeTimers({ toFake: withDate ? ['setTimeout', 'clearTimeout', 'Date'] : ['setTimeout', 'clearTimeout'] });
  vi.stubGlobal('jest', { advanceTimersByTime: (ms: number) => vi.advanceTimersByTime(ms) });
}

/**
 * Avanza el reloj falso `ms` milisegundos dentro de `act`: los temporizadores
 * que vencen se ejecutan y React aplica sus cambios de estado (y los efectos que
 * desencadenan, como lanzar una petición) antes de volver al test.
 *
 * @param ms milisegundos que se dejan pasar
 */
export function passTime(ms: number): void {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}
