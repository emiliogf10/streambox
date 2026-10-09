import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from '../Button';
import type { User } from '../../lib/types';
import { PasswordForm } from './PasswordForm';
import { ProfileCard } from './ProfileCard';
import { UsernameForm } from './UsernameForm';

/** Ajuste editable de la tarjeta: qué formulario está abierto (como mucho uno). */
type EditableSetting = 'username' | 'password';

/** Propiedades de {@link AccountRow}. */
interface AccountRowProps {
  label: string;
  /** Valor actual (texto). */
  value: ReactNode;
  /** Nota bajo el valor (p. ej. por qué no se puede cambiar o qué pasa al cambiarlo). */
  note?: string;
  /** Botón «Cambiar» (solo en los datos editables, y oculto mientras su formulario está abierto). */
  action?: ReactNode;
  /** Formulario de edición, cuando está abierto. */
  children?: ReactNode;
}

/**
 * Fila «etiqueta: valor» de la tarjeta (un grupo `dt`/`dd` de la lista de
 * descripción). El botón y el formulario van DENTRO del `dd`: un `div` de un
 * `dl` solo puede contener `dt` y `dd`.
 */
function AccountRow({ label, value, note, action, children }: AccountRowProps) {
  return (
    <div className="border-t border-line py-3 first:border-t-0 first:pt-0 last:pb-0">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="mt-0.5">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <span className="min-w-0 break-all text-white">{value}</span>
          {action}
        </div>
        {note && <p className="mt-1 text-xs leading-relaxed text-muted">{note}</p>}
        {children}
      </dd>
    </div>
  );
}

/** Propiedades de {@link AccountCard}. */
interface AccountCardProps {
  user: User;
}

/**
 * Tarjeta «Cuenta» del perfil: nombre, correo y contraseña, con «Editar perfil»
 * en línea.
 *
 * - **Nombre**: «Cambiar» abre {@link UsernameForm}.
 * - **Correo**: no se puede cambiar (decisión del autor: es con lo que se inicia
 *   sesión y el `subject` del JWT; cambiarlo sin verificar el correo nuevo abriría
 *   la puerta a robos de cuenta). Se dice al lado, para que nadie busque cómo hacerlo.
 * - **Contraseña**: no se muestra (no se conoce: el servidor solo guarda su hash)
 *   ni se pintan puntos, que parecían un campo editable; «Cambiar» abre
 *   {@link PasswordForm}.
 *
 * **Foco.** «Cambiar» abre el formulario debajo de su fila y el foco pasa a su
 * primer campo; mientras está abierto el botón se oculta (el formulario ya tiene
 * «Cancelar», y un «Cambiar» que cerrara sería confuso). Al cerrarse (guardar o
 * cancelar), el foco vuelve a ese botón: si no, quien usa teclado o lector de
 * pantalla se quedaría en el `body`, al principio de la página. Solo hay un
 * formulario abierto a la vez: abrir otro cierra el anterior (y se pierde lo
 * escrito en él, que es lo esperable al cambiar de tarea).
 */
export function AccountCard({ user }: AccountCardProps) {
  const [editing, setEditing] = useState<EditableSetting | null>(null);
  const usernameFormId = useId();
  const passwordFormId = useId();
  const usernameButtonRef = useRef<HTMLButtonElement>(null);
  const passwordButtonRef = useRef<HTMLButtonElement>(null);
  // Botón al que hay que devolver el foco cuando su formulario se cierre (tras el render que lo vuelve a pintar).
  const returnFocusTo = useRef<EditableSetting | null>(null);

  useEffect(() => {
    if (editing !== null || returnFocusTo.current === null) return;
    const target = returnFocusTo.current === 'username' ? usernameButtonRef : passwordButtonRef;
    returnFocusTo.current = null;
    target.current?.focus();
  }, [editing]);

  /** Cierra el formulario abierto y devuelve el foco a su botón «Cambiar». */
  const close = (setting: EditableSetting) => {
    returnFocusTo.current = setting;
    setEditing(null);
  };

  /**
   * Botón «Cambiar» de una fila. Su nombre accesible dice qué cambia («Cambiar
   * nombre») y empieza por el texto visible (WCAG 2.5.3: quien lo activa por voz
   * diciendo «Cambiar» lo encuentra).
   */
  const changeButton = (setting: EditableSetting, what: string) =>
    editing !== setting && (
      <Button
        ref={setting === 'username' ? usernameButtonRef : passwordButtonRef}
        variant="outline"
        className="px-4"
        aria-label={`Cambiar ${what}`}
        onClick={() => setEditing(setting)}
      >
        Cambiar
      </Button>
    );

  return (
    <ProfileCard title="Cuenta">
      <dl>
        <AccountRow label="Nombre" value={user.username} action={changeButton('username', 'nombre')}>
          {editing === 'username' && (
            <UsernameForm id={usernameFormId} currentUsername={user.username} onClose={() => close('username')} />
          )}
        </AccountRow>
        <AccountRow
          label="Correo"
          value={user.email}
          note="No se puede cambiar: es el dato con el que inicias sesión."
        />
        <AccountRow
          label="Contraseña"
          value={<span className="text-muted">No se muestra por seguridad.</span>}
          action={changeButton('password', 'contraseña')}
        >
          {editing === 'password' && <PasswordForm id={passwordFormId} onClose={() => close('password')} />}
        </AccountRow>
      </dl>
    </ProfileCard>
  );
}
