import { useEffect, useState } from 'react';

/**
 * Devuelve `value` con retraso: solo cambia cuando `value` lleva `delayMs`
 * milisegundos sin cambiar ("debounce").
 *
 * Sirve para no reaccionar a cada tecla: el buscador del panel no lanza una
 * petición por letra y la vista previa de la portada no intenta descargar
 * `https://e`, `https://ej`, `https://eje`... mientras se escribe la URL.
 *
 * @param value valor que cambia a menudo (el texto de un campo)
 * @param delayMs espera tras el último cambio
 * @returns el último valor que se ha mantenido estable durante `delayMs`
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    // Cada cambio reinicia la espera: el temporizador anterior se cancela en la limpieza.
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
