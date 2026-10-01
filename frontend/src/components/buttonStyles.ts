/**
 * Clases de Tailwind de los botones. Están en un archivo aparte (y no en
 * `Button.tsx`) para poder reutilizarlas en enlaces sin romper el "fast refresh"
 * de los componentes. `min-h-11` (44 px) garantiza un objetivo táctil cómodo (WCAG 2.5.5).
 */

/** Variantes visuales de botón disponibles. */
export type ButtonVariant = 'primary' | 'light' | 'outline' | 'danger';

const base =
  'focus-ring inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-5 py-2.5 text-sm font-bold ' +
  'transition-colors aria-disabled:cursor-not-allowed aria-disabled:opacity-50 ' +
  'disabled:cursor-not-allowed disabled:opacity-50';

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-black hover:bg-accent/90',
  light: 'bg-white text-black hover:bg-white/90',
  outline: 'border border-white/30 bg-transparent text-white hover:bg-white/10',
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
