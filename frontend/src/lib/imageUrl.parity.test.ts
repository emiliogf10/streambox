import { describe, expect, it } from 'vitest';
import contract from '../test/imageUrlCases.json';
import { EPISODE_MESSAGES, validateEpisodeForm, validateEpisodeImageUrl } from './episodeValidation';
import type { EpisodeFormValues } from './episodeValidation';
import {
  BACKDROP_URL_FORMAT_MESSAGE,
  BACKDROP_URL_SIZE_MESSAGE,
  EMPTY_MOVIE_FORM,
  IMAGE_URL_FORMAT_MESSAGE,
  IMAGE_URL_SIZE_MESSAGE,
  validateBackdropUrl,
  validateImageUrl,
  validateMovieForm,
} from './movieValidation';
import type { MovieFormValues } from './movieValidation';
import { EMPTY_SERIES_FORM, validateSeriesForm } from './seriesValidation';
import type { SeriesFormValues } from './seriesValidation';

/**
 * Paridad de las TRES imágenes con la regla de la portada: la portada, la
 * imagen de fondo (películas y series) y la imagen del episodio.
 *
 * El backend las valida con la misma anotación (`@HttpsUrl(allowLocalCovers =
 * true)`) y solo cambia el texto; aquí cada campo tiene su mensaje, pero las
 * tres comparten `validateCoverLikeUrl`. Este test comprueba, con el catálogo
 * común `src/test/imageUrlCases.json`, que ninguno de los cinco campos del
 * panel se separa de los demás (ni de lo que dirá el servidor): un mismo texto
 * da el mismo resultado («valida», «formato» o «longitud») en todos, cada uno
 * con SU mensaje literal del contrato.
 */

interface ContractCase {
  nota: string;
  url: string;
  /** «valida», «formato» o «longitud». */
  esperado: 'valida' | 'formato' | 'longitud';
}

const cases = (contract as { casos: ContractCase[] }).casos;

/** Mensajes de cada campo (contrato con el backend, letra por letra). */
const MESSAGES = {
  portada: { formato: IMAGE_URL_FORMAT_MESSAGE, longitud: IMAGE_URL_SIZE_MESSAGE },
  fondo: { formato: BACKDROP_URL_FORMAT_MESSAGE, longitud: BACKDROP_URL_SIZE_MESSAGE },
  episodio: { formato: EPISODE_MESSAGES.imageUrlFormat, longitud: EPISODE_MESSAGES.imageUrlSize },
} as const;

/** Mensaje que debe dar un campo para un caso (`undefined` = sin error). */
function expected(field: keyof typeof MESSAGES, item: ContractCase): string | undefined {
  return item.esperado === 'valida' ? undefined : MESSAGES[field][item.esperado];
}

/** Película válida salvo lo que cambie cada caso. */
const MOVIE: MovieFormValues = {
  ...EMPTY_MOVIE_FORM,
  title: 'Sintel',
  description: 'Una joven busca a su dragón.',
  duration: '15',
  releaseYear: '2010',
  imageUrl: '/covers/sintel.webp',
  genreIds: [1],
};

/** Serie válida salvo lo que cambie cada caso. */
const SERIES: SeriesFormValues = {
  ...EMPTY_SERIES_FORM,
  title: 'Dark',
  description: 'Un pueblo alemán y sus secretos.',
  releaseYear: '2017',
  imageUrl: '/covers/dark.webp',
  genreIds: [1],
};

/** Episodio válido salvo lo que cambie cada caso. */
const EPISODE: EpisodeFormValues = {
  seasonNumber: '1',
  episodeNumber: '1',
  title: 'Piloto',
  description: '',
  duration: '45',
  videoUrl: '',
  subtitlesUrl: '',
  imageUrl: '',
};

describe('imágenes: paridad entre campos y con el servidor (contrato compartido)', () => {
  it('el catálogo tiene las tres clases, sin casos repetidos y con el límite exacto de 500', () => {
    for (const kind of ['valida', 'formato', 'longitud']) {
      expect(cases.some((item) => item.esperado === kind)).toBe(true);
    }
    expect(new Set(cases.map((item) => item.url)).size).toBe(cases.length);
    expect(cases.filter((item) => item.nota.startsWith('500')).every((item) => item.url.length === 500)).toBe(true);
    expect(cases.filter((item) => item.nota.startsWith('501')).every((item) => item.url.length === 501)).toBe(true);
  });

  it('los mensajes nuevos son literalmente los del contrato con el backend', () => {
    expect(BACKDROP_URL_FORMAT_MESSAGE).toBe(
      'La URL de la imagen de fondo debe empezar por https:// o ser una portada propia (/covers/archivo)',
    );
    expect(BACKDROP_URL_SIZE_MESSAGE).toBe('La URL de la imagen de fondo no puede superar los 500 caracteres');
    expect(EPISODE_MESSAGES.imageUrlFormat).toBe(
      'La URL de la imagen del episodio debe empezar por https:// o ser una portada propia (/covers/archivo)',
    );
    expect(EPISODE_MESSAGES.imageUrlSize).toBe('La URL de la imagen del episodio no puede superar los 500 caracteres');
  });

  for (const item of cases) {
    describe(`${item.nota} → ${item.esperado}`, () => {
      it('portada (validateImageUrl)', () => {
        expect(validateImageUrl(item.url)).toBe(expected('portada', item));
      });

      it('imagen de fondo (función y formularios de película y serie)', () => {
        expect(validateBackdropUrl(item.url)).toBe(expected('fondo', item));
        expect(validateMovieForm({ ...MOVIE, backdropUrl: item.url }).backdropUrl).toBe(expected('fondo', item));
        expect(validateSeriesForm({ ...SERIES, backdropUrl: item.url }).backdropUrl).toBe(expected('fondo', item));
      });

      it('imagen del episodio (función y formulario)', () => {
        expect(validateEpisodeImageUrl(item.url)).toBe(expected('episodio', item));
        expect(validateEpisodeForm({ ...EPISODE, imageUrl: item.url }).imageUrl).toBe(expected('episodio', item));
      });
    });
  }
});
