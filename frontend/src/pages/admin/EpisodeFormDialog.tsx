import { useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../components/Button';
import { FormAlert } from '../../components/FormAlert';
import { FormField } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { TextAreaField } from '../../components/TextAreaField';
import { ApiError, apiFetch, getErrorMessage } from '../../lib/api';
import {
  EPISODE_DESCRIPTION_MAX,
  EPISODE_SEASON_MAX,
  EPISODE_SEASON_MIN,
  newEpisodeFormValues,
  nextEpisodeNumber,
  parseInRange,
  toEpisodeFormValues,
  toEpisodeRequest,
  validateEpisodeForm,
} from '../../lib/episodeValidation';
import type { EpisodeFormErrors, EpisodeFormValues } from '../../lib/episodeValidation';
import { episodeCode } from '../../lib/series';
import type { Episode, Season } from '../../lib/types';
import { focusFirstInvalidField, splitValidationErrors } from './formErrors';

/** Orden de los campos en pantalla: el foco va al primero con error. */
const FIELD_ORDER: (keyof EpisodeFormValues)[] = [
  'title',
  'seasonNumber',
  'episodeNumber',
  'duration',
  'description',
  'videoUrl',
];

/**
 * `id` de cada campo (etiquetas, mensajes y foco). Distintos de los del
 * formulario de la serie (`series-title`...), que sigue en la página detrás del diálogo.
 */
const FIELD_IDS: Record<keyof EpisodeFormValues, string> = {
  seasonNumber: 'episode-season',
  episodeNumber: 'episode-number',
  title: 'episode-title',
  duration: 'episode-duration',
  description: 'episode-description',
  videoUrl: 'episode-video-url',
};

/** Propiedades de {@link EpisodeFormDialog}. */
interface EpisodeFormDialogProps {
  seriesId: number;
  /** Título de la serie (lo nombra la descripción del diálogo al editar). */
  seriesTitle: string;
  /** Temporadas actuales de la serie: de ellas salen la temporada y el número que se proponen. */
  seasons: readonly Season[];
  /** `null` = añadir un episodio nuevo; un episodio = editarlo. */
  episode: Episode | null;
  /** Cerrar sin guardar (Cancelar, Escape, clic en el fondo). */
  onClose: () => void;
  /**
   * El servidor ha guardado el episodio. El diálogo sigue «Guardando...» hasta
   * que la promesa se resuelve: así el padre refresca la serie y cierra con la
   * lista ya al día (sin un instante con la fila antigua).
   */
  onSaved: (saved: Episode) => Promise<void>;
  /** 404 al editar: el episodio ya no existe (lo borró otra persona). El padre cierra y refresca. */
  onMissing: () => Promise<void>;
}

/**
 * Formulario de un episodio en un diálogo modal, para añadirlo
 * (`POST /api/series/{id}/episodes`) o editarlo (`PUT .../episodes/{episodeId}`).
 *
 * Se monta al abrirlo y se desmonta al cerrarlo (el padre lo pinta solo cuando
 * hace falta), así que cada apertura empieza de cero, sin arrastrar lo escrito
 * ni los errores de la vez anterior.
 *
 * - **En un diálogo y no en otra página**: un episodio son seis campos y la
 *   lista de la que se parte (temporadas y números ya usados) es el contexto
 *   que hace falta al rellenarlo. El {@link Modal} aporta foco atrapado,
 *   Escape, devolución del foco a quien lo abrió y los avisos dentro del diálogo.
 * - **Valores propuestos al añadir**: la última temporada y el siguiente número
 *   ({@link newEpisodeFormValues}). Si el admin cambia la temporada, el número se
 *   recalcula para esa temporada… **salvo que ya haya escrito un número a
 *   mano**: sobrescribir algo que ha tecleado sería peor que no ayudar.
 * - **El Título va primero y recibe el foco inicial**: temporada y número ya
 *   vienen propuestos y lo normal es aceptarlos; la descripción del diálogo lo
 *   dice, para que quien usa lector de pantalla lo sepa sin buscarlo.
 * - Los tres números (temporada, número y duración) comparten fila y el pie
 *   con los botones es pegajoso: el diálogo cabe en una pantalla de portátil y,
 *   si no cabe, los botones siguen a la vista.
 * - **Validación** con los mensajes exactos del servidor (`episodeValidation.ts`),
 *   errores del servidor (`validationErrors`) junto a cada campo y foco al primero.
 * - **409 `EPISODE_ALREADY_EXISTS`** (ese número ya existe en esa temporada):
 *   el mensaje del servidor va bajo el **número**, que está al lado de la
 *   temporada, el foco va ahí, y la temporada también se marca como inválida y
 *   apunta a ese mensaje (cualquiera de los dos lo arregla). Al cambiar
 *   cualquiera de los dos el aviso desaparece: ya no se sabe si sigue ocupado.
 * - Mientras se envía, el botón queda desactivado y el diálogo no se puede
 *   cerrar (`dismissible`), para no dejar una petición a medias sin respuesta visible.
 */
export function EpisodeFormDialog({
  seriesId,
  seriesTitle,
  seasons,
  episode,
  onClose,
  onSaved,
  onMissing,
}: EpisodeFormDialogProps) {
  const isEdit = episode !== null;
  const titleId = useId();
  const descriptionId = useId();
  const titleInputRef = useRef<HTMLInputElement>(null);

  const [values, setValues] = useState<EpisodeFormValues>(() =>
    episode ? toEpisodeFormValues(episode) : newEpisodeFormValues(seasons),
  );
  const [errors, setErrors] = useState<EpisodeFormErrors>({});
  /** Mensaje del 409 (temporada + número ya usados). Aparte de `errors` porque afecta a los dos campos. */
  const [conflict, setConflict] = useState('');
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  /** `true` en cuanto el admin escribe en «Número»: desde entonces no se le cambia al elegir temporada. */
  const [numberTouched, setNumberTouched] = useState(isEdit);

  /** Actualiza un campo y borra su error: ya lo está corrigiendo. */
  const setField = (field: keyof EpisodeFormValues, value: string) => {
    setValues((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  };

  const changeSeason = (value: string) => {
    setField('seasonNumber', value);
    setConflict('');
    if (numberTouched) return;
    const season = parseInRange(value, EPISODE_SEASON_MIN, EPISODE_SEASON_MAX);
    if (season === null) return; // temporada a medio escribir o no válida: no se adivina nada
    const next = nextEpisodeNumber(seasons, season);
    setField('episodeNumber', next === null ? '' : String(next));
  };

  const changeNumber = (value: string) => {
    setNumberTouched(true);
    setField('episodeNumber', value);
    setConflict('');
  };

  /** Traduce un error del servidor a mensajes de campo, al aviso de conflicto o al aviso general. */
  const showServerError = async (error: unknown) => {
    if (error instanceof ApiError) {
      if (error.sessionExpired) return; // el aviso y la salida al login ya los gestiona AuthProvider
      if (error.code === 'VALIDATION_ERROR' && error.validationErrors) {
        const { perField, unmatched } = splitValidationErrors(error.validationErrors, FIELD_IDS);
        setErrors(perField);
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : 'Revisa los campos marcados.');
        focusFirstInvalidField(perField, FIELD_ORDER, FIELD_IDS);
        return;
      }
      if (error.code === 'EPISODE_ALREADY_EXISTS') {
        setConflict(error.message);
        document.getElementById(FIELD_IDS.episodeNumber)?.focus();
        return;
      }
      if (error.status === 404 && isEdit) {
        // El episodio (o la serie) ya no existe: no hay nada que corregir aquí, el padre cierra y refresca.
        await onMissing();
        return;
      }
    }
    setFormError(getErrorMessage(error, 'No se pudo guardar el episodio.'));
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    setFormError('');
    setConflict('');
    const found = validateEpisodeForm(values);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      focusFirstInvalidField(found, FIELD_ORDER, FIELD_IDS);
      return;
    }

    const body = toEpisodeRequest(values);
    setSubmitting(true);
    try {
      const saved = episode
        ? await apiFetch<Episode>(`/series/${seriesId}/episodes/${episode.id}`, { method: 'PUT', body })
        : await apiFetch<Episode>(`/series/${seriesId}/episodes`, { method: 'POST', body });
      await onSaved(saved);
      return; // el padre cierra el diálogo (se desmonta): no hay que tocar su estado
    } catch (error) {
      await showServerError(error);
    }
    setSubmitting(false);
  };

  const numberError = errors.episodeNumber ?? (conflict || undefined);
  const heading = episode ? `Editar episodio ${episodeCode(episode.seasonNumber, episode.episodeNumber)}` : 'Añadir episodio';
  const description = episode
    ? `De la serie «${seriesTitle}». Los cambios se ven en su página en cuanto los guardes.`
    : 'Se proponen la última temporada y el siguiente número libre; puedes cambiarlos.';

  return (
    <Modal
      onClose={onClose}
      dismissible={!submitting}
      labelledBy={titleId}
      describedBy={descriptionId}
      initialFocusRef={titleInputRef}
      className="max-w-xl"
    >
      {/* Sin relleno inferior: el pie (botones) es pegajoso y lleva el suyo. */}
      <form onSubmit={handleSubmit} noValidate className="px-5 pt-5 sm:px-7 sm:pt-7">
        <h2 id={titleId} className="text-lg font-bold tracking-tight">
          {heading}
        </h2>
        <p id={descriptionId} className="mt-1 text-sm leading-relaxed text-muted wrap-anywhere">
          {description}
        </p>

        <div className="mt-6 flex flex-col gap-5">
          <FormField
            ref={titleInputRef}
            id={FIELD_IDS.title}
            label="Título"
            autoComplete="off"
            error={errors.title}
            value={values.title}
            onChange={(event) => setField('title', event.target.value)}
          />
          {/*
            Los tres números en una fila (en móvil, temporada y número juntos y la duración debajo):
            el diálogo cabe en una pantalla de portátil sin desplazarse. Temporada y número, siempre
            uno al lado del otro: son la "posición" del episodio y el 409 se refiere a los dos.
          */}
          <div className="grid grid-cols-2 items-start gap-4 sm:grid-cols-3">
            <FormField
              id={FIELD_IDS.seasonNumber}
              label="Temporada"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              error={errors.seasonNumber}
              value={values.seasonNumber}
              onChange={(event) => changeSeason(event.target.value)}
              // Con el 409 la temporada también es "inválida" y su descripción es el mensaje de debajo del número.
              {...(conflict && !errors.seasonNumber
                ? { 'aria-invalid': true, 'aria-describedby': `${FIELD_IDS.episodeNumber}-error` }
                : {})}
            />
            <FormField
              id={FIELD_IDS.episodeNumber}
              label="Número"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              error={numberError}
              value={values.episodeNumber}
              onChange={(event) => changeNumber(event.target.value)}
            />
            <FormField
              id={FIELD_IDS.duration}
              label="Duración (minutos)"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              placeholder="Ej.: 45"
              error={errors.duration}
              value={values.duration}
              onChange={(event) => setField('duration', event.target.value)}
            />
          </div>
          <TextAreaField
            id={FIELD_IDS.description}
            label="Sinopsis (opcional)"
            rows={4}
            maxChars={EPISODE_DESCRIPTION_MAX}
            error={errors.description}
            value={values.description}
            onChange={(event) => setField('description', event.target.value)}
          />
          <FormField
            id={FIELD_IDS.videoUrl}
            label="URL del vídeo"
            type="url"
            autoComplete="off"
            spellCheck={false}
            placeholder="https://…"
            error={errors.videoUrl}
            value={values.videoUrl}
            onChange={(event) => setField('videoUrl', event.target.value)}
          />
        </div>

        <div className="mt-6">
          <FormAlert message={formError} />
        </div>
        {/*
          Pie pegajoso: si el diálogo no cabe (pantallas bajas, errores a la vista) sus campos se
          desplazan por debajo y «Cancelar» / «Guardar» siguen a la vista, sin tener que buscarlos.
          En móvil, los dos botones en una fila (mitad y mitad, con menos relleno para que el texto
          quepa en una línea) para no comerse media pantalla.
        */}
        <div className="sticky bottom-0 -mx-5 flex gap-3 border-t border-line bg-surface px-5 py-4 sm:-mx-7 sm:justify-end sm:px-7">
          <Button variant="outline" onClick={onClose} disabled={submitting} className="flex-1 max-sm:px-3 sm:flex-none">
            Cancelar
          </Button>
          <Button type="submit" disabled={submitting} className="flex-1 max-sm:px-3 sm:flex-none">
            {submitting ? 'Guardando...' : isEdit ? 'Guardar cambios' : 'Añadir episodio'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
