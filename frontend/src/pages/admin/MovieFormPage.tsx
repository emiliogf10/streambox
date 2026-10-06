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
import { TextAreaField } from '../../components/TextAreaField';
import { useToast } from '../../context/ToastContext';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { useGenres } from '../../hooks/useGenres';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../../lib/api';
import {
  EMPTY_MOVIE_FORM,
  MOVIE_DESCRIPTION_MAX,
  toMovieRequest,
  validateImageUrl,
  validateMovieForm,
  validateVideoUrl,
} from '../../lib/movieValidation';
import type { MovieFormErrors, MovieFormValues } from '../../lib/movieValidation';
import type { Movie } from '../../lib/types';
import { getReturnTo } from './adminPaths';
import { CoverPreview } from './CoverPreview';
import { focusFirstInvalidField, splitValidationErrors } from './formErrors';
import { GenreCheckboxes } from './GenreCheckboxes';

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
  focusFirstInvalidField(errors, FIELD_ORDER, FIELD_IDS);
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
        const { perField, unmatched } = splitValidationErrors(error.validationErrors, FIELD_IDS);
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
            id={FIELD_IDS.genreIds}
            itemNoun="cada película"
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

        <CoverPreview title={values.title} imageUrl={values.imageUrl} placeholderTitle="Nueva película" />

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
