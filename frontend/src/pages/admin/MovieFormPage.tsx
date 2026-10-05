import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, SearchX } from 'lucide-react';
import { Button } from '../../components/Button';
import { buttonClasses } from '../../components/buttonStyles';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { FormAlert } from '../../components/FormAlert';
import { FormField } from '../../components/FormField';
import { LoadingState } from '../../components/LoadingState';
import { MoviePoster } from '../../components/MoviePoster';
import { TextAreaField } from '../../components/TextAreaField';
import { useToast } from '../../context/ToastContext';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { useGenres } from '../../hooks/useGenres';
import type { GenresStatus } from '../../hooks/useGenres';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../../lib/api';
import {
  EMPTY_MOVIE_FORM,
  MOVIE_DESCRIPTION_MAX,
  getPreviewImageUrl,
  toMovieRequest,
  validateImageUrl,
  validateMovieForm,
  validateVideoUrl,
} from '../../lib/movieValidation';
import type { MovieFormErrors, MovieFormValues } from '../../lib/movieValidation';
import type { Genre, Movie } from '../../lib/types';
import { ADMIN_GENRES_PATH, getReturnTo } from './adminPaths';

/** Espera tras la última tecla antes de intentar cargar la portada en la vista previa. */
const PREVIEW_DEBOUNCE_MS = 400;

/** Orden de los campos en pantalla: el foco va al primero con error. */
const FIELD_ORDER: (keyof MovieFormValues)[] = [
  'title',
  'description',
  'duration',
  'releaseYear',
  'imageUrl',
  'videoUrl',
  'genreIds',
];

/** `id` de cada campo (etiquetas, mensajes y foco). Los géneros son un grupo: se enfoca su primer control. */
const FIELD_IDS: Record<keyof MovieFormValues, string> = {
  title: 'movie-title',
  description: 'movie-description',
  duration: 'movie-duration',
  releaseYear: 'movie-year',
  imageUrl: 'movie-image-url',
  videoUrl: 'movie-video-url',
  genreIds: 'movie-genres',
};

/** Pasa una película de la API a los valores del formulario (los números, como texto). */
function toFormValues(movie: Movie): MovieFormValues {
  return {
    title: movie.title,
    description: movie.description,
    duration: String(movie.duration),
    releaseYear: String(movie.releaseYear),
    imageUrl: movie.imageUrl,
    videoUrl: movie.videoUrl,
    genreIds: movie.genres.map((genre) => genre.id),
  };
}

/**
 * Errores de URL de una película recién cargada.
 *
 * Las películas antiguas pueden tener URL que hoy no se admiten (p. ej.
 * `http://`). Se marcan nada más abrir la edición, y no al pulsar «Guardar»,
 * para que el administrador vea desde el principio qué tiene que corregir.
 */
function legacyUrlErrors(values: MovieFormValues): MovieFormErrors {
  const errors: MovieFormErrors = {};
  const image = validateImageUrl(values.imageUrl.trim());
  const video = validateVideoUrl(values.videoUrl.trim());
  if (image) errors.imageUrl = image;
  if (video) errors.videoUrl = video;
  return errors;
}

/** Lleva el foco al primer campo con error (en el grupo de géneros, a su primer control). */
function focusFirstInvalid(errors: MovieFormErrors) {
  const first = FIELD_ORDER.find((field) => errors[field]);
  if (!first) return;
  const element = document.getElementById(FIELD_IDS[first]);
  if (first === 'genreIds') element?.querySelector<HTMLElement>('input, a, button')?.focus();
  else element?.focus();
}

/** Qué pantalla de película se pide: alta, o edición de un id (`null` si el de la URL no es un número válido). */
type EditorMode = { kind: 'create' } | { kind: 'edit'; id: number | null };

/**
 * Ruta del formulario de película (`/admin/peliculas/nueva` y
 * `/admin/peliculas/:id/editar`): alta y edición comparten componente.
 *
 * El formulario se monta con `key` distinta por película. Sin ella, pasar de
 * «Nueva película» a editar una (o de una película a otra) reutilizaría la
 * misma instancia —es el mismo componente en la misma posición— y conservaría
 * lo escrito y los errores de la pantalla anterior.
 */
export function MovieFormPage() {
  const { id } = useParams();
  const location = useLocation();
  const returnTo = getReturnTo(location.state);
  const parsedId = id !== undefined && /^\d+$/.test(id) ? Number(id) : null;
  const mode: EditorMode = id === undefined ? { kind: 'create' } : { kind: 'edit', id: parsedId };
  return <MovieEditor key={id ?? 'nueva'} mode={mode} returnTo={returnTo} />;
}

/** Resultado de cargar la película a editar, con el intento que lo pidió (ver `useGenres`). */
interface LoadResult {
  attempt: number;
  status: 'ready' | 'not-found' | 'error';
  errorMessage: string;
  originalTitle: string;
}

/**
 * Formulario de alta y edición de películas (`POST /api/movies`, `PUT /api/movies/{id}`).
 *
 * - **Edición**: carga `GET /api/movies/{id}`; con 404 (o un id que no es un
 *   número) explica que no existe y enlaza al listado; con otro error, «Reintentar».
 * - **Validación en el cliente** con las reglas del backend
 *   (`lib/movieValidation.ts`) y, además, los `validationErrors` del servidor
 *   junto a cada campo. El foco va al primer campo con error.
 * - **Envío**: el botón se bloquea mientras dura; al terminar, aviso y vuelta al
 *   listado (a la misma página y búsqueda de las que se vino).
 * - **Vista previa de la portada**: ver {@link CoverPreview}.
 * - **Géneros**: casillas cargadas de `GET /api/genres`. Si no hay ninguno, se
 *   explica que hay que crear uno antes y se enlaza a la pestaña Géneros.
 */
function MovieEditor({ mode, returnTo }: { mode: EditorMode; returnTo: string }) {
  const isEdit = mode.kind === 'edit';
  const editId = mode.kind === 'edit' ? mode.id : null;
  useDocumentTitle(isEdit ? 'Editar película · Administración' : 'Nueva película · Administración');
  const navigate = useNavigate();
  const toast = useToast();
  const genres = useGenres();

  const [values, setValues] = useState<MovieFormValues>(EMPTY_MOVIE_FORM);
  const [errors, setErrors] = useState<MovieFormErrors>({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [loadResult, setLoadResult] = useState<LoadResult | null>(null);

  useEffect(() => {
    if (!isEdit || editId === null) return;
    const controller = new AbortController();
    apiFetch<Movie>(`/movies/${editId}`, { signal: controller.signal })
      .then((movie) => {
        const loaded = toFormValues(movie);
        setValues(loaded);
        setErrors(legacyUrlErrors(loaded));
        setLoadResult({ attempt: loadAttempt, status: 'ready', errorMessage: '', originalTitle: movie.title });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        if (error instanceof ApiError && error.sessionExpired) return;
        const notFound = error instanceof ApiError && error.status === 404;
        setLoadResult({
          attempt: loadAttempt,
          status: notFound ? 'not-found' : 'error',
          errorMessage: getErrorMessage(error, 'No se pudo cargar la película.'),
          originalTitle: '',
        });
      });
    return () => controller.abort();
  }, [isEdit, editId, loadAttempt]);

  const current = loadResult?.attempt === loadAttempt ? loadResult : null;
  const loadStatus = !isEdit ? 'ready' : editId === null ? 'not-found' : (current?.status ?? 'loading');

  /** Actualiza un campo de texto y borra su error: ya lo está corrigiendo. */
  const setField = (field: Exclude<keyof MovieFormValues, 'genreIds'>, value: string) => {
    setValues((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  };

  const toggleGenre = (genreId: number) => {
    setValues((previous) => ({
      ...previous,
      genreIds: previous.genreIds.includes(genreId)
        ? previous.genreIds.filter((idInList) => idInList !== genreId)
        : [...previous.genreIds, genreId],
    }));
    setErrors((previous) => ({ ...previous, genreIds: undefined }));
  };

  /** Traduce un error del servidor a mensajes de campo y/o de formulario. */
  const showServerError = (error: unknown) => {
    if (error instanceof ApiError) {
      if (error.sessionExpired) return; // el aviso y la salida al login ya los gestiona AuthProvider
      if (error.code === 'VALIDATION_ERROR' && error.validationErrors) {
        const perField: MovieFormErrors = {};
        const unmatched: string[] = [];
        for (const [field, message] of Object.entries(error.validationErrors)) {
          if (Object.hasOwn(FIELD_IDS, field)) perField[field as keyof MovieFormValues] = message;
          else unmatched.push(message);
        }
        setErrors(perField);
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Revisa los campos marcados.');
        focusFirstInvalid(perField);
        return;
      }
      if (error.status === 404) {
        // La película (en edición) o algún género elegido ya no existe; el mensaje del servidor dice cuál.
        // Se recargan los géneros por si el que falta era uno de ellos.
        setFormError(error.message);
        genres.reload();
        return;
      }
    }
    setFormError(getErrorMessage(error, 'No se pudo guardar la película.'));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError('');
    const found = validateMovieForm(values);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      focusFirstInvalid(found);
      return;
    }

    const body = toMovieRequest(values);
    setSubmitting(true);
    try {
      if (isEdit) {
        await apiFetch<Movie>(`/movies/${editId}`, { method: 'PUT', body });
        toast.success(`Se han guardado los cambios de «${body.title}».`);
      } else {
        await apiFetch<Movie>('/movies', { method: 'POST', body });
        toast.success(`«${body.title}» se ha añadido al catálogo.`);
      }
      navigate(returnTo);
    } catch (error) {
      showServerError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const heading = !isEdit
    ? 'Nueva película'
    : current?.status === 'ready'
      ? `Editar «${current.originalTitle}»`
      : 'Editar película';

  let content: ReactNode;
  if (loadStatus === 'loading') {
    content = <LoadingState label="Cargando la película..." />;
  } else if (loadStatus === 'not-found') {
    content = (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title="No se encontró la película"
        description="Puede que la haya borrado otra persona o que el enlace no sea correcto."
        action={
          <Link to={returnTo} className={buttonClasses('outline')}>
            Volver al listado
          </Link>
        }
      />
    );
  } else if (loadStatus === 'error') {
    content = (
      <ErrorState
        title="No se pudo cargar la película"
        message={current?.errorMessage ?? ''}
        onRetry={() => setLoadAttempt((n) => n + 1)}
      />
    );
  } else {
    content = (
      <form
        onSubmit={handleSubmit}
        noValidate
        className="mt-6 grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(0,1fr)_15rem]"
      >
        <div className="flex min-w-0 flex-col gap-5">
          <FormField
            id={FIELD_IDS.title}
            label="Título"
            autoComplete="off"
            error={errors.title}
            value={values.title}
            onChange={(event) => setField('title', event.target.value)}
          />
          <TextAreaField
            id={FIELD_IDS.description}
            label="Sinopsis"
            rows={6}
            maxChars={MOVIE_DESCRIPTION_MAX}
            error={errors.description}
            value={values.description}
            onChange={(event) => setField('description', event.target.value)}
          />
          <div className="grid gap-5 sm:grid-cols-2">
            {/* Texto con teclado numérico y no `type="number"`: este último cambia el valor con la rueda del
                ratón sin querer, admite "1e3" y vacía el campo si lo escrito no es un número. */}
            <FormField
              id={FIELD_IDS.duration}
              label="Duración (minutos)"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              placeholder="Ej.: 120"
              error={errors.duration}
              value={values.duration}
              onChange={(event) => setField('duration', event.target.value)}
            />
            <FormField
              id={FIELD_IDS.releaseYear}
              label="Año de estreno"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              placeholder="Ej.: 2014"
              error={errors.releaseYear}
              value={values.releaseYear}
              onChange={(event) => setField('releaseYear', event.target.value)}
            />
          </div>
          <FormField
            id={FIELD_IDS.imageUrl}
            label="URL de la portada"
            type="url"
            autoComplete="off"
            spellCheck={false}
            placeholder="https://… o /covers/archivo.webp"
            hint="Una dirección https:// o una portada propia de la carpeta /covers (p. ej. /covers/interstellar.webp)."
            error={errors.imageUrl}
            value={values.imageUrl}
            onChange={(event) => setField('imageUrl', event.target.value)}
          />
          <FormField
            id={FIELD_IDS.videoUrl}
            label="URL del vídeo"
            type="url"
            autoComplete="off"
            spellCheck={false}
            placeholder="https://…"
            hint="Debe empezar por https://."
            error={errors.videoUrl}
            value={values.videoUrl}
            onChange={(event) => setField('videoUrl', event.target.value)}
          />
          <GenreCheckboxes
            genres={genres.genres}
            status={genres.status}
            loaded={genres.loaded}
            errorMessage={genres.errorMessage}
            onRetry={genres.reload}
            selected={values.genreIds}
            onToggle={toggleGenre}
            error={errors.genreIds}
          />
        </div>

        <CoverPreview title={values.title} imageUrl={values.imageUrl} />

        <div className="lg:col-start-1">
          {/* Junto a los botones (y no arriba del todo): es donde está quien acaba de pulsar «Guardar». */}
          <FormAlert message={formError} />
          <div className="flex flex-col-reverse gap-3 border-t border-line pt-6 sm:flex-row sm:justify-end">
            <Link to={returnTo} className={buttonClasses('outline')}>
              Cancelar
            </Link>
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Guardando...' : isEdit ? 'Guardar cambios' : 'Crear película'}
            </Button>
          </div>
        </div>
      </form>
    );
  }

  return (
    <section aria-labelledby="movie-form-title">
      <Link
        to={returnTo}
        className="focus-ring -ml-1 mb-2 inline-flex min-h-11 items-center gap-1.5 rounded-md px-1 text-sm text-muted transition-colors hover:text-white"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        Volver al listado
      </Link>
      <h2 id="movie-form-title" className="text-xl font-semibold tracking-tight wrap-anywhere">
        {heading}
      </h2>
      {content}
    </section>
  );
}

/** Propiedades de {@link GenreCheckboxes}. */
interface GenreCheckboxesProps {
  genres: Genre[];
  status: GenresStatus;
  loaded: boolean;
  errorMessage: string;
  onRetry: () => void;
  selected: number[];
  onToggle: (genreId: number) => void;
  error?: string;
}

/**
 * Grupo de casillas de géneros.
 *
 * `fieldset` + `legend` agrupan las casillas bajo un nombre («Géneros») que el
 * lector de pantalla anuncia al entrar en el grupo; el requisito o el error van
 * enlazados con `aria-describedby`. Cada casilla está dentro de su `<label>`,
 * que ocupa toda la fila (objetivo táctil de 44 px) y se marca con borde y
 * fondo de acento cuando está elegida, además de la propia marca de la casilla.
 */
function GenreCheckboxes({ genres, status, loaded, errorMessage, onRetry, selected, onToggle, error }: GenreCheckboxesProps) {
  const hintId = `${FIELD_IDS.genreIds}-hint`;
  const errorId = `${FIELD_IDS.genreIds}-error`;

  let body: ReactNode;
  if (!loaded && status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando géneros...
      </p>
    );
  } else if (!loaded && status === 'error') {
    body = (
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-red-500/30 bg-red-950/30 p-3">
        <p className="text-sm text-red-300">{errorMessage}</p>
        <Button variant="outline" onClick={onRetry}>
          Reintentar
        </Button>
      </div>
    );
  } else if (genres.length === 0) {
    body = (
      <p className="rounded-lg border border-dashed border-line p-4 text-sm leading-relaxed text-muted">
        Todavía no hay géneros y cada película necesita al menos uno.{' '}
        <Link to={ADMIN_GENRES_PATH} className="focus-ring rounded-sm font-semibold text-accent underline-offset-2 hover:underline">
          Crea uno en la pestaña Géneros
        </Link>{' '}
        y vuelve después a este formulario.
      </p>
    );
  } else {
    body = (
      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {genres.map((genre) => (
          <label
            key={genre.id}
            className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-line bg-surface px-3 py-2 text-sm text-white transition-colors hover:border-muted has-checked:border-accent/60 has-checked:bg-accent/10"
          >
            <input
              type="checkbox"
              className="focus-ring size-4 shrink-0 accent-accent"
              checked={selected.includes(genre.id)}
              onChange={() => onToggle(genre.id)}
            />
            <span className="min-w-0 wrap-anywhere">{genre.name}</span>
          </label>
        ))}
      </div>
    );
  }

  return (
    <fieldset
      id={FIELD_IDS.genreIds}
      aria-describedby={error ? errorId : hintId}
      aria-invalid={error ? true : undefined}
      className="min-w-0"
    >
      <legend className="mb-1.5 text-sm font-medium text-gray-300">Géneros</legend>
      {body}
      {error ? (
        <p id={errorId} role="alert" className="mt-1.5 text-xs font-medium text-danger">
          {error}
        </p>
      ) : (
        <p id={hintId} className="mt-1.5 text-xs text-muted">
          Elige al menos uno.
        </p>
      )}
    </fieldset>
  );
}

/**
 * Vista previa de la portada, junto al formulario (debajo de los campos en móvil).
 *
 * - Se actualiza al escribir la URL, con una espera de 400 ms ({@link useDebouncedValue}):
 *   no se intenta descargar cada versión a medias de la dirección.
 * - Solo se carga una URL que cumple las reglas (`getPreviewImageUrl`): una
 *   `http://` o `javascript:` no llega nunca a una `<img>`. Mientras no sea
 *   válida se ve el respaldo de {@link MoviePoster}, el mismo que verían los
 *   usuarios si la imagen fallara.
 * - Usa `MoviePoster` con una película "borrador" (título + `imageUrl`), así que
 *   lo que se ve aquí es exactamente cómo se pintará en el catálogo.
 */
function CoverPreview({ title, imageUrl }: { title: string; imageUrl: string }) {
  const debouncedUrl = useDebouncedValue(imageUrl, PREVIEW_DEBOUNCE_MS);
  const draft = { title: title.trim() || 'Nueva película', imageUrl: getPreviewImageUrl(debouncedUrl) };

  const message = !debouncedUrl.trim()
    ? 'Escribe la URL de la portada para verla aquí.'
    : draft.imageUrl
      ? 'Así se verá en el catálogo. Si la imagen no carga, se mostrará este respaldo con el título.'
      : 'La URL aún no es válida, así que no se carga: se muestra el respaldo con el título.';

  return (
    <aside
      aria-labelledby="movie-preview-title"
      className="flex items-start gap-4 self-start rounded-xl border border-line bg-surface p-4 lg:sticky lg:top-20 lg:col-start-2 lg:row-span-2 lg:row-start-1 lg:flex-col"
    >
      <MoviePoster
        title={draft.title}
        src={draft.imageUrl}
        alt="Vista previa de la portada"
        className="aspect-2/3 w-28 shrink-0 rounded-lg lg:w-full"
      />
      <div className="min-w-0">
        <h3 id="movie-preview-title" className="text-sm font-semibold text-white">
          Vista previa de la portada
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-muted">{message}</p>
      </div>
    </aside>
  );
}
