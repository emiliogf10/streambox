import { useId, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, LogOut, ShieldCheck } from 'lucide-react';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { ErrorState } from '../components/ErrorState';
import { ExploreLink } from '../components/ExploreLink';
import { LoadingState } from '../components/LoadingState';
import { MovieCard } from '../components/MovieCard';
import { MovieDetailsModal } from '../components/MovieDetailsModal';
import { SeriesCard } from '../components/SeriesCard';
import { AccountCard } from '../components/profile/AccountCard';
import { ProfileCard } from '../components/profile/ProfileCard';
import { SessionsCard } from '../components/profile/SessionsCard';
import { useAuth } from '../context/AuthContext';
import { useFavorites } from '../context/FavoritesContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { avatarInitial, countGenres, formatMemberSince, pickListPreview, roleLabel } from '../lib/profile';
import type { GenreCount } from '../lib/profile';
import { pluralize } from '../lib/series';
import type { Movie, Series, User } from '../lib/types';

/** Cuántos géneros enseña la tarjeta «Tus géneros» (los más frecuentes; más serían una nube de etiquetas). */
const TOP_GENRES = 6;

/** Propiedades de {@link ProfileHeader}. */
interface ProfileHeaderProps {
  user: User;
  isAdmin: boolean;
  onLogout: () => void;
  /** `true` mientras el servidor confirma el cierre de sesión (ver `logout` en `AuthContext`). */
  isLoggingOut: boolean;
}

/**
 * Cabecera: avatar con la inicial, rol, nombre (el `<h1>`), «Miembro desde» y
 * las acciones.
 *
 * - El rol sale de `user.role` y se escribe en mayúsculas con CSS (`uppercase`),
 *   no en el texto: un lector de pantalla lee «Administrador», no deletrea siglas.
 *   Lleva icono en el administrador y texto siempre: el rol no se distingue solo
 *   por el color.
 * - El avatar es decorativo (`aria-hidden`): su inicial repite el nombre del `<h1>`.
 * - «Panel de administración» solo se pinta con `isAdmin` (confirmado por el
 *   servidor). Es interfaz, no seguridad: el backend responde 403 a un `USER`.
 * - «Editar perfil» no es un botón de la cabecera: cada dato se cambia en su
 *   fila de la tarjeta «Cuenta» ({@link AccountCard}), junto al valor que cambia.
 * - «Cerrar sesión» espera la confirmación del servidor: mientras tanto dice
 *   «Cerrando sesión...» con `aria-disabled` (no `disabled`, que sacaría el foco
 *   del botón y obligaría a buscarlo de nuevo si falla y hay que reintentar).
 */
function ProfileHeader({ user, isAdmin, onLogout, isLoggingOut }: ProfileHeaderProps) {
  const memberSince = formatMemberSince(user.createdAt);
  return (
    <div className="mb-8 flex flex-wrap items-center justify-between gap-x-6 gap-y-5">
      <div className="flex min-w-0 items-center gap-4 sm:gap-5">
        <div
          aria-hidden="true"
          className="flex size-16 shrink-0 items-center justify-center rounded-full border-2 border-accent/60 bg-accent/10 text-2xl font-bold text-accent sm:size-20 sm:text-3xl"
        >
          {avatarInitial(user.username)}
        </div>
        <div className="min-w-0">
          <p
            className={`mb-1 inline-flex items-center gap-1.5 text-xs font-semibold tracking-widest uppercase ${
              user.role === 'ADMIN' ? 'text-accent' : 'text-muted'
            }`}
          >
            {user.role === 'ADMIN' && <ShieldCheck aria-hidden="true" className="size-3.5" />}
            {roleLabel(user.role)}
          </p>
          <h1 className="text-3xl font-bold tracking-tight break-all sm:text-4xl">{user.username}</h1>
          {memberSince && <p className="mt-1 text-sm text-muted">Miembro desde {memberSince}</p>}
        </div>
      </div>

      <div className="flex flex-wrap gap-3">
        {isAdmin && (
          <Link to="/admin" className={buttonClasses('primary')}>
            <ShieldCheck aria-hidden="true" className="size-4" />
            Panel de administración
          </Link>
        )}
        <Button variant="outline" onClick={onLogout} aria-disabled={isLoggingOut || undefined}>
          <LogOut aria-hidden="true" className="size-4" />
          {isLoggingOut ? 'Cerrando sesión...' : 'Cerrar sesión'}
        </Button>
      </div>
    </div>
  );
}

/** Propiedades de {@link Stat}. */
interface StatProps {
  value: number;
  label: string;
}

/**
 * Una estadística: número grande y etiqueta. Es un grupo `dt`/`dd` de una lista
 * de descripción (la etiqueta nombra el dato); `flex-col-reverse` la pone DEBAJO
 * del número a la vista sin cambiar el orden de lectura (etiqueta y luego valor).
 */
function Stat({ value, label }: StatProps) {
  return (
    <div className="flex flex-col-reverse gap-1 rounded-xl border border-line bg-surface px-5 py-4">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="text-4xl font-bold tracking-tight text-white tabular-nums">{value}</dd>
    </div>
  );
}

/** Propiedades de {@link GenresCard}. */
interface GenresCardProps {
  /** Estado de carga de la lista de favoritos (de ella salen los géneros). */
  status: 'loading' | 'ready' | 'error';
  errorMessage: string;
  onRetry: () => void;
  /** Todos los géneros de la lista con su recuento, ya ordenados. */
  genres: GenreCount[];
  /** Títulos en la lista (películas + series). */
  totalTitles: number;
}

/**
 * Tarjeta «Tus géneros»: los géneros que más se repiten en «Mi lista», con
 * cuántos títulos tiene cada uno. Sustituye a unas «preferencias» que no
 * existen: aquí no se pregunta nada al usuario, se resume lo que ya guardó.
 *
 * Cuatro situaciones, y en las cuatro el texto es verdad para cualquier rol:
 * - Cargando / error: la lista de favoritos aún no está (el error es de la
 *   tarjeta, no de la página, y ofrece «Reintentar»).
 * - Lista vacía: lo dice y cómo llenarla; «Explorar películas» solo si hay
 *   películas que explorar (ver {@link ExploreLink}), para no llevar a otra página vacía.
 * - Títulos sin ningún género: caso raro (el alta de un título exige al menos
 *   uno, pero la tarjeta no debe romperse ni quedar en blanco si pasa).
 * - Con datos: etiquetas con nombre y recuento; si hay más de {@link TOP_GENRES},
 *   se avisa de cuántos se muestran.
 */
function GenresCard({ status, errorMessage, onRetry, genres, totalTitles }: GenresCardProps) {
  if (status === 'error') {
    return <ErrorState compact title="No se pudo cargar tu lista" message={errorMessage} onRetry={onRetry} />;
  }

  let body: ReactNode;
  if (status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando tu lista...
      </p>
    );
  } else if (totalTitles === 0) {
    body = (
      <>
        <p className="text-sm leading-relaxed text-muted">
          Tu lista está vacía. Cuando guardes películas o series con «Mi lista», aquí verás los géneros que más se
          repiten.
        </p>
        <div className="mt-4 empty:hidden">
          <ExploreLink catalog="/movies" to="/peliculas" className={buttonClasses('outline')}>
            Explorar películas
          </ExploreLink>
        </div>
      </>
    );
  } else if (genres.length === 0) {
    body = <p className="text-sm leading-relaxed text-muted">Los títulos de tu lista todavía no tienen géneros asignados.</p>;
  } else {
    const top = genres.slice(0, TOP_GENRES);
    body = (
      <>
        <ul role="list" className="flex flex-wrap gap-2">
          {top.map((genre) => (
            <li
              key={genre.id}
              className="inline-flex min-h-9 items-center gap-2 rounded-full border border-line bg-surface-raised px-3.5 text-sm text-white"
            >
              {genre.name}
              <span aria-hidden="true" className="text-muted tabular-nums">
                {genre.count}
              </span>
              <span className="sr-only">, {pluralize(genre.count, 'título', 'títulos')}</span>
            </li>
          ))}
        </ul>
        <p className="mt-4 text-sm text-muted">
          {genres.length > TOP_GENRES
            ? `Los ${TOP_GENRES} más frecuentes de ${genres.length} géneros en tu lista.`
            : 'El número indica cuántos títulos de tu lista tienen cada género.'}
        </p>
      </>
    );
  }

  return <ProfileCard title="Tus géneros">{body}</ProfileCard>;
}

/** Propiedades de {@link ListPreview}. */
interface ListPreviewProps {
  movies: Movie[];
  series: Series[];
  onOpenMovie: (movie: Movie) => void;
}

/**
 * Sección «De tu lista»: hasta cinco títulos guardados (primero películas, luego
 * series) y el enlace «Ver toda mi lista». Reutiliza las tarjetas de siempre:
 * una película abre su diálogo de detalles y una serie lleva a su página.
 *
 * Solo se pinta con la lista llena. Con la lista vacía la explicación y la salida
 * («Explorar películas») ya están en «Tus géneros»: repetirlas aquí serían dos
 * estados vacíos casi iguales uno encima de otro.
 */
function ListPreview({ movies, series, onOpenMovie }: ListPreviewProps) {
  const headingId = useId();
  const items = pickListPreview(movies, series);
  return (
    <section aria-labelledby={headingId} className="mt-10">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4">
        <h2 id={headingId} className="text-lg font-bold tracking-tight text-white">
          De tu lista
        </h2>
        <Link
          to="/favorites"
          className="focus-ring inline-flex min-h-11 items-center gap-1.5 rounded-md text-sm font-semibold text-accent hover:underline"
        >
          Ver toda mi lista
          <ArrowRight aria-hidden="true" className="size-4" />
        </Link>
      </div>
      <ul role="list" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {items.map((entry) => (
          <li key={`${entry.kind}:${entry.item.id}`}>
            {entry.kind === 'movie' ? (
              <MovieCard movie={entry.item} onClick={onOpenMovie} className="w-full" />
            ) : (
              <SeriesCard series={entry.item} className="w-full" />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Página «Mi perfil» (`/perfil`): quién eres y qué has guardado.
 *
 * **De dónde sale cada dato (no hay nada inventado).** El usuario viene de
 * `GET /api/users/me` (`AuthContext`) y todo lo demás de la lista compartida de
 * favoritos (`FavoritesContext`, que `AppShell` ya cargó): para enseñarlo no
 * hace falta ninguna petición propia. Las únicas son las de «Editar perfil»
 * (nombre y contraseña en {@link AccountCard}; cerrar sesión en todos los
 * dispositivos en {@link SessionsCard}). Por eso no hay «títulos vistos», «horas», suscripción ni
 * preferencias de idioma: no existen ni historial de reproducción ni planes.
 *
 * **Estados.** Dos fuentes independientes, cada una con los tres estados:
 * - El usuario (cabecera y «Cuenta»): cargando o error con «Reintentar»
 *   (`refreshUser`). El `<h1>` «Mi perfil» está siempre; con el usuario cargado
 *   pasa a ser su nombre.
 * - La lista (estadísticas, «Tus géneros», «De tu lista»): mientras carga o si
 *   falla, solo la tarjeta de géneros lo cuenta (con «Reintentar») y el resto de
 *   la página sigue siendo útil. Con la lista vacía, un único estado vacío en
 *   «Tus géneros» (ver {@link GenresCard}).
 *
 * **Estructura.** Un `<h1>`, secciones con `<h2>` y estadísticas como lista de
 * descripción (`dl`), que es lo que son: pares «etiqueta → valor».
 *
 * **Contraste (WCAG AA).** Solo pares ya calculados en `index.css` (blanco y
 * `muted` sobre `surface`/`surface-raised`/`canvas`, `accent` sobre `canvas`) más
 * uno propio: el acento de la inicial sobre `accent/10` encima de `canvas`, 7,4:1.
 */
export function ProfilePage() {
  useDocumentTitle('Mi perfil');
  const { user, userStatus, isAdmin, refreshUser, logout, isLoggingOut } = useAuth();
  const { movies, series, status, errorMessage, reload } = useFavorites();
  const summaryId = useId();
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const genres = useMemo(() => countGenres([...movies, ...series]), [movies, series]);
  const totalTitles = movies.length + series.length;

  let content: ReactNode;
  if (userStatus === 'error') {
    content = (
      <>
        <h1 className="mb-2 text-2xl font-bold tracking-tight sm:text-3xl">Mi perfil</h1>
        <ErrorState
          title="No se pudo cargar tu perfil"
          message="No se pudieron cargar los datos de tu cuenta. Comprueba tu conexión e inténtalo de nuevo."
          onRetry={refreshUser}
        />
      </>
    );
  } else if (!user) {
    content = (
      <>
        <h1 className="mb-2 text-2xl font-bold tracking-tight sm:text-3xl">Mi perfil</h1>
        <LoadingState label="Cargando tu perfil..." />
      </>
    );
  } else {
    content = (
      <>
        <ProfileHeader user={user} isAdmin={isAdmin} onLogout={() => void logout()} isLoggingOut={isLoggingOut} />

        {status === 'ready' && (
          <section aria-labelledby={summaryId} className="mb-4">
            <h2 id={summaryId} className="sr-only">
              Resumen de tu lista
            </h2>
            <dl className="grid gap-4 sm:grid-cols-3">
              <Stat value={movies.length} label={movies.length === 1 ? 'Película en mi lista' : 'Películas en mi lista'} />
              <Stat value={series.length} label={series.length === 1 ? 'Serie en mi lista' : 'Series en mi lista'} />
              <Stat value={genres.length} label={genres.length === 1 ? 'Género distinto' : 'Géneros distintos'} />
            </dl>
          </section>
        )}

        <div className="grid items-start gap-4 lg:grid-cols-2">
          <div className="flex flex-col gap-4">
            <AccountCard user={user} />
            <SessionsCard />
          </div>
          <GenresCard
            status={status}
            errorMessage={errorMessage}
            onRetry={reload}
            genres={genres}
            totalTitles={totalTitles}
          />
        </div>

        {status === 'ready' && totalTitles > 0 && (
          <ListPreview movies={movies} series={series} onOpenMovie={setSelectedMovie} />
        )}
      </>
    );
  }

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6">
      {content}
      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}
