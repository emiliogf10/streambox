/**
 * Tests de `titleSearch`: las reglas del texto de las búsquedas por título que el
 * servidor valida (máx. 150 caracteres, sin caracteres de control `\p{Cc}`).
 *
 * Protegen que lo que se envía nunca lleve un carácter de control (el servidor
 * respondería 400), que un tabulador o salto de línea pegado separe las palabras
 * en vez de juntarlas, que no se toque nada más del texto, y que del 400 de
 * validación se saque el motivo real (`validationErrors.title`) y solo de ese error.
 */
import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import { getTitleSearchError, normalizeTitleSearch, TITLE_SEARCH_MAX_LENGTH } from './titleSearch';

describe('TITLE_SEARCH_MAX_LENGTH', () => {
  it('es 150, el límite del servidor (y la longitud máxima de un título)', () => {
    expect(TITLE_SEARCH_MAX_LENGTH).toBe(150);
  });
});

describe('normalizeTitleSearch', () => {
  it('un tabulador o salto de línea entre palabras se queda en UN espacio (no las pega)', () => {
    expect(normalizeTitleSearch('Blade\tRunner')).toBe('Blade Runner');
    expect(normalizeTitleSearch('Star\nWars')).toBe('Star Wars');
    expect(normalizeTitleSearch('Star\r\nWars')).toBe('Star Wars');
  });

  it('el tramo entero (control y espacios de alrededor) cuenta como un solo espacio', () => {
    expect(normalizeTitleSearch('Blade \t Runner')).toBe('Blade Runner');
    expect(normalizeTitleSearch('Blade\t\t\nRunner')).toBe('Blade Runner');
  });

  it('quita cualquier carácter de control (NUL, DEL, C1), no solo los espacios', () => {
    expect(normalizeTitleSearch('matrix\u0000')).toBe('matrix');
    expect(normalizeTitleSearch('ma\u007Ftrix')).toBe('ma trix');
    expect(normalizeTitleSearch('\u0085matrix\u009F')).toBe('matrix');
    expect(normalizeTitleSearch('ma\u0000trix')).not.toMatch(/\p{Cc}/u);
  });

  it('recorta los espacios de los lados y deja vacío lo que solo eran espacios o controles', () => {
    expect(normalizeTitleSearch('  matrix  ')).toBe('matrix');
    expect(normalizeTitleSearch('\t\n ')).toBe('');
    expect(normalizeTitleSearch('')).toBe('');
  });

  it('no cambia nada más: espacios dobles escritos a propósito, acentos y caracteres de formato invisibles', () => {
    expect(normalizeTitleSearch('Blade  Runner')).toBe('Blade  Runner');
    expect(normalizeTitleSearch('Mañana')).toBe('Mañana');
    // U+200B (espacio de ancho cero) es de formato (Cf), no de control: el servidor lo acepta.
    expect(normalizeTitleSearch('ma​trix')).toBe('ma​trix');
  });
});

describe('getTitleSearchError', () => {
  const validation = (validationErrors?: Record<string, string>) =>
    new ApiError({ status: 400, code: 'VALIDATION_ERROR', message: 'Los datos proporcionados no son válidos', validationErrors });

  it('devuelve el motivo del servidor en `title` (con punto final), no el mensaje genérico', () => {
    expect(getTitleSearchError(validation({ title: 'La búsqueda no puede superar los 150 caracteres' }))).toBe(
      'La búsqueda no puede superar los 150 caracteres.',
    );
    expect(getTitleSearchError(validation({ title: 'La búsqueda no puede contener caracteres de control' }))).toBe(
      'La búsqueda no puede contener caracteres de control.',
    );
  });

  it('no duplica el punto si el mensaje ya lo trae', () => {
    expect(getTitleSearchError(validation({ title: 'Texto no válido.' }))).toBe('Texto no válido.');
  });

  it('`null` si el 400 no es por el título (p. ej. la paginación) o no trae motivos', () => {
    expect(getTitleSearchError(validation({ size: 'El tamaño debe estar entre 1 y 100' }))).toBeNull();
    expect(getTitleSearchError(validation())).toBeNull();
    expect(getTitleSearchError(validation({ title: '  ' }))).toBeNull();
  });

  it('`null` para cualquier otro error: decide por status y code, no por el texto', () => {
    const other = (status: number, code: string) =>
      new ApiError({ status, code, message: 'x', validationErrors: { title: 'La búsqueda no puede superar los 150 caracteres' } });
    expect(getTitleSearchError(other(500, 'INTERNAL_ERROR'))).toBeNull();
    expect(getTitleSearchError(other(400, 'BAD_REQUEST'))).toBeNull();
    expect(getTitleSearchError(other(403, 'VALIDATION_ERROR'))).toBeNull();
    expect(getTitleSearchError(new Error('boom'))).toBeNull();
    expect(getTitleSearchError(undefined)).toBeNull();
  });
});
