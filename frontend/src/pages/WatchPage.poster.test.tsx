/**
 * Tests del `poster` del reproductor (lo que se ve mientras carga el vídeo).
 *
 * El hueco del vídeo es 16:9, así que se prefiere una imagen horizontal y solo
 * si no hay, la portada vertical (`lib/titleImages.ts`):
 * - película: imagen de fondo → portada;
 * - episodio: imagen del episodio → imagen de fondo de la serie → portada de la serie.
 *
 * Y si la elegida está ROTA, la siguiente (incidencia QA-V16-1): el atributo
 * `poster` no avisa cuando falla, así que sin esa comprobación una imagen de
 * fondo con 404 dejaba el vídeo en negro (antes de la V16 se veía la portada).
 * jsdom no descarga imágenes: el resultado lo decide `test/fakeImage.ts`.
 */
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Movie, SeriesDetail } from '../lib/types';
import {
  jsonResponse,
  makeEpisode,
  makeMovie,
  makeSeriesDetail,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { failImage, installFakeImage, loadImage, requestedImages } from '../test/fakeImage';
import { WatchEpisodePage, WatchMoviePage } from './WatchPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  installFakeImage();
});

/** `poster` que tiene ahora el vídeo. */
function currentPoster() {
  return document.querySelector('video')?.getAttribute('poster');
}

async function moviePoster(movie: Movie) {
  routeFetch(fetchMock, {
    'GET /api/movies/7': () => jsonResponse(movie),
    'GET /api/users/me/progress/movies/7': noContentResponse,
  });
  renderWithProviders(
    <Routes>
      <Route path="/ver/peliculas/:id" element={<WatchMoviePage />} />
    </Routes>,
    { session: true, route: '/ver/peliculas/7' },
  );
  await screen.findByRole('heading', { level: 1, name: movie.title });
  return currentPoster();
}

async function episodePoster(series: SeriesDetail) {
  routeFetch(fetchMock, {
    'GET /api/series/3': () => jsonResponse(series),
    'GET /api/users/me/progress/episodes/41': noContentResponse,
  });
  renderWithProviders(
    <Routes>
      <Route path="/ver/series/:seriesId/episodios/:episodeId" element={<WatchEpisodePage />} />
    </Routes>,
    { session: true, route: '/ver/series/3/episodios/41' },
  );
  await screen.findByRole('heading', { level: 1, name: series.title });
  return currentPoster();
}

const movie = makeMovie({
  id: 7,
  title: 'Sintel',
  imageUrl: '/covers/sintel.webp',
  videoUrl: 'https://cdn.example/sintel.webm',
});

describe('reproductor: poster de una película', () => {
  it('con imagen de fondo, la imagen de fondo', async () => {
    expect(await moviePoster({ ...movie, backdropUrl: '/covers/sintel-fondo.webp' })).toBe('/covers/sintel-fondo.webp');
  });

  it('sin ella, la portada de siempre', async () => {
    expect(await moviePoster({ ...movie, backdropUrl: null })).toBe('/covers/sintel.webp');
  });

  it('si la imagen de fondo no carga, pasa a la portada (no se queda en negro)', async () => {
    expect(await moviePoster({ ...movie, backdropUrl: '/covers/rota.webp' })).toBe('/covers/rota.webp');
    failImage('/covers/rota.webp');
    expect(currentPoster()).toBe('/covers/sintel.webp');
  });

  it('si la imagen de fondo carga, se queda con ella y no pide nada más', async () => {
    expect(await moviePoster({ ...movie, backdropUrl: '/covers/sintel-fondo.webp' })).toBe('/covers/sintel-fondo.webp');
    loadImage('/covers/sintel-fondo.webp');
    expect(currentPoster()).toBe('/covers/sintel-fondo.webp');
    // La portada (la última candidata) no se comprueba: no hay nada detrás a lo que pasar.
    expect(requestedImages()).not.toContain('/covers/sintel.webp');
  });

  it('con una sola imagen, ninguna petición extra', async () => {
    expect(await moviePoster({ ...movie, backdropUrl: null })).toBe('/covers/sintel.webp');
    expect(requestedImages()).toEqual([]);
  });
});

describe('reproductor: poster de un episodio', () => {
  function seriesWith(episodeImage: string | null, backdropUrl: string | null) {
    const episode = makeEpisode({
      id: 41,
      seasonNumber: 1,
      episodeNumber: 1,
      title: 'Piloto',
      videoUrl: 'https://cdn.example/dark-1x01.webm',
      imageUrl: episodeImage,
    });
    return makeSeriesDetail({
      id: 3,
      title: 'Dark',
      imageUrl: '/covers/dark.webp',
      backdropUrl,
      seasons: [{ seasonNumber: 1, episodes: [episode] }],
    });
  }

  it('con imagen propia, la del episodio', async () => {
    expect(await episodePoster(seriesWith('/covers/dark-1x01.webp', '/covers/dark-fondo.webp'))).toBe(
      '/covers/dark-1x01.webp',
    );
  });

  it('sin ella, la imagen de fondo de la serie', async () => {
    expect(await episodePoster(seriesWith(null, '/covers/dark-fondo.webp'))).toBe('/covers/dark-fondo.webp');
  });

  it('sin ninguna de las dos, la portada de la serie (como antes)', async () => {
    expect(await episodePoster(seriesWith(null, null))).toBe('/covers/dark.webp');
  });

  it('una imagen del episodio rota no tapa el fondo de la serie; si el fondo también falla, la portada', async () => {
    expect(await episodePoster(seriesWith('/covers/rota-1x01.webp', '/covers/rota-fondo.webp'))).toBe(
      '/covers/rota-1x01.webp',
    );
    failImage('/covers/rota-1x01.webp');
    expect(currentPoster()).toBe('/covers/rota-fondo.webp');
    failImage('/covers/rota-fondo.webp');
    expect(currentPoster()).toBe('/covers/dark.webp');
  });
});
