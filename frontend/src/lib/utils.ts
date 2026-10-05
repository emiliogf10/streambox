/** Formatea duración en minutos → "1h 48m" */
export function formatDuration(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/**
 * Formatea una espera en segundos para leerla de un vistazo: "45 s", "15 min",
 * "14 min 5 s".
 *
 * Existe porque el bloqueo de cuenta por intentos fallidos dura 15 minutos y
 * "Reintentar en 900 s" obliga a hacer la cuenta de cabeza. Por debajo del
 * minuto se mantiene el formato en segundos que ya usaba el límite por IP.
 *
 * @param totalSeconds segundos que faltan (se redondea hacia arriba; negativos = 0)
 */
export function formatWaitTime(totalSeconds: number): string {
  const seconds = Math.max(0, Math.ceil(totalSeconds));
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes === 0) return `${rest} s`;
  return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}

/**
 * Valida que una URL de vídeo sea segura para abrirla en una pestaña nueva.
 *
 * Solo se admiten los esquemas `http` y `https`: un valor como
 * `javascript:...` o `data:...` se podría ejecutar al hacer clic (XSS). El
 * backend valida el campo con `@URL` (formato de URL de Java), que NO restringe
 * el esquema a http(s), por eso el cliente lo comprueba por su cuenta.
 *
 * También se rechazan las URL con credenciales embebidas (`user:pw@host`): son
 * un truco de suplantación, porque `https://netflix.com@evil.com/` parece de un
 * dominio de confianza pero en realidad navega a `evil.com`.
 *
 * @param rawUrl URL tal como viene de la API
 * @returns la URL normalizada, o `null` si falta, no es http(s) o lleva credenciales
 */
export function getSafeVideoUrl(rawUrl: string | null | undefined): string | null {
  if (!rawUrl) return null;
  try {
    const url = new URL(rawUrl.trim());
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    if (url.username !== '' || url.password !== '') return null;
    return url.href;
  } catch {
    return null;
  }
}
