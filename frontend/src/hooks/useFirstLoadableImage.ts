import { useEffect, useState } from 'react';

/** Qué candidata se está usando y para qué lista (la lista se identifica por su texto). */
interface Choice {
  key: string;
  index: number;
}

/**
 * Elige la primera imagen de `candidates` que de verdad carga, para usarla en
 * un sitio que **no avisa cuando la imagen falla**: el atributo `poster` del
 * `<video>` (si su URL da 404, el vídeo se queda en negro sin ningún evento).
 *
 * Cómo lo hace (incidencia QA-V16-1):
 * - **Devuelve la primera candidata desde el principio**, sin esperar a nada:
 *   el caso normal (la imagen existe) no se retrasa ni un fotograma.
 * - A la vez la comprueba con `new Image()`. Si da `error`, pasa a la
 *   siguiente, y así sucesivamente. Si carga, no hace nada más.
 * - **No comprueba la última candidata**: detrás no hay nada a lo que pasar,
 *   así que esa petición no serviría de nada. Por lo mismo, con una sola
 *   candidata no hace ninguna petición extra.
 * - La comprobación pide la imagen igual que el `poster` (misma URL y la
 *   política de *referrer* del documento; `<video>` no tiene `referrerpolicy`):
 *   así la segunda petición sale de la caché del navegador y el resultado es el
 *   mismo que verá el vídeo (un servidor que filtra por `Referer` respondería
 *   igual a las dos). No se envía nada que el `poster` no envíe ya.
 * - **Se reinicia al cambiar la lista** (otro título u otro episodio): la
 *   elección se guarda junto al texto de la lista, y una lista distinta vuelve
 *   a empezar por su primera candidata sin pasar por un estado intermedio.
 * - Al desmontar o cambiar de candidata, la comprobación anterior se ignora (un
 *   `error` que llegue tarde no puede saltarse una imagen buena).
 *
 * @param candidates URLs en orden de preferencia, sin vacías ni repetidas (ver
 *   `lib/titleImages.ts`).
 * @returns La URL que usar ahora, o `undefined` si la lista está vacía.
 */
export function useFirstLoadableImage(candidates: readonly string[]): string | undefined {
  // Separador que no aparece en una URL válida: dos listas distintas nunca dan el mismo texto.
  const key = candidates.join('\n');
  const [choice, setChoice] = useState<Choice>({ key, index: 0 });
  const index = choice.key === key ? choice.index : 0;
  const current = candidates[index];
  const hasNext = index < candidates.length - 1;

  useEffect(() => {
    if (current === undefined || !hasNext) return;
    let active = true;
    const probe = new Image();
    probe.decoding = 'async';
    probe.onerror = () => {
      if (active) setChoice({ key, index: index + 1 });
    };
    probe.src = current;
    return () => {
      active = false;
      probe.onerror = null;
    };
  }, [current, hasNext, index, key]);

  return current;
}
