import { useEffect, useRef, useState } from 'react';
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
import { useGenres } from '../../hooks/useGenres';
import { useInvalidateCatalog } from '../../hooks/useInvalidateCatalog';
import { ApiError, apiFetch } from '../../lib/api';
import { GENRE_NAME_MAX, GENRE_NAME_MIN, validateGenreName } from '../../lib/movieValidation';
import { queryKeys } from '../../lib/queryKeys';
import type { Genre, GenreRequest } from '../../lib/types';

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
 * - **Borrar**: con confirmación y esperando al servidor. Si el género está en
 *   uso (409 `GENRE_IN_USE`), el mensaje del servidor —que dice cuántas
 *   películas y series lo usan y qué hacer— se queda **en la fila** de ese género en vez
 *   de en un aviso emergente: hay que poder leerlo con calma mientras se
 *   decide, y un toast desaparece a los pocos segundos.
 * - **404** al renombrar o borrar: el género ya no existía (otra persona, otra
 *   pestaña). Se informa y se recarga la lista.
 * - **Caché.** La lista es la entrada compartida de `useGenres`: cada cambio
 *   confirmado por el servidor se escribe en ella (`setQueryData`, con lo que
 *   devuelve el servidor), así que los formularios y los filtros de `/peliculas`
 *   lo ven sin pedir nada. Además se invalidan las películas, las series y «Mi
 *   lista», que llevan el nombre de sus géneros dentro (ver `keysAffectedBy`).
 */
export function AdminGenresPage() {
  useDocumentTitle('Géneros · Administración');
  const toast = useToast();
  const { genres, status, loaded, errorMessage, reload } = useGenres();
  const queryClient = useQueryClient();
  const invalidateCatalog = useInvalidateCatalog();

  /**
   * Aplica a la lista en caché un cambio que el servidor ya ha confirmado y avisa
   * a los listados que muestran géneros. El orden lo pone `useGenres` al leer.
   */
  const applyGenreChange = (change: (list: Genre[]) => Genre[]) => {
    queryClient.setQueryData<Genre[]>(queryKeys.genres.all, (list) => (list ? change(list) : list));
    void invalidateCatalog('genres');
  };

  const [newName, setNewName] = useState('');
  const [newNameError, setNewNameError] = useState<string | undefined>(undefined);
  const [creating, setCreating] = useState(false);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState('');
  const [editError, setEditError] = useState<string | undefined>(undefined);
  const [saving, setSaving] = useState(false);

  const [genreToDelete, setGenreToDelete] = useState<Genre | null>(null);
  const [deleting, setDeleting] = useState(false);
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
      applyGenreChange((list) => [...list, created]);
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

  const startRename = (genre: Genre) => {
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

  const handleRename = async (event: FormEvent, genre: Genre) => {
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
      applyGenreChange((list) => list.map((item) => (item.id === genre.id ? updated : item)));
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
        reload();
        void invalidateCatalog('genres');
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
      applyGenreChange((list) => list.filter((item) => item.id !== genre.id));
      pendingFocus.current = { kind: 'heading' }; // la fila (y su botón) desaparece
      toast.success(`Género «${genre.name}» borrado.`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && error.code === 'GENRE_IN_USE') {
        // El foco vuelve solo al botón «Borrar» (sigue existiendo) y el aviso de la fila se anuncia (role="alert").
        setRowNotice({ genreId: genre.id, message: error.message });
      } else if (error instanceof ApiError && error.status === 404) {
        pendingFocus.current = { kind: 'heading' };
        toast.info(`El género «${genre.name}» ya no existía. Se ha actualizado la lista.`);
        reload();
        void invalidateCatalog('genres');
      } else {
        toast.errorFrom(error, `No se pudo borrar «${genre.name}».`);
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
    list = <ErrorState title="No se pudieron cargar los géneros" message={errorMessage} onRetry={reload} />;
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
                  <Button type="submit" disabled={saving}>
                    {saving ? 'Guardando...' : 'Guardar'}
                  </Button>
                  <Button variant="outline" onClick={cancelRename} disabled={saving}>
                    Cancelar
                  </Button>
                </div>
              </form>
            ) : (
              <>
                <span className="min-w-0 flex-1 font-medium wrap-anywhere text-white">{genre.name}</span>
                <div className="flex gap-1 sm:gap-2">
                  <Button
                    ref={(element) => {
                      if (element) renameButtons.current.set(genre.id, element);
                      else renameButtons.current.delete(genre.id);
                    }}
                    variant="ghost"
                    aria-label={`Renombrar ${genre.name}`}
                    onClick={() => startRename(genre)}
                    className="font-medium max-sm:w-11 max-sm:px-0 sm:px-3"
                  >
                    <Pencil aria-hidden="true" className="size-4 shrink-0" />
                    <span className="max-sm:sr-only">Renombrar</span>
                  </Button>
                  <Button
                    variant="ghost"
                    aria-label={`Borrar ${genre.name}`}
                    onClick={() => {
                      setRowNotice(null);
                      setGenreToDelete(genre);
                    }}
                    className="font-medium text-danger hover:bg-red-500/10 hover:text-danger max-sm:w-11 max-sm:px-0 sm:px-3"
                  >
                    <Trash aria-hidden="true" className="size-4 shrink-0" />
                    <span className="max-sm:sr-only">Borrar</span>
                  </Button>
                </div>
              </>
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
        <Button type="submit" disabled={creating} className="sm:mt-6.5">
          {creating ? 'Añadiendo...' : 'Añadir'}
        </Button>
      </form>

      {list}

      <ConfirmDialog
        open={genreToDelete !== null}
        title={genreToDelete ? `¿Borrar el género «${genreToDelete.name}»?` : ''}
        description="Solo se puede borrar un género que no tenga ninguna película ni serie. Esta acción no se puede deshacer."
        confirmLabel="Sí, borrar género"
        busy={deleting}
        onConfirm={() => void handleConfirmDelete()}
        onCancel={() => setGenreToDelete(null)}
      />
    </section>
  );
}
