import { describe, expect, it } from 'vitest';
import contract from '../test/youtubeTrailerUrlCases.json';
import {
  getTrailer,
  normalizeYouTubeUrl,
  TRAILER_URL_FORMAT_MESSAGE,
  TRAILER_URL_SIZE_MESSAGE,
  validateTrailerUrl,
} from './youtube';

/**
 * Paridad cliente/servidor del enlace del tráiler (revisión de QA).
 *
 * Los casos viven en `src/test/youtubeTrailerUrlCases.json` y los comprueba
 * TAMBIÉN el backend (`YouTubeUrlParityContractTest`, que lee el mismo
 * archivo): si alguien cambia la validación en un lado y no en el otro, falla
 * la suite del lado que se ha quedado atrás. La referencia es el servidor
 * (`java.net.URI`): «esperado» es lo que responde la API.
 *
 * Qué protege: que el formulario del panel nunca dé por bueno un enlace que el
 * servidor rechaza con 400 (el administrador vería el error solo al guardar)
 * ni rechace uno que el servidor aceptaría.
 *
 * Todos los casos deben coincidir: no hay excepciones toleradas. Los ocho que
 * QA encontró divergentes (incidencia trailer-1: `%` mal formado aceptado y
 * corchetes rechazados) se corrigieron en `youtube.ts` y ya pasan como el resto.
 */

interface ContractCase {
  nota: string;
  url: string;
  /** Forma normalizada, «formato» o «longitud». */
  esperado: string;
}

const cases = (contract as { casos: ContractCase[] }).casos;

/** Lo que el frontend responde para un enlace, en el mismo vocabulario que el JSON. */
function frontendResult(url: string): string | null {
  const error = validateTrailerUrl(url);
  if (error === TRAILER_URL_SIZE_MESSAGE) return 'longitud';
  if (error === TRAILER_URL_FORMAT_MESSAGE) return 'formato';
  if (error) return `mensaje inesperado: ${error}`;
  return normalizeYouTubeUrl(url);
}

describe('youtube: paridad con el servidor (contrato compartido)', () => {
  it('el contrato tiene casos de las tres clases y ninguno repetido', () => {
    expect(cases.length).toBeGreaterThan(50);
    expect(cases.some((item) => item.esperado === 'formato')).toBe(true);
    expect(cases.some((item) => item.esperado === 'longitud')).toBe(true);
    expect(cases.some((item) => item.esperado.startsWith('https://'))).toBe(true);
    expect(new Set(cases.map((item) => item.url)).size).toBe(cases.length);
  });

  for (const item of cases) {
    const name = `${item.nota} → ${item.esperado.startsWith('https://') ? 'acepta' : item.esperado}`;
    it(name, () => {
      expect(frontendResult(item.url)).toBe(item.esperado);
    });
  }

  it('getTrailer solo reconoce la forma que guarda el servidor: de cada caso aceptado, su normalizada', () => {
    for (const item of cases) {
      if (!item.esperado.startsWith('https://')) continue;
      const id = item.esperado.slice('https://www.youtube.com/watch?v='.length);
      expect(getTrailer(item.esperado)?.embedUrl).toBe(`https://www.youtube-nocookie.com/embed/${id}?autoplay=1&rel=0`);
    }
  });

  /**
   * Un `trailerUrl` manipulado en la API (imposible con el CHECK, pero el
   * cliente no debe fiarse) nunca llega al `src` del iframe: ni con un salto
   * de línea final (en JavaScript `$` sin la bandera `m` no lo admite, a
   * diferencia de Java), ni con separadores Unicode, ni repitiendo `v`.
   */
  it.each([
    `https://www.youtube.com/watch?v=aB3_-eFgHiJ\n`,
    `https://www.youtube.com/watch?v=aB3_-eFgHiJ `,
    `\nhttps://www.youtube.com/watch?v=aB3_-eFgHiJ`,
    `https://www.youtube.com/watch?v=aB3_-eFgHiJ&v=zzzzzzzzzzz`,
    `https://www.youtube.com/watch?v=aB3_-eFgHiJ\u0000`,
    `HTTPS://WWW.YOUTUBE.COM/watch?v=aB3_-eFgHiJ`,
    `https://www.youtube.com/watch?v=aB3_-eFgHiK`,
  ])('getTrailer(%j) → null', (value) => {
    expect(getTrailer(value)).toBeNull();
  });

  it('todo lo que el cliente acepta tiene la forma exacta del CHECK de la base de datos', () => {
    for (const item of cases) {
      const normalized = normalizeYouTubeUrl(item.url);
      if (normalized !== null) {
        expect(normalized).toMatch(/^https:\/\/www\.youtube\.com\/watch\?v=[A-Za-z0-9_-]{11}$/);
      }
    }
  });
});
