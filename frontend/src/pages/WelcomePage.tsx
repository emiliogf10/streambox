import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Button } from '../components/Button';
import { ErrorState } from '../components/ErrorState';
import { FormAlert } from '../components/FormAlert';
import { GenrePicker } from '../components/GenrePicker';
import { MAIN_CONTENT_ID } from '../components/SkipLink';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useGenreOptions, useSaveFavoriteGenres } from '../hooks/useFavoriteGenres';
import { ApiError, getErrorMessage } from '../lib/api';
import {
  GENRE_SHORTAGE_LIKES_HINT,
  MIN_FAVORITE_GENRES,
  canSaveGenreSelection,
  genreShortageMessage,
  genreShortageTitle,
  hasEnoughGenreOptions,
  needsGenresOnboarding,
  welcomeDestination,
} from '../lib/favoriteGenres';

/** Aviso tras «Guardar». */
export const WELCOME_SAVED_MESSAGE = 'Hemos guardado tus gustos. Puedes cambiarlos cuando quieras en tu perfil.';

/** Aviso tras «Ahora no» («Continuar», cuando no hay géneros suficientes, no avisa: ver `submit`). */
export const WELCOME_SKIPPED_MESSAGE = 'De acuerdo. Puedes elegir tus géneros cuando quieras en tu perfil, en «Tus gustos».';

/**
 * En qué punto está la página:
 * - `idle`: eligiendo (o tras un error, para reintentar);
 * - `saving` / `skipping`: esperando al servidor tras «Guardar» o «Ahora no»;
 * - `done`: terminado; se va al destino.
 */
type Phase = 'idle' | 'saving' | 'skipping' | 'done';

/**
 * Pregunta de bienvenida (`/bienvenida`): «¿qué géneros te gustan?».
 *
 * **Por qué existe.** Las recomendaciones del inicio salen de lo que te gusta;
 * una cuenta nueva no tiene nada y no veía ninguna hasta marcar algún «Me
 * gusta» (el «arranque en frío»). Eligiendo de 3 a 5 géneros al empezar, las
 * tiene desde el primer minuto (cada género pesa como dos «Me gusta»).
 *
 * **A quién se le muestra.** Solo a un `USER` con `genresOnboardingPending`
 * (`GET /api/users/me`): `RequireAuth` le trae aquí UNA vez, tras el login o al
 * cargar la app con sesión, guardando a dónde iba. Quien entra sin tenerla
 * pendiente (un ADMIN, una cuenta que ya contestó, alguien que escribe la URL)
 * vuelve al inicio sin ningún aviso. Se pinta sin la barra superior, como el
 * login: es un paso, no una sección más, y sus dos salidas son sus dos botones.
 *
 * **Nunca bloquea la app.**
 * - «Ahora no» está siempre visible (también mientras cargan los géneros o si
 *   no se pudieron cargar) y envía la lista vacía, que cierra la pregunta para
 *   siempre (el texto lo dice).
 * - Con menos de 3 géneros que elegir (ninguno, o 1 o 2 con títulos visibles:
 *   el mínimo sería imposible), lo dice con un texto distinto para cada caso y
 *   solo ofrece «Continuar» (también la lista vacía): ni un «Guardar» que nunca
 *   se habilita ni un contador que pide más de los que hay.
 * - Si el `PUT` falla (red, 5xx, 429), se avisa, se puede reintentar con el
 *   mismo botón y además se ofrece «Continuar sin guardar», que sigue sin
 *   cerrar la pregunta en el servidor (se volverá a preguntar la próxima vez
 *   que se entre, y el texto lo avisa). Un 400 `VALIDATION_ERROR` de los
 *   géneros (p. ej. uno se quedó sin títulos mientras se elegía) se enseña junto
 *   al grupo y se vuelven a pedir las opciones; la selección solo cuenta (y
 *   envía) las que siguen ofreciéndose, así que el contador dice la verdad y el
 *   id rechazado no se reenvía.
 *
 * **Al terminar** (`useSaveFavoriteGenres`): el usuario de la sesión deja de
 * tener la pregunta pendiente, se descartan las recomendaciones en caché y se
 * va al destino guardado (o al inicio) con `<Navigate replace>`: «Atrás» no
 * vuelve a la pregunta.
 *
 * **Accesibilidad.** Al llegar, el foco va al `<h1>` (se anuncia la pantalla y el
 * siguiente Tab empieza en ella). «Guardar» usa `aria-disabled` y no `disabled`
 * mientras no hay entre 3 y 5 marcados: sigue en el orden de tabulación con el
 * contador como descripción, que dice qué falta.
 *
 * **Responsive.** Columna ancha (`max-w-5xl`) con el selector en rejilla de 2 a 5
 * columnas: en escritorio aprovecha el ancho; a 375 px, dos columnas y los
 * botones uno bajo otro si no caben.
 */
export function WelcomePage() {
  useDocumentTitle('Bienvenida');
  const { user, userStatus, updateUser } = useAuth();
  const toast = useToast();
  const location = useLocation();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const pickerRef = useRef<HTMLFieldSetElement>(null);
  const counterId = useId();
  const skipHintId = useId();
  const pending = needsGenresOnboarding(user);
  // Solo se piden los géneros a quien de verdad tiene la pregunta pendiente (los demás se van al inicio).
  const options = useGenreOptions({ enabled: pending });
  const { save } = useSaveFavoriteGenres();
  const [selected, setSelected] = useState<number[]>([]);
  const [phase, setPhase] = useState<Phase>('idle');
  const [fieldError, setFieldError] = useState('');
  const [formError, setFormError] = useState('');
  // `true` tras un fallo que no es de validación: entonces se ofrece «Continuar sin guardar».
  const [canContinueWithoutSaving, setCanContinueWithoutSaving] = useState(false);

  const busy = phase === 'saving' || phase === 'skipping';
  const destination = welcomeDestination(location.state);
  // Mientras se espera al servidor (o ya se terminó), la pregunta deja de estar pendiente en la sesión: no es
  // motivo para mandar al inicio, porque el destino lo decide `done`.
  const shouldLeave = phase === 'idle' && userStatus !== 'loading' && !pending;

  // Al llegar, el foco al título (después del de `RouteChangeFocus`, que corre antes: es un efecto de diseño).
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  if (phase === 'done') return <Navigate to={destination} replace />;
  if (shouldLeave) return <Navigate to="/" replace />;

  /**
   * Envía la lista (`[]` = «Ahora no»/«Continuar») y, si sale bien, termina.
   * Los errores se quedan en la página para reintentar (ver la documentación).
   *
   * @param successMessage aviso al terminar; `null` con «Continuar» (no hay géneros
   *   suficientes): la página ya explicó por qué y cuándo podrá elegirlos, y
   *   «Puedes elegir tus géneros cuando quieras» sería falso; el aviso es llegar
   *   al destino.
   */
  const submit = async (genreIds: number[], successMessage: string | null) => {
    if (busy) return;
    setFormError('');
    setFieldError('');
    setPhase(genreIds.length > 0 ? 'saving' : 'skipping');
    try {
      await save(genreIds);
      if (successMessage) toast.success(successMessage);
      setPhase('done');
    } catch (error) {
      // Sesión caducada: `apiFetch` ya la cerró y `RequireAuth` lleva al login.
      if (error instanceof ApiError && error.sessionExpired) return;
      setPhase('idle');
      const genreError = error instanceof ApiError ? error.validationErrors?.genreIds : undefined;
      if (genreError) {
        setFieldError(genreError);
        // Quizá un género dejó de tener títulos mientras se elegía: se vuelven a pedir las opciones.
        options.reload();
        pickerRef.current?.focus();
      } else {
        setFormError(getErrorMessage(error));
        setCanContinueWithoutSaving(true);
      }
    }
  };

  /** Sigue sin guardar tras un fallo: solo en esta visita (el servidor la sigue teniendo pendiente). */
  const continueWithoutSaving = () => {
    // Sobre el usuario más reciente: el del render podría tener un nombre o avatar ya sustituidos.
    updateUser((latest) => ({ ...latest, genresOnboardingPending: false }));
    setPhase('done');
  };

  // Solo cuenta (y se envía) lo que se ofrece de verdad: tras un 400 se vuelven a pedir las opciones y, si una
  // ya no está, su casilla desaparece y el usuario no podría desmarcarla (lo mismo que el editor del perfil).
  const optionIds = new Set(options.genres.map((genre) => genre.id));
  const visibleSelected = options.status === 'ready' ? selected.filter((id) => optionIds.has(id)) : selected;
  const canSave = canSaveGenreSelection(visibleSelected.length);
  // Con menos de 3 elegibles no se puede llegar al mínimo: se dice y solo queda «Continuar».
  const notEnoughOptions = options.status === 'ready' && !hasEnoughGenreOptions(options.genres.length);

  let body: ReactNode;
  if (userStatus === 'loading' || options.status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando géneros...
      </p>
    );
  } else if (options.status === 'error') {
    body = (
      <ErrorState
        compact
        title="No se pudieron cargar los géneros"
        message={options.errorMessage}
        onRetry={options.reload}
        retryFocusRef={titleRef}
      />
    );
  } else if (notEnoughOptions) {
    body = (
      <div className="rounded-xl border border-dashed border-white/15 px-5 py-6">
        <p className="font-semibold text-white">{genreShortageTitle(options.genres)}</p>
        <p className="mt-1 max-w-prose text-sm leading-relaxed text-muted">
          {genreShortageMessage(options.genres)} Cuando haya títulos de al menos {MIN_FAVORITE_GENRES} géneros, podrás
          elegir tus favoritos en tu perfil, en «Tus gustos».
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
        legend="Tus géneros favoritos"
        error={fieldError}
        disabled={busy}
        counterId={counterId}
      />
    );
  }

  const showPicker = !notEnoughOptions && options.status === 'ready' && userStatus !== 'loading';

  return (
    <main
      id={MAIN_CONTENT_ID}
      tabIndex={-1}
      className="relative isolate min-h-dvh overflow-hidden bg-canvas text-white outline-hidden"
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-[36rem] bg-radial-[ellipse_at_top] from-accent/12 via-accent/4 via-40% to-transparent to-70%"
      />
      <div className="mx-auto w-full max-w-5xl px-5 py-10 sm:px-8 sm:py-14">
        <div className="mb-10 flex items-center gap-2.5">
          <span aria-hidden="true" className="size-3 rounded-full bg-accent" />
          <span className="text-xl font-bold tracking-tight">streambox</span>
        </div>

        {user && <p className="mb-2 text-sm font-medium break-all text-muted">Hola, {user.username}</p>}
        {/* Sin anillo al enfocarlo por código: no es un control (como el `<main>`). */}
        <h1
          ref={titleRef}
          tabIndex={-1}
          className="mb-3 text-3xl leading-tight font-bold tracking-tight text-balance outline-hidden sm:text-4xl"
        >
          Cuéntanos qué te gusta
        </h1>
        <p className="mb-8 max-w-prose text-base leading-relaxed text-gray-300">
          Elige entre 3 y 5 géneros y te recomendaremos películas y series desde el primer día. Podrás cambiarlos
          cuando quieras en tu perfil.
        </p>

        <FormAlert message={formError} />
        {canContinueWithoutSaving && formError && (
          <div className="-mt-2 mb-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted">
            <p>Si prefieres, sigue sin guardar: te lo volveremos a preguntar la próxima vez que entres.</p>
            <Button variant="ghost" onClick={continueWithoutSaving}>
              Continuar sin guardar
            </Button>
          </div>
        )}

        {body}

        <div className="mt-8 flex flex-col gap-4 border-t border-line pt-6 sm:flex-row sm:flex-wrap sm:items-center">
          <div className="flex flex-wrap gap-3">
            {notEnoughOptions ? (
              <Button onClick={() => void submit([], null)} aria-disabled={busy || undefined}>
                {phase === 'skipping' ? 'Un momento...' : 'Continuar'}
              </Button>
            ) : (
              <>
                {showPicker && (
                  <Button
                    onClick={() => {
                      if (canSave) void submit(visibleSelected, WELCOME_SAVED_MESSAGE);
                    }}
                    aria-disabled={!canSave || busy || undefined}
                    aria-describedby={counterId}
                  >
                    {phase === 'saving' ? 'Guardando...' : 'Guardar'}
                  </Button>
                )}
                <Button
                  variant="outline"
                  onClick={() => void submit([], WELCOME_SKIPPED_MESSAGE)}
                  aria-disabled={busy || undefined}
                  aria-describedby={skipHintId}
                >
                  {phase === 'skipping' ? 'Un momento...' : 'Ahora no'}
                </Button>
              </>
            )}
          </div>
          {!notEnoughOptions && (
            <p id={skipHintId} className="text-sm leading-relaxed text-muted sm:max-w-md">
              Con «Ahora no» no volveremos a preguntártelo; podrás elegirlos en tu perfil.
            </p>
          )}
        </div>
      </div>
    </main>
  );
}
