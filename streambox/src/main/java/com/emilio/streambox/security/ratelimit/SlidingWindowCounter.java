package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Contador de eventos por clave en una ventana de tiempo deslizante, en memoria.
 *
 * <p>
 * Para cada clave (una IP, un email...) guarda los instantes de los eventos
 * ocurridos en los últimos {@code window}. Es la base tanto del límite de
 * peticiones por IP como del bloqueo de cuentas tras fallos de login.
 * </p>
 *
 * <p>
 * <strong>Limitaciones conocidas:</strong> el estado vive en la memoria de una
 * única instancia. Si la aplicación se ejecuta en varias réplicas, cada una
 * lleva su propia cuenta (el límite efectivo se multiplica) y un reinicio lo
 * pone a cero. Para ese escenario habría que mover el contador a un almacén
 * compartido (por ejemplo Redis).
 * </p>
 *
 * <p>
 * Es seguro para uso concurrente. Las claves caducadas se eliminan de forma
 * periódica para que un atacante no pueda llenar la memoria con claves
 * distintas.
 * </p>
 */
public final class SlidingWindowCounter {

    /** Cada cuántas operaciones se revisan y eliminan las claves caducadas. */
    private static final int PURGE_EVERY = 500;

    private final long windowMillis;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> events = new HashMap<>();
    private int operations;

    /**
     * @param window duración de la ventana
     * @param clock  reloj usado para medir el tiempo (inyectable en tests)
     */
    public SlidingWindowCounter(Duration window, Clock clock) {
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Registra un evento solo si la clave no ha alcanzado aún el máximo.
     *
     * @param key clave a contabilizar
     * @param max número máximo de eventos permitidos dentro de la ventana
     * @return {@code true} si el evento se registró; {@code false} si se ha
     *         superado el límite (el evento rechazado no se registra)
     */
    public synchronized boolean tryAcquire(String key, int max) {
        ArrayDeque<Long> queue = prunedQueue(key, true);
        if (queue.size() >= max) {
            return false;
        }
        queue.addLast(clock.millis());
        return true;
    }

    /**
     * Registra un evento incondicionalmente.
     *
     * @param key clave a contabilizar
     */
    public synchronized void record(String key) {
        prunedQueue(key, true).addLast(clock.millis());
    }

    /**
     * @param key clave consultada
     * @return número de eventos de la clave dentro de la ventana actual
     */
    public synchronized int count(String key) {
        ArrayDeque<Long> queue = prunedQueue(key, false);
        return queue == null ? 0 : queue.size();
    }

    /**
     * @param key clave consultada
     * @return tiempo que falta hasta que el evento más antiguo salga de la
     *         ventana (es decir, hasta que se libere un hueco); cero si no hay eventos
     */
    public synchronized Duration retryAfter(String key) {
        ArrayDeque<Long> queue = prunedQueue(key, false);
        if (queue == null || queue.isEmpty()) {
            return Duration.ZERO;
        }
        long remaining = queue.peekFirst() + windowMillis - clock.millis();
        return Duration.ofMillis(Math.max(remaining, 0));
    }

    /**
     * Olvida todos los eventos de una clave.
     *
     * @param key clave a reiniciar
     */
    public synchronized void reset(String key) {
        events.remove(key);
    }

    private ArrayDeque<Long> prunedQueue(String key, boolean create) {

        if (++operations % PURGE_EVERY == 0) {
            purgeExpired();
        }

        ArrayDeque<Long> queue = events.get(key);
        if (queue == null) {
            if (!create) {
                return null;
            }
            queue = new ArrayDeque<>();
            events.put(key, queue);
        }

        long threshold = clock.millis() - windowMillis;
        while (!queue.isEmpty() && queue.peekFirst() <= threshold) {
            queue.removeFirst();
        }
        return queue;
    }

    private void purgeExpired() {
        long threshold = clock.millis() - windowMillis;
        for (Iterator<ArrayDeque<Long>> it = events.values().iterator(); it.hasNext();) {
            ArrayDeque<Long> queue = it.next();
            if (queue.isEmpty() || queue.peekLast() <= threshold) {
                it.remove();
            }
        }
    }
}
