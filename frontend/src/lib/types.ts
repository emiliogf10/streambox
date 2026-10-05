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

/** Película tal como la devuelve la API (`MovieResponse`). */
export interface Movie {
  id: number;
  title: string;
  description: string;
  /** Duración en minutos. */
  duration: number;
  releaseYear: number;
  imageUrl: string;
  videoUrl: string;
  /** Instante de alta en formato ISO-8601 (UTC). */
  createdAt: string;
  /** Géneros de la película, ya ordenados por nombre por el servidor. */
  genres: Genre[];
}

/** Página de resultados con los metadatos de paginación (`MoviePageResponse`). */
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

/** Respuesta de `POST /api/auth/login`. */
export interface LoginResponse {
  token: string;
}

/** Cuerpo de `POST /api/users` (registro público; el servidor siempre crea rol `USER`). */
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
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
