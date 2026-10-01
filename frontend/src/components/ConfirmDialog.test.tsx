/**
 * Tests de `Modal` y `ConfirmDialog` (diálogo de confirmación de acciones destructivas).
 *
 * Protegen la gestión del foco y del cierre: el foco inicial va al botón SEGURO
 * ("Cancelar"), Escape y el fondo cancelan salvo mientras hay una operación en
 * curso, solo "Confirmar" ejecuta la acción destructiva, el foco vuelve a quien
 * abrió el diálogo y los avisos se ven por encima del modal.
 *
 * Límite de jsdom: `showModal()` está simulado en `src/test/setup.ts`; el fondo
 * inerte y el foco atrapado del navegador real NO se pueden comprobar aquí.
 */
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToast } from '../context/ToastContext';
import { ConfirmDialog } from './ConfirmDialog';
import { Modal } from './Modal';

/** Renderiza el diálogo (abierto por defecto) con los avisos disponibles, como en la app. */
function renderDialog(props: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  const utils = render(
    <ToastProvider>
      <ConfirmDialog
        open
        title="¿Vaciar tu lista?"
        description="Se quitarán todas las películas. Esta acción no se puede deshacer."
        confirmLabel="Vaciar lista"
        onConfirm={onConfirm}
        onCancel={onCancel}
        {...props}
      />
    </ToastProvider>,
  );
  return { onConfirm, onCancel, ...utils };
}

describe('ConfirmDialog', () => {
  it('es un alertdialog modal con nombre (título) y descripción', () => {
    renderDialog();

    const dialog = screen.getByRole('alertdialog', { name: '¿Vaciar tu lista?' });

    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription('Se quitarán todas las películas. Esta acción no se puede deshacer.');
    expect(dialog).toHaveAttribute('open');
  });

  it('el foco inicial va a "Cancelar" (la opción segura), no a la acción destructiva', () => {
    renderDialog();

    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus();
  });

  it('cerrado (open=false) no es accesible ni se muestra', () => {
    renderDialog({ open: false });

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('bloquea el scroll de la página mientras está abierto y lo libera al cerrarse', () => {
    const { unmount } = renderDialog();
    expect(document.documentElement).toHaveClass('scroll-locked');

    unmount();

    expect(document.documentElement).not.toHaveClass('scroll-locked');
  });

  it('"Vaciar lista" llama a onConfirm (y solo a él)', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Vaciar lista' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('"Cancelar" llama a onCancel y nunca a onConfirm', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('pulsar Intro nada más abrir (sin leer) activa "Cancelar", no la acción destructiva', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.keyboard('{Enter}');

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('Escape cancela y el diálogo no se cierra por su cuenta: lo decide el padre', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.keyboard('{Escape}');

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
    // El navegador lo habría cerrado; el componente evita ese cierre (preventDefault) para que React siga siendo la fuente de verdad.
    expect(screen.getByRole('alertdialog')).toHaveAttribute('open');
  });

  it('un clic en el fondo (el propio <dialog>) cancela', async () => {
    const user = userEvent.setup();
    const { onCancel } = renderDialog();

    await user.click(screen.getByRole('alertdialog'));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('un clic dentro del contenido no cancela', async () => {
    const user = userEvent.setup();
    const { onCancel } = renderDialog();

    await user.click(screen.getByText('¿Vaciar tu lista?'));

    expect(onCancel).not.toHaveBeenCalled();
  });

  it('seleccionar texto dentro y soltar el ratón sobre el fondo NO cancela', () => {
    const { onCancel } = renderDialog();
    const dialog = screen.getByRole('alertdialog');

    fireEvent.mouseDown(screen.getByText('¿Vaciar tu lista?'));
    fireEvent.click(dialog); // el clic acaba (mouseup) en el fondo, pero empezó dentro

    expect(onCancel).not.toHaveBeenCalled();
  });

  describe('mientras hay una operación en curso (busy)', () => {
    it('bloquea ambos botones y muestra "Procesando..."', () => {
      renderDialog({ busy: true });

      expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Procesando...' })).toBeDisabled();
    });

    it('Escape y el clic en el fondo no cancelan', async () => {
      const user = userEvent.setup();
      const { onCancel } = renderDialog({ busy: true });

      await user.keyboard('{Escape}');
      await user.click(screen.getByRole('alertdialog'));

      expect(onCancel).not.toHaveBeenCalled();
    });
  });
});

describe('Modal: foco y avisos', () => {
  /** Un botón abre el diálogo y "Cancelar" lo cierra, como hace la página "Mi lista". */
  function Harness() {
    const [open, setOpen] = useState(false);
    return (
      <ToastProvider>
        <button type="button" onClick={() => setOpen(true)}>
          Abrir
        </button>
        <ConfirmDialog
          open={open}
          title="Título"
          description="Descripción"
          confirmLabel="Aceptar"
          onConfirm={() => setOpen(false)}
          onCancel={() => setOpen(false)}
        />
      </ToastProvider>
    );
  }

  it('al cerrarse devuelve el foco al botón que lo abrió', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole('button', { name: 'Abrir' }));
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus();
  });

  it('Escape también cierra y devuelve el foco (flujo completo con teclado)', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole('button', { name: 'Abrir' }));
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus();
  });

  it('los avisos (toasts) se pintan DENTRO del diálogo abierto para que no queden tapados por el fondo', async () => {
    function Emitter() {
      const toast = useToast();
      return (
        <button type="button" onClick={() => toast.error('No se pudo vaciar')}>
          Provocar fallo
        </button>
      );
    }
    const user = userEvent.setup();
    render(
      <ToastProvider>
        <Modal onClose={() => {}} labelledBy="t">
          <h2 id="t">Diálogo</h2>
          <Emitter />
        </Modal>
      </ToastProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Provocar fallo' }));

    const dialog = screen.getByRole('dialog', { name: 'Diálogo' });
    expect(within(dialog).getByText('No se pudo vaciar')).toBeInTheDocument();
  });
});
