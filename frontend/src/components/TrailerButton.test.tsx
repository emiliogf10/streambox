/**
 * Tests de `TrailerButton` y su diálogo (`TrailerDialog`).
 *
 * Protegen: que el botón solo exista con un tráiler válido, que el `<iframe>`
 * reciba exactamente la dirección construida (nunca el texto de la API) con los
 * atributos de seguridad, que el diálogo tenga nombre accesible, que se cierre
 * con Escape y con su botón devolviendo el foco a «Ver tráiler», y que al
 * cerrarlo el `<iframe>` desaparezca (el vídeo se detiene).
 */
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ToastProvider } from '../context/ToastContext';
import { TrailerButton } from './TrailerButton';

const ID = 'zSWdZVtXT7E';
const TRAILER = `https://www.youtube.com/watch?v=${ID}`;

/** El diálogo usa `useToast` (zona de avisos dentro del modal): hace falta su proveedor. */
function renderButton(trailerUrl: string | null, variant?: 'light' | 'outline') {
  return render(
    <ToastProvider>
      <TrailerButton trailerUrl={trailerUrl} title="Dune" variant={variant} />
    </ToastProvider>,
  );
}

async function openTrailer() {
  const user = userEvent.setup();
  renderButton(TRAILER);
  const button = screen.getByRole('button', { name: 'Ver tráiler de Dune' });
  await user.click(button);
  return { user, button, dialog: screen.getByRole('dialog', { name: 'Tráiler de Dune' }) };
}

describe('TrailerButton', () => {
  it.each([[null], [''], ['https://vimeo.com/1'], [`https://youtu.be/${ID}`], ['javascript:alert(1)']])(
    'con %j no pinta nada',
    (trailerUrl) => {
      renderButton(trailerUrl);
      expect(screen.queryByRole('button')).not.toBeInTheDocument();
      expect(screen.queryByText(/tráiler/i)).not.toBeInTheDocument();
    },
  );

  it('con tráiler válido: botón «Ver tráiler» que anuncia que abre un diálogo; aún sin iframe', () => {
    renderButton(TRAILER);

    const button = screen.getByRole('button', { name: 'Ver tráiler de Dune' });
    expect(button).toHaveTextContent(/^Ver tráiler/);
    expect(button).toHaveAttribute('aria-haspopup', 'dialog');
    // No es un enlace: no navega, abre el diálogo.
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    // Nada se carga de YouTube hasta que se pide.
    expect(document.querySelector('iframe')).toBeNull();
  });

  it('variante: secundaria (borde) por defecto y principal (blanca) cuando se pide', () => {
    const { unmount } = renderButton(TRAILER);
    expect(screen.getByRole('button', { name: 'Ver tráiler de Dune' })).toHaveClass('border');
    unmount();

    renderButton(TRAILER, 'light');
    expect(screen.getByRole('button', { name: 'Ver tráiler de Dune' })).toHaveClass('bg-white');
  });
});

describe('TrailerDialog', () => {
  it('se abre como diálogo modal con título «Tráiler de <título>» y el foco en «Cerrar»', async () => {
    const { dialog } = await openTrailer();

    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('open');
    expect(within(dialog).getByRole('heading', { level: 2, name: 'Tráiler de Dune' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Cerrar el tráiler' })).toHaveFocus();
  });

  // Incidencia QA trailer-2: dentro del iframe de YouTube (otro origen) la
  // página no recibe Escape; la pista explica cómo salir (Mayús+Tab) y cerrar.
  it('muestra la pista de teclado para salir del reproductor y cerrar (oculta en táctiles)', async () => {
    const { dialog } = await openTrailer();

    const hint = within(dialog).getByText(
      'Pulsa Esc para cerrar. Si estás dentro del vídeo, sal antes con Mayús+Tab.',
    );
    expect(hint).toHaveClass('text-muted', 'pointer-coarse:hidden');
  });

  it('el iframe carga exactamente el reproductor sin cookies, con título y atributos de seguridad', async () => {
    const { dialog } = await openTrailer();

    const iframe = dialog.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe).toHaveAttribute('src', `https://www.youtube-nocookie.com/embed/${ID}?autoplay=1&rel=0`);
    expect(iframe).toHaveAttribute('title', 'Tráiler de Dune (YouTube)');
    expect(iframe).toHaveAttribute('loading', 'lazy');
    expect(iframe).toHaveAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    expect(iframe).toHaveAttribute('allow', 'autoplay; encrypted-media; picture-in-picture; fullscreen');
    expect(iframe).toHaveAttribute('allowfullscreen');
    expect(iframe).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox');
    // 16:9 con Tailwind (sin estilos en línea).
    expect(iframe?.parentElement).toHaveClass('aspect-video');
    expect(iframe).not.toHaveAttribute('style');
  });

  it('«Ver en YouTube» abre el enlace normalizado en una pestaña nueva y sin opener', async () => {
    const { dialog } = await openTrailer();

    const link = within(dialog).getByRole('link', { name: 'Ver en YouTube (se abre en una pestaña nueva)' });
    expect(link).toHaveAttribute('href', TRAILER);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('Escape lo cierra, el iframe desaparece (el vídeo se detiene) y el foco vuelve a «Ver tráiler»', async () => {
    const { user, button } = await openTrailer();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.querySelector('iframe')).toBeNull();
    expect(button).toHaveFocus();
  });

  it('el botón «Cerrar el tráiler» lo cierra igual, devolviendo el foco', async () => {
    const { user, button, dialog } = await openTrailer();

    await user.click(within(dialog).getByRole('button', { name: 'Cerrar el tráiler' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.querySelector('iframe')).toBeNull();
    expect(button).toHaveFocus();
  });

  it('cada apertura empieza de cero: al reabrir hay un iframe nuevo', async () => {
    const { user, button } = await openTrailer();
    const first = document.querySelector('iframe');

    await user.keyboard('{Escape}');
    await user.click(button);

    const second = document.querySelector('iframe');
    expect(second).not.toBeNull();
    expect(second).not.toBe(first);
  });

  it('el diálogo va fuera del contenedor del botón (portal): no altera la rejilla de acciones', async () => {
    const user = userEvent.setup();
    renderButton(TRAILER);
    const button = screen.getByRole('button', { name: 'Ver tráiler de Dune' });

    await user.click(button);

    const dialog = screen.getByRole('dialog', { name: 'Tráiler de Dune' });
    expect(dialog.parentElement).toBe(document.body);
    expect(button.parentElement?.querySelector('dialog')).toBeNull();
  });
});
