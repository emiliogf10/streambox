import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { Button } from '../Button';
import { ConfirmDialog } from '../ConfirmDialog';
import { ErrorState } from '../ErrorState';
import { FormAlert } from '../FormAlert';
import { GenrePicker } from '../GenrePicker';
import { useToast } from '../../context/ToastContext';
import { useFavoriteGenres, useGenreOptions, useSaveFavoriteGenres } from '../../hooks/useFavoriteGenres';
import { ApiError, getErrorMessage } from '../../lib/api';
import {
  GENRE_SHORTAGE_LIKES_HINT,
  TASTES_SECTION_ID,
  canSaveGenreSelection,
  formatGenreNames,
  genreShortageMessage,
  genreShortageTitle,
  hasEnoughGenreOptions,
} from '../../lib/favoriteGenres';
import type { Genre } from '../../lib/types';

/** Aviso tras guardar los gustos desde el perfil. */
export const TASTES_SAVED_MESSAGE = 'Hemos guardado tus gustos. Ya cuentan para tus recomendaciones.';

/** Aviso tras quitar los gustos. */
export const TASTES_REMOVED_MESSAGE = 'Has quitado tus gustos. Puedes volver a elegirlos cuando quieras.';

/** A dónde va el foco tras cerrar el editor o quitar los gustos (ver `focusRequest`). */
type FocusTarget = 'heading' | 'edit';

/** Etiqueta de un género, la misma que las de «Géneros de tu lista» (sin recuento). */
function GenreChips({ genres }: { genres: Genre[] }) {
  return (
    <ul role="list" className="flex flex-wrap gap-2">
      {genres.map((genre) => (
        <li
          key={genre.id}
          className="inline-flex min-h-9 items-center rounded-full border border-line bg-surface-raised px-3.5 text-sm text-white"
        >
          {genre.name}
        </li>
      ))}
    </ul>
  );
}

/** Propiedades de {@link TastesEditor}. */
interface TastesEditorProps {
  /** Favoritos actuales: los marcados al abrir. */
  current: Genre[];
  /** Cierra el editor (al guardar o al cancelar); el padre devuelve el foco al botón que lo abrió. */
  onClose: () => void;
}

/**
 * Editor de los gustos: el mismo selector que la bienvenida ({@link GenrePicker}),
 * con «Guardar» (de 3 a 5) y «Cancelar».
 *
 * - Pide las opciones (`useGenreOptions`) al abrirse, con sus tres estados; con
 *   menos de 3 (ninguna, o 1 o 2 géneros con títulos visibles) el mínimo es
 *   imposible: lo dice con un texto distinto para cada caso y solo queda
 *   «Cancelar», sin un selector cuyo «Guardar» nunca se habilitaría.
 * - Marca al abrir los favoritos que SIGUEN siendo elegibles. Si alguno ya no
 *   tiene títulos, no está entre las opciones: se avisa de que al guardar dejará
 *   de estar entre tus gustos (el servidor rechazaría guardarlo).
 * - Un 400 de los géneros se enseña junto al grupo (y se vuelven a pedir las
 *   opciones); cualquier otro fallo, en un aviso sobre el editor, que sigue
 *   abierto para reintentar.
 */
function TastesEditor({ current, onClose }: TastesEditorProps) {
  const toast = useToast();
  const options = useGenreOptions();
  const { save, saving } = useSaveFavoriteGenres();
  const pickerRef = useRef<HTMLFieldSetElement>(null);
  const counterId = useId();
  const [selected, setSelected] = useState<number[]>(() => current.map((genre) => genre.id));
  const [fieldError, setFieldError] = useState('');
  const [formError, setFormError] = useState('');

  const optionIds = new Set(options.genres.map((genre) => genre.id));
  // Solo cuenta (y se envía) lo que se puede elegir de verdad.
  const visibleSelected = options.status === 'ready' ? selected.filter((id) => optionIds.has(id)) : selected;
  const lost = options.status === 'ready' ? current.filter((genre) => !optionIds.has(genre.id)) : [];
  // Con menos de 3 elegibles no se pinta el selector: su «Guardar» nunca se habilitaría (revisión de `qa`).
  const ready = options.status === 'ready' && hasEnoughGenreOptions(options.genres.length);
  const canSave = canSaveGenreSelection(visibleSelected.length);

  // Al tener ya las opciones, el foco va al grupo: el lector anuncia su nombre y el contador.
  useEffect(() => {
    if (ready) pickerRef.current?.focus();
  }, [ready]);

  const submit = async () => {
    if (!canSave || saving) return;
    setFormError('');
    setFieldError('');
    try {
      await save(visibleSelected);
      toast.success(TASTES_SAVED_MESSAGE);
      onClose();
    } catch (error) {
      if (error instanceof ApiError && error.sessionExpired) return;
      const genreError = error instanceof ApiError ? error.validationErrors?.genreIds : undefined;
      if (genreError) {
        setFieldError(genreError);
        options.reload();
        pickerRef.current?.focus();
      } else {
        setFormError(getErrorMessage(error));
      }
    }
  };

  let body: ReactNode;
  if (options.status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando géneros...
      </p>
    );
  } else if (options.status === 'error') {
    body = (
      <ErrorState compact title="No se pudieron cargar los géneros" message={options.errorMessage} onRetry={options.reload} />
    );
  } else if (!ready) {
    body = (
      <div>
        <p className="font-semibold text-white">{genreShortageTitle(options.genres)}</p>
        <p className="mt-1 max-w-prose text-sm leading-relaxed text-muted">
          {genreShortageMessage(options.genres)}
          {options.genres.length > 0 && ` ${GENRE_SHORTAGE_LIKES_HINT}`}
        </p>
      </div>
    );
  } else {
    body = (
      <GenrePicker
        ref={pickerRef}
        options={options.genres}
        selected={visibleSelected}
        onChange={(next) => {
          setSelected(next);
          setFieldError('');
        }}
        legend="Elige entre 3 y 5 géneros"
        description={
          lost.length > 0 ? (
            <p>
              {lost.length === 1
                ? `«${lost[0].name}» ya no tiene títulos, así que no se puede elegir: si guardas, dejará de estar entre tus gustos.`
                : `${formatGenreNames(lost.map((genre) => `«${genre.name}»`))} ya no tienen títulos, así que no se pueden elegir: si guardas, dejarán de estar entre tus gustos.`}
            </p>
          ) : undefined
        }
        error={fieldError}
        disabled={saving}
        counterId={counterId}
      />
    );
  }

  return (
    <div>
      <FormAlert message={formError} />
      {body}
      <div className="mt-5 flex flex-wrap gap-3">
        {ready && (
          <Button
            onClick={() => void submit()}
            aria-disabled={!canSave || saving || undefined}
            aria-describedby={counterId}
          >
            {saving ? 'Guardando...' : 'Guardar'}
          </Button>
        )}
        <Button variant="outline" onClick={onClose} disabled={saving}>
          Cancelar
        </Button>
      </div>
    </div>
  );
}

/**
 * Sección «Tus gustos» de `/perfil`: los géneros favoritos del usuario, que el
 * servidor usa para recomendarle títulos (cada uno pesa como dos «Me gusta»).
 * Para USER y ADMIN por igual (decisión del autor: el ADMIN no ve la pregunta
 * de bienvenida, pero sí esta sección).
 *
 * - **Ver**: la lista (`GET /api/users/me/genres`) o, sin ninguno, un estado
 *   vacío que explica para qué sirven, con «Elegir géneros». Cargando y error
 *   (con «Reintentar») solo afectan a la sección.
 * - **Editar** / **Elegir géneros**: abre {@link TastesEditor} en la misma
 *   sección (no un diálogo: el selector necesita ancho).
 * - **Quitar mis gustos**: `PUT` con la lista vacía tras una confirmación
 *   (`ConfirmDialog`): pierde de golpe la base de las recomendaciones. Un fallo
 *   se enseña dentro del diálogo, que sigue abierto para reintentar.
 *
 * **Foco.** Al llegar con `#tus-gustos` (la tarjeta de la portada), el foco va
 * al título de la sección (lo que lleva la página hasta ella, sin quedar debajo
 * de la barra gracias al `scroll-margin-top` de `index.css`). Al cerrar el
 * editor vuelve al botón que lo abrió; tras quitar los gustos, al título (el
 * botón ya no existe).
 *
 * Tras guardar o quitar, `useSaveFavoriteGenres` actualiza la lista con la
 * respuesta y descarta las recomendaciones en caché: la portada las vuelve a
 * pedir con los gustos nuevos.
 */
export function FavoriteGenresSection() {
  const toast = useToast();
  const location = useLocation();
  const favorites = useFavoriteGenres();
  const { save, saving } = useSaveFavoriteGenres();
  const headingId = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [removeError, setRemoveError] = useState('');
  // Petición de foco tras un cambio de modo: se atiende en un efecto, cuando el destino ya está en la página.
  const [focusRequest, setFocusRequest] = useState<{ target: FocusTarget } | null>(null);

  // Llegada desde la tarjeta de la portada (`/perfil#tus-gustos`).
  useEffect(() => {
    if (location.hash === `#${TASTES_SECTION_ID}`) headingRef.current?.focus();
  }, [location.hash]);

  useEffect(() => {
    if (!focusRequest) return;
    (focusRequest.target === 'edit' ? editButtonRef.current : headingRef.current)?.focus();
  }, [focusRequest]);

  const closeEditor = () => {
    setEditing(false);
    setFocusRequest({ target: 'edit' });
  };

  const remove = async () => {
    setRemoveError('');
    try {
      await save([]);
      setConfirming(false);
      toast.success(TASTES_REMOVED_MESSAGE);
      setFocusRequest({ target: 'heading' });
    } catch (error) {
      if (error instanceof ApiError && error.sessionExpired) return;
      setRemoveError(getErrorMessage(error));
    }
  };

  let body: ReactNode;
  if (favorites.status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando tus gustos...
      </p>
    );
  } else if (favorites.status === 'error') {
    body = (
      <ErrorState
        compact
        title="No se pudieron cargar tus gustos"
        message={favorites.errorMessage}
        onRetry={favorites.reload}
        retryFocusRef={headingRef}
      />
    );
  } else if (editing) {
    body = <TastesEditor current={favorites.genres} onClose={closeEditor} />;
  } else if (favorites.genres.length === 0) {
    body = (
      <>
        <p className="font-semibold text-white">Aún no has elegido tus géneros favoritos</p>
        <p className="mt-1 max-w-prose text-sm leading-relaxed text-muted">
          Elige entre 3 y 5 y te recomendaremos en el inicio películas y series de esos géneros, junto con las
          parecidas a lo que marques con «Me gusta».
        </p>
        <Button ref={editButtonRef} className="mt-4" onClick={() => setEditing(true)}>
          Elegir géneros
        </Button>
      </>
    );
  } else {
    body = (
      <>
        <GenreChips genres={favorites.genres} />
        <p className="mt-4 max-w-prose text-sm leading-relaxed text-muted">
          Los tenemos en cuenta para recomendarte películas y series en el inicio, junto con lo que marcas con «Me
          gusta».
        </p>
        <div className="mt-4 flex flex-wrap gap-3">
          <Button ref={editButtonRef} variant="outline" onClick={() => setEditing(true)}>
            Editar
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setRemoveError('');
              setConfirming(true);
            }}
          >
            Quitar mis gustos
          </Button>
        </div>
      </>
    );
  }

  return (
    <section
      id={TASTES_SECTION_ID}
      aria-labelledby={headingId}
      className="mt-4 rounded-xl border border-line bg-surface p-5 sm:p-6"
    >
      {/* Enfocable por código (llegada con #tus-gustos, tras quitar los gustos); sin anillo: no es un control. */}
      <h2
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="mb-4 text-lg font-bold tracking-tight text-white outline-hidden"
      >
        Tus gustos
      </h2>
      {body}
      <ConfirmDialog
        open={confirming}
        title="¿Quitar tus gustos?"
        description="Dejaremos de usar estos géneros para recomendarte títulos. Podrás volver a elegirlos cuando quieras."
        confirmLabel="Quitar mis gustos"
        busyLabel="Quitando..."
        busy={saving}
        error={removeError}
        onConfirm={() => void remove()}
        onCancel={() => setConfirming(false)}
      />
    </section>
  );
}
