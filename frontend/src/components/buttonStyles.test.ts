/**
 * Tests de `buttonClasses`: la jerarquía de variantes y que la respuesta al
 * pulsar no se aplique a botones desactivados ni con movimiento reducido.
 */
import { describe, expect, it } from 'vitest';
import { buttonClasses } from './buttonStyles';

describe('buttonClasses', () => {
  it('la variante terciaria `ghost` no lleva borde ni fondo de reposo; `outline` sí lleva borde', () => {
    const ghost = buttonClasses('ghost').split(' ');
    expect(ghost).toContain('bg-transparent');
    expect(ghost).not.toContain('border');
    expect(buttonClasses('outline').split(' ')).toContain('border');
  });

  it('se hunde al pulsar, salvo desactivado (`disabled`/`aria-disabled`) o con `prefers-reduced-motion`', () => {
    const classes = buttonClasses('light').split(' ');
    expect(classes).toContain('active:scale-97');
    expect(classes).toContain('disabled:active:scale-100');
    expect(classes).toContain('aria-disabled:active:scale-100');
    expect(classes).toContain('motion-reduce:active:scale-100');
  });

  it('mantiene el objetivo táctil de 44 px y el anillo de foco, y añade las clases extra al final', () => {
    const classes = buttonClasses('primary', 'w-full');
    expect(classes.split(' ')).toEqual(expect.arrayContaining(['min-h-11', 'focus-ring']));
    expect(classes.endsWith('w-full')).toBe(true);
  });
});
