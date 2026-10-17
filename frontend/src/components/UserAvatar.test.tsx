/**
 * Tests de `UserAvatar`: dibuja el icono de la clave o, sin clave o con una
 * desconocida, la inicial del nombre; es decorativo por defecto (no se lee dos
 * veces junto a un nombre visible) y, con `decorative={false}`, una imagen con
 * su nombre.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { UserAvatar } from './UserAvatar';

describe('UserAvatar', () => {
  it('con una clave conocida dibuja su icono (con sus colores) y no la inicial', () => {
    const { container } = render(<UserAvatar username="ana" avatar="cat" size="md" />);

    const avatar = container.firstElementChild!;
    expect(avatar).toHaveAttribute('data-avatar', 'cat');
    expect(avatar).toHaveClass('bg-pink-900', 'text-pink-200');
    expect(avatar.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    expect(avatar).not.toHaveTextContent('A');
  });

  it.each([null, undefined, 'dragon'])('con %j dibuja la inicial en mayúscula', (avatar) => {
    const { container } = render(<UserAvatar username="ana" avatar={avatar} size="sm" />);

    const drawn = container.firstElementChild!;
    expect(drawn).toHaveAttribute('data-avatar', 'initial');
    expect(drawn).toHaveTextContent(/^A$/);
    expect(drawn.querySelector('svg')).toBeNull();
  });

  it('es decorativo por defecto: no aparece en el árbol de accesibilidad', () => {
    render(<UserAvatar username="ana" avatar="moon" size="lg" />);

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('sin nombre visible al lado, es una imagen con el nombre del avatar o «Tu inicial: A»', () => {
    render(
      <>
        <UserAvatar username="ana" avatar="moon" size="xs" decorative={false} />
        <UserAvatar username="beto" avatar={null} size="xs" decorative={false} />
      </>,
    );

    expect(screen.getByRole('img', { name: 'Luna' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Tu inicial: B' })).toBeInTheDocument();
  });
});
