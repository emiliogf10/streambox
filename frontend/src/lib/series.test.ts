/**
 * Tests de las funciones puras de series (`lib/series.ts`).
 *
 * Protegen el formato de los años (terminada, en emisión, un solo año), los
 * plurales, el código de episodio que da nombre a cada «Ver» y, sobre todo, la
 * validación de `?temporada=` de la URL: la escribe cualquiera, así que nunca
 * debe dar una temporada que no existe.
 */
import { describe, expect, it } from 'vitest';
import {
  episodeCode,
  formatEpisodeCount,
  formatSeasonCount,
  formatSeriesYears,
  isOnAir,
  pluralize,
  resolveSeasonNumber,
  seriesCardMeta,
} from './series';
import type { Season } from './types';

describe('formatSeriesYears', () => {
  it('terminada: «inicio–fin» con raya', () => {
    expect(formatSeriesYears(2019, 2022)).toBe('2019–2022');
  });

  it('en emisión (endYear null): «inicio–» sin fin', () => {
    expect(formatSeriesYears(2021, null)).toBe('2021–');
  });

  it('empezó y terminó el mismo año: un solo año', () => {
    expect(formatSeriesYears(2020, 2020)).toBe('2020');
  });
});

describe('recuentos', () => {
  it('singular con 1 y plural con el resto (también con 0)', () => {
    expect(pluralize(1, 'serie', 'series')).toBe('1 serie');
    expect(pluralize(0, 'serie', 'series')).toBe('0 series');
    expect(formatSeasonCount(1)).toBe('1 temporada');
    expect(formatSeasonCount(3)).toBe('3 temporadas');
    expect(formatEpisodeCount(1)).toBe('1 episodio');
    expect(formatEpisodeCount(24)).toBe('24 episodios');
  });

  it('isOnAir solo es true sin año de fin', () => {
    expect(isOnAir({ endYear: null })).toBe(true);
    expect(isOnAir({ endYear: 2022 })).toBe(false);
  });

  it('el dato de la tarjeta junta años y temporadas', () => {
    expect(seriesCardMeta({ releaseYear: 2019, endYear: 2022, seasonCount: 3 })).toBe('2019–2022 · 3 temporadas');
    expect(seriesCardMeta({ releaseYear: 2021, endYear: null, seasonCount: 1 })).toBe('2021– · 1 temporada');
  });

  it('episodeCode da «T<temporada>:E<episodio>»', () => {
    expect(episodeCode(1, 3)).toBe('T1:E3');
    expect(episodeCode(12, 10)).toBe('T12:E10');
  });
});

describe('resolveSeasonNumber (?temporada= de la URL)', () => {
  const seasons: Season[] = [
    { seasonNumber: 2, episodes: [] },
    { seasonNumber: 3, episodes: [] },
  ];

  it('una temporada que existe se respeta', () => {
    expect(resolveSeasonNumber('3', seasons)).toBe(3);
  });

  it('sin parámetro, la PRIMERA de la serie (que no tiene por qué ser la 1)', () => {
    expect(resolveSeasonNumber(null, seasons)).toBe(2);
  });

  it.each([
    ['1'], // no existe en esta serie
    ['99'],
    ['0'],
    ['-2'],
    ['2.5'],
    ['1e1'],
    [' 3'],
    ['abc'],
    [''],
  ])('valor inválido %j → la primera temporada', (raw) => {
    expect(resolveSeasonNumber(raw, seasons)).toBe(2);
  });

  it('sin temporadas devuelve null (no inventa una)', () => {
    expect(resolveSeasonNumber('1', [])).toBeNull();
  });
});
