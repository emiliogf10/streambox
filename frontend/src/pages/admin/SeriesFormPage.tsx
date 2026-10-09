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
import { useInvalidateCatalog } from '../../hooks/useInvalidateCatalog';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../../lib/api';
import {
  EMPTY_SERIES_FORM,
  SERIES_DESCRIPTION_MAX,
  toSeriesRequest,
  validateSeriesForm,
  validateSeriesImageUrl,
} from '../../lib/seriesValidation';
import type { SeriesFormErrors, SeriesFormValues } from '../../lib/seriesValidation';
import type { SeriesDetail } from '../../lib/types';
import { ADMIN_SERIES_PATH, adminEditSeriesPath, getReturnTo } from './adminPaths';
import type { ReturnToState } from './adminPaths';
import { CoverPreview } from './CoverPreview';
import { focusFirstInvalidField, splitValidationErrors } from './formErrors';
import { GenreCheckboxes } from './GenreCheckboxes';
import { SeriesEpisodesSection } from './SeriesEpisodesSection';

/** Orden de los campos en pantalla: el foco va al primero con error. */
const FIELD_ORDER: (keyof SeriesFormValues)[] = ['title', 'description', 'releaseYear', 'endYear', 'imageUrl', 'genreIds'];

/** `id` de cada campo (etiquetas, mensajes y foco). Los géneros son un grupo: se enfoca su primer control. */
const FIELD_IDS: Record<keyof SeriesFormValues, string> = {
  title: 'series-title',
  description: 'series-description',
  releaseYear: 'series-release-year',
  endYear: 'series-end-year',
  imageUrl: 'series-image-url',
  genreIds: 'series-genres',
};

/** Aviso tras crear una serie: nace vacía y, sin episodios, nadie más la ve. */
const SERIES_CREATED_MESSAGE = 'Serie creada. Añade episodios para que sea visible.';

/** Pasa una serie de la API a los valores del formulario (los años, como texto; sin año de fin, vacío). */
function toFormValues(series: SeriesDetail): SeriesFormValues {
  return {
    title: series.title,
    description: series.description,
    releaseYear: String(series.releaseYear),
    endYear: series.endYear === null ? '' : String(series.endYear),
    imageUrl: series.imageUrl,
    genreIds: series.genres.map((genre) => genre.id),
  };
}

/**
 * Error de portada de una serie recién cargada. Por coherencia con películas
 * (donde hay datos antiguos con `http://`): si la URL guardada ya no cumple las
 * reglas, se marca al abrir la edición y no al pulsar «Guardar».
 */
function storedUrlErrors(values: SeriesFormValues): SeriesFormErrors {
  const image = validateSeriesImageUrl(values.imageUrl.trim());
  return image ? { imageUrl: image } : {};
}

/** Lleva el foco al primer campo con error (en el grupo de géneros, a su primer control). */
function focusFirstInvalid(errors: SeriesFormErrors) {
  focusFirstInvalidField(errors, FIELD_ORDER, FIELD_IDS);
}

/** Qué pantalla de serie se pide: alta, o edición de un id (`null` si el de la URL no es un número válido). */
type EditorMode = { kind: 'create' } | { kind: 'edit'; id: number | null };

/**
 * Ruta del formulario de serie (`/admin/series/nueva` y
 * `/admin/series/:id/editar`): alta y edición comparten componente.
 *
 * Como en películas, el editor se monta con `key` distinta por serie: al pasar
 * del alta a la edición de la serie recién creada (o de una serie a otra) se
 * empieza de cero, sin arrastrar lo escrito ni los errores de la pantalla anterior.
 */
export function SeriesFormPage() {
  const { id } = useParams();
  const location = useLocation();
  const returnTo = getReturnTo(location.state, ADMIN_SERIES_PATH);
  const parsedId = id !== undefined && /^\d+$/.test(id) ? Number(id) : null;
  const mode: EditorMode = id === undefined ? { kind: 'create' } : { kind: 'edit', id: parsedId };
  return <SeriesEditor key={id ?? 'nueva'} mode={mode} returnTo={returnTo} />;
}

/** Resultado de cargar la serie a editar, con el intento que lo pidió (ver `useGenres`). */
interface LoadResult {
  attempt: number;
  status: 'ready' | 'not-found' | 'error';
  errorMessage: string;
}

/**
 * Formulario de alta y edición de series (`POST /api/series`, `PUT /api/series/{id}`).
 *
 * Sigue las mismas reglas que el de películas (validación con los mensajes
 * exactos del servidor en `lib/seriesValidation.ts`, `validationErrors` junto
 * a cada campo, foco al primer error, vista previa de la portada, géneros en
 * casillas) con estas diferencias, propias de una serie:
 *
 * - **Año de fin opcional**: vacío = sigue en emisión (`endYear: null`).
 * - **Al crear se aterriza en la edición** de la serie nueva (y no en el
 *   listado), con el aviso «Añade episodios para que sea visible»: una serie
 *   recién creada no tiene episodios y los usuarios no la ven, así que el
 *   siguiente paso natural es completarla. La navegación usa `replace` para que
 *   «Atrás» no vuelva a un formulario de alta ya enviado.
 * - **Al guardar cambios se queda en la página** (con aviso): la edición de una
 *   serie es también donde se gestionan sus episodios, y volver al listado
 *   obligaría a entrar otra vez para seguir. «Volver al listado» y «Cancelar»
 *   siguen a mano.
 * - **No se puede enviar hasta que cargan los géneros**: sin ellos no se ve qué
 *   géneros tiene la serie ni se puede corregir la elección. El botón queda
 *   desactivado con una explicación visible.
 * - Debajo del formulario, en la edición, la sección {@link SeriesEpisodesSection}
 *   (lista y gestión de episodios), que actualiza `saved` tras cada cambio.
 *
 * La edición carga `GET /api/admin/series/{id}` (vista de gestión, que también
 * devuelve las series sin episodios); con 404 (o un id que no es un número)
 * explica que no existe y enlaza al listado; con otro error, «Reintentar».
 */
function SeriesEditor({ mode, returnTo }: { mode: EditorMode; returnTo: string }) {
  const isEdit = mode.kind === 'edit';
  const editId = mode.kind === 'edit' ? mode.id : null;
  useDocumentTitle(isEdit ? 'Editar serie · Administración' : 'Nueva serie · Administración');
  const navigate = useNavigate();
  const toast = useToast();
  const genres = useGenres();
  const invalidateCatalog = useInvalidateCatalog();

  const [values, setValues] = useState<SeriesFormValues>(EMPTY_SERIES_FORM);
  const [errors, setErrors] = useState<SeriesFormErrors>({});
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [loadResult, setLoadResult] = useState<LoadResult | null>(null);
  /** Serie tal como está guardada (la cargada o la que devolvió el último `PUT`): título del encabezado y episodios. */
  const [saved, setSaved] = useState<SeriesDetail | null>(null);

  useEffect(() => {
    if (!isEdit || editId === null) return;
    const controller = new AbortController();
    apiFetch<SeriesDetail>(`/admin/series/${editId}`, { signal: controller.signal })
      .then((series) => {
        const loaded = toFormValues(series);
        setValues(loaded);
        setErrors(storedUrlErrors(loaded));
        setSaved(series);
        setLoadResult({ attempt: loadAttempt, status: 'ready', errorMessage: '' });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        if (error instanceof ApiError && error.sessionExpired) return;
        const notFound = error instanceof ApiError && error.status === 404;
        setLoadResult({
          attempt: loadAttempt,
          status: notFound ? 'not-found' : 'error',
          errorMessage: getErrorMessage(error, 'No se pudo cargar la serie.'),
        });
      });
    return () => controller.abort();
  }, [isEdit, editId, loadAttempt]);

  const current = loadResult?.attempt === loadAttempt ? loadResult : null;
  const loadStatus = !isEdit ? 'ready' : editId === null ? 'not-found' : (current?.status ?? 'loading');

  /** Actualiza un campo de texto y borra su error: ya lo está corrigiendo. */
  const setField = (field: Exclude<keyof SeriesFormValues, 'genreIds'>, value: string) => {
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
        // La serie (en edición) o algún género elegido ya no existe; el mensaje del servidor dice cuál.
        // Se recargan los géneros por si el que falta era uno de ellos.
        setFormError(error.message);
        genres.reload();
        void invalidateCatalog('series');
        return;
      }
    }
    setFormError(getErrorMessage(error, 'No se pudo guardar la serie.'));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!genres.loaded || submitting) return; // el botón ya está desactivado; Intro en un campo también envía
    setFormError('');
    const found = validateSeriesForm(values);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      focusFirstInvalid(found);
      return;
    }

    const body = toSeriesRequest(values);
    setSubmitting(true);
    try {
      if (isEdit) {
        const updated = await apiFetch<SeriesDetail>(`/series/${editId}`, { method: 'PUT', body });
        void invalidateCatalog('series'); // los listados públicos, el detalle y «Mi lista» se ponen al día
        setSaved(updated);
        setValues(toFormValues(updated));
        toast.success(`Se han guardado los cambios de «${updated.title}».`);
      } else {
        const created = await apiFetch<SeriesDetail>('/series', { method: 'POST', body });
        void invalidateCatalog('series');
        toast.success(SERIES_CREATED_MESSAGE);
        const state: ReturnToState = { returnTo };
        navigate(adminEditSeriesPath(created.id), { replace: true, state });
        return; // el editor se desmonta (cambia la `key`): no hay que tocar su estado
      }
    } catch (error) {
      showServerError(error);
    }
    setSubmitting(false);
  };

  const heading = !isEdit ? 'Nueva serie' : saved && current?.status === 'ready' ? `Editar «${saved.title}»` : 'Editar serie';

  let content: ReactNode;
  if (loadStatus === 'loading') {
    content = <LoadingState label="Cargando la serie..." />;
  } else if (loadStatus === 'not-found') {
    content = (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title="No se encontró la serie"
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
        title="No se pudo cargar la serie"
        message={current?.errorMessage ?? ''}
        onRetry={() => setLoadAttempt((n) => n + 1)}
      />
    );
  } else {
    content = (
      <>
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
              maxChars={SERIES_DESCRIPTION_MAX}
              error={errors.description}
              value={values.description}
              onChange={(event) => setField('description', event.target.value)}
            />
            <div className="grid gap-5 sm:grid-cols-2">
              {/* Texto con teclado numérico y no `type="number"` (ver el formulario de películas). */}
              <FormField
                id={FIELD_IDS.releaseYear}
                label="Año de estreno"
                inputMode="numeric"
                pattern="[0-9]*"
                autoComplete="off"
                placeholder="Ej.: 2019"
                error={errors.releaseYear}
                value={values.releaseYear}
                onChange={(event) => setField('releaseYear', event.target.value)}
              />
              <FormField
                id={FIELD_IDS.endYear}
                label="Año de fin (opcional)"
                inputMode="numeric"
                pattern="[0-9]*"
                autoComplete="off"
                placeholder="Ej.: 2022"
                hint="Déjalo vacío si sigue en emisión."
                error={errors.endYear}
                value={values.endYear}
                onChange={(event) => setField('endYear', event.target.value)}
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
            <GenreCheckboxes
              id={FIELD_IDS.genreIds}
              itemNoun="cada serie"
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

          <CoverPreview title={values.title} imageUrl={values.imageUrl} placeholderTitle="Nueva serie" />

          <div className="lg:col-start-1">
            {/* Junto a los botones (y no arriba del todo): es donde está quien acaba de pulsar «Guardar». */}
            <FormAlert message={formError} />
            <div className="flex flex-col-reverse gap-3 border-t border-line pt-6 sm:flex-row sm:items-center sm:justify-end">
              {!genres.loaded && (
                <p id="series-submit-hint" className="text-xs text-muted sm:mr-auto">
                  Podrás guardar cuando se carguen los géneros.
                </p>
              )}
              <Link to={returnTo} className={buttonClasses('outline')}>
                {isEdit ? 'Volver al listado' : 'Cancelar'}
              </Link>
              <Button
                type="submit"
                disabled={submitting || !genres.loaded}
                aria-describedby={genres.loaded ? undefined : 'series-submit-hint'}
              >
                {submitting ? 'Guardando...' : isEdit ? 'Guardar cambios' : 'Crear serie'}
              </Button>
            </div>
          </div>
        </form>
        {/* Tras cada cambio de episodios la sección pasa la serie recién pedida: recuentos y título al día, sin tocar los campos. */}
        {saved && <SeriesEpisodesSection series={saved} onSeriesChange={setSaved} />}
      </>
    );
  }

  return (
    <section aria-labelledby="series-form-title">
      <Link
        to={returnTo}
        className="focus-ring -ml-1 mb-2 inline-flex min-h-11 items-center gap-1.5 rounded-md px-1 text-sm text-muted transition-colors hover:text-white"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        Volver al listado
      </Link>
      <h2 id="series-form-title" className="text-xl font-semibold tracking-tight wrap-anywhere">
        {heading}
      </h2>
      {content}
    </section>
  );
}
