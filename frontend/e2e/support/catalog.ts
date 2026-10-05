/**
 * Catálogo de ejemplo que la suite siembra en el backend efímero (ver `global-setup.ts`).
 *
 * Es DETERMINISTA y está pensado para ejercitar cada parte de la portada:
 *  - 25 películas: con `size=20` por página son exactamente DOS páginas, así que
 *    "Cargar más películas" se prueba de verdad (20 + 5).
 *  - 3 géneros repartidos de forma que todos tengan filas propias (≥ 3 películas)
 *    y algunas películas pertenezcan a dos géneros.
 *  - Portadas reales de `public/covers` en unas y una portada propia válida pero
 *    inexistente en otras, para provocar el hueco de respaldo con el título (`MoviePoster`).
 *  - Títulos largos y una sinopsis larga para ver cómo se comporta el diseño.
 *
 * Nota: el backend exige AL MENOS UN género por película (`@NotEmpty` en
 * `MovieRequest.genreIds`), así que no se puede sembrar una película sin género.
 *
 * El ORDEN es el de creación (de la más antigua a la más reciente). La portada
 * ordena por `createdAt` descendente, así que la ÚLTIMA de la lista es la del banner.
 */
export interface SeedMovie {
  title: string;
  description: string;
  duration: number;
  releaseYear: number;
  /** Nombre del archivo de `public/covers` o `null` para una portada válida que no existe (404). */
  cover: string | null;
  /** Nombres de los géneros (deben estar en {@link GENRES}). */
  genres: string[];
}

/** Géneros que se crean antes que las películas. */
export const GENRES = ['Ciencia ficción', 'Acción', 'Drama'] as const;

const SCI_FI = 'Ciencia ficción';
const ACTION = 'Acción';
const DRAMA = 'Drama';

const SHORT = 'Una historia de ejemplo para las pruebas automáticas de StreamBox.';

const LONG =
  'En un futuro cercano, una tripulación internacional emprende una misión sin retorno hacia los confines del sistema solar. ' +
  'Lo que empieza como una expedición científica se convierte en una lucha por la supervivencia cuando descubren que la nave ' +
  'no viaja sola. Entre decisiones imposibles, lealtades que se rompen y silencios de semanas, cada miembro deberá elegir ' +
  'entre cumplir el plan original o salvar a quienes quedaron atrás. Una película sobre la soledad, la ciencia y el precio ' +
  'de la curiosidad, rodada con un cuidado especial por el detalle y la luz.';

/** Películas en orden de creación; la última es la más reciente (banner de la portada). */
export const MOVIES: readonly SeedMovie[] = [
  // --- Página 2 del catálogo (las 5 más antiguas) ---
  { title: 'Matrix', description: SHORT, duration: 136, releaseYear: 1999, cover: 'cover_matrix_1790887046226.jpg', genres: [SCI_FI, ACTION] },
  { title: 'Matrix Reloaded', description: SHORT, duration: 138, releaseYear: 2003, cover: 'cover_matrix_reloaded_1790887056372.jpg', genres: [SCI_FI, ACTION] },
  { title: 'Minority Report', description: SHORT, duration: 145, releaseYear: 2002, cover: 'cover_minority_report_1790887067191.jpg', genres: [SCI_FI] },
  { title: 'Origen', description: SHORT, duration: 148, releaseYear: 2010, cover: 'cover_origen_1790886992960.jpg', genres: [SCI_FI, DRAMA] },
  { title: 'Prometheus', description: SHORT, duration: 124, releaseYear: 2012, cover: 'cover_prometheus_1790887003569.jpg', genres: [SCI_FI] },
  // --- Página 1 (las 20 más recientes; la última creada es el banner) ---
  { title: 'Tenet', description: SHORT, duration: 150, releaseYear: 2020, cover: 'cover_tenet_1790887077146.jpg', genres: [SCI_FI, ACTION] },
  { title: 'Cielo de Ceniza', description: SHORT, duration: 102, releaseYear: 2015, cover: null, genres: [DRAMA] },
  { title: 'La Última Frontera', description: SHORT, duration: 118, releaseYear: 2016, cover: null, genres: [ACTION] },
  { title: 'Eco de Medianoche', description: SHORT, duration: 95, releaseYear: 2017, cover: null, genres: [DRAMA] },
  { title: 'Dredd', description: SHORT, duration: 95, releaseYear: 2012, cover: 'dredd.webp', genres: [ACTION] },
  { title: 'Mad Max', description: SHORT, duration: 120, releaseYear: 2015, cover: 'mad-max.webp', genres: [ACTION, SCI_FI] },
  { title: 'Ex Machina', description: SHORT, duration: 108, releaseYear: 2014, cover: 'ex-machina.webp', genres: [SCI_FI, DRAMA] },
  {
    title: 'El increíble viaje de la nave perdida más allá de las estrellas olvidadas',
    description: LONG,
    duration: 187,
    releaseYear: 2019,
    cover: null,
    genres: [DRAMA, SCI_FI],
  },
  { title: 'Guardianes de la Galaxia', description: SHORT, duration: 121, releaseYear: 2014, cover: 'guardianes-de-la-galaxia.webp', genres: [ACTION, SCI_FI] },
  { title: 'Puerto Seco', description: SHORT, duration: 99, releaseYear: 2018, cover: null, genres: [DRAMA] },
  { title: 'Al filo del mañana', description: SHORT, duration: 113, releaseYear: 2014, cover: 'al-filo-del-manana.webp', genres: [ACTION, SCI_FI] },
  { title: 'Rutas de Hierro', description: SHORT, duration: 110, releaseYear: 2021, cover: null, genres: [ACTION] },
  { title: 'Blade Runner', description: SHORT, duration: 117, releaseYear: 1982, cover: 'blade-runner.webp', genres: [SCI_FI] },
  { title: 'El marciano', description: SHORT, duration: 144, releaseYear: 2015, cover: 'el-marciano.webp', genres: [SCI_FI, DRAMA] },
  { title: 'Noches de Invierno', description: SHORT, duration: 105, releaseYear: 2013, cover: null, genres: [DRAMA] },
  { title: 'Interstellar', description: LONG, duration: 169, releaseYear: 2014, cover: 'interstellar.webp', genres: [SCI_FI, DRAMA] },
  { title: 'Fuego Cruzado', description: SHORT, duration: 101, releaseYear: 2022, cover: null, genres: [ACTION] },
  { title: 'Blade Runner 2049', description: SHORT, duration: 164, releaseYear: 2017, cover: 'blade-runner-2049.webp', genres: [SCI_FI, DRAMA] },
  { title: 'La Casa del Lago', description: SHORT, duration: 97, releaseYear: 2020, cover: null, genres: [DRAMA] },
  // --- La más reciente: protagoniza el banner. Tiene portada para ver el degradado sobre una imagen real. ---
  { title: 'Dune: Parte Dos', description: LONG, duration: 166, releaseYear: 2024, cover: 'dune-parte-dos.webp', genres: [SCI_FI, ACTION, DRAMA] },
];

/** Título de la película más reciente: la del banner de la portada. */
export const HERO_TITLE = MOVIES[MOVIES.length - 1].title;

/** Películas por página que pide la portada (`PAGE_SIZE` de `useCatalog`). */
export const PAGE_SIZE = 20;

/**
 * Convierte un título en un nombre de archivo seguro: sin tildes, en minúsculas y con guiones
 * ("La Última Frontera" → "la-ultima-frontera"). Se usa en lugar de `encodeURIComponent`, que mete
 * `%` y espacios codificados que el backend ya no admite en una portada propia.
 */
function slug(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '') // marcas diacríticas que deja `normalize('NFD')` (tildes, diéresis...)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40);
}

/**
 * URL de la portada: una portada PROPIA, relativa (`/covers/<archivo>`).
 *
 * El backend solo admite `https://` o `/covers/<archivo>`, con un archivo que empieza por letra o
 * número y solo lleva letras, números, `.`, `_` y `-`. Antes se usaba la URL absoluta del Vite de la
 * suite (`http://localhost:5199/covers/...`), que hoy se rechazaría por no ser `https`. La ruta
 * relativa la sirve el propio frontend (`public/covers`) sea cual sea el puerto.
 *
 * Las películas "sin portada" apuntan a un archivo que cumple el formato pero no existe
 * (`/covers/no-existe-<título>.webp`): el navegador recibe un 404 y se ve el hueco de respaldo.
 */
export function coverUrl(movie: SeedMovie): string {
  return movie.cover ? `/covers/${movie.cover}` : `/covers/no-existe-${slug(movie.title)}.webp`;
}

/** Enlace de vídeo de ejemplo (`https`). El test nunca lo abre: solo comprueba el `href`. */
export function videoUrl(movie: SeedMovie): string {
  return `https://videos.streambox.example/watch/${encodeURIComponent(movie.title)}`;
}
