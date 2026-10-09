/**
 * `navigator.locks` (Web Locks API) simulado para los tests.
 *
 * jsdom no lo implementa, así que por defecto `lib/api.ts` usa su cola local de
 * reserva. Para probar la rama con Web Locks —la que coordina las pestañas— se
 * instala este simulacro: un lock EXCLUSIVO por nombre con cola FIFO, que es lo
 * único que usa la aplicación (`navigator.locks.request(nombre, callback)`). Como
 * en el navegador, la promesa de `request` se resuelve con lo que devuelva el
 * callback y el lock se suelta cuando ese resultado termina.
 *
 * «Otra pestaña» se simula cogiendo el lock desde el test con {@link FakeLocks.hold}:
 * mientras no se suelte, las peticiones de la app esperan, igual que esperarían a
 * una pestaña que está renovando la sesión.
 */

/** Simulacro instalado y lo que el test puede consultar o forzar. */
export interface FakeLocks {
  /** Nombres pedidos con `request`, en orden (también los que aún esperan). */
  readonly requested: string[];
  /**
   * Coge el lock `name` como lo haría otra pestaña. Devuelve una función que lo
   * suelta; la promesa `acquired` se resuelve cuando de verdad se tiene.
   */
  hold: (name: string) => { acquired: Promise<void>; release: () => void };
  /** Quita el simulacro: `navigator.locks` vuelve a no existir. */
  uninstall: () => void;
}

/** Instala un `navigator.locks` simulado (ver la cabecera del archivo). */
export function installFakeLocks(): FakeLocks {
  const queues = new Map<string, Promise<unknown>>();
  const requested: string[] = [];

  /** Pone `task` a la cola del lock `name` y devuelve su resultado. */
  function enqueue<T>(name: string, task: () => T | Promise<T>): Promise<T> {
    const previous = queues.get(name) ?? Promise.resolve();
    const run = previous.then(() => task());
    queues.set(
      name,
      run.catch(() => undefined),
    );
    return run;
  }

  const locks = {
    request: (name: string, callback: (lock: { name: string; mode: 'exclusive' }) => unknown) => {
      requested.push(name);
      return enqueue(name, () => callback({ name, mode: 'exclusive' }));
    },
    query: async () => ({ held: [], pending: [] }),
  };

  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true });

  return {
    requested,
    hold: (name) => {
      let release!: () => void;
      let markAcquired!: () => void;
      const acquired = new Promise<void>((resolve) => {
        markAcquired = resolve;
      });
      const released = new Promise<void>((resolve) => {
        release = resolve;
      });
      void enqueue(name, () => {
        markAcquired();
        return released;
      });
      return { acquired, release };
    },
    uninstall: () => {
      Reflect.deleteProperty(navigator, 'locks');
    },
  };
}
