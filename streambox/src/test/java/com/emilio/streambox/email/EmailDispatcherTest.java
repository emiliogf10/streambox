package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tests unitarios de {@link EmailDispatcher}: el correo sale <b>solo tras el
 * commit</b>, <b>por el ejecutor</b> (no en el hilo que llama) y un fallo del
 * envío se registra sin el destinatario ni el cuerpo (que lleva el token).
 *
 * <p>
 * La transacción se simula con {@link TransactionSynchronizationManager}
 * (lo que hace Spring por dentro) y el ejecutor es una lista de tareas que el
 * test ejecuta cuando quiere: así se ve, sin hilos ni esperas, qué pasa en
 * cada momento.
 * </p>
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailDispatcherTest {

    private static final String TOKEN = "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY";
    private static final EmailMessage MESSAGE = new EmailMessage("victima@correo.test", "Asunto fijo",
            "Enlace: https://streambox.test/verificar-email#token=" + TOKEN);

    private EmailSender sender;
    private final List<Runnable> queued = new ArrayList<>();
    private EmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        sender = mock(EmailSender.class);
        Executor executor = queued::add;
        dispatcher = new EmailDispatcher(sender, executor);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Fuera de una transacción es un error de programación: el correo saldría aunque nada se guardase. */
    @Test
    void sinTransaccionActivaLanzaExcepcion() {
        assertThrows(IllegalStateException.class, () -> dispatcher.sendAfterCommit(MESSAGE));
        assertTrue(queued.isEmpty());
    }

    /**
     * Nada sale antes del commit; tras el commit, la tarea va al ejecutor (no
     * se envía en el hilo que confirma) y solo al ejecutarla llega al emisor.
     */
    @Test
    void elCorreoSaleTrasElCommitYPorElEjecutor() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit(MESSAGE);
        verify(sender, never()).send(any());
        assertTrue(queued.isEmpty());

        commit();

        verify(sender, never()).send(any());
        assertEquals(1, queued.size());
        queued.get(0).run();
        verify(sender).send(MESSAGE);
    }

    /** Si la transacción se deshace, no se envía nada (el token del enlace no existiría). */
    @Test
    void siLaTransaccionSeDeshaceNoSeEnviaNada() {
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit(MESSAGE);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertTrue(queued.isEmpty());
        verify(sender, never()).send(any());
    }

    /**
     * Un fallo del SMTP no llega a nadie (la petición ya respondió) y el WARN
     * solo lleva el asunto y los tipos de error: ni el destinatario (el mensaje
     * de Jakarta Mail puede incluirlo) ni el token.
     */
    @Test
    void unFalloDelEnvioSeRegistraSinDestinatarioNiToken(CapturedOutput output) {
        doThrow(new MailSendException("550 buzón victima@correo.test lleno"))
                .when(sender).send(any());
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit(MESSAGE);
        commit();

        queued.get(0).run();

        assertThat(output).contains("No se ha podido enviar el correo «Asunto fijo»")
                .contains("MailSendException")
                .doesNotContain("victima@correo.test")
                .doesNotContain(TOKEN);
    }

    /** Con la cola llena, el correo se descarta con un WARN, sin excepción hacia quien confirma. */
    @Test
    void conLaColaLlenaSeDescartaConUnAviso(CapturedOutput output) {
        dispatcher = new EmailDispatcher(sender, task -> {
            throw new RejectedExecutionException("cola llena");
        });
        TransactionSynchronizationManager.initSynchronization();
        dispatcher.sendAfterCommit(MESSAGE);

        commit();

        verify(sender, never()).send(any());
        assertThat(output).contains("la cola de envíos está llena").doesNotContain(TOKEN);
    }

    /** {@link EmailMessage#toString()} no lleva el cuerpo ni el destinatario: imprimirlo no filtra el token. */
    @Test
    void elToStringDelMensajeNoLlevaElCuerpoNiElDestinatario() {
        assertThat(MESSAGE.toString()).doesNotContain(TOKEN).doesNotContain("victima@correo.test");
    }

    /** Un salto de línea en el destinatario o el asunto (inyección de cabeceras) se rechaza. */
    @Test
    void unSaltoDeLineaEnLasCabecerasSeRechaza() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmailMessage("a@b.test\r\nBcc: c@d.test", "Asunto", "cuerpo"));
        assertThrows(IllegalArgumentException.class, () -> new EmailMessage("a@b.test", "Asunto\nBcc: x", "cuerpo"));
    }

    private static void commit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }
}
