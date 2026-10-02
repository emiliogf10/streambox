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
}
