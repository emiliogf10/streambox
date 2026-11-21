/**
 * Formatea una duración en minutos para leerla de un vistazo: "1h 48m", "45m"
 * y, si las horas son exactas, solo "2h".
 *
 * Antes salía "2h 0m" (revisión visual D-14): el "0m" es ruido y hace dudar de
 * si falta un dato. Se mantiene el estilo compacto sin espacio ("1h 48m") que
 * ya usan todas las tarjetas, fichas y tablas: mezclar "2 h" con "1h 48m" en la
 * misma fila sería más raro que el propio "0m".
 *
 * @param mins minutos (enteros, ≥0, como los valida el backend)
 */
export function formatDuration(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
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

/**
 * Convierte el `:id` de una ruta de detalle (`/peliculas/:id`, `/series/:id`) en
 * un id válido (entero positivo) o `null`.
 *
 * Solo se aceptan dígitos (`^\d+$`): `/peliculas/abc`, `/peliculas/1.5`,
 * `/peliculas/-1` o `/peliculas/1e3` no llegan a pedir nada y la página dice
 * «no encontrada» sin preguntar al servidor. `Number.isSafeInteger` descarta
 * los números tan largos que JavaScript ya no representa con exactitud: con
 * ellos se pediría OTRO id distinto del escrito.
 *
 * @param raw el parámetro tal cual viene de `useParams`
 */
export function parseRouteId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}
