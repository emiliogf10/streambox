package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

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
    private final Map<String, ArrayDeque<Event>> events = new HashMap<>();
    private int operations;

    /** Identificador del siguiente evento; único en este contador y creciente. */
    private long nextEventId;

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
        return acquire(key, max) > 0;
    }

    /**
     * Como {@link #tryAcquire(String, int)}, pero devuelve el número de orden
     * del evento registrado dentro de la ventana.
     *
     * <p>
     * Comprobar y registrar ocurren en una sola operación sincronizada. Si se
     * hiciera en dos llamadas ({@link #count(String)} y después
     * {@link #record(String)}), dos peticiones simultáneas podrían ver la misma
     * cuenta y pasar las dos el límite (condición de carrera
     * <i>check-then-act</i>). Así, aunque lleguen cien a la vez, solo
     * {@code max} obtienen un número y cada una obtiene uno distinto.
     * </p>
     *
     * @param key clave a contabilizar
     * @param max número máximo de eventos permitidos dentro de la ventana
     * @return número de eventos de la clave en la ventana contando este (de 1 a
     *         {@code max}), o {@code 0} si se ha alcanzado el límite (el evento
     *         rechazado no se registra)
     */
    public synchronized int acquire(String key, int max) {
        return reserve(key, max).map(Reservation::number).orElse(0);
    }

    /**
     * Como {@link #acquire(String, int)}, pero devuelve también un
     * identificador del evento para poder localizarlo después con
     * {@link #confirm(String, Reservation, int)}.
     *
     * @param key clave a contabilizar
     * @param max número máximo de eventos permitidos dentro de la ventana
     * @return el evento registrado, o vacío si se ha alcanzado el límite (el
     *         evento rechazado no se registra)
     */
    public synchronized Optional<Reservation> reserve(String key, int max) {
        ArrayDeque<Event> queue = prunedQueue(key, true);
        if (queue.size() >= max) {
            return Optional.empty();
        }
        Event event = newEvent();
        queue.addLast(event);
        return Optional.of(new Reservation(event.id(), queue.size()));
    }

    /**
     * Confirma un evento reservado con {@link #reserve(String, int)} y devuelve
     * su posición <b>actual</b> en la ventana.
     *
     * <p>
     * La posición puede no coincidir con el número que se obtuvo al reservar:
     * baja si mientras tanto han caducado eventos más antiguos. Y si el evento
     * ya no está (lo borró un {@link #reset(String)} o caducó), se vuelve a
     * registrar al final de la ventana, si cabe, con el mismo identificador:
     * así confirmar dos veces la misma reserva no la cuenta dos veces. Todo
     * ocurre en una sola operación sincronizada, por el mismo motivo que
     * {@link #acquire(String, int)}.
     * </p>
     *
     * @param key         clave del evento
     * @param reservation evento devuelto por {@link #reserve(String, int)}
     * @param max         número máximo de eventos permitidos dentro de la
     *                    ventana (solo se usa si hay que registrarlo de nuevo)
     * @return posición del evento en la ventana (1 es el más antiguo), o
     *         {@code 0} si ya no estaba y no cabe uno nuevo
     */
    public synchronized int confirm(String key, Reservation reservation, int max) {
        ArrayDeque<Event> queue = prunedQueue(key, true);
        int position = 0;
        for (Event event : queue) {
            position++;
            if (event.id() == reservation.id()) {
                return position;
            }
        }
        if (queue.size() >= max) {
            return 0;
        }
        // El evento original ya no está en la cola, así que reutilizar su
        // identificador no crea duplicados.
        queue.addLast(new Event(clock.millis(), reservation.id()));
        return queue.size();
    }

    /**
     * Registra un evento incondicionalmente.
     *
     * @param key clave a contabilizar
     */
    public synchronized void record(String key) {
        prunedQueue(key, true).addLast(newEvent());
    }

    /**
     * @param key clave consultada
     * @return número de eventos de la clave dentro de la ventana actual
     */
    public synchronized int count(String key) {
        ArrayDeque<Event> queue = prunedQueue(key, false);
        return queue == null ? 0 : queue.size();
    }

    /**
     * @param key clave consultada
     * @return tiempo que falta hasta que el evento más antiguo salga de la
     *         ventana (es decir, hasta que se libere un hueco); cero si no hay eventos
     */
    public synchronized Duration retryAfter(String key) {
        ArrayDeque<Event> queue = prunedQueue(key, false);
        if (queue == null || queue.isEmpty()) {
            return Duration.ZERO;
        }
        long remaining = queue.peekFirst().timestamp() + windowMillis - clock.millis();
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

    private Event newEvent() {
        return new Event(clock.millis(), nextEventId++);
    }

    private ArrayDeque<Event> prunedQueue(String key, boolean create) {

        if (++operations % PURGE_EVERY == 0) {
            purgeExpired();
        }

        ArrayDeque<Event> queue = events.get(key);
        if (queue == null) {
            if (!create) {
                return null;
            }
            queue = new ArrayDeque<>();
            events.put(key, queue);
        }

        long threshold = clock.millis() - windowMillis;
        while (!queue.isEmpty() && queue.peekFirst().timestamp() <= threshold) {
            queue.removeFirst();
        }
        return queue;
    }

    private void purgeExpired() {
        long threshold = clock.millis() - windowMillis;
        for (Iterator<ArrayDeque<Event>> it = events.values().iterator(); it.hasNext();) {
            ArrayDeque<Event> queue = it.next();
            if (queue.isEmpty() || queue.peekLast().timestamp() <= threshold) {
                it.remove();
            }
        }
    }

    /**
     * Evento registrado: el instante sirve para caducarlo y el identificador
     * para localizarlo (dos eventos del mismo milisegundo tienen el mismo
     * instante).
     */
    private record Event(long timestamp, long id) {
    }

    /**
     * Evento reservado con {@link SlidingWindowCounter#reserve(String, int)}.
     *
     * @param id     identificador único del evento en este contador
     * @param number número de orden del evento en la ventana en el momento de
     *               reservarlo (de 1 al máximo)
     */
    public record Reservation(long id, int number) {
    }
}
