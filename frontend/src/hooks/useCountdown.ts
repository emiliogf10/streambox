import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Cuenta atrás en segundos, pensada para el 429 (`Retry-After`): bloquea el
 * botón de enviar y muestra cuánto falta.
 *
 * Se calcula contra la hora de fin (`Date.now()`) en lugar de restar 1 en cada
 * tick: así no se desvía si el navegador ralentiza los temporizadores de una
 * pestaña en segundo plano.
 *
 * @param onEnd función opcional que se invoca cuando la cuenta llega a 0
 *              (p. ej. para borrar el mensaje de "demasiados intentos")
 * @param initialSeconds cuenta atrás con la que empieza (0 = ninguna). Va en el
 *              estado inicial y no en un `start` desde un efecto: así el primer
 *              pintado ya sale en espera, sin un instante con el botón activo
 *              (lo usa el «Reenviar el correo» de justo después del registro)
 * @returns `remaining` (segundos que faltan; 0 si no hay cuenta atrás) y
 *          `start(segundos)` para iniciarla
 */
export function useCountdown(onEnd?: () => void, initialSeconds = 0) {
  const [endsAt, setEndsAt] = useState<number | null>(() =>
    initialSeconds > 0 ? Date.now() + initialSeconds * 1000 : null,
  );
  const [remaining, setRemaining] = useState(initialSeconds > 0 ? initialSeconds : 0);
  // Se guarda la última versión del callback sin reiniciar el temporizador al cambiar.
  const onEndRef = useRef(onEnd);
  useEffect(() => {
    onEndRef.current = onEnd;
  });

  useEffect(() => {
    if (endsAt === null) return;

    const tick = () => {
      const left = Math.max(0, Math.ceil((endsAt - Date.now()) / 1000));
      setRemaining(left);
      if (left === 0) {
        setEndsAt(null);
        onEndRef.current?.();
      }
    };

    // Cada 250 ms (y no 1000) para que el número baje sin saltos aunque el tick se retrase.
    const timer = setInterval(tick, 250);
    return () => clearInterval(timer);
  }, [endsAt]);

  const start = useCallback((seconds: number) => {
    setRemaining(seconds);
    setEndsAt(Date.now() + seconds * 1000);
  }, []);

  return { remaining, start };
}
