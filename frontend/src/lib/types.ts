/**
 * Tipos que describen los datos que intercambia el frontend con la API.
 *
 * Reflejan los DTO del backend (`MovieResponse`, `UserResponse`, `ErrorResponse`...).
 * Si cambia un DTO, se cambia aquí y el compilador señala todo lo que se rompe.
 */
import type { AvatarKey } from './avatars';

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
  /**
   * Imagen HORIZONTAL opcional (16:9 recomendado) para el banner del inicio y
   * de la página del título: `https://...`, `/covers/<archivo>` o `null`. Sin
   * ella, el banner usa el póster desenfocado de siempre. Las tarjetas, «Seguir
   * viendo» y el historial siguen con el póster vertical ({@link CatalogItem.imageUrl}).
   */
  backdropUrl: string | null;
  /** Instante de alta en formato ISO-8601 (UTC). */
  createdAt: string;
  /** Géneros, ya ordenados por nombre por el servidor. */
  genres: Genre[];
}

/** Película tal como la devuelve la API (`MovieResponse`). */
export interface Movie extends CatalogItem {
  /** Duración en minutos. */
  duration: number;
  /**
   * Vídeo de la película; `null` si StreamBox no lo tiene (es opcional: sin él
   * no hay «Ver ahora»). Sin validar: antes de enlazarlo, `getSafeVideoUrl`.
   */
  videoUrl: string | null;
  /**
   * Tráiler de YouTube, siempre normalizado por el servidor
   * (`https://www.youtube.com/watch?v=<id>`), o `null`. Para mostrarlo, `getTrailer`.
   */
  trailerUrl: string | null;
  /**
   * Subtítulos en español (archivo WebVTT, `https://…/archivo.vtt`) o `null`.
   * Solo los usa el reproductor integrado; sin validar: antes, `getSubtitlesUrl`.
   */
  subtitlesUrl: string | null;
}

/**
 * Episodio de una serie (`EpisodeResponse`). Su imagen es opcional: sin ella,
 * la lista de episodios y el reproductor usan las de la serie.
 */
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
  /**
   * Vídeo del episodio; `null` si no lo hay (es opcional: sin él no hay «Ver»).
   * Sin validar: antes de enlazarla hay que pasarla por `getSafeVideoUrl`.
   */
  videoUrl: string | null;
  /** Subtítulos del episodio (WebVTT) o `null`; como en {@link Movie.subtitlesUrl}. */
  subtitlesUrl: string | null;
  /**
   * Imagen propia del episodio (un fotograma, 16:9 recomendado): `https://...`,
   * `/covers/<archivo>` o `null`. Miniatura de la lista de episodios y
   * `poster` del reproductor; sin ella, se usan las imágenes de la serie.
   */
  imageUrl: string | null;
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
  /** Tráiler de YouTube de la serie (normalizado por el servidor) o `null`. Para mostrarlo, `getTrailer`. */
  trailerUrl: string | null;
}

/** Detalle de una serie (`SeriesDetailResponse`): la serie con sus temporadas, ordenadas por número. */
export interface SeriesDetail extends Series {
  seasons: Season[];
}

/**
 * En qué se basan las recomendaciones (`RecommendationsResponse.basis`):
 * - `NONE`: en nada (sin géneros favoritos ni «Me gusta»); las listas llegan vacías.
 * - `GENRES`: solo en los géneros favoritos («Tus gustos»).
 * - `LIKES`: solo en los títulos que le gustan.
 * - `GENRES_AND_LIKES`: en las dos cosas (un género favorito pesa como dos «Me gusta»).
 *
 * Existe para que la portada diga la verdad sobre el porqué de cada fila.
 */
export type RecommendationBasis = 'NONE' | 'GENRES' | 'LIKES' | 'GENRES_AND_LIKES';

/**
 * Recomendaciones del usuario de la sesión (`GET /api/users/me/recommendations`).
 *
 * Las calcula el servidor a partir de sus géneros favoritos y de los géneros de
 * lo que le gusta («Me gusta»), sin incluir lo que ya le gusta. Cada lista
 * llega ya ordenada (la más afín primero) y por separado: el servidor no da una
 * puntuación común con la que mezclarlas sin inventar un orden.
 */
export interface Recommendations {
  /**
   * En qué se basan (ver {@link RecommendationBasis}). Con `NONE` las dos listas
   * llegan vacías y la portada invita a elegir géneros favoritos.
   */
  basis: RecommendationBasis;
  movies: Movie[];
  /** Solo series con episodios (las demás no las ve un usuario). */
  series: Series[];
}

/**
 * Cuerpo de `PUT /api/users/me/genres` (guardar los géneros favoritos). Sustituye
 * la lista entera: vacía (= «Ahora no» o quitar los gustos) o de 3 a 5 ids
 * distintos de géneros con títulos visibles. Responde 200 con la lista guardada
 * (`Genre[]`, por nombre) y, sea cual sea, cierra para siempre la pregunta de
 * la bienvenida.
 */
export interface FavoriteGenresRequest {
  genreIds: number[];
}

/**
 * Progreso de visionado de una película o de un episodio
 * (`GET /api/users/me/progress/movies/{id}` y `/episodes/{id}`; 204 = sin progreso).
 */
export interface WatchProgress {
  /** Segundo por el que va el usuario (entero). */
  positionSeconds: number;
  /** Duración del vídeo que se reprodujo, en segundos (entero, 1 a 86 400). */
  durationSeconds: number;
  /**
   * `true` si la posición ACTUAL llega casi al final (≥ 95 %, regla del servidor):
   * no se reanuda, no sale en «Seguir viendo» y el botón dice «Volver a ver».
   * Se vuelve `false` al empezar a verlo otra vez (la posición baja).
   */
  finished: boolean;
  /**
   * `true` si lo ha terminado ALGUNA VEZ: no se pierde al volver a verlo (solo
   * al quitarlo de «Seguir viendo», que borra el progreso). Decide la marca
   * «Visto»; nunca desde dónde se empieza (eso es {@link WatchProgress.finished}).
   */
  watched: boolean;
  /** Instante del último guardado (ISO-8601, UTC). */
  updatedAt: string;
}

/** Progreso de un episodio dentro del de su serie (`GET /api/users/me/progress/series/{id}`). */
export interface EpisodeProgress extends WatchProgress {
  episodeId: number;
}

/**
 * Cuerpo de `PUT /api/users/me/progress/movies/{id}` y `/episodes/{id}` (204).
 * Enteros; `positionSeconds` entre 0 y `durationSeconds`. Si el título ya no
 * tiene vídeo, 409 `NO_VIDEO_AVAILABLE`.
 */
export interface WatchProgressRequest {
  positionSeconds: number;
  durationSeconds: number;
}

/**
 * Un elemento de «Seguir viendo» (`GET /api/users/me/continue-watching`), lo más
 * reciente primero y sin lo que ya no tiene vídeo:
 *
 * - **Película:** la que está a medias (lo terminado no sale).
 * - **Serie:** una sola tarjeta, con el mismo criterio que el botón de su página
 *   (`seriesWatchTarget`, decisión QA-4): el **último episodio que tocó** el
 *   usuario si está a medias; si lo terminó, **el siguiente con vídeo**
 *   (`upNext`, puede ser de otra temporada); si no hay siguiente, la serie no sale.
 */
export interface ContinueWatchingItem {
  type: 'MOVIE' | 'EPISODE';
  /** Id de la película o del EPISODIO (no de la serie: esa va en `seriesId`). */
  id: number;
  /** Título de la película o del episodio. */
  title: string;
  /** Portada de la película o, en un episodio, la de su serie. */
  imageUrl: string;
  seasonNumber: number | null;
  episodeNumber: number | null;
  seriesId: number | null;
  seriesTitle: string | null;
  /**
   * `true` si el usuario TERMINÓ el último episodio que tocó y esta tarjeta es el
   * siguiente; `false` en las películas y en el episodio a medias de siempre.
   */
  upNext: boolean;
  /**
   * Por dónde va en ESTE título. `null` solo con `upNext` y si ese siguiente
   * episodio no tiene progreso propio sin terminar (aún no lo ha empezado); si
   * lo tiene, viene con él, como cualquier otro elemento.
   */
  positionSeconds: number | null;
  /** Duración del vídeo de ese progreso; `null` en los mismos casos que `positionSeconds`. */
  durationSeconds: number | null;
  /** Último guardado del título (en una serie, el del último episodio tocado): ordena la fila. */
  updatedAt: string;
}

/**
 * Una entrada del historial de visionado (`GET /api/users/me/watch-history`),
 * de la más reciente a la más antigua.
 *
 * - **Película:** una entrada por película, terminada o no.
 * - **Serie:** una sola entrada por serie, la de su **último episodio tocado**
 *   (`id`/`title` son los del episodio; `seriesId`, `seriesTitle` e `imageUrl`,
 *   los de la serie).
 *
 * A diferencia de «Seguir viendo», es historial: salen también lo terminado y
 * los títulos que ya no tienen vídeo.
 */
export interface WatchHistoryItem {
  type: 'MOVIE' | 'EPISODE';
  /** Id de la película o del EPISODIO (no de la serie: esa va en `seriesId`). */
  id: number;
  /** Título de la película o del episodio. */
  title: string;
  /** Portada de la película o, en un episodio, la de su serie. */
  imageUrl: string;
  seasonNumber: number | null;
  episodeNumber: number | null;
  seriesId: number | null;
  seriesTitle: string | null;
  /** Hasta dónde llegó (segundos enteros). */
  positionSeconds: number;
  /** Duración del vídeo de ese progreso (segundos enteros). */
  durationSeconds: number;
  /** `true` si la posición actual llega casi al final (≥ 95 %); ver {@link WatchProgress.finished}. */
  finished: boolean;
  /** `true` si lo ha terminado alguna vez, aunque lo esté viendo otra vez; ver {@link WatchProgress.watched}. */
  watched: boolean;
  /** Último guardado (ISO-8601, UTC): ordena el historial. */
  updatedAt: string;
}

/**
 * Cifras de lo visto (`GET /api/users/me/watch-stats`).
 *
 * Cuenta lo terminado ALGUNA VEZ (`watched`), no la posición actual: volver a
 * ver algo no lo resta. `watchedSeconds` es una **estimación**: por cada título
 * suma la duración si lo terminó alguna vez y la posición a la que llegó si no. Como solo se guarda el último
 * punto de cada título, no cuenta las veces que se vuelve a ver ni lo que se
 * rebobina: la interfaz lo presenta como aproximado.
 */
export interface WatchStats {
  /** Películas terminadas alguna vez (≥ 95 %). */
  finishedMovies: number;
  /** Episodios terminados alguna vez (≥ 95 %). */
  finishedEpisodes: number;
  /** Tiempo visto aproximado, en segundos. */
  watchedSeconds: number;
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
  /**
   * `true` si NINGÚN título contiene el texto buscado y lo que llega son títulos
   * **parecidos** (tolerancia a erratas: «interstelar» → «Interstellar»). Solo
   * puede ser `true` en las búsquedas por título (`/movies/search`,
   * `/series/search`, `/admin/series`); entonces el servidor ordena por
   * parecido e **ignora `sort`/`direction`**. En los listados sin texto, siempre `false`.
   */
  approximate: boolean;
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
  /**
   * Clave del icono de perfil (`"cat"`, `"rocket"`... ver `lib/avatars.ts`) o
   * `null` si no eligió ninguno: entonces se enseña la inicial del nombre. Se
   * trata como texto libre y no como `AvatarKey`: una clave que esta versión de
   * la interfaz no conozca también se pinta con la inicial, sin romper nada.
   */
  avatar: string | null;
  /**
   * `true` si a este usuario aún hay que hacerle la pregunta de la bienvenida
   * («¿qué géneros te gustan?», `/bienvenida`): solo un `USER` que todavía no la
   * ha contestado ni saltado; un `ADMIN`, nunca. Solo llega en `GET /api/users/me`
   * (por eso es opcional: la respuesta de `PATCH /api/users/me` puede no
   * traerlo). Ausente se trata como `false`: preguntar sin estar seguros sería peor.
   */
  genresOnboardingPending?: boolean;
}

/** Cuerpo de `POST /api/users` (registro público; el servidor siempre crea rol `USER`). */
export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

/**
 * Respuesta 202 de las operaciones que, a propósito, contestan SIEMPRE lo mismo
 * para no revelar qué correos tienen cuenta: el registro (`POST /api/users`) y
 * el reenvío del correo de confirmación (`POST /api/auth/email-verification/resend`).
 * La interfaz no enseña este texto: pone el suyo, pensado para cada pantalla.
 */
export interface AcceptedResponse {
  message: string;
}

/** Cuerpo de `POST /api/auth/email-verification` (confirmar el correo; 204 si el token vale). */
export interface EmailVerificationRequest {
  token: string;
}

/** Cuerpo de `POST /api/auth/email-verification/resend` (pedir un enlace nuevo; 202 siempre igual). */
export interface ResendVerificationRequest {
  email: string;
}

/**
 * Cuerpo de `POST /api/auth/password-reset` («He olvidado mi contraseña»):
 * pide el correo con el enlace para elegir una contraseña nueva. Responde 202
 * con el mismo {@link AcceptedResponse} exista o no la cuenta.
 */
export interface PasswordResetRequest {
  email: string;
}

/**
 * Cuerpo de `POST /api/auth/password-reset/confirm`: el token del enlace y la
 * contraseña nueva (204 si se cambia; 400 `INVALID_RESET_TOKEN` si el enlace no
 * vale; 400 `VALIDATION_ERROR` con `validationErrors.newPassword` si la
 * contraseña no cumple la política, y entonces el token NO se gasta).
 */
export interface PasswordResetConfirmRequest {
  token: string;
  newPassword: string;
}

/**
 * Cuerpo de `PATCH /api/users/me` (editar mi perfil). Cada campo es opcional:
 * ausente = no cambia. `avatar: null` vuelve a la inicial. Enviar `email`,
 * `role` o `password` da 400.
 */
export interface UpdateProfileRequest {
  username?: string;
  avatar?: AvatarKey | null;
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
  /**
   * Opcional: imagen horizontal del banner (`https://...` o `/covers/<archivo>`),
   * o `null` = sin ella (en un `PUT`, omitirlo también la borra).
   */
  backdropUrl: string | null;
  /** Opcional: solo `https://...`, o `null` = sin vídeo. */
  videoUrl: string | null;
  /**
   * Opcional: enlace de YouTube (el servidor lo guarda normalizado), o `null` =
   * sin tráiler. Se valida con `validateTrailerUrl` (`lib/youtube.ts`).
   */
  trailerUrl: string | null;
  /**
   * Opcional: subtítulos WebVTT (`https://…/archivo.vtt`), o `null` = sin
   * subtítulos (en un `PUT`, omitirlo también los borra). Exigen vídeo.
   */
  subtitlesUrl: string | null;
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
  /** Opcional: imagen horizontal del banner, o `null` = sin ella; como en {@link MovieRequest.backdropUrl}. */
  backdropUrl: string | null;
  /** Opcional: enlace de YouTube (el servidor lo guarda normalizado), o `null` = sin tráiler. */
  trailerUrl: string | null;
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
  /** Opcional: solo `https://...`, o `null` = sin vídeo. */
  videoUrl: string | null;
  /** Opcional: subtítulos WebVTT (`https://…/archivo.vtt`) o `null`; exigen vídeo. */
  subtitlesUrl: string | null;
  /**
   * Opcional: imagen propia del episodio (`https://...` o `/covers/<archivo>`),
   * o `null` = usa las de la serie (en un `PUT`, omitirla también la borra).
   */
  imageUrl: string | null;
}

/**
 * Género con su uso, tal como lo da `GET /api/admin/genres` (`AdminGenreResponse`,
 * solo administradores). `movieCount` y `seriesCount` cuentan TODAS las películas
 * y series que lo usan (también las series sin episodios, que el público no ve):
 * son las mismas cifras con las que el servidor responde 409 `GENRE_IN_USE`, así
 * que un género solo se puede borrar si las dos son 0.
 */
export interface AdminGenre extends Genre {
  movieCount: number;
  seriesCount: number;
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
