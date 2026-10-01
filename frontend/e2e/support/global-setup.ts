/**
 * Siembra del catálogo, una sola vez antes de todos los tests.
 *
 * Playwright lo ejecuta DESPUÉS de que los dos servidores (`webServer`) estén
 * listos. Con el administrador del backend efímero crea los géneros y las 25
 * películas de `catalog.ts`, en orden (la última creada es la del banner).
 */
import { request } from '@playwright/test';
import { coverUrl, GENRES, MOVIES, PAGE_SIZE, videoUrl } from './catalog';
import { catalogSize, createGenre, createMovie, loginAdmin } from './api';

export default async function globalSetup(): Promise<void> {
  // Si alguien edita el catálogo y rompe la premisa de "dos páginas", que falle aquí y no en un test lejano.
  if (MOVIES.length <= PAGE_SIZE || MOVIES.length > 2 * PAGE_SIZE) {
    throw new Error(`El catálogo sembrado debe tener entre ${PAGE_SIZE + 1} y ${2 * PAGE_SIZE} películas (dos páginas).`);
  }

  const api = await request.newContext();
  try {
    const adminToken = await loginAdmin(api);

    // Solo ocurre con `E2E_REUSE_SERVERS=1` (backend ya sembrado de una ejecución anterior): no duplicar.
    if ((await catalogSize(api, adminToken)) > 0) return;

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
        genreIds: movie.genres.map((name) => {
          const id = genreIds.get(name);
          if (id === undefined) throw new Error(`Género desconocido en el catálogo: ${name}`);
          return id;
        }),
      });
    }
  } finally {
    await api.dispose();
  }
}
