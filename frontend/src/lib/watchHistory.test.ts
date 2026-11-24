/**
 * Tests de las reglas del historial de visionado (`lib/watchHistory.ts`).
 *
 * Protegen que cada entrada lleve a la página de su título (la de la serie, en
 * la temporada del episodio; nunca al reproductor), que un episodio se nombre
 * por su serie y que el tiempo visto se diga sin aparentar más precisión de la
 * que tiene (hacia abajo, «aprox.»), con su forma entera para el lector de pantalla.
 */
import { describe, expect, it } from 'vitest';
import { makeWatchHistoryItem } from '../test/helpers';
import { formatWatchedTime, watchHistoryEpisodeLine, watchHistoryName, watchHistoryPath } from './watchHistory';

const episode = makeWatchHistoryItem({
  type: 'EPISODE',
  id: 205,
  title: 'Mentiras',
  seasonNumber: 2,
  episodeNumber: 5,
  seriesId: 3,
  seriesTitle: 'Dark',
});

describe('watchHistoryPath', () => {
  it('una película lleva a su página, no al reproductor', () => {
    expect(watchHistoryPath(makeWatchHistoryItem({ id: 7 }))).toBe('/peliculas/7');
  });

  it('un episodio lleva a la página de su SERIE, abierta en la temporada del episodio', () => {
    expect(watchHistoryPath(episode)).toBe('/series/3?temporada=2');
  });

  it('sin temporada (no previsto por el contrato), la página de la serie sin más', () => {
    expect(watchHistoryPath({ ...episode, seasonNumber: null })).toBe('/series/3');
  });
});

describe('watchHistoryName y watchHistoryEpisodeLine', () => {
  it('una película se nombra por su título y no tiene línea de episodio', () => {
    const movie = makeWatchHistoryItem({ title: 'Dune' });
    expect(watchHistoryName(movie)).toBe('Dune');
    expect(watchHistoryEpisodeLine(movie)).toBeNull();
  });

  it('un episodio se nombra por su serie y debajo va «T2:E5 · título del episodio»', () => {
    expect(watchHistoryName(episode)).toBe('Dark');
    expect(watchHistoryEpisodeLine(episode)).toBe('T2:E5 · Mentiras');
  });
});

describe('formatWatchedTime', () => {
  it.each([
    [0, '0 min', '0 minutos'],
    [-5, '0 min', '0 minutos'],
    [Number.NaN, '0 min', '0 minutos'],
    [30, '<1 min', 'menos de 1 minuto'],
    [59, '<1 min', 'menos de 1 minuto'],
    [60, '1 min', '1 minuto'],
    [119, '1 min', '1 minuto'],
    [45 * 60, '45 min', '45 minutos'],
    [3599, '59 min', '59 minutos'],
    [3600, '1 h', '1 hora'],
    [5400, '1,5 h', '1,5 horas'],
    // Hacia abajo: 1 h 59 min no se convierte en «2 h».
    [7140, '1,9 h', '1,9 horas'],
    [9.99 * 3600, '9,9 h', '9,9 horas'],
    [10 * 3600, '10 h', '10 horas'],
    [10.9 * 3600, '10 h', '10 horas'],
    [1234 * 3600, '1234 h', '1234 horas'],
  ])('%s s → «%s» (se lee «%s»)', (seconds, short, spoken) => {
    expect(formatWatchedTime(seconds)).toEqual({ short, spoken });
  });
});
