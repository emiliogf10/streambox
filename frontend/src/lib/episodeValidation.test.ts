/**
 * Tests de la validación del formulario de episodios (`episodeValidation.ts`).
 *
 * Protegen el contrato con `EpisodeRequest`: los mensajes deben ser los del
 * servidor letra por letra, cada campo recibe un solo error, la sinopsis es
 * opcional (vacía = `null`) y los valores que se proponen al añadir un episodio.
 */
import { describe, expect, it } from 'vitest';
import { makeEpisode } from '../test/helpers';
import {
  EPISODE_MESSAGES,
  newEpisodeFormValues,
  nextEpisodeNumber,
  parseInRange,
  toEpisodeFormValues,
  toEpisodeRequest,
  validateEpisodeForm,
} from './episodeValidation';
import type { EpisodeFormValues } from './episodeValidation';
import type { Season } from './types';

const VALID: EpisodeFormValues = {
  seasonNumber: '1',
  episodeNumber: '3',
  title: 'El faro',
  description: '',
  duration: '45',
  videoUrl: 'https://videos.example.com/t1e3',
};

/** Temporada `seasonNumber` con episodios de los números indicados. */
function season(seasonNumber: number, numbers: number[]): Season {
  return { seasonNumber, episodes: numbers.map((episodeNumber) => makeEpisode({ seasonNumber, episodeNumber })) };
}

describe('validateEpisodeForm', () => {
  it('un formulario válido (sin sinopsis) no tiene errores', () => {
    expect(validateEpisodeForm(VALID)).toEqual({});
  });

  it('vacío: cada obligatorio con el mensaje exacto del servidor; la sinopsis no es obligatoria', () => {
    const empty: EpisodeFormValues = {
      seasonNumber: '',
      episodeNumber: ' ',
      title: '   ',
      description: '',
      duration: '',
      videoUrl: '',
    };
    expect(validateEpisodeForm(empty)).toEqual({
      seasonNumber: 'La temporada es obligatoria',
      episodeNumber: 'El número de episodio es obligatorio',
      title: 'El título es obligatorio',
      duration: 'La duración es obligatoria',
      videoUrl: 'La URL del vídeo es obligatoria',
    });
  });

  it.each([
    ['0', 'seasonNumber', 'La temporada debe estar entre 1 y 100'],
    ['101', 'seasonNumber', 'La temporada debe estar entre 1 y 100'],
    ['1.5', 'seasonNumber', 'La temporada debe estar entre 1 y 100'],
    ['-1', 'seasonNumber', 'La temporada debe estar entre 1 y 100'],
    ['0', 'episodeNumber', 'El número de episodio debe estar entre 1 y 1000'],
    ['1001', 'episodeNumber', 'El número de episodio debe estar entre 1 y 1000'],
    ['dos', 'episodeNumber', 'El número de episodio debe estar entre 1 y 1000'],
    ['0', 'duration', 'La duración debe estar entre 1 y 600 minutos'],
    ['601', 'duration', 'La duración debe estar entre 1 y 600 minutos'],
    ['99999999999', 'duration', 'La duración debe estar entre 1 y 600 minutos'],
  ] as const)('«%s» en %s da «%s»', (raw, field, message) => {
    expect(validateEpisodeForm({ ...VALID, [field]: raw })).toEqual({ [field]: message });
  });

  it('acepta los extremos de cada rango', () => {
    expect(validateEpisodeForm({ ...VALID, seasonNumber: '100', episodeNumber: '1000', duration: '600' })).toEqual({});
    expect(validateEpisodeForm({ ...VALID, seasonNumber: '1', episodeNumber: '1', duration: '1' })).toEqual({});
  });

  it('título de más de 150 caracteres y sinopsis de más de 1000, con los mensajes del servidor', () => {
    expect(validateEpisodeForm({ ...VALID, title: 'a'.repeat(150), description: 'b'.repeat(1000) })).toEqual({});
    expect(validateEpisodeForm({ ...VALID, title: 'a'.repeat(151), description: 'b'.repeat(1001) })).toEqual({
      title: EPISODE_MESSAGES.titleSize,
      description: 'La descripción no puede superar los 1000 caracteres',
    });
    expect(EPISODE_MESSAGES.titleSize).toBe('El título no puede superar los 150 caracteres');
  });

  it('la URL del vídeo sigue las reglas de las películas: solo https:// (ni portadas propias)', () => {
    expect(validateEpisodeForm({ ...VALID, videoUrl: 'http://videos.example.com/x' })).toEqual({
      videoUrl: 'La URL del vídeo debe empezar por https://',
    });
    expect(validateEpisodeForm({ ...VALID, videoUrl: '/covers/x.webp' })).toEqual({
      videoUrl: 'La URL del vídeo debe empezar por https://',
    });
    expect(validateEpisodeForm({ ...VALID, videoUrl: `https://v.example.com/${'a'.repeat(480)}` })).toEqual({
      videoUrl: 'La URL del vídeo no puede superar los 500 caracteres',
    });
  });
});

describe('toEpisodeRequest / toEpisodeFormValues', () => {
  it('recorta los textos, pasa los números a number y una sinopsis en blanco a null', () => {
    expect(
      toEpisodeRequest({
        seasonNumber: ' 2 ',
        episodeNumber: '04',
        title: '  Piloto ',
        description: '   ',
        duration: ' 52',
        videoUrl: ' https://v.example.com/p ',
      }),
    ).toEqual({
      seasonNumber: 2,
      episodeNumber: 4,
      title: 'Piloto',
      description: null,
      duration: 52,
      videoUrl: 'https://v.example.com/p',
    });
    expect(toEpisodeRequest({ ...VALID, description: ' Algo pasa. ' }).description).toBe('Algo pasa.');
  });

  it('un episodio sin sinopsis se edita con el campo vacío', () => {
    const values = toEpisodeFormValues(makeEpisode({ seasonNumber: 2, episodeNumber: 7, description: null, duration: 50 }));
    expect(values).toMatchObject({ seasonNumber: '2', episodeNumber: '7', description: '', duration: '50' });
  });
});

describe('valores propuestos al añadir', () => {
  it('serie vacía: temporada 1, episodio 1', () => {
    expect(newEpisodeFormValues([])).toMatchObject({ seasonNumber: '1', episodeNumber: '1', title: '' });
  });

  it('la última temporada y el siguiente al número más alto (no rellena huecos)', () => {
    const seasons = [season(1, [1, 2, 3]), season(3, [1, 2, 5])];
    expect(newEpisodeFormValues(seasons)).toMatchObject({ seasonNumber: '3', episodeNumber: '6' });
  });

  it('nextEpisodeNumber: 1 en una temporada nueva; hueco solo si el siguiente pasa de 1000; null si está llena', () => {
    const seasons = [season(1, [1, 2])];
    expect(nextEpisodeNumber(seasons, 1)).toBe(3);
    expect(nextEpisodeNumber(seasons, 2)).toBe(1);
    expect(nextEpisodeNumber([season(1, [1, 2, 1000])], 1)).toBe(3);
    const full = season(1, Array.from({ length: 1000 }, (_, index) => index + 1));
    expect(nextEpisodeNumber([full], 1)).toBeNull();
    expect(newEpisodeFormValues([full]).episodeNumber).toBe('');
  });

  it('parseInRange solo acepta enteros dentro del rango', () => {
    expect(parseInRange('7', 1, 100)).toBe(7);
    expect(parseInRange(' 7 ', 1, 100)).toBe(7);
    expect(parseInRange('0', 1, 100)).toBeNull();
    expect(parseInRange('7.0', 1, 100)).toBeNull();
    expect(parseInRange('', 1, 100)).toBeNull();
  });
});
