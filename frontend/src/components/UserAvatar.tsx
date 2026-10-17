import { avatarLabel, getAvatar } from '../lib/avatars';
import { avatarInitial } from '../lib/profile';

/**
 * Tamaños del avatar. Cada uno fija el círculo, el icono y la letra a la vez
 * para que la inicial y los iconos ocupen lo mismo y nada «salte» al cambiar.
 */
const SIZES = {
  /** Fila «Icono de perfil» de la tarjeta «Cuenta». */
  xs: { box: 'size-7 border text-sm', icon: 'size-4' },
  /** Botón del menú de usuario en la barra (dentro de su objetivo táctil de 44 px). */
  sm: { box: 'size-9 border-2 text-base', icon: 'size-5' },
  /** Opciones del selector y vista previa. */
  md: { box: 'size-12 border-2 text-lg', icon: 'size-6' },
  /** Cabecera de «Mi perfil». */
  lg: { box: 'size-16 border-2 text-2xl sm:size-20 sm:text-3xl', icon: 'size-8 sm:size-10' },
} as const;

/** Tamaño de {@link UserAvatar}. */
export type UserAvatarSize = keyof typeof SIZES;

/** Propiedades de {@link UserAvatar}. */
interface UserAvatarProps {
  /** Nombre del usuario: de él sale la inicial cuando no hay icono. */
  username: string;
  /** Clave del avatar (`User.avatar`); `null`, ausente o desconocida = la inicial. */
  avatar: string | null | undefined;
  size: UserAvatarSize;
  /**
   * `true` (por defecto) si al lado ya hay un nombre visible o el control que lo
   * contiene ya tiene nombre (el `<h1>` del perfil, «Menú de usuario», el texto
   * de la opción): entonces se oculta a los lectores de pantalla para no leerlo
   * dos veces. Con `false` se presenta como imagen con su nombre («Gato»).
   */
  decorative?: boolean;
  className?: string;
}

/**
 * Avatar del usuario: el icono que eligió en «Mi perfil» o, si no eligió ninguno
 * (o la clave no se conoce), la inicial de su nombre como siempre.
 *
 * `data-avatar` dice qué se ha pintado (la clave o `initial`): no lo usa la
 * interfaz, pero permite a los tests comprobarlo sobre un elemento localizado
 * por su rol sin depender de cómo dibuja `lucide-react` cada icono.
 */
export function UserAvatar({ username, avatar, size, decorative = true, className = '' }: UserAvatarProps) {
  const option = getAvatar(avatar);
  const { box, icon } = SIZES[size];
  const a11y = decorative
    ? ({ 'aria-hidden': true } as const)
    : ({ role: 'img', 'aria-label': option ? option.label : `${avatarLabel(null)}: ${avatarInitial(username)}` } as const);
  const colors = option ? `border-transparent ${option.className}` : 'border-accent/60 bg-accent/10 font-bold text-accent';

  return (
    <span
      {...a11y}
      data-avatar={option ? option.key : 'initial'}
      className={`inline-flex shrink-0 items-center justify-center rounded-full leading-none select-none ${box} ${colors} ${className}`}
    >
      {option ? <option.Icon aria-hidden="true" className={icon} /> : avatarInitial(username)}
    </span>
  );
}
