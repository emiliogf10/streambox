package com.emilio.streambox.email;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Envía correos <b>después del commit</b> de la transacción actual y
 * <b>fuera del hilo de la petición</b>. Es lo que usan los servicios; nadie
 * llama a {@link EmailSender} directamente.
 *
 * <h2>Por qué después del commit</h2>
 * <p>
 * El correo de verificación lleva un token que se guarda en la misma
 * transacción. Si se enviara antes del commit y la transacción se deshiciera
 * (una carrera con otro registro, la base de datos caída...), el usuario
 * recibiría un enlace a un token que no existe. Al revés, una vez confirmado,
 * un fallo del correo no debe deshacer el alta: la cuenta existe y se puede
 * pedir otro enlace.
 * </p>
 *
 * <h2>Por qué en otro hilo</h2>
 * <p>
 * El registro responde lo mismo exista o no el email. Si la respuesta
 * esperase al servidor SMTP (cientos de milisegundos) solo cuando hay correo
 * que mandar, el <b>tiempo de respuesta</b> delataría lo que el cuerpo oculta.
 * Con el envío en un ejecutor aparte, la petición no espera al SMTP. El
 * ejecutor está acotado ({@link EmailSenderConfig}): si se llena, el correo se
 * descarta con un WARN en lugar de acumular memoria sin límite.
 * </p>
 *
 * <h2>Si el envío falla</h2>
 * <p>
 * Se registra un WARN con el asunto y el tipo de error, <b>nunca</b> con el
 * cuerpo (lleva el token) ni el destinatario (dato personal), y la excepción
 * no llega a nadie: la petición ya respondió (o, en modo síncrono, ya se
 * confirmó la transacción y un 500 mentiría).
 * </p>
 */
public class EmailDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailSender sender;
    private final Executor executor;

    /**
     * Crea el despachador.
     *
     * @param sender   emisor del entorno ({@link EmailSenderConfig})
     * @param executor ejecutor de los envíos; uno que ejecute en el mismo hilo
     *                 ({@code Runnable::run}) hace el envío síncrono, como en
     *                 los tests
     */
    public EmailDispatcher(EmailSender sender, Executor executor) {
        this.sender = sender;
        this.executor = executor;
    }

    /**
     * Programa el envío para cuando se confirme la transacción actual. Si la
     * transacción se deshace, no se envía nada.
     *
     * @param message correo que se envía
     * @throws IllegalStateException si no hay una transacción activa: enviar
     *         fuera de una sería un error de programación (el correo saldría
     *         aunque lo que lo motivó no se guardase)
     */
    public void sendAfterCommit(EmailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("sendAfterCommit necesita una transacción activa");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(message);
            }
        });
    }

    /**
     * Ejecuta en el ejecutor de los correos una tarea que <b>termina en un
     * correo</b> (o en nada) y cuyo resultado no debe influir en la respuesta
     * HTTP, ni siquiera en lo que tarda.
     *
     * <p>
     * Lo usa el reenvío del enlace de verificación: buscar la cuenta, mirar el
     * límite silencioso, invalidar los enlaces anteriores y guardar el nuevo
     * son media docena de sentencias para una cuenta pendiente y una sola para
     * un email sin cuenta. Hecho en la petición, esa diferencia de tiempo
     * delataba qué emails tienen una cuenta pendiente aunque el cuerpo fuera
     * el mismo. Aquí la petición solo encola la tarea y responde.
     * </p>
     *
     * <p>
     * La tarea abre su propia transacción (la de la petición, si la hay, no
     * llega a otro hilo) y desde ella puede llamar a {@link #sendAfterCommit}.
     * Comparte el ejecutor acotado de los correos: si la cola está llena, la
     * tarea se descarta con un WARN, como un correo. Sus errores tampoco
     * llegan a nadie: se registran (solo el tipo, sin datos) y ya.
     * </p>
     *
     * @param description qué hace la tarea, para el log (sin datos personales)
     * @param task        la tarea
     */
    public void runInBackground(String description, Runnable task) {
        try {
            executor.execute(() -> runSafely(description, task));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("No se ha ejecutado la tarea «{}»: la cola de envíos está llena", description);
        }
    }

    /**
     * Apaga el ejecutor (si es un grupo de hilos), esperando a los envíos en
     * curso. Lo llama Spring al cerrar el contexto.
     */
    public void close() {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            pool.shutdown();
        }
    }

    private void dispatch(EmailMessage message) {
        try {
            executor.execute(() -> sendSafely(message));
        } catch (RejectedExecutionException e) {
            LOGGER.warn("No se ha enviado el correo «{}»: la cola de envíos está llena", message.subject());
        }
    }

    private void sendSafely(EmailMessage message) {
        try {
            sender.send(message);
        } catch (RuntimeException e) {
            // Solo los tipos de error: el mensaje de una excepción de Jakarta
            // Mail puede incluir la dirección del destinatario.
            LOGGER.warn("No se ha podido enviar el correo «{}»: {} (causa: {})", message.subject(),
                    e.getClass().getSimpleName(), rootCause(e).getClass().getSimpleName());
        }
    }

    private static void runSafely(String description, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // Solo los tipos de error, por lo mismo que en sendSafely: el
            // mensaje puede llevar el email (p. ej. el de una consulta SQL).
            LOGGER.warn("Ha fallado la tarea «{}»: {} (causa: {})", description, e.getClass().getSimpleName(),
                    rootCause(e).getClass().getSimpleName());
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
