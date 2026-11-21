import { useEffect, useId, useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent, ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Pencil, Tags, Trash } from 'lucide-react';
import { Button } from '../../components/Button';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { FormField } from '../../components/FormField';
import { LoadingState } from '../../components/LoadingState';
import { useToast } from '../../context/ToastContext';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { useAdminGenres } from '../../hooks/useGenres';
import { useInvalidateCatalog } from '../../hooks/useInvalidateCatalog';
import { useToastAfterDialog } from '../../hooks/useToastAfterDialog';
import { ApiError, apiFetch } from '../../lib/api';
import { GENRE_NAME_MAX, GENRE_NAME_MIN, validateGenreName } from '../../lib/movieValidation';
import { queryKeys } from '../../lib/queryKeys';
import type { AdminGenre, Genre, GenreRequest } from '../../lib/types';
import { genreInUseExplanation, genreUsageText, usageTotal } from './genreUsage';

/** Aviso que se queda en la fila de un género (p. ej. "no se puede borrar: lo usan 3 películas"). */
interface RowNotice {
  genreId: number;
  message: string;
}

/** Elemento al que hay que llevar el foco en cuanto se pinte el siguiente estado (ver el efecto de foco). */
type FocusTarget = { kind: 'rename-button'; genreId: number } | { kind: 'heading' } | null;

/**
 * Mensaje de un error de nombre de género (alta o renombrado) para mostrarlo
 * junto al campo, o `null` si no es un error "del nombre".
 *
 * - 409 `GENRE_ALREADY_EXISTS`: el mensaje del servidor dice cuál ("Ya existe
 *   un género con el nombre «Drama»"; compara ya normalizado, así que
 *   «drama» también choca).
 * - 400 con `validationErrors.name` (o sin él): longitud tras normalizar, etc.
 */
function nameErrorFrom(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  if (error.status === 409 && error.code === 'GENRE_ALREADY_EXISTS') return error.message;
  if (error.status === 400) return error.validationErrors?.name ?? error.message;
  return null;
}

/** Propiedades de {@link GenreRow}. */
interface GenreRowProps {
  genre: AdminGenre;
  renameRef: (element: HTMLButtonElement | null) => void;
  onRename: () => void;
  onDelete: () => void;
}

/**
 * Fila de un género (fuera del modo renombrar): nombre, uso y acciones.
 *
 * En móvil, el uso va en una segunda línea bajo el nombre; desde `sm`, en su
 * propia columna, para que la lista aproveche el ancho del panel.
 *
 * **«Borrar» de un género en uso** (D-4): se ve y se anuncia como no disponible
 * (`inactive` de `Button`: `aria-disabled`, sin soltar el foco ni salir del
 * orden de tabulación) y su descripción accesible dice por qué y qué hacer. No
 * abre la confirmación: antes se preguntaba «¿Borrar el género «Drama»?» y solo
 * después el servidor contestaba que lo usaban 12 películas.
 */
function GenreRow({ genre, renameRef, onRename, onDelete }: GenreRowProps) {
  const explanationId = useId();
  const inUse = usageTotal(genre) > 0;
  return (
    <>
      <div className="min-w-0 flex-1 sm:grid sm:grid-cols-2 sm:items-baseline sm:gap-6">
        <span className="block font-medium wrap-anywhere text-white">{genre.name}</span>
        <span className="block text-sm text-muted tabular-nums">{genreUsageText(genre)}</span>
      </div>
      <div className="flex gap-1 sm:gap-2">
        <Button
          ref={renameRef}
          variant="ghost"
          aria-label={`Renombrar ${genre.name}`}
          onClick={onRename}
          className="font-medium max-sm:w-11 max-sm:px-0 sm:px-3"
        >
          <Pencil aria-hidden="true" className="size-4 shrink-0" />
          <span className="max-sm:sr-only">Renombrar</span>
        </Button>
        <Button
          variant="ghost"
          aria-label={`Borrar ${genre.name}`}
          inactive={inUse}
          aria-describedby={inUse ? explanationId : undefined}
          onClick={onDelete}
          className="font-medium text-danger hover:bg-red-500/10 hover:text-danger max-sm:w-11 max-sm:px-0 sm:px-3"
        >
          <Trash aria-hidden="true" className="size-4 shrink-0" />
          <span className="max-sm:sr-only">Borrar</span>
        </Button>
        {inUse && (
          <span id={explanationId} className="sr-only">
            {genreInUseExplanation(genre)}
          </span>
        )}
      </div>
    </>
  );
}

/**
 * Pestaña «Géneros» del panel (`/admin/generos`): alta, renombrado en línea y borrado.
 *
 * - **Alta**: formulario arriba. El servidor normaliza el nombre («ciencia
 *   FICCIÓN» → «Ciencia ficción»); se muestra el nombre que devuelve, no el
 *   escrito, y la ayuda del campo lo anuncia.
 * - **Renombrar en línea**: «Renombrar X» convierte la fila en un campo con
 *   «Guardar» y «Cancelar». El foco entra en el campo (con el texto
 *   seleccionado); Escape o «Cancelar» lo devuelven al botón «Renombrar X» y,
 *   tras guardar, al botón del género ya renombrado. Solo una fila a la vez.
 * - **Uso de cada género** (`GET /api/admin/genres`, D-4): cuántas películas y
 *   series lo usan. Un género en uso no ofrece «Borrar» (ver {@link GenreRow}) y
 *   el texto de cabecera explica la regla a la vista de todos.
 * - **Borrar**: con confirmación y esperando al servidor. Si aun así el género
 *   está en uso (409 `GENRE_IN_USE`: alguien le asignó un título entre medias),
 *   el mensaje del servidor —que dice cuántas películas y series lo usan y qué
 *   hacer— se queda **en la fila** de ese género en vez de en un aviso
 *   emergente, para leerlo con calma, y se vuelven a pedir los recuentos.
 * - **404** al renombrar o borrar: el género ya no existía (otra persona, otra
 *   pestaña). Se informa y se recargan la lista del panel y la de los
 *   formularios (ver `refreshAfterMissingGenre`).
 * - **Avisos del borrado**: se lanzan cuando ya se ha cerrado la confirmación
 *   ({@link useToastAfterDialog}), para que nazcan una sola vez y en la página.
 * - **Caché.** La lista de la pestaña es `queryKeys.genres.admin`: cada cambio
 *   confirmado por el servidor se escribe en ella (`setQueryData`, con lo que
 *   devuelve el servidor) sin volver a pedirla. La lista completa de los
 *   formularios (`genres.all`) se marca como anticuada (no está en pantalla: se
 *   pedirá al abrir un formulario) y se invalidan las películas, las series, los
 *   filtros y «Mi lista», que llevan el nombre de sus géneros dentro (ver
 *   `keysAffectedBy`).
 */
export function AdminGenresPage() {
  useDocumentTitle('Géneros · Administración');
  const toast = useToast();
  const { genres, status, loaded, errorMessage, reload } = useAdminGenres();
  const queryClient = useQueryClient();
  const invalidateCatalog = useInvalidateCatalog();

  /**
   * Aplica a la lista en caché un cambio que el servidor ya ha confirmado y avisa
   * a lo demás que muestra géneros. El orden lo pone `useAdminGenres` al leer.
   *
   * Antes corta la lectura de géneros en vuelo: salió antes de esta escritura y,
   * al responder después, desharía el cambio en la lista. Si aún no había lista (el
   * formulario de alta se puede usar mientras carga la primera vez), no hay dónde
   * aplicar el cambio: se pide de nuevo, porque la lectura cortada ya no llegará.
   * `exact`: cada lista de géneros es su propia entrada (ver `queryKeys.genres`).
   */
  const applyGenreChange = async (change: (list: AdminGenre[]) => AdminGenre[]) => {
    const admin = { queryKey: queryKeys.genres.admin, exact: true };
    const full = { queryKey: queryKeys.genres.all, exact: true };
    await Promise.all([queryClient.cancelQueries(admin), queryClient.cancelQueries(full)]);
    const updated = queryClient.setQueryData<AdminGenre[]>(queryKeys.genres.admin, (list) => (list ? change(list) : list));
    if (updated === undefined) void queryClient.invalidateQueries(admin);
    void queryClient.invalidateQueries(full);
    void invalidateCatalog('genres');
  };

  /**
   * 404 al renombrar o borrar: el género ya no existe en el servidor. Se vuelven a
   * pedir la lista del panel (`reload`) y TAMBIÉN la completa de los formularios
   * de película y serie (`genres.all`, exacta: por prefijo alcanzaría también a la
   * del panel, que ya se está pidiendo). Sin esto último, esos formularios podían
   * seguir ofreciendo el género borrado hasta que su caché caducara (60 s). Lo
   * demás que muestra géneros se invalida con `invalidateCatalog('genres')`.
   */
  const refreshAfterMissingGenre = () => {
    reload();
    void queryClient.invalidateQueries({ queryKey: queryKeys.genres.all, exact: true });
    void invalidateCatalog('genres');
  };

  const [newName, setNewName] = useState('');
  const [newNameError, setNewNameError] = useState<string | undefined>(undefined);
  const [creating, setCreating] = useState(false);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState('');
  const [editError, setEditError] = useState<string | undefined>(undefined);
  const [saving, setSaving] = useState(false);

  const [genreToDelete, setGenreToDelete] = useState<AdminGenre | null>(null);
  const [deleting, setDeleting] = useState(false);
  const toastAfterDialog = useToastAfterDialog(genreToDelete !== null);
  const [rowNotice, setRowNotice] = useState<RowNotice | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);
  const renameButtons = useRef(new Map<number, HTMLButtonElement>());
  const editInputRef = useRef<HTMLInputElement>(null);
  /**
   * Adónde llevar el foco cuando se pinte el siguiente estado. Es una `ref` y no estado porque no
   * necesita provocar un render: siempre va acompañada de un cambio que ya lo provoca (salir del
   * renombrado, cambiar la lista o cerrar el diálogo).
   */
  const pendingFocus = useRef<FocusTarget>(null);

  // El foco se mueve DESPUÉS de pintar el nuevo estado (el botón o el título no existen, o la página está
  // inerte, en el momento de pulsar). Con el diálogo abierto se espera: el resto de la página es inerte.
  useEffect(() => {
    const target = pendingFocus.current;
    if (!target || genreToDelete) return;
    pendingFocus.current = null;
    if (target.kind === 'heading') headingRef.current?.focus();
    else renameButtons.current.get(target.genreId)?.focus();
  }, [genreToDelete, genres, editingId]);

  // Al abrir el renombrado, el foco entra en el campo con el nombre seleccionado (escribir lo sustituye).
  useEffect(() => {
    if (editingId === null) return;
    editInputRef.current?.focus();
    editInputRef.current?.select();
  }, [editingId]);

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault();
    setRowNotice(null);
    const invalid = validateGenreName(newName);
    if (invalid) {
      setNewNameError(invalid);
      document.getElementById('genre-new-name')?.focus();
      return;
    }
    setCreating(true);
    try {
      const body: GenreRequest = { name: newName.trim() };
      const created = await apiFetch<Genre>('/genres', { method: 'POST', body });
      // Recién creado, no lo usa nadie.
      await applyGenreChange((list) => [...list, { ...created, movieCount: 0, seriesCount: 0 }]);
      setNewName('');
      toast.success(`Género «${created.name}» creado.`);
    } catch (error) {
      const message = nameErrorFrom(error);
      if (message) {
        setNewNameError(message);
        document.getElementById('genre-new-name')?.focus();
      } else {
        toast.errorFrom(error, 'No se pudo crear el género.');
      }
    } finally {
      setCreating(false);
    }
  };

  const startRename = (genre: AdminGenre) => {
    setRowNotice(null);
    setEditingId(genre.id);
    setEditName(genre.name);
    setEditError(undefined);
  };

  /** Sale del renombrado sin guardar y devuelve el foco al botón «Renombrar» de esa fila. */
  const cancelRename = () => {
    if (editingId !== null) pendingFocus.current = { kind: 'rename-button', genreId: editingId };
    setEditingId(null);
    setEditError(undefined);
  };

  const handleRename = async (event: FormEvent, genre: AdminGenre) => {
    event.preventDefault();
    const invalid = validateGenreName(editName);
    if (invalid) {
      setEditError(invalid);
      editInputRef.current?.focus();
      return;
    }
    if (editName.trim() === genre.name) {
      cancelRename(); // sin cambios: no hace falta molestar al servidor
      return;
    }
    setSaving(true);
    try {
      const body: GenreRequest = { name: editName.trim() };
      const updated = await apiFetch<Genre>(`/genres/${genre.id}`, { method: 'PUT', body });
      await applyGenreChange((list) => list.map((item) => (item.id === genre.id ? { ...item, name: updated.name } : item)));
      setEditingId(null);
      pendingFocus.current = { kind: 'rename-button', genreId: genre.id };
      toast.success(`Género «${genre.name}» renombrado a «${updated.name}».`);
    } catch (error) {
      const message = nameErrorFrom(error);
      if (message) {
        setEditError(message);
        editInputRef.current?.focus();
      } else if (error instanceof ApiError && error.status === 404) {
        setEditingId(null);
        pendingFocus.current = { kind: 'heading' };
        toast.info(`El género «${genre.name}» ya no existía. Se ha actualizado la lista.`);
        refreshAfterMissingGenre();
      } else {
        toast.errorFrom(error, `No se pudo renombrar «${genre.name}».`);
      }
    } finally {
      setSaving(false);
    }
  };

  const handleConfirmDelete = async () => {
    if (!genreToDelete) return;
    const genre = genreToDelete;
    setDeleting(true);
    try {
      await apiFetch(`/genres/${genre.id}`, { method: 'DELETE' });
      await applyGenreChange((list) => list.filter((item) => item.id !== genre.id));
      pendingFocus.current = { kind: 'heading' }; // la fila (y su botón) desaparece
      toastAfterDialog(() => toast.success(`Género «${genre.name}» borrado.`));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && error.code === 'GENRE_IN_USE') {
        // El foco vuelve solo al botón «Borrar» (sigue existiendo) y el aviso de la fila se anuncia (role="alert").
        // Los recuentos que se veían estaban desfasados: al llegar los nuevos, «Borrar» queda desactivado.
        setRowNotice({ genreId: genre.id, message: error.message });
        void queryClient.invalidateQueries({ queryKey: queryKeys.genres.admin, exact: true });
      } else if (error instanceof ApiError && error.status === 404) {
        pendingFocus.current = { kind: 'heading' };
        toastAfterDialog(() => toast.info(`El género «${genre.name}» ya no existía. Se ha actualizado la lista.`));
        refreshAfterMissingGenre();
      } else {
        toastAfterDialog(() => toast.errorFrom(error, `No se pudo borrar «${genre.name}».`));
      }
    } finally {
      setDeleting(false);
      setGenreToDelete(null);
    }
  };

  let list: ReactNode;
  if (!loaded && status === 'loading') {
    list = <LoadingState label="Cargando géneros..." />;
  } else if (!loaded && status === 'error') {
    list = (
      <ErrorState title="No se pudieron cargar los géneros" message={errorMessage} onRetry={reload} retryFocusRef={headingRef} />
    );
  } else if (genres.length === 0) {
    list = (
      <EmptyState
        icon={<Tags className="size-8" />}
        title="Todavía no hay géneros"
        description="Crea el primero con el formulario de arriba. Cada película o serie necesita al menos un género."
      />
    );
  } else {
    list = (
      <ul role="list" aria-labelledby="admin-genres-title" className="divide-y divide-line rounded-xl border border-line">
        {genres.map((genre) => (
          <li key={genre.id} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2.5">
            {editingId === genre.id ? (
              <form
                onSubmit={(event) => void handleRename(event, genre)}
                onKeyDown={(event: KeyboardEvent) => {
                  if (event.key === 'Escape') {
                    event.preventDefault();
                    cancelRename();
                  }
                }}
                noValidate
                className="flex w-full flex-wrap items-start gap-2"
              >
                <div className="min-w-48 flex-1">
                  <FormField
                    ref={editInputRef}
                    id={`genre-rename-${genre.id}`}
                    label={`Nuevo nombre para ${genre.name}`}
                    hideLabel
                    autoComplete="off"
                    error={editError}
                    value={editName}
                    onChange={(event) => {
                      setEditName(event.target.value);
                      setEditError(undefined);
                    }}
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="submit" pending={saving}>
                    {saving ? 'Guardando...' : 'Guardar'}
                  </Button>
                  <Button variant="outline" onClick={cancelRename} disabled={saving}>
                    Cancelar
                  </Button>
                </div>
              </form>
            ) : (
              <GenreRow
                genre={genre}
                renameRef={(element) => {
                  if (element) renameButtons.current.set(genre.id, element);
                  else renameButtons.current.delete(genre.id);
                }}
                onRename={() => startRename(genre)}
                onDelete={() => {
                  setRowNotice(null);
                  setGenreToDelete(genre);
                }}
              />
            )}
            {rowNotice?.genreId === genre.id && (
              <p role="alert" className="basis-full rounded-md border border-red-500/30 bg-red-950/30 px-3 py-2 text-sm text-red-300">
                {rowNotice.message}
              </p>
            )}
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section aria-labelledby="admin-genres-title">
      <div className="mb-5 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        {/* tabIndex -1: recibe el foco por código cuando desaparece la fila que lo tenía. */}
        <h2 id="admin-genres-title" ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight outline-hidden">
          Géneros
        </h2>
        {loaded && (
          <p className="text-sm text-muted tabular-nums">
            {genres.length === 1 ? '1 género' : `${genres.length} géneros`}
          </p>
        )}
      </div>
      {/* La regla, a la vista: un «Borrar» atenuado no debe ser un misterio (D-4). */}
      <p className="-mt-3 mb-5 max-w-prose text-sm leading-relaxed text-muted">
        Solo se pueden borrar los géneros que no usa ninguna película ni serie.
      </p>

      <form onSubmit={(event) => void handleCreate(event)} noValidate className="mb-6 flex max-w-xl flex-col gap-3 sm:flex-row sm:items-start">
        <div className="min-w-0 flex-1">
          <FormField
            id="genre-new-name"
            label="Nombre del nuevo género"
            autoComplete="off"
            hint={`Entre ${GENRE_NAME_MIN} y ${GENRE_NAME_MAX} caracteres. Se guarda con mayúscula inicial: «ciencia FICCIÓN» → «Ciencia ficción».`}
            error={newNameError}
            value={newName}
            onChange={(event) => {
              setNewName(event.target.value);
              setNewNameError(undefined);
            }}
          />
        </div>
        {/* sm:mt-6.5: alinea el botón con el campo (altura de la etiqueta + su margen). */}
        <Button type="submit" pending={creating} className="sm:mt-6.5">
          {creating ? 'Añadiendo...' : 'Añadir'}
        </Button>
      </form>

      {list}

      <ConfirmDialog
        open={genreToDelete !== null}
        title={genreToDelete ? `¿Borrar el género «${genreToDelete.name}»?` : ''}
        description="No lo usa ninguna película ni serie. Esta acción no se puede deshacer."
        confirmLabel="Sí, borrar género"
        busy={deleting}
        onConfirm={() => void handleConfirmDelete()}
        onCancel={() => setGenreToDelete(null)}
      />
    </section>
  );
}
