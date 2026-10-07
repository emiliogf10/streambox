/// <reference types="node" />
/**
 * Pruebas de las reglas globales de `index.css` que se pueden comprobar sin
 * navegador (jsdom no calcula `@media` ni `@starting-style`): se leen como texto.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

// Se lee del disco (Vitest se ejecuta desde frontend/): el procesado de CSS de Vitest vacía `?raw`, y aquí interesa el texto fuente.
const css = readFileSync('src/index.css', 'utf8');

/** Cuerpo del bloque `@media (prefers-reduced-motion: reduce)` (hasta su llave de cierre de primer nivel). */
function reducedMotionBlock(): string {
  const start = css.indexOf('@media (prefers-reduced-motion: reduce)');
  expect(start, 'falta el bloque de movimiento reducido').toBeGreaterThan(-1);
  let depth = 0;
  for (let i = css.indexOf('{', start); i < css.length; i++) {
    if (css[i] === '{') depth++;
    if (css[i] === '}' && --depth === 0) return css.slice(start, i + 1);
  }
  throw new Error('bloque sin cerrar');
}

describe('movimiento reducido', () => {
  it('no anula las transiciones: los fundidos de opacidad y color se conservan', () => {
    expect(reducedMotionBlock()).not.toMatch(/transition-duration/);
  });

  it('el diálogo conserva el fundido pero no crece (escala inicial 1)', () => {
    const block = reducedMotionBlock();
    expect(block).toMatch(/dialog\[open\][\s\S]*transition:\s*opacity/);
    expect(block).toMatch(/@starting-style\s*{[^}]*opacity:\s*0;[^}]*scale:\s*1;/);
  });

  it('sigue parando las animaciones continuas y el desplazamiento suave', () => {
    const block = reducedMotionBlock();
    expect(block).toMatch(/animation-duration:\s*0\.01ms/);
    expect(block).toMatch(/scroll-behavior:\s*auto/);
  });
});
