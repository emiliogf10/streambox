import { SpellCheck } from 'lucide-react';
import { approximateResultsText } from './approximateResults';
import type { ApproximateKind } from './approximateResults';

/** Propiedades de {@link ApproximateResultsNotice}. */
interface ApproximateResultsNoticeProps {
  kind: ApproximateKind;
  /** Búsqueda de los resultados que se están viendo (`resultsQuery` de `useAdminSearchList`). */
  query: string;
}

/**
 * Aviso encima de la tabla del panel cuando la búsqueda por título no encontró coincidencias y el
 * servidor devolvió títulos parecidos (`approximate: true`, tolerancia a erratas). Sin él, una
 * búsqueda de «interstelar» enseñaría «Interstellar» como si lo contuviera y en un orden que no es
 * el alfabético de siempre. Lleva icono y texto (no depende del color).
 */
export function ApproximateResultsNotice({ kind, query }: ApproximateResultsNoticeProps) {
  return (
    <p className="mb-4 flex items-start gap-2.5 rounded-xl border border-line bg-surface px-4 py-3 text-sm leading-relaxed text-white/90">
      <SpellCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
      <span className="min-w-0 wrap-anywhere">{approximateResultsText(kind, query)}</span>
    </p>
  );
}
