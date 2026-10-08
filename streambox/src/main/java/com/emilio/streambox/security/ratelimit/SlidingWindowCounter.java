package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * <h2>Memoria acotada</h2>
 * <p>
 * El contador nunca guarda más de {@code maxKeys} claves vivas, por muchas
 * claves distintas que le lleguen (IPs rotadas, emails inventados...). Las
 * claves están ordenadas por el instante de su <b>último evento registrado</b>
 * (las lecturas y los rechazos no cambian el orden), de modo que:
 * </p>
 * <ul>
 * <li>Las caducadas se eliminan <b>desde el principio del orden</b>, en cada
 * operación, hasta encontrar la primera viva: cuesta lo que se elimina y no
 * recorre todo el mapa (antes se recorría entero, bajo el cerrojo, cada 500
 * operaciones, y las claves no tenían tope).</li>
 * <li><b>Política al llenarse:</b> para insertar una clave nueva con el mapa
 * lleno se purgan primero las caducadas y, si sigue lleno, se <b>expulsa la
 * clave con la actividad más antigua</b>. Es una decisión consciente: se prefiere
 * olvidar claves viejas a rechazar las nuevas, porque rechazar permitiría a un
 * atacante agotar el mapa con claves falsas y dejar sin login ni registro a
 * todo el mundo. El precio es que, con un volumen de claves distintas superior
 * al tope dentro de una misma ventana, un atacante con muchísimas
 * direcciones podría hacer que se olvide el contador de otra clave; el tope
 * (100 000 por defecto) lo hace costoso, y las IPv6 se agrupan por /64
 * ({@link ClientAddress}) para que rotar direcciones no baste.</li>
 * </ul>
 *
 * <p>
 * Es seguro para uso concurrente.
 * </p>
 */
public final class SlidingWindowCounter {

    /** Capacidad inicial de la cola de cada clave: casi todas guardan muy pocos eventos. */
    private static final int INITIAL_QUEUE_CAPACITY = 4;

    private final long windowMillis;
    private final Clock clock;
    private final int maxKeys;

    /**
     * Eventos por clave. El orden de iteración es el del último evento
     * registrado de cada clave (la primera es la más antigua).
     */
    private final Map<String, ArrayDeque<Event>> events = new LinkedHashMap<>();

    /** Identificador del siguiente evento; único en este contador y creciente. */
    private long nextEventId;

    /**
     * @param window  duración de la ventana
     * @param clock   reloj usado para medir el tiempo (inyectable en tests)
     * @param maxKeys número máximo de claves vivas (mayor que cero)
     * @throws IllegalArgumentException si {@code maxKeys} no es positivo
     */
    public SlidingWindowCounter(Duration window, Clock clock, int maxKeys) {
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys debe ser positivo");
        }
        this.windowMillis = window.toMillis();
        this.clock = clock;
        this.maxKeys = maxKeys;
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
        ArrayDeque<Event> queue = liveQueue(key);
        int size = queue == null ? 0 : queue.size();
        if (size >= max) {
            return Optional.empty();
        }
        Event event = newEvent();
        append(key, queue, event);
        return Optional.of(new Reservation(event.id(), size + 1));
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
        ArrayDeque<Event> queue = liveQueue(key);
        int position = 0;
        if (queue != null) {
            for (Event event : queue) {
                position++;
                if (event.id() == reservation.id()) {
                    return position;
                }
            }
        }
        int size = queue == null ? 0 : queue.size();
        if (size >= max) {
            return 0;
        }
        // El evento original ya no está en la cola, así que reutilizar su
        // identificador no crea duplicados.
        append(key, queue, new Event(clock.millis(), reservation.id()));
        return size + 1;
    }

    /**
     * Registra un evento incondicionalmente.
     *
     * @param key clave a contabilizar
     */
    public synchronized void record(String key) {
        append(key, liveQueue(key), newEvent());
    }

    /**
     * @param key clave consultada
     * @return número de eventos de la clave dentro de la ventana actual
     */
    public synchronized int count(String key) {
        ArrayDeque<Event> queue = liveQueue(key);
        return queue == null ? 0 : queue.size();
    }

    /**
     * @param key clave consultada
     * @return tiempo que falta hasta que el evento más antiguo salga de la
     *         ventana (es decir, hasta que se libere un hueco); cero si no hay eventos
     */
    public synchronized Duration retryAfter(String key) {
        ArrayDeque<Event> queue = liveQueue(key);
        if (queue == null) {
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

    /**
     * @return número de claves que se guardan ahora mismo (nunca supera el
     *         tope); incluye las que han caducado y aún no se han purgado
     */
    public synchronized int size() {
        return events.size();
    }

    private Event newEvent() {
        return new Event(clock.millis(), nextEventId++);
    }

    /**
     * Cola de eventos vivos de la clave, o {@code null} si no tiene ninguno.
     * Purga antes las claves caducadas del principio del orden y recorta los
     * eventos caducados de esta; si se queda vacía, borra la clave.
     */
    private ArrayDeque<Event> liveQueue(String key) {
        purgeExpired();

        ArrayDeque<Event> queue = events.get(key);
        if (queue == null) {
            return null;
        }
        long threshold = clock.millis() - windowMillis;
        while (!queue.isEmpty() && queue.peekFirst().timestamp() <= threshold) {
            queue.removeFirst();
        }
        if (queue.isEmpty()) {
            events.remove(key);
            return null;
        }
        return queue;
    }

    /**
     * Añade el evento a la clave y la coloca al final del orden. Si la clave
     * es nueva ({@code queue == null}) y el mapa está lleno, hace sitio antes.
     */
    private void append(String key, ArrayDeque<Event> queue, Event event) {
        ArrayDeque<Event> target = queue;
        if (target == null) {
            makeRoom();
            target = new ArrayDeque<>(INITIAL_QUEUE_CAPACITY);
        } else {
            // Sacar y volver a meter la clave la lleva al final del orden
            // (put sobre una clave existente no la reordena).
            events.remove(key);
        }
        target.addLast(event);
        events.put(key, target);
    }

    /**
     * Elimina las claves caducadas del principio del orden, hasta la primera
     * viva. Como el orden es el del último evento de cada clave, todas las
     * siguientes tienen un último evento igual o más reciente y siguen vivas:
     * no hace falta recorrer el mapa entero.
     */
    private void purgeExpired() {
        long threshold = clock.millis() - windowMillis;
        for (Iterator<ArrayDeque<Event>> it = events.values().iterator(); it.hasNext();) {
            ArrayDeque<Event> queue = it.next();
            if (queue.isEmpty() || queue.peekLast().timestamp() <= threshold) {
                it.remove();
            } else {
                return;
            }
        }
    }

    /**
     * Garantiza que cabe una clave más: ya se han purgado las caducadas, así
     * que si sigue lleno se expulsa la clave con la actividad más antigua.
     */
    private void makeRoom() {
        while (events.size() >= maxKeys) {
            Iterator<ArrayDeque<Event>> it = events.values().iterator();
            it.next();
            it.remove();
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
