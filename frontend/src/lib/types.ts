/**
 * Tipos que describen los datos que intercambia el frontend con la API.
 *
 * Reflejan los DTO del backend (`MovieResponse`, `UserResponse`, `ErrorResponse`...).
 * Si cambia un DTO, se cambia aquí y el compilador señala todo lo que se rompe.
 */

/** Género cinematográfico (`GenreResponse`). */
export interface Genre {
  id: number;
  name: string;
}

/**
 * Lo que tienen en común todos los títulos del catálogo (películas y series).
 *
 * Existe para que los componentes visuales (póster, tarjeta, fila, banner...)
 * sirvan a los dos tipos sin duplicarse: piden un `CatalogItem` y no saben si es
 * una película o una serie. Lo propio de cada tipo (duración y vídeo de la
 * película; años, temporadas y episodios de la serie) lo añade quien los usa.
 */
export interface CatalogItem {
  id: number;
  title: string;
  description: string;
  /** Año de estreno (en una serie, el de su primera temporada). */
  releaseYear: number;
  /** Portada vertical (2:3): `https://...` o una propia `/covers/<archivo>`. */
  imageUrl: string;
  /** Instante de alta en formato ISO-8601 (UTC). */
  createdAt: string;
  /** Géneros, ya ordenados por nombre por el servidor. */
  genres: Genre[];
}

/** Película tal como la devuelve la API (`MovieResponse`). */
export interface Movie extends CatalogItem {
  /** Duración en minutos. */
  duration: number;
  videoUrl: string;
}

/** Episodio de una serie (`EpisodeResponse`). No tiene imagen propia: usa la de la serie. */
export interface Episode {
  id: number;
  seasonNumber: number;
  /** Número dentro de su temporada (empieza en 1). */
  episodeNumber: number;
  title: string;
  /** Sinopsis del episodio; `null` si no la tiene (es opcional). */
  description: string | null;
  /** Duración en minutos. */
  duration: number;
  /** Sin validar: antes de enlazarla hay que pasarla por `getSafeVideoUrl`. */
  videoUrl: string;
}

/** Temporada de una serie (`SeasonResponse`), con sus episodios ordenados por número. */
export interface Season {
  seasonNumber: number;
  episodes: Episode[];
}

/**
 * Serie tal como la devuelven los listados (`SeriesResponse`).
 *
 * Los usuarios solo reciben series con al menos un episodio: las vacías no
 * aparecen en listados ni búsquedas, y su detalle responde 404.
 */
export interface Series extends CatalogItem {
  /** Año de la última temporada, o `null` si sigue en emisión. */
  endYear: number | null;
  seasonCount: number;
  episodeCount: number;
}

/** Detalle de una serie (`SeriesDetailResponse`): la serie con sus temporadas, ordenadas por número. */
export interface SeriesDetail extends Series {
  seasons: Season[];
}

/** Página de resultados con los metadatos de paginación (`MoviePageResponse`, `SeriesPageResponse`). */
export interface PageResponse<T> {
  content: T[];
  /** Número de página actual, empezando en 0. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/** Rol de un usuario (`Role`). */
export type Role = 'USER' | 'ADMIN';

/** Datos públicos de un usuario (`UserResponse`); nunca incluye la contraseña. */
export interface User {
  id: number;
  username: string;
  email: string;
  role: Role;
  createdAt: string;
}

/** Cuerpo de `POST /api/users` (registro público; el servidor siempre crea rol `USER`). */
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

/** Cuerpo de `PATCH /api/users/me` (cambiar mi nombre). Solo `username`: enviar `email`, `role` o `password` da 400. */
export interface UpdateProfileRequest {
  username: string;
}

/** Cuerpo de `PUT /api/users/me/password` (cambiar mi contraseña; responde 204 con cookies nuevas). */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * Cuerpo de `POST /api/movies` y `PUT /api/movies/{id}` (`MovieRequest`, solo
 * administradores). `PUT` sustituye la película completa, así que alta y
 * edición envían exactamente los mismos campos.
 */
export interface MovieRequest {
  title: string;
  description: string;
  /** Duración en minutos (entero, al menos 1). */
  duration: number;
  releaseYear: number;
  /** `https://...` o una portada propia `/covers/<archivo>`. */
  imageUrl: string;
  /** Solo `https://...`. */
  videoUrl: string;
  /** Al menos un género. */
  genreIds: number[];
}

/**
 * Cuerpo de `POST /api/series` y `PUT /api/series/{id}` (`SeriesRequest`, solo
 * administradores). Como en películas, `PUT` sustituye la serie completa. Los
 * episodios no van aquí: se gestionan uno a uno ({@link EpisodeRequest}).
 */
export interface SeriesRequest {
  title: string;
  description: string;
  releaseYear: number;
  /** Año de la última temporada; `null` si sigue en emisión. Nunca anterior a `releaseYear`. */
  endYear: number | null;
  /** `https://...` o una portada propia `/covers/<archivo>`. */
  imageUrl: string;
  /** Entre 1 y 20 géneros. */
  genreIds: number[];
}

/**
 * Cuerpo de `POST /api/series/{id}/episodes` y `PUT /api/series/{id}/episodes/{episodeId}`
 * (`EpisodeRequest`, solo administradores). La serie la indica la ruta, no el cuerpo.
 */
export interface EpisodeRequest {
  /** 1 a 100. */
  seasonNumber: number;
  /** 1 a 1000, único dentro de su temporada (si no, 409 `EPISODE_ALREADY_EXISTS`). */
  episodeNumber: number;
  title: string;
  /** Opcional: `null` (o en blanco) = sin sinopsis. */
  description: string | null;
  /** Minutos, 1 a 600. */
  duration: number;
  /** Solo `https://...`. */
  videoUrl: string;
}

/** Cuerpo de `POST /api/genres` y `PUT /api/genres/{id}` (`GenreRequest`, solo administradores). */
export interface GenreRequest {
  name: string;
}

/**
 * Cuerpo de cualquier respuesta de error de la API (`ErrorResponse`).
 *
 * El cliente decide por `status` y `code`, nunca por el texto de `message`,
 * que puede cambiar. `validationErrors` mapea nombre de campo → mensaje.
 */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  validationErrors?: Record<string, string>;
  /**
   * Intentos de login que quedan antes de que la cuenta se bloquee. Solo viene
   * en el 401 `INVALID_CREDENTIALS` de `POST /api/auth/login` (entero ≥ 1); en
   * el resto de errores no aparece.
   */
  remainingAttempts?: number;
}
