import { describe, expect, it } from 'vitest';
import contract from '../test/subtitlesUrlCases.json';
import { validateEpisodeForm } from './episodeValidation';
import type { EpisodeFormValues } from './episodeValidation';
import {
  SUBTITLES_REQUIRE_VIDEO_MESSAGE,
  SUBTITLES_URL_FORMAT_MESSAGE,
  SUBTITLES_URL_SIZE_MESSAGE,
  validateMovieForm,
  validateSubtitlesUrl,
} from './movieValidation';
import type { MovieFormValues } from './movieValidation';

/**
 * Paridad cliente/servidor de la URL de los subtítulos (revisión de QA de la V15).
 *
 * Los casos viven en `src/test/subtitlesUrlCases.json` y los comprueba TAMBIÉN
 * el backend (`SubtitlesUrlParityContractTest`, que lee el mismo archivo con
 * `MovieRequest` y `EpisodeRequest` reales). La regla del `.vtt` se implementa
 * dos veces (`hasVttPath` aquí, `WebVttUrlValidator` allí) y los casos límite
 * que más fácil separan las dos copias son justo los de este archivo: `?` antes
 * o después de `#`, un `#` dentro de la consulta, un host que acaba en `.vtt`
 * sin ruta o un punto codificado (`%2E`, que un `URL.pathname` o un
 * `URI.getPath()` decodificarían o no según el lado).
 *
 * Qué protege: que el panel nunca dé por buenos unos subtítulos que el servidor
 * rechaza con 400 (el administrador solo lo vería al guardar) ni rechace unos
 * que el servidor aceptaría. Se comprueba la función y, además, los dos
 * formularios (película y episodio), que son los que de verdad usa el panel.
 */

interface ContractCase {
  nota: string;
  url: string;
  /** URL del vídeo que acompaña al caso; `null` = sin vídeo. */
  video: string | null;
  /** «valida», «formato», «longitud» o «sinVideo». */
  esperado: string;
}

const cases = (contract as { casos: ContractCase[] }).casos;

/** Traduce el mensaje del cliente al vocabulario del contrato. */
function toContractResult(error: string | undefined): string {
  if (error === undefined) return 'valida';
  if (error === SUBTITLES_URL_SIZE_MESSAGE) return 'longitud';
  if (error === SUBTITLES_URL_FORMAT_MESSAGE) return 'formato';
  if (error === SUBTITLES_REQUIRE_VIDEO_MESSAGE) return 'sinVideo';
  return `mensaje inesperado: ${error}`;
}

/** Película válida en todo lo demás (solo puede fallar el campo de subtítulos). */
const MOVIE: MovieFormValues = {
  title: 'Dune',
  description: '',
  duration: '155',
  releaseYear: '2021',
  imageUrl: 'https://cdn.example.com/dune.jpg',
  videoUrl: '',
  trailerUrl: '',
  subtitlesUrl: '',
  genreIds: [1],
};

/** Episodio válido en todo lo demás. */
const EPISODE: EpisodeFormValues = {
  seasonNumber: '1',
  episodeNumber: '1',
  title: 'Piloto',
  description: '',
  duration: '45',
  videoUrl: '',
  subtitlesUrl: '',
};

describe('subtítulos: paridad con el servidor (contrato compartido)', () => {
  it('el contrato tiene casos de las cuatro clases y ninguno repetido', () => {
    expect(cases.length).toBeGreaterThanOrEqual(40);
    for (const kind of ['valida', 'formato', 'longitud', 'sinVideo']) {
      expect(cases.some((item) => item.esperado === kind)).toBe(true);
    }
    expect(new Set(cases.map((item) => `${item.url} ${item.video}`)).size).toBe(cases.length);
  });

  it('los casos de longitud están justo en el límite (500 vale, 501 no)', () => {
    expect(cases.find((item) => item.nota.startsWith('500'))?.url).toHaveLength(500);
    expect(cases.find((item) => item.nota.startsWith('501'))?.url).toHaveLength(501);
  });

  for (const item of cases) {
    describe(`${item.nota} → ${item.esperado}`, () => {
      it('validateSubtitlesUrl', () => {
        expect(toContractResult(validateSubtitlesUrl(item.url, item.video ?? ''))).toBe(item.esperado);
      });

      it('formulario de película', () => {
        const errors = validateMovieForm({ ...MOVIE, videoUrl: item.video ?? '', subtitlesUrl: item.url });
        expect(toContractResult(errors.subtitlesUrl)).toBe(item.esperado);
      });

      it('formulario de episodio', () => {
        const errors = validateEpisodeForm({ ...EPISODE, videoUrl: item.video ?? '', subtitlesUrl: item.url });
        expect(toContractResult(errors.subtitlesUrl)).toBe(item.esperado);
      });
    });
  }
});
