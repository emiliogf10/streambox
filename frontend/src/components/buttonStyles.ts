/**
 * Clases de Tailwind de los botones. Están en un archivo aparte (y no en
 * `Button.tsx`) para poder reutilizarlas en enlaces sin romper el "fast refresh"
 * de los componentes. `min-h-11` (44 px) garantiza un objetivo táctil cómodo (WCAG 2.5.5).
 */

/**
 * Variantes visuales de botón disponibles.
 *
 * Jerarquía: `light`/`primary` = acción principal (una por bloque), `outline` =
 * secundaria, `ghost` = terciaria (sin borde: pesa menos y no compite con las
 * otras dos), `danger` = destructiva.
 */
export type ButtonVariant = 'primary' | 'light' | 'outline' | 'ghost' | 'danger';

/**
 * - **Respuesta al pulsar** (`active:scale-97`): el botón se hunde un 3 %
 *   durante 150 ms. Confirma que la interfaz ha recibido el toque antes de que
 *   llegue la respuesta del servidor. No se aplica a botones desactivados (no
 *   hacen nada, no deben fingir que sí) ni con `prefers-reduced-motion`.
 * - **Transición de propiedades concretas** (no `transition-all`): solo color,
 *   fondo, borde y escala; así nada inesperado (p. ej. el ancho) se anima.
 */
const base =
  'focus-ring inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-5 py-2.5 text-sm font-bold ' +
  'transition-[color,background-color,border-color,scale] duration-150 ease-out-strong active:scale-97 ' +
  'motion-reduce:active:scale-100 aria-disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:active:scale-100 ' +
  'disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100';

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-black hover:bg-accent/90',
  light: 'bg-white text-black hover:bg-white/90',
  outline: 'border border-white/30 bg-transparent text-white hover:bg-white/10',
  ghost: 'bg-transparent text-white/85 hover:bg-white/10 hover:text-white',
  danger: 'bg-red-600 text-white hover:bg-red-500',
};

/**
 * Devuelve las clases de Tailwind de una variante de botón.
 *
 * Se exporta para poder dar el mismo aspecto a elementos que no son
 * `<button>` (un `<a>` o un `<Link>`) sin duplicar clases.
 *
 * @param variant variante visual
 * @param extra clases adicionales (márgenes, ancho...)
 */
export function buttonClasses(variant: ButtonVariant = 'primary', extra = ''): string {
  return `${base} ${variants[variant]} ${extra}`.trim();
}
