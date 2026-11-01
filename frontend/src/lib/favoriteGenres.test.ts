/**
 * Tests de las reglas y textos de «Tus gustos» (`lib/favoriteGenres.ts`).
 *
 * Protegen los límites que comparte la interfaz con el servidor (de 3 a 5), que
 * el contador diga siempre la verdad, que el porqué de las recomendaciones no
 * invente nada según `basis`, que solo se pregunte a un `USER` con la pregunta
 * pendiente y que el destino tras la bienvenida no pueda sacar de la aplicación
 * ni volver a la propia bienvenida.
 */
import { describe, expect, it } from 'vitest';
import { makeUser } from '../test/helpers';
import {
  MAX_FAVORITE_GENRES,
  MIN_FAVORITE_GENRES,
  GENRE_SHORTAGE_LIKES_HINT,
  canSaveGenreSelection,
  formatGenreNames,
  genreCounterMessage,
  genreShortageMessage,
  genreShortageTitle,
  hasEnoughGenreOptions,
  locationPath,
  needsGenresOnboarding,
  recommendationsReason,
  welcomeDestination,
} from './favoriteGenres';

describe('canSaveGenreSelection', () => {
  it('solo de 3 a 5 (la lista vacía tiene sus propios botones)', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map(canSaveGenreSelection)).toEqual([false, false, false, true, true, true, false]);
    expect(MIN_FAVORITE_GENRES).toBe(3);
    expect(MAX_FAVORITE_GENRES).toBe(5);
  });
});

describe('hasEnoughGenreOptions y el aviso de «no hay suficientes»', () => {
  const accion = { id: 1, name: 'Acción' };
  const drama = { id: 2, name: 'Drama' };

  it('hacen falta al menos 3 géneros elegibles para poder llegar al mínimo', () => {
    expect([0, 1, 2, 3, 6].map(hasEnoughGenreOptions)).toEqual([false, false, false, true, true]);
  });

  it('sin ninguno: habla de títulos visibles (verdad también para un ADMIN con series sin episodios)', () => {
    expect(genreShortageTitle([])).toBe('Todavía no hay géneros que elegir');
    expect(genreShortageMessage([])).toBe('Ningún título visible tiene género todavía.');
  });

  it('con 1 o 2: los nombra y dice que hacen falta 3', () => {
    expect(genreShortageTitle([accion])).toBe('Todavía no hay géneros suficientes');
    expect(genreShortageMessage([accion])).toBe(
      'Hacen falta títulos visibles de al menos 3 géneros y por ahora solo los hay de «Acción».',
    );
    expect(genreShortageMessage([accion, drama])).toBe(
      'Hacen falta títulos visibles de al menos 3 géneros y por ahora solo los hay de «Acción» y «Drama».',
    );
    expect(GENRE_SHORTAGE_LIKES_HINT).toBe(
      'Mientras tanto, los títulos que marques con «Me gusta» también sirven para recomendarte otros parecidos.',
    );
  });
});

describe('genreCounterMessage', () => {
  it('dice cuántos van de 5 y qué falta o qué se puede hacer', () => {
    expect(genreCounterMessage(0)).toBe('Has elegido 0 de 5. Elige al menos 3 más.');
    expect(genreCounterMessage(2)).toBe('Has elegido 2 de 5. Elige al menos 1 más.');
    expect(genreCounterMessage(3)).toBe('Has elegido 3 de 5. Ya puedes guardar.');
    expect(genreCounterMessage(4)).toBe('Has elegido 4 de 5. Ya puedes guardar.');
    expect(genreCounterMessage(5)).toBe('Has elegido 5 de 5. Es el máximo: quita uno si quieres elegir otro.');
  });
});

describe('formatGenreNames', () => {
  it('une los nombres como en español', () => {
    expect(formatGenreNames(['Acción'])).toBe('Acción');
    expect(formatGenreNames(['Acción', 'Drama'])).toBe('Acción y Drama');
    expect(formatGenreNames(['Acción', 'Drama', 'Comedia'])).toBe('Acción, Drama y Comedia');
  });
});

describe('recommendationsReason', () => {
  it.each([
    ['NONE', ['Acción'], null],
    ['GENRES', ['Acción', 'Drama'], 'Porque te gustan Acción y Drama.'],
    ['GENRES', undefined, 'Según los géneros que has elegido como favoritos.'],
    ['GENRES', [], 'Según los géneros que has elegido como favoritos.'],
    ['LIKES', ['Acción'], 'Según los títulos que has marcado con «Me gusta».'],
    [
      'GENRES_AND_LIKES',
      ['Acción', 'Drama', 'Comedia'],
      'Porque te gustan Acción, Drama y Comedia y según los títulos que has marcado con «Me gusta».',
    ],
    ['GENRES_AND_LIKES', undefined, 'Según tus géneros favoritos y los títulos que has marcado con «Me gusta».'],
  ] as const)('%s con %j → %j', (basis, names, expected) => {
    expect(recommendationsReason(basis, names ? [...names] : undefined)).toBe(expected);
  });
});

describe('needsGenresOnboarding', () => {
  it('solo un USER con la pregunta pendiente; un ADMIN nunca, aunque llegara marcada', () => {
    expect(needsGenresOnboarding(makeUser({ genresOnboardingPending: true }))).toBe(true);
    expect(needsGenresOnboarding(makeUser({ genresOnboardingPending: false }))).toBe(false);
    expect(needsGenresOnboarding(makeUser({ genresOnboardingPending: undefined }))).toBe(false);
    expect(needsGenresOnboarding(makeUser({ role: 'ADMIN', genresOnboardingPending: true }))).toBe(false);
    expect(needsGenresOnboarding(null)).toBe(false);
  });
});

describe('welcomeDestination', () => {
  it('vuelve a la ruta interna guardada, con búsqueda y fragmento', () => {
    expect(welcomeDestination({ from: '/peliculas?genero=4#x' })).toBe('/peliculas?genero=4#x');
    expect(locationPath({ pathname: '/series/3', search: '?temporada=2', hash: '' })).toBe('/series/3?temporada=2');
  });

  it('sin destino válido, al inicio: nada externo, nada que no sea texto y nunca la propia bienvenida', () => {
    for (const state of [
      undefined,
      null,
      {},
      { from: 42 },
      { from: 'https://malo.example' },
      { from: '//malo.example' },
      { from: '/\\malo.example' },
      { from: 'peliculas' },
      { from: '/bienvenida' },
      { from: '/bienvenida?x=1' },
    ]) {
      expect(welcomeDestination(state)).toBe('/');
    }
  });
});
