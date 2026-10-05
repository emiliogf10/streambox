/**
 * Tests de `utils.ts`.
 *
 * `getSafeVideoUrl` es una barrera de seguridad (XSS y suplantación): `videoUrl`
 * viene de la base de datos y acaba en un `href`. Estos tests fijan qué se deja
 * pasar y, sobre todo, todo lo que debe rechazarse.
 */
import { describe, expect, it } from 'vitest';
import { formatDuration, formatWaitTime, getSafeVideoUrl } from './utils';

describe('getSafeVideoUrl', () => {
  it.each([
    ['https://example.com/video.mp4', 'https://example.com/video.mp4'],
    ['http://example.com/watch?v=1&t=2', 'http://example.com/watch?v=1&t=2'],
    // Se devuelve la forma normalizada del navegador (añade la barra final).
    ['https://example.com', 'https://example.com/'],
    ['HTTPS://EXAMPLE.COM/a', 'https://example.com/a'],
    ['  https://example.com/a  ', 'https://example.com/a'],
  ])('acepta y normaliza %j', (input, expected) => {
    expect(getSafeVideoUrl(input)).toBe(expected);
  });

  it.each([
    ['javascript:alert(1)'],
    ['JaVaScRiPt:alert(1)'],
    ['java\tscript:alert(1)'], // el analizador de URL ignora tabuladores y saltos de línea
    ['java\nscript:alert(1)'],
    [' javascript:alert(1)'],
    ['data:text/html,<script>alert(1)</script>'],
    ['vbscript:msgbox(1)'],
    ['file:///etc/passwd'],
    ['ftp://example.com/video'],
    ['blob:https://example.com/uuid'],
  ])('rechaza el esquema peligroso %j', (input) => {
    expect(getSafeVideoUrl(input)).toBeNull();
  });

  it.each([
    ['//evil.example/video'],
    ['/videos/1'],
    ['video.mp4'],
    ['not a url'],
    ['https://'],
  ])('rechaza lo que no es una URL absoluta http(s): %j', (input) => {
    expect(getSafeVideoUrl(input)).toBeNull();
  });

  it.each([[''], ['   '], [null], [undefined]])('rechaza el valor vacío %j', (input) => {
    expect(getSafeVideoUrl(input)).toBeNull();
  });

  it.each([
    ['https://user:pass@example.com/'],
    ['https://user@example.com/'],
    // Suplantación clásica: parece netflix.com pero navega a evil.com.
    ['https://netflix.com@evil.example/'],
    ['http://:pass@example.com/'],
  ])('rechaza credenciales embebidas %j', (input) => {
    expect(getSafeVideoUrl(input)).toBeNull();
  });
});

describe('formatDuration', () => {
  it.each([
    [108, '1h 48m'],
    [45, '45m'],
    [60, '1h 0m'],
    [0, '0m'],
    [59, '59m'],
    [125, '2h 5m'],
  ])('%i minutos -> %s', (minutes, expected) => {
    expect(formatDuration(minutes)).toBe(expected);
  });
});

describe('formatWaitTime', () => {
  // La cuenta atrás del bloqueo de cuenta (15 min) debe leerse de un vistazo, no como "900 s".
  it.each([
    [0, '0 s'],
    [1, '1 s'],
    [59, '59 s'],
    [60, '1 min'],
    [65, '1 min 5 s'],
    [899, '14 min 59 s'],
    [900, '15 min'],
    [3600, '60 min'],
  ])('%i s → %j', (seconds, expected) => {
    expect(formatWaitTime(seconds)).toBe(expected);
  });

  it('redondea hacia arriba los decimales y trata los negativos como 0 (nunca "-1 s")', () => {
    expect(formatWaitTime(59.2)).toBe('1 min');
    expect(formatWaitTime(-3)).toBe('0 s');
  });
});
