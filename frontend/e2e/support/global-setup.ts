/**
 * Siembra del catálogo, una sola vez antes de todos los tests.
 *
 * Playwright lo ejecuta DESPUÉS de que los dos servidores (`webServer`) estén
 * listos. Con el administrador del backend efímero crea los géneros y las 25
 * películas de `catalog.ts`, en orden (la última creada es la del banner), y
 * después las series con sus episodios (también en orden: la última con
 * episodios es la del banner de `/series`; la última de todas no tiene episodios).
 */
import { request } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import { coverUrl, episodeTitle, episodeVideoUrl, GENRES, MOVIES, PAGE_SIZE, SERIES, videoUrl } from './catalog';
import {
  catalogSize,
  createEpisode,
  createGenre,
  createMovie,
  createSeries,
  listGenres,
  loginAdmin,
  seriesCatalogSize,
} from './api';

/** Traduce nombres de género a ids; falla con un mensaje claro si alguno no existe. */
function toGenreIds(names: readonly string[], genreIds: Map<string, number>): number[] {
  return names.map((name) => {
    const id = genreIds.get(name);
    if (id === undefined) throw new Error(`Género desconocido en el catálogo: ${name}`);
    return id;
  });
}

/** Crea las películas (y antes sus géneros). */
async function seedMovies(api: APIRequestContext, adminToken: string): Promise<void> {
  const genreIds = new Map<string, number>();
  for (const name of GENRES) {
    genreIds.set(name, await createGenre(api, adminToken, name));
  }

  // En secuencia, no en paralelo: el orden de creación define el orden del catálogo.
  for (const movie of MOVIES) {
    await createMovie(api, adminToken, {
      title: movie.title,
      description: movie.description,
      duration: movie.duration,
      releaseYear: movie.releaseYear,
      imageUrl: coverUrl(movie),
      videoUrl: videoUrl(movie),
      genreIds: toGenreIds(movie.genres, genreIds),
    });
  }
}

/**
 * Crea las series y sus episodios. Cada episodio se llama «Capítulo T.E» y la
 * segunda mitad lleva sinopsis (la primera no: la sinopsis es opcional y la
 * página debe verse bien sin ella).
 */
async function seedSeries(api: APIRequestContext, adminToken: string): Promise<void> {
  const genreIds = new Map((await listGenres(api, adminToken)).map((genre) => [genre.name, genre.id] as const));

  // En secuencia: el orden de creación define el orden de `/series` (más reciente primero).
  for (const series of SERIES) {
    const seriesId = await createSeries(api, adminToken, {
      title: series.title,
      description: series.description,
      releaseYear: series.releaseYear,
      endYear: series.endYear,
      imageUrl: coverUrl(series),
      genreIds: toGenreIds(series.genres, genreIds),
    });
    for (const [seasonIndex, episodeCount] of series.seasons.entries()) {
      const seasonNumber = seasonIndex + 1;
      for (let episodeNumber = 1; episodeNumber <= episodeCount; episodeNumber += 1) {
        await createEpisode(api, adminToken, seriesId, {
          seasonNumber,
          episodeNumber,
          title: episodeTitle(seasonNumber, episodeNumber),
          description: episodeNumber % 2 === 0 ? `Sinopsis del capítulo ${seasonNumber}.${episodeNumber}.` : null,
          duration: 40 + episodeNumber,
          videoUrl: episodeVideoUrl(series, seasonNumber, episodeNumber),
        });
      }
    }
  }
}

export default async function globalSetup(): Promise<void> {
  // Si alguien edita el catálogo y rompe la premisa de "dos páginas", que falle aquí y no en un test lejano.
  if (MOVIES.length <= PAGE_SIZE || MOVIES.length > 2 * PAGE_SIZE) {
    throw new Error(`El catálogo sembrado debe tener entre ${PAGE_SIZE + 1} y ${2 * PAGE_SIZE} películas (dos páginas).`);
  }

  const api = await request.newContext();
  try {
    const adminToken = await loginAdmin(api);

    // Cada parte se comprueba por separado: con `E2E_REUSE_SERVERS=1` (backend ya sembrado de una
    // ejecución anterior) no se duplica nada, y un backend sembrado antes de existir las series las recibe.
    if ((await catalogSize(api, adminToken)) === 0) await seedMovies(api, adminToken);
    if ((await seriesCatalogSize(api, adminToken)) === 0) await seedSeries(api, adminToken);
  } finally {
    await api.dispose();
  }
}
