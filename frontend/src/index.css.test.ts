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

  it('el diálogo conserva el fundido pero no crece (empieza ya sin escala: `none`, ver «diálogo sin escala»)', () => {
    const block = reducedMotionBlock();
    expect(block).toMatch(/dialog\[open\][\s\S]*transition:\s*opacity/);
    expect(block).toMatch(/@starting-style\s*{[^}]*opacity:\s*0;[^}]*scale:\s*none;/);
  });

  it('sigue parando las animaciones continuas y el desplazamiento suave', () => {
    const block = reducedMotionBlock();
    expect(block).toMatch(/animation-duration:\s*0\.01ms/);
    expect(block).toMatch(/scroll-behavior:\s*auto/);
  });
});

describe('alto de la ventana', () => {
  // B9: con `100vh`, en móvil la página medía la altura con la barra de direcciones OCULTA y «Mi lista» vacía
  // tenía siempre unos píxeles de scroll sin contenido. `100dvh` sigue a la ventana visible.
  it('el cuerpo usa la altura dinámica de la ventana (`100dvh`), no `100vh`', () => {
    expect(css).toMatch(/body\s*{[^}]*min-height:\s*100dvh;/);
    expect(css).not.toMatch(/min-height:\s*100vh/);
  });
});

describe('diálogo sin escala (B11)', () => {
  // Cualquier `scale` distinto de `none` (también `1`) hace del `<dialog>` el bloque contenedor de sus hijos
  // `position: fixed`: la zona de avisos del diálogo se desplazaba con su contenido y se salía por arriba.
  it('el estado final del diálogo abierto es `scale: none`, y la entrada sigue partiendo de 0.96', () => {
    const rule = css.slice(css.indexOf('dialog[open] {'), css.indexOf('dialog[open]::backdrop'));
    expect(rule).toMatch(/^dialog\[open\] {\s*opacity:\s*1;\s*scale:\s*none;/);
    expect(rule).toMatch(/@starting-style\s*{[^}]*scale:\s*0\.96;/);
    expect(css).not.toMatch(/scale:\s*1;/);
  });
});

describe('avisos de móvil bajo la barra (D-1 / E-10)', () => {
  it('define la variante `navbar-away` con la misma condición que aparta la barra (sin el foco dentro)', () => {
    expect(css).toMatch(/@custom-variant navbar-away\s*{\s*:root:has\(\[data-navbar-away\]:not\(:focus-within\)\) &/);
  });

  it('en móvil, el margen de desplazamiento del contenido reserva también la altura de los avisos', () => {
    expect(css).toMatch(
      /@variant max-sm\s*{\s*scroll-margin-top:\s*calc\(var\(--navbar-height, 0px\) \+ var\(--toasts-height, 0px\) \+ 2rem\);/,
    );
  });
});
