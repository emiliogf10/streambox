import { useEffect, useId, useRef, useState } from 'react';
import { Eye, EyeOff, Pencil, Plus, Trash } from 'lucide-react';
import { Button } from '../../components/Button';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { useToast } from '../../context/ToastContext';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../../lib/api';
import { episodeCode, formatEpisodeCount, formatSeasonCount } from '../../lib/series';
import type { Episode, Season, SeriesDetail } from '../../lib/types';
import { formatDuration } from '../../lib/utils';
import { EpisodeFormDialog } from './EpisodeFormDialog';

/** Propiedades de {@link SeriesEpisodesSection}. */
interface SeriesEpisodesSectionProps {
  /** Serie cargada en la edición (`GET /api/admin/series/{id}`), con sus temporadas y episodios. */
  series: SeriesDetail;
  /**
   * Se llama con la serie recién pedida al servidor tras cada cambio de
   * episodios. El padre (`SeriesFormPage`) la guarda como "serie guardada";
   * los campos del formulario de la serie NO se tocan (puede haber cambios sin guardar).
   */
  onSeriesChange: (series: SeriesDetail) => void;
}

/** Aviso que se añade al primer episodio de una serie vacía. */
const NOW_VISIBLE_MESSAGE = 'La serie ya es visible para los usuarios.';
/** Aviso que se añade al borrar el último episodio. */
const NOW_HIDDEN_MESSAGE = 'La serie ya no tiene episodios: vuelve a estar oculta.';

/** Qué tiene abierto la sección: nada, el diálogo para añadir o el de editar un episodio. */
type Editor = { kind: 'create' } | { kind: 'edit'; episode: Episode } | null;

/** "T1:E3 «Piloto»": cómo se nombra un episodio en avisos y confirmaciones. */
function episodeLabel(episode: Episode): string {
  return `${episodeCode(episode.seasonNumber, episode.episodeNumber)} «${episode.title}»`;
}

/**
 * Sección «Episodios» de la edición de una serie (`/admin/series/:id/editar`):
 * la lista de episodios agrupada por temporada y su gestión (añadir, editar y
 * borrar). Está separada del formulario de la serie porque son acciones
 * independientes: cada episodio se guarda al momento, sin pulsar «Guardar cambios».
 *
 * Decisiones:
 * - **La verdad la tiene el servidor.** Tras cada cambio se vuelve a pedir la
 *   serie (`GET /api/admin/series/{id}`) en lugar de recalcular aquí temporadas,
 *   recuentos y visibilidad: una temporada que se queda vacía desaparece, otra
 *   nueva aparece en su orden, y si otra persona ha tocado la serie a la vez
 *   también se ve. Si ese refresco falla, el cambio ya está guardado: se avisa
 *   de que la lista puede estar desactualizada y se ofrece «Reintentar».
 * - **El diálogo espera al refresco** antes de cerrarse («Guardando...» /
 *   «Procesando...»): al cerrarse, la lista ya es la nueva y no hay un instante
 *   con la fila antigua. **Nada es optimista**: es el catálogo de todos.
 * - **Visibilidad.** Una serie sin episodios no la ven los usuarios. La
 *   cabecera lo dice siempre (con icono y texto, no solo color) y los avisos
 *   lo cuentan cuando cambia: al añadir el primer episodio y al borrar el último.
 * - **Nombres accesibles únicos**: «Editar episodio T1:E3 Piloto». El código va
 *   en el nombre porque dos episodios de temporadas distintas pueden llamarse
 *   igual (lo mismo hace «Ver» en la página pública).
 * - **Foco.** Al cerrar el diálogo, el `Modal` devuelve el foco a quien lo
 *   abrió. Hay dos casos en que ese elemento ya no existe y se mueve a mano:
 *   tras **borrar** (su fila desaparece) va al encabezado «Episodios», y tras
 *   **editar** cambiando de temporada (la fila se vuelve a crear en otra lista)
 *   va al botón «Editar» del episodio editado.
 * - En móvil las acciones se quedan en su icono (44 × 44 px, el texto sigue en
 *   el nombre accesible), como en los listados del panel, para que el título
 *   tenga sitio sin scroll horizontal.
 */
export function SeriesEpisodesSection({ series, onSeriesChange }: SeriesEpisodesSectionProps) {
  const headingId = useId();
  const toast = useToast();
  const empty = series.episodeCount === 0;

  const [editor, setEditor] = useState<Editor>(null);
  const [toDelete, setToDelete] = useState<Episode | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  /** Mensaje si el último refresco falló (la lista puede estar desactualizada); `null` si no. */
  const [refreshError, setRefreshError] = useState<string | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);
  /** Botones «Editar» por id de episodio, para devolverles el foco tras editar. */
  const editButtons = useRef(new Map<number, HTMLButtonElement>());
  /** Adónde llevar el foco cuando se cierre el diálogo abierto (ver la documentación del componente). */
  const focusTarget = useRef<'heading' | number | null>(null);
  /**
   * Aviso pendiente de mostrar cuando se cierre el diálogo. Se espera al cierre porque, mientras un
   * diálogo está abierto, los avisos se pintan DENTRO de él (ver `Modal`): uno lanzado justo antes de
   * cerrarlo nacería en el diálogo y se volvería a crear en la página un instante después, y un lector
   * de pantalla podría perderlo o leerlo dos veces.
   */
  const pendingToast = useRef<(() => void) | null>(null);
  const refreshController = useRef<AbortController | null>(null);

  /** Lo llama cada botón «Editar» al montarse (con el elemento) y al desmontarse (con `null`). */
  const registerEditButton = (episodeId: number, element: HTMLButtonElement | null) => {
    if (element) editButtons.current.set(episodeId, element);
    else editButtons.current.delete(episodeId);
  };

  // Si la página se va mientras llega un refresco, se cancela.
  useEffect(() => () => refreshController.current?.abort(), []);

  // Con los dos diálogos cerrados, se aplican el foco y el aviso pendientes. Corre DESPUÉS de las limpiezas
  // del Modal (devolver el foco a quien lo abrió y la zona de avisos a la página: las limpiezas van antes que
  // los efectos nuevos), así que este tiene la última palabra y el aviso ya nace en la página.
  useEffect(() => {
    if (editor !== null || toDelete !== null) return;
    const target = focusTarget.current;
    focusTarget.current = null;
    if (target === 'heading') headingRef.current?.focus();
    else if (target !== null) editButtons.current.get(target)?.focus();
    const showToast = pendingToast.current;
    pendingToast.current = null;
    showToast?.();
  }, [editor, toDelete]);

  /**
   * Vuelve a pedir la serie al servidor y se la pasa al padre.
   * @returns la serie nueva, o `null` si no se pudo (el error queda a la vista con «Reintentar»)
   */
  const refresh = async (): Promise<SeriesDetail | null> => {
    refreshController.current?.abort();
    const controller = new AbortController();
    refreshController.current = controller;
    setRefreshing(true);
    try {
      const detail = await apiFetch<SeriesDetail>(`/admin/series/${series.id}`, { signal: controller.signal });
      setRefreshError(null);
      onSeriesChange(detail);
      return detail;
    } catch (error) {
      if (controller.signal.aborted || isAbortError(error)) return null;
      if (!(error instanceof ApiError && error.sessionExpired)) {
        setRefreshError(getErrorMessage(error, 'Comprueba tu conexión.'));
      }
      return null;
    } finally {
      if (refreshController.current === controller) setRefreshing(false);
    }
  };

  /** El diálogo ha guardado el episodio: se refresca la lista, se cierra y se avisa. */
  const handleSaved = async (saved: Episode) => {
    const created = editor?.kind === 'create';
    const wasEmpty = series.episodeCount === 0;
    await refresh();
    if (!created) focusTarget.current = saved.id;
    const label = episodeLabel(saved);
    // Tras crear un episodio la serie tiene al menos uno: si antes no tenía ninguno, acaba de hacerse visible.
    const message = created
      ? `Se ha añadido el episodio ${label}.${wasEmpty ? ` ${NOW_VISIBLE_MESSAGE}` : ''}`
      : `Se han guardado los cambios del episodio ${label}.`;
    pendingToast.current = () => toast.success(message);
    setEditor(null);
  };

  /** 404 al editar: el episodio ya no existe. Se refresca, se cierra y se explica. */
  const handleMissing = async () => {
    await refresh();
    focusTarget.current = 'heading';
    pendingToast.current = () =>
      toast.info('Ese episodio ya no existe: puede que lo haya borrado otra persona. Se ha actualizado la lista.');
    setEditor(null);
  };

  /** Borra el episodio pendiente de confirmar. No optimista; 404 = ya estaba borrado (estado deseado). */
  const confirmDelete = async () => {
    if (!toDelete) return;
    const episode = toDelete;
    const label = episodeLabel(episode);
    setDeleting(true);
    try {
      await apiFetch(`/series/${series.id}/episodes/${episode.id}`, { method: 'DELETE' });
      const detail = await refresh();
      // Sin refresco, se deduce del recuento anterior: si solo quedaba este, la serie se ha quedado vacía.
      const nowEmpty = detail ? detail.episodeCount === 0 : series.episodeCount === 1;
      const message = `Se ha borrado el episodio ${label}.${nowEmpty ? ` ${NOW_HIDDEN_MESSAGE}` : ''}`;
      pendingToast.current = () => toast.success(message);
      focusTarget.current = 'heading';
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        await refresh();
        pendingToast.current = () => toast.info(`El episodio ${label} ya no existía. Se ha actualizado la lista.`);
        focusTarget.current = 'heading';
      } else {
        // No ha cambiado nada: la fila sigue y el Modal devuelve el foco a su botón «Borrar».
        pendingToast.current = () => toast.errorFrom(error, `No se pudo borrar el episodio ${label}.`);
      }
    }
    setDeleting(false);
    setToDelete(null);
  };

  const deleteDescription =
    toDelete && series.episodeCount === 1
      ? 'Es el único episodio: la serie volverá a estar oculta para los usuarios. Esta acción no se puede deshacer.'
      : 'Esta acción no se puede deshacer.';

  return (
    <section aria-labelledby={headingId} className="mt-10 border-t border-line pt-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          {/* tabIndex -1: recibe el foco por código tras borrar; no es una parada de tabulador. */}
          <h3 id={headingId} ref={headingRef} tabIndex={-1} className="text-lg font-semibold tracking-tight outline-hidden">
            Episodios
          </h3>
          {!empty && (
            <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-white/90">
              <span>{`${formatEpisodeCount(series.episodeCount)} en ${formatSeasonCount(series.seasonCount)}`}</span>
              <span className="inline-flex items-center gap-1.5 text-success">
                <Eye aria-hidden="true" className="size-4 shrink-0" />
                Visible para los usuarios
              </span>
            </p>
          )}
        </div>
        {/* Sin episodios es EL siguiente paso (botón principal); con ellos, una acción más (secundario). */}
        <Button variant={empty ? 'primary' : 'outline'} onClick={() => setEditor({ kind: 'create' })}>
          <Plus aria-hidden="true" className="size-4" />
          Añadir episodio
        </Button>
      </div>

      {empty && (
        <p className="mt-4 flex items-start gap-2 rounded-lg border border-accent/40 bg-accent/10 p-4 text-sm leading-relaxed text-white">
          <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent" />
          <span>
            Esta serie todavía no tiene episodios, así que <strong className="font-semibold">los usuarios no la ven</strong>{' '}
            en el catálogo, el buscador ni su lista. Aparecerá en cuanto tenga al menos uno.
          </span>
        </p>
      )}

      {refreshError !== null && (
        <div
          role="alert"
          className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-500/40 bg-red-900/40 px-4 py-3 text-sm text-red-300"
        >
          <span className="min-w-0">{`No se pudo actualizar la lista de episodios: puede estar desactualizada. ${refreshError}`}</span>
          <Button variant="outline" onClick={() => void refresh()} disabled={refreshing}>
            {refreshing ? 'Actualizando...' : 'Reintentar'}
          </Button>
        </div>
      )}

      <div aria-busy={refreshing || undefined}>
        {series.seasons.map((season) => (
          <SeasonEpisodes
            key={season.seasonNumber}
            season={season}
            registerEditButton={registerEditButton}
            onEdit={(episode) => setEditor({ kind: 'edit', episode })}
            onDelete={setToDelete}
          />
        ))}
      </div>

      {editor && (
        <EpisodeFormDialog
          seriesId={series.id}
          seriesTitle={series.title}
          seasons={series.seasons}
          episode={editor.kind === 'edit' ? editor.episode : null}
          onClose={() => setEditor(null)}
          onSaved={handleSaved}
          onMissing={handleMissing}
        />
      )}

      <ConfirmDialog
        open={toDelete !== null}
        title={toDelete ? `¿Borrar el episodio ${episodeLabel(toDelete)}?` : ''}
        description={toDelete ? deleteDescription : ''}
        confirmLabel="Sí, borrar episodio"
        busy={deleting}
        onConfirm={() => void confirmDelete()}
        onCancel={() => setToDelete(null)}
      />
    </section>
  );
}

/** Propiedades de {@link SeasonEpisodes}. */
interface SeasonEpisodesProps {
  season: Season;
  /** Registra el botón «Editar» de cada episodio (para devolverle el foco tras editarlo). */
  registerEditButton: (episodeId: number, element: HTMLButtonElement | null) => void;
  onEdit: (episode: Episode) => void;
  onDelete: (episode: Episode) => void;
}

/**
 * Una temporada: su encabezado «Temporada 1 · 8 episodios» (`<h4>`, bajo el
 * `<h3>` «Episodios») y la lista ordenada de sus episodios, que el encabezado nombra.
 */
function SeasonEpisodes({ season, registerEditButton, onEdit, onDelete }: SeasonEpisodesProps) {
  const headingId = useId();
  return (
    <div className="mt-6">
      <h4 id={headingId} className="text-sm font-semibold text-white">
        {`Temporada ${season.seasonNumber} · ${formatEpisodeCount(season.episodes.length)}`}
      </h4>
      {/* role="list": Safari quita la semántica de lista a un <ol> sin viñetas. */}
      <ol role="list" aria-labelledby={headingId} className="mt-2 border-t border-line">
        {season.episodes.map((episode) => {
          const code = episodeCode(episode.seasonNumber, episode.episodeNumber);
          return (
            <li key={episode.id} className="flex items-center gap-3 border-b border-line py-2.5 sm:gap-4">
              <span className="min-w-12 shrink-0 font-mono text-xs text-muted tabular-nums">{code}</span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-white wrap-anywhere">{episode.title}</p>
                <p className="mt-0.5 text-xs text-muted">
                  {formatDuration(episode.duration)}
                  {episode.description === null && ' · Sin sinopsis'}
                </p>
              </div>
              <div className="flex shrink-0 gap-1 sm:gap-2">
                <Button
                  ref={(element) => registerEditButton(episode.id, element)}
                  variant="ghost"
                  aria-label={`Editar episodio ${code} ${episode.title}`}
                  onClick={() => onEdit(episode)}
                  className="font-medium max-sm:w-11 max-sm:px-0 sm:px-3"
                >
                  <Pencil aria-hidden="true" className="size-4 shrink-0" />
                  <span className="max-sm:sr-only">Editar</span>
                </Button>
                <Button
                  variant="ghost"
                  aria-label={`Borrar episodio ${code} ${episode.title}`}
                  onClick={() => onDelete(episode)}
                  className="font-medium text-danger hover:bg-red-500/10 hover:text-danger max-sm:w-11 max-sm:px-0 sm:px-3"
                >
                  <Trash aria-hidden="true" className="size-4 shrink-0" />
                  <span className="max-sm:sr-only">Borrar</span>
                </Button>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
