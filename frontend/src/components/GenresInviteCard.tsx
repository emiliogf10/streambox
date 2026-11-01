import { useId } from 'react';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { PROFILE_TASTES_PATH } from '../lib/favoriteGenres';
import { buttonClasses } from './buttonStyles';

/** Título de la tarjeta (su `<h2>` y el nombre de su región). */
export const GENRES_INVITE_TITLE = 'Dinos qué géneros te gustan y te recomendaremos títulos';

/**
 * Tarjeta de la portada para quien no tiene recomendaciones porque no hay nada
 * en qué basarlas (`basis: 'NONE'`: ni géneros favoritos ni «Me gusta»).
 *
 * Lleva a «Tus gustos» del perfil (`/perfil#tus-gustos`, donde la página pone
 * el foco en la sección). Es la vía de las cuentas que ya existían antes de esta
 * función (decisión del autor: a ellas nunca se les hace la pregunta de
 * bienvenida) y de quien contestó «Ahora no».
 *
 * Solo se pinta si hay al menos 3 géneros elegibles (lo decide
 * `RecommendedRows` con `useGenreOptions`): con menos, «Tus gustos» no tiene
 * selector y la tarjeta llevaría a un callejón sin salida (revisión de `qa`).
 *
 * El texto es verdad para los dos roles: un ADMIN también tiene «Tus gustos» y
 * recomendaciones. Menciona también los «Me gusta» porque es la otra forma de
 * conseguirlas, y no hace falta elegir géneros para tenerlas.
 *
 * Es una región con su `<h2>` como nombre, hermana de las filas de la portada,
 * con los mismos márgenes que ellas. Superficie y textos ya calculados en
 * `index.css` (blanco y `muted` sobre `surface`).
 */
export function GenresInviteCard() {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="mx-4 mb-9 sm:mx-6 sm:mb-10">
      <div className="flex flex-col items-start gap-4 rounded-xl border border-line bg-surface px-5 py-5 sm:flex-row sm:items-center sm:gap-5 sm:px-6">
        <div
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-full border border-accent/40 bg-accent/10 text-accent"
        >
          <Sparkles className="size-5" />
        </div>
        <div className="min-w-0 flex-1">
          <h2 id={headingId} className="text-lg font-bold tracking-tight text-balance text-white">
            {GENRES_INVITE_TITLE}
          </h2>
          <p className="mt-1 max-w-prose text-sm leading-relaxed text-muted">
            Elige entre 3 y 5 en tu perfil. También aprendemos de los títulos que marcas con «Me gusta».
          </p>
        </div>
        <Link to={PROFILE_TASTES_PATH} className={`${buttonClasses('primary')} shrink-0`}>
          Elegir mis géneros
        </Link>
      </div>
    </section>
  );
}
