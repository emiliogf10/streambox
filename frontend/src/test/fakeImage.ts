/**
 * `Image` falso para los tests que comprueban si una imagen carga con
 * `new Image()` (hoy, `hooks/useFirstLoadableImage.ts`, que elige el `poster`
 * del reproductor).
 *
 * **Por qué.** jsdom no descarga imágenes: un `new Image()` real nunca dispara
 * `load` ni `error`, así que no se podría probar qué pasa cuando una imagen
 * está rota. Este falso apunta cada imagen creada y deja que el test decida su
 * resultado con {@link failImage} o {@link loadImage}.
 *
 * **Uso:** `installFakeImage()` en `beforeEach`; lo retira solo
 * `unstubGlobals: true` de `vite.config.ts`.
 */
import { act } from '@testing-library/react';
import { vi } from 'vitest';

/** Imagen falsa: solo lo que usa el código (`src`, `decoding` y los manejadores). */
export class FakeImage {
  /** Todas las imágenes creadas desde `installFakeImage()`, en orden. */
  static created: FakeImage[] = [];

  src = '';
  decoding = 'auto';
  referrerPolicy = '';
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor() {
    FakeImage.created.push(this);
  }
}

/** Sustituye el `Image` global por {@link FakeImage} y vacía la lista de creadas. */
export function installFakeImage(): void {
  FakeImage.created = [];
  vi.stubGlobal('Image', FakeImage);
}

/** URLs que se han pedido con `new Image()`, en orden. */
export function requestedImages(): string[] {
  return FakeImage.created.map((image) => image.src).filter(Boolean);
}

function settle(src: string, outcome: 'onload' | 'onerror'): void {
  const pending = FakeImage.created.filter((image) => image.src === src);
  if (pending.length === 0) throw new Error(`Nadie ha pedido la imagen ${src}`);
  act(() => {
    for (const image of pending) image[outcome]?.();
  });
}

/** Simula que la imagen `src` da error (404, red...) en todas las peticiones abiertas. */
export function failImage(src: string): void {
  settle(src, 'onerror');
}

/** Simula que la imagen `src` ha cargado. */
export function loadImage(src: string): void {
  settle(src, 'onload');
}
