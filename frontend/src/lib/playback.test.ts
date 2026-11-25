/**
 * Tests de las reglas del reproductor y del progreso (`lib/playback.ts`).
 *
 * Son las decisiones que el usuario nota aunque no las vea: qué vídeo se abre
 * dentro de StreamBox y cuál en otra pestaña, cuándo se guarda (nunca un título
 * abierto por error), qué se envía a la API (enteros, sin pasarse de la
 * duración), desde dónde se reanuda y qué dice el texto que acompaña a la barra.
 */
import { describe, expect, it } from 'vitest';
import { makeEpisode, makeSeriesDetail } from '../test/helpers';
import {
  episodeLabel,
  episodePlayerPath,
  formatRemaining,
  formatTimestamp,
  getDirectVideoUrl,
  getSubtitlesUrl,
  isFinishedAt,
  moviePlayerPath,
  playbackStep,
  progressRatio,
  resumePositionOf,
  seriesWatchTarget,
  toProgressPayload,
} from './playback';
import type { EpisodeProgress, WatchProgress } from './types';

describe('getSubtitlesUrl', () => {
  it.each(['https://subs.example/peli.vtt', 'https://subs.example/peli.vtt?token=a', 'https://subs.example/PELI.VTT'])(
    'acepta %j (https)',
    (url) => {
      expect(getSubtitlesUrl(url)).toBe(new URL(url).href);
    },
  );

  it.each([
    ['vacía', ''],
    ['null', null],
    ['http:', 'http://subs.example/peli.vtt'],
    ['credenciales', 'https://user:pass@subs.example/peli.vtt'],
    ['javascript:', 'javascript:alert(1)'],
    ['data:', 'data:text/vtt,WEBVTT'],
    ['relativa', '/subs/peli.vtt'],
  ])('rechaza %s: el vídeo se ve sin pista', (_caso, url) => {
    expect(getSubtitlesUrl(url)).toBeNull();
  });
});

describe('seriesWatchTarget: `watched` no cambia el destino (lo decide `finished`)', () => {
  it('un episodio visto que se está volviendo a ver se reanuda; no salta al siguiente', () => {
    const e1 = makeEpisode({ id: 1, episodeNumber: 1, videoUrl: 'https://cdn.example/1.mp4' });
    const e2 = makeEpisode({ id: 2, episodeNumber: 2, videoUrl: 'https://cdn.example/2.mp4' });
    const series = makeSeriesDetail({ seasons: [{ seasonNumber: 1, episodes: [e1, e2] }] });
    const rewatching: EpisodeProgress = {
      episodeId: 1,
      positionSeconds: 600,
      durationSeconds: 2700,
      finished: false,
      watched: true,
      updatedAt: '2026-11-21T10:00:00Z',
    };

    expect(seriesWatchTarget(series, [rewatching])).toEqual({ episode: e1, mode: 'resume' });
  });
});

describe('getDirectVideoUrl', () => {
  it.each([
    ['https://cdn.example/peli.mp4'],
    ['https://cdn.example/peli.webm'],
    ['https://cdn.example/peli.ogg'],
    ['https://cdn.example/peli.ogv'],
    ['https://cdn.example/carpeta/PELI.MP4'],
    ['https://cdn.example/peli.mp4?token=abc&exp=1'],
    ['https://cdn.example/peli.webm#t=10'],
  ])('%s es un archivo de vídeo directo: se ve dentro de StreamBox', (url) => {
    expect(getDirectVideoUrl(url)).toBe(new URL(url).href);
  });

  // Antes este test exigía lo contrario (`http:` también al reproductor integrado). Se cambió por la incidencia
  // QA-2: la CSP de nginx (`media-src 'self' https:`) bloquea el `<video>` con un `http://` de otro origen y, con la
  // aplicación en HTTPS, sería contenido mixto. El usuario veía «No se ha podido abrir el archivo» de un enlace que
  // funciona; ahora se abre en otra pestaña, como cualquier vídeo que no se ve aquí.
  it.each([['http://cdn.example/peli.mp4'], ['http://cdn.example/peli.webm?token=abc'], ['HTTP://CDN.EXAMPLE/PELI.MP4']])(
    '%s es un archivo directo, pero por `http:`: se abre en otra pestaña, no en el reproductor integrado',
    (url) => {
      expect(getDirectVideoUrl(url)).toBeNull();
    },
  );

  it.each([
    ['https://www.youtube.com/watch?v=abc'],
    ['https://vimeo.com/12345'],
    ['https://cdn.example/lista.m3u8'],
    ['https://cdn.example/peli.mp4.html'],
    ['https://cdn.example/ver?archivo=peli.mp4'],
    ['https://cdn.example/peli.mkv'],
    ['https://cdn.example/peli'],
    ['https://cdn.example/'],
  ])('%s no es un archivo directo: sigue abriéndose en otra pestaña', (url) => {
    expect(getDirectVideoUrl(url)).toBeNull();
  });

  it.each([
    ['javascript:alert(1)//.mp4'],
    ['data:video/mp4;base64,AAAA'],
    ['https://usuario:clave@cdn.example/peli.mp4'],
    ['/covers/peli.mp4'],
    [''],
    [null],
    [undefined],
  ])('%j no es seguro (o no es una URL): nunca llega al <video>', (url) => {
    expect(getDirectVideoUrl(url)).toBeNull();
  });
});

describe('rutas del reproductor', () => {
  it('cada título tiene su dirección propia (enlazable y con «Atrás»)', () => {
    expect(moviePlayerPath(7)).toBe('/ver/peliculas/7');
    expect(episodePlayerPath(3, 41)).toBe('/ver/series/3/episodios/41');
  });
});

describe('playbackStep: solo cuenta la reproducción real', () => {
  it('un avance normal entre dos `timeupdate` cuenta entero (también a velocidad 2×)', () => {
    expect(playbackStep(10, 10.25)).toBeCloseTo(0.25);
    expect(playbackStep(10, 10.5)).toBeCloseTo(0.5);
  });

  it('un salto con la barra (hacia delante o hacia atrás) no cuenta como visto', () => {
    expect(playbackStep(10, 600)).toBe(0);
    expect(playbackStep(600, 10)).toBe(0);
    expect(playbackStep(10, 10)).toBe(0);
  });
});

describe('toProgressPayload', () => {
  it('envía segundos enteros (hacia abajo)', () => {
    expect(toProgressPayload(754.9, 8160.6, false)).toEqual({ positionSeconds: 754, durationSeconds: 8160 });
  });

  it('al terminar, la posición es la duración (el servidor lo da por visto)', () => {
    expect(toProgressPayload(29.7, 30.4, true)).toEqual({ positionSeconds: 30, durationSeconds: 30 });
  });

  it('la posición nunca pasa de la duración (la API respondería 400)', () => {
    expect(toProgressPayload(30.9, 30.4, false)).toEqual({ positionSeconds: 30, durationSeconds: 30 });
  });

  it.each([
    ['duración desconocida (antes de cargar)', Number.NaN],
    ['un directo (duración infinita)', Number.POSITIVE_INFINITY],
    ['menos de 1 s', 0.6],
    ['más de 24 h', 86_401],
  ])('sin nada que se pueda guardar con %s: null', (_name, duration) => {
    expect(toProgressPayload(0, duration, false)).toBeNull();
  });

  it('24 h justas todavía se guardan (el máximo de la API)', () => {
    expect(toProgressPayload(10, 86_400, false)).toEqual({ positionSeconds: 10, durationSeconds: 86_400 });
  });
});

describe('isFinishedAt (copia de la regla del servidor: 95 %)', () => {
  it('desde el 95 % cuenta como visto', () => {
    expect(isFinishedAt(95, 100)).toBe(true);
    expect(isFinishedAt(94, 100)).toBe(false);
    expect(isFinishedAt(0, 0)).toBe(false);
  });
});

function progress(overrides: Partial<WatchProgress> = {}): WatchProgress {
  return { positionSeconds: 754, durationSeconds: 8160, finished: false, watched: overrides.finished ?? false, updatedAt: '2026-11-21T10:00:00Z', ...overrides };
}

describe('resumePositionOf', () => {
  it('a medias: se reanuda donde se dejó', () => {
    expect(resumePositionOf(progress())).toBe(754);
  });

  it('sin progreso, terminado o apenas empezado: desde el principio', () => {
    expect(resumePositionOf(null)).toBe(0);
    expect(resumePositionOf(undefined)).toBe(0);
    expect(resumePositionOf(progress({ finished: true, positionSeconds: 8100 }))).toBe(0);
    expect(resumePositionOf(progress({ positionSeconds: 3 }))).toBe(0);
  });
});

describe('progressRatio', () => {
  it('es la proporción vista, entre 0 y 1', () => {
    expect(progressRatio(30, 120)).toBe(0.25);
    expect(progressRatio(500, 120)).toBe(1);
    expect(progressRatio(10, 0)).toBe(0);
  });
});

describe('formatTimestamp', () => {
  it.each([
    [5, '0:05'],
    [754, '12:34'],
    [3723, '1:02:03'],
    [-4, '0:00'],
    [59.9, '0:59'],
  ])('%d s → %s', (seconds, text) => {
    expect(formatTimestamp(seconds)).toBe(text);
  });
});

describe('formatRemaining', () => {
  it.each([
    [0, 30, 'Queda menos de 1 min'],
    [0, 60, 'Queda 1 min'],
    [0, 61, 'Quedan 2 min'],
    [100, 2140, 'Quedan 34 min'],
    [0, 3600, 'Queda 1 h'],
    [0, 7200, 'Quedan 2 h'],
    [0, 3900, 'Quedan 1 h 5 min'],
    [500, 400, 'Queda menos de 1 min'],
  ])('en el segundo %d de %d: «%s»', (position, duration, text) => {
    expect(formatRemaining(position, duration)).toBe(text);
  });
});

describe('seriesWatchTarget', () => {
  const e1 = makeEpisode({ id: 1, seasonNumber: 1, episodeNumber: 1, videoUrl: 'https://cdn.example/1.mp4' });
  const e2 = makeEpisode({ id: 2, seasonNumber: 1, episodeNumber: 2, videoUrl: 'https://cdn.example/2.mp4' });
  const e3 = makeEpisode({ id: 3, seasonNumber: 2, episodeNumber: 1, videoUrl: 'https://cdn.example/3.mp4' });
  const series = makeSeriesDetail({
    seasons: [
      { seasonNumber: 1, episodes: [e1, e2] },
      { seasonNumber: 2, episodes: [e3] },
    ],
  });

  function episodeProgress(episodeId: number, overrides: Partial<EpisodeProgress> = {}): EpisodeProgress {
    return { episodeId, ...progress(), ...overrides };
  }

  it('sin progreso: el primer episodio con vídeo', () => {
    expect(seriesWatchTarget(series, undefined)).toEqual({ episode: e1, mode: 'start' });
    expect(seriesWatchTarget(series, [])).toEqual({ episode: e1, mode: 'start' });
  });

  it('el último episodio que tocó, a medias: se reanuda (aunque otro más antiguo también esté a medias)', () => {
    const list = [
      episodeProgress(1, { updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(2, { updatedAt: '2026-11-21T10:00:00Z' }),
    ];
    expect(seriesWatchTarget(series, list)).toEqual({ episode: e2, mode: 'resume' });
  });

  it('el último que vio está terminado: el siguiente, aunque sea de otra temporada', () => {
    expect(seriesWatchTarget(series, [episodeProgress(2, { finished: true })])).toEqual({ episode: e3, mode: 'episode' });
  });

  it('terminado el último de la serie: se vuelve a empezar', () => {
    expect(seriesWatchTarget(series, [episodeProgress(3, { finished: true })])).toEqual({ episode: e1, mode: 'start' });
  });

  it('QA-4: T1:E1 a medias y luego T1:E2 terminado → el siguiente al último tocado (T2:E1), no el T1:E1 a medias', () => {
    const list = [
      episodeProgress(1, { positionSeconds: 600, updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(2, { finished: true, updatedAt: '2026-11-21T10:00:00Z' }),
    ];
    expect(seriesWatchTarget(series, list)).toEqual({ episode: e3, mode: 'episode' });
  });

  it('terminado el último tocado y el siguiente ya estaba a medias: se REANUDA el siguiente (el reproductor lo reanudará)', () => {
    const list = [
      episodeProgress(3, { positionSeconds: 600, updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(2, { finished: true, updatedAt: '2026-11-21T10:00:00Z' }),
    ];
    expect(seriesWatchTarget(series, list)).toEqual({ episode: e3, mode: 'resume' });
  });

  it('el siguiente apenas empezado o ya visto entero: se abre sin «Reanudar» (el reproductor empezará en el 0)', () => {
    const barely = [
      episodeProgress(3, { positionSeconds: 2, updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(2, { finished: true, updatedAt: '2026-11-21T10:00:00Z' }),
    ];
    expect(seriesWatchTarget(series, barely)).toEqual({ episode: e3, mode: 'episode' });
    const seen = [
      episodeProgress(3, { finished: true, updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(2, { finished: true, updatedAt: '2026-11-21T10:00:00Z' }),
    ];
    expect(seriesWatchTarget(series, seen)).toEqual({ episode: e3, mode: 'episode' });
  });

  it('el siguiente con vídeo se salta los que no tienen (aunque sean de otra temporada)', () => {
    const gap = makeSeriesDetail({
      seasons: [
        { seasonNumber: 1, episodes: [e1, { ...e2, videoUrl: null }] },
        { seasonNumber: 2, episodes: [e3] },
      ],
    });
    expect(seriesWatchTarget(gap, [episodeProgress(1, { finished: true })])).toEqual({ episode: e3, mode: 'episode' });
  });

  it('terminado el último y sin más: el primero, con el texto que dice su propio progreso', () => {
    const lastAt = '2026-11-21T10:00:00Z';
    // Toda la serie vista: «Volver a ver».
    const all = [
      episodeProgress(1, { finished: true, updatedAt: '2026-11-19T10:00:00Z' }),
      episodeProgress(2, { finished: true, updatedAt: '2026-11-20T10:00:00Z' }),
      episodeProgress(3, { finished: true, updatedAt: lastAt }),
    ];
    expect(seriesWatchTarget(series, all)).toEqual({ episode: e1, mode: 'rewatch' });
    // El primero se quedó a medias: el reproductor lo reanudará, así que el botón también.
    const firstHalf = [episodeProgress(1, { positionSeconds: 600, updatedAt: '2026-11-19T10:00:00Z' }), all[2]];
    expect(seriesWatchTarget(series, firstHalf)).toEqual({ episode: e1, mode: 'resume' });
  });

  it('apenas empezado: se abre ese episodio sin «Reanudar»', () => {
    expect(seriesWatchTarget(series, [episodeProgress(3, { positionSeconds: 2 })])).toEqual({ episode: e3, mode: 'episode' });
  });

  it('el progreso de un episodio que ya no está (o no tiene vídeo) no cuenta', () => {
    const noVideo = makeSeriesDetail({
      seasons: [{ seasonNumber: 1, episodes: [e1, { ...e2, videoUrl: null }] }],
    });
    expect(seriesWatchTarget(noVideo, [episodeProgress(2), episodeProgress(99)])).toEqual({ episode: e1, mode: 'start' });
  });

  it('ningún episodio con vídeo: no hay botón', () => {
    const none = makeSeriesDetail({ seasons: [{ seasonNumber: 1, episodes: [{ ...e1, videoUrl: null }] }] });
    expect(seriesWatchTarget(none, [])).toBeNull();
  });

  /**
   * Revisión QA de QA-4 (severidad BAJA, corregida): nació como `it.fails` y
   * pasó a `it` con el desempate por id de `seriesWatchTarget`.
   *
   * Empate de `updatedAt` entre dos episodios de la serie: el servidor
   * (`WatchProgressRepository.CONTINUE_WATCHING_SQL`, `ORDER BY p.updated_at
   * DESC, p.episode_id DESC`) toma como último tocado el de **id mayor** (aquí
   * el T1:E2 terminado) y la tarjeta de «Seguir viendo» es el siguiente, T2:E1.
   * `seriesWatchTarget` ordenaba solo por fecha con un `sort` estable, así que se
   * quedaba con el que llega antes del servidor (orden de temporada y número: el
   * T1:E1 a medias) y la página proponía «Reanudar T1:E1»: tarjeta y página
   * dejaban de coincidir, que es justo lo que QA-4 quería evitar.
   */
  it('empate de fecha: el último tocado es el de id mayor, como en el servidor', () => {
    const sameInstant = '2026-11-21T10:00:00Z';
    const list = [
      episodeProgress(1, { positionSeconds: 600, updatedAt: sameInstant }),
      episodeProgress(2, { finished: true, updatedAt: sameInstant }),
    ];
    expect(seriesWatchTarget(series, list)).toEqual({ episode: e3, mode: 'episode' });
  });
});

describe('episodeLabel', () => {
  it('código y título, con el mismo código que la lista de episodios', () => {
    expect(episodeLabel({ seasonNumber: 1, episodeNumber: 2, title: 'Mentiras' })).toBe('T1:E2 · Mentiras');
  });
});
