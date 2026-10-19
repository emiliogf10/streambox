package com.emilio.streambox.email;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bandeja de correo <b>en memoria</b>: guarda los correos en lugar de
 * enviarlos. Solo existe con los perfiles {@code test} y {@code e2e} (ver
 * {@link EmailSenderConfig}).
 *
 * <p>
 * Los tests de integración la leen para comprobar qué correo llegó y sacar el
 * token del enlace; el E2E la lee por HTTP con
 * {@code GET /api/admin/e2e/emails} ({@code E2eEmailInboxController}).
 * </p>
 *
 * <p>
 * <b>Segura entre hilos.</b> Con el envío asíncrono, los correos llegan desde
 * los hilos del ejecutor mientras el test (o la petición del E2E) los lee. Un
 * único cerrojo protege la cola, y su {@link Condition} permite
 * <em>esperar</em> a que llegue un correo ({@link #awaitSentTo}) sin
 * {@code Thread.sleep}: el hilo que espera se despierta en cuanto llega.
 * </p>
 *
 * <p>
 * <b>Acotada</b> a {@value #MAX_MESSAGES} correos (se descartan los más
 * antiguos): el backend del E2E vive toda la batería y no debe crecer sin
 * límite.
 * </p>
 */
public class MemoryEmailSender implements EmailSender {

    /** Correos que se conservan como mucho (los más antiguos se descartan). */
    static final int MAX_MESSAGES = 1000;

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    /** Del más reciente (primero) al más antiguo. */
    private final Deque<SentEmail> messages = new ArrayDeque<>();

    /**
     * Crea la bandeja.
     *
     * @param clock reloj de la aplicación (fecha de cada correo)
     */
    public MemoryEmailSender(Clock clock) {
        this.clock = clock;
    }

    /**
     * Guarda el correo en la bandeja y despierta a quien esté esperando.
     *
     * @param message correo que se «envía»
     */
    @Override
    public void send(EmailMessage message) {
        SentEmail sent = new SentEmail(message.to(), message.subject(), message.body(), clock.instant());
        lock.lock();
        try {
            messages.addFirst(sent);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeLast();
            }
            arrived.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Correos enviados a una dirección, del más reciente al más antiguo.
     *
     * @param to dirección del destinatario (se compara sin espacios exteriores
     *           y sin distinguir mayúsculas, como se normalizan los emails)
     * @return copia de los correos de esa dirección (vacía si no hay)
     */
    public List<SentEmail> sentTo(String to) {
        String normalized = normalize(to);
        lock.lock();
        try {
            return messages.stream().filter(sent -> sent.to().equals(normalized)).toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Espera a que una dirección tenga al menos {@code count} correos y los
     * devuelve (del más reciente al más antiguo).
     *
     * <p>
     * Pensado para los tests del envío asíncrono: espera sin sondear (el envío
     * despierta al hilo) y con un tope, para que un correo que no llega haga
     * fallar el test en lugar de colgarlo.
     * </p>
     *
     * @param to      dirección del destinatario
     * @param count   número mínimo de correos que se espera
     * @param timeout espera máxima
     * @return los correos de esa dirección; si se agota la espera, los que
     *         haya (menos de {@code count})
     * @throws InterruptedException si se interrumpe el hilo mientras espera
     */
    public List<SentEmail> awaitSentTo(String to, int count, Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            List<SentEmail> current = sentTo(to);
            while (current.size() < count && remaining > 0) {
                remaining = arrived.awaitNanos(remaining);
                current = sentTo(to);
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Todos los correos guardados, del más reciente al más antiguo.
     *
     * @return copia de la bandeja
     */
    public List<SentEmail> all() {
        lock.lock();
        try {
            return new ArrayList<>(messages);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Vacía la bandeja (los tests lo hacen antes de cada caso).
     */
    public void clear() {
        lock.lock();
        try {
            messages.clear();
        } finally {
            lock.unlock();
        }
    }

    private static String normalize(String to) {
        return to == null ? "" : to.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Un correo de la bandeja.
     *
     * @param to      destinatario
     * @param subject asunto
     * @param body    cuerpo en texto plano
     * @param sentAt  instante en que se «envió» (reloj de la aplicación)
     */
    public record SentEmail(String to, String subject, String body, Instant sentAt) {
    }
}
