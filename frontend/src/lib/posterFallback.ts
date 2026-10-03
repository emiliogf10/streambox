/**
 * Degradados del hueco de respaldo de `MoviePoster` (película sin portada o con
 * una portada que no carga).
 *
 * **Por qué no un único color.** Con un solo rectángulo para todas, varias
 * películas sin portada seguidas en una fila parecen una página que no ha
 * terminado de cargar. Con un degradado distinto por película se ven como
 * carteles "sin imagen" intencionados y se distinguen entre sí.
 *
 * **Por qué determinista.** El degradado se elige a partir del TÍTULO (y no al
 * azar): la misma película se ve igual en la fila, en "Mi lista", en el banner y
 * en el modal, y no cambia de color en cada render ni al recargar.
 *
 * **Por qué clases completas en una lista.** Tailwind solo genera las clases que
 * encuentra escritas tal cual en el código: no se pueden componer
 * (`from-${color}-900`). Por eso cada combinación está escrita entera.
 *
 * **Contraste (WCAG 2.2 AA, texto normal ≥ 4.5:1).** El título del hueco es
 * blanco (`text-white`) y va centrado, sobre la mezcla de los dos extremos: el
 * peor caso es el extremo MÁS CLARO (`from-*-900`). Cifras calculadas con la
 * fórmula de luminancia relativa del estándar a partir de los valores `oklch`
 * de Tailwind v4 (el extremo oscuro, `*-950`, da siempre más de 13:1):
 *
 *   indigo-900  11.47 · rose-900  9.63 · emerald-900 9.69
 *   amber-900    9.09 · cyan-900  9.12 · violet-900 11.03
 *
 * Todas superan holgadamente el 4.5:1 (incluso el 7:1 del nivel AAA).
 */
export const POSTER_FALLBACK_GRADIENTS = [
  'bg-linear-to-br from-indigo-900 to-slate-950',
  'bg-linear-to-br from-rose-900 to-stone-950',
  'bg-linear-to-br from-emerald-900 to-slate-950',
  'bg-linear-to-br from-amber-900 to-stone-950',
  'bg-linear-to-br from-cyan-900 to-slate-950',
  'bg-linear-to-br from-violet-900 to-zinc-950',
] as const;

/**
 * Hash entero sencillo de un texto: FNV-1a de 32 bits.
 *
 * No es criptográfico ni lo necesita: solo debe ser estable (mismo texto, mismo
 * número) y repartir títulos parecidos entre valores distintos. Se probó antes
 * el de `String.hashCode` de Java (`h·31 + c`), pero con 6 colores reparte mal:
 * como 31 ≡ 1 (mód 6), el resto acaba dependiendo casi solo de la SUMA de los
 * caracteres y 8 títulos de ejemplo caían en 3 colores. FNV-1a mezcla cada
 * carácter con un XOR y un primo grande, y no tiene ese problema.
 * `Math.imul` mantiene la multiplicación en 32 bits (sin perder precisión con
 * títulos largos).
 *
 * @param text texto de entrada (el título de la película)
 * @returns un entero de 32 bits sin signo
 */
export function hashText(text: string): number {
  let hash = 0x811c9dc5; // base de FNV-1a (32 bits)
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193); // primo de FNV (32 bits)
  }
  return hash >>> 0;
}

/**
 * Clases del degradado de respaldo para una película.
 *
 * @param title título de la película (vacío también vale: da siempre el primero)
 * @returns una de {@link POSTER_FALLBACK_GRADIENTS}, siempre la misma para el mismo título
 */
export function posterFallbackGradient(title: string): string {
  return POSTER_FALLBACK_GRADIENTS[hashText(title) % POSTER_FALLBACK_GRADIENTS.length];
}
