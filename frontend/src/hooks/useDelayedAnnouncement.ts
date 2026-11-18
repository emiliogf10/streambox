import { useEffect, useState } from 'react';

/** Espera por defecto antes de anunciar: lo que tarda un lector en leer el campo al que se acaba de pasar. */
const DEFAULT_DELAY_MS = 500;

/**
 * Texto para una región viva cortés (`role="status"`) que anuncia un error de
 * campo que aparece SIN enviar el formulario (al salir del campo o al llegar a
 * cierto número de caracteres), con un pequeño retraso.
 *
 * **Por qué así.** Los errores de campo ya no son regiones vivas propias
 * (`FormField` explica el motivo): tras un envío fallido se oyen porque el
 * foco va al campo. Pero un error que aparece mientras el usuario sigue a lo
 * suyo no movería el foco, y sin anuncio pasaría desapercibido. La región la
 * pinta siempre la página (los lectores solo anuncian con fiabilidad los
 * cambios de una región que ya existía) y el texto llega medio segundo
 * después, para no pisar la lectura del campo al que se acaba de pasar.
 * Cuando el error se corrige, el texto se vacía al momento: así no queda un
 * aviso antiguo que alguien pueda encontrarse después al recorrer la página.
 *
 * @param message error actual del campo, o `null`/vacío si no hay
 * @param delayMs retraso antes de anunciarlo
 * @returns el texto que debe tener la región viva ahora mismo
 */
export function useDelayedAnnouncement(message: string | null | undefined, delayMs = DEFAULT_DELAY_MS): string {
  const [announced, setAnnounced] = useState('');

  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setAnnounced(message), delayMs);
    return () => clearTimeout(timer);
  }, [message, delayMs]);

  // Un texto antiguo (de otro error, o del mismo ya corregido) no se enseña mientras corre la espera.
  return message && announced === message ? announced : '';
}
