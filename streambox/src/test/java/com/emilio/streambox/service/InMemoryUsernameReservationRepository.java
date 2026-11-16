package com.emilio.streambox.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.emilio.streambox.repository.UsernameReservationRepository;

/**
 * Doble de {@link UsernameReservationRepository} en memoria para los tests
 * unitarios de los servicios ({@code RegistrationServiceTest},
 * {@code UserServiceTest}), que no tienen base de datos.
 *
 * <p>
 * Reproduce la semántica documentada en el repositorio real (crear sin alargar,
 * sustituir la caducada, dueños por hash del email, una caducada no cuenta, la
 * fila de cerrojo de {@code lock}/{@code deleteLock}) para que esos tests sigan
 * comprobando el comportamiento de los servicios con reservas de verdad y no
 * solo qué métodos se llaman. Que el SQL real cumple esa misma semántica lo
 * prueban {@code UsernameReservationQueryContract} (H2 y PostgreSQL). Los
 * bloqueos no se simulan: la concurrencia se prueba contra PostgreSQL.
 * </p>
 *
 * <p>
 * <b>Transacciones simuladas.</b> Un test que simule el gestor de
 * transacciones puede llamar a {@link #commit()} y {@link #rollback()} para
 * que una transacción deshecha se lleve también sus reservas, como en la base
 * de datos (sin ellas, una reserva de una transacción deshecha seguiría aquí).
 * Si nadie las llama, todo cuenta como confirmado.
 * </p>
 */
class InMemoryUsernameReservationRepository extends UsernameReservationRepository {

    /** Una reserva: su caducidad y los hashes de los emails dueños. */
    private record Reservation(Instant expiresAt, Set<String> owners) {

        Reservation copy() {
            return new Reservation(expiresAt, new HashSet<>(owners));
        }
    }

    private final Map<String, Reservation> byName = new HashMap<>();

    /** Estado en el último {@link #commit()}: al que vuelve {@link #rollback()}. */
    private Map<String, Reservation> committed = new HashMap<>();

    InMemoryUsernameReservationRepository() {
        // Sin JdbcClient: ningún método de la clase padre llega a usarlo.
        super(null);
    }

    @Override
    public boolean reserve(String username, String email, Instant now, Duration ttl) {
        String hash = emailHash(email);
        boolean created = lock(username, now, ttl);
        byName.get(username).owners().add(hash);
        return created;
    }

    @Override
    public boolean lock(String username, Instant now, Duration ttl) {
        Reservation current = byName.get(username);
        if (current == null || !current.expiresAt().isAfter(now)) {
            byName.put(username, new Reservation(now.plus(ttl), new HashSet<>()));
            return true;
        }
        return false;
    }

    @Override
    public void deleteLock(String username) {
        Reservation current = byName.get(username);
        if (current != null && current.owners().isEmpty()) {
            byName.remove(username);
        }
    }

    @Override
    public boolean isReserved(String username, Instant now) {
        Reservation current = byName.get(username);
        return current != null && current.expiresAt().isAfter(now);
    }

    @Override
    public boolean isReservedForOthers(String username, String email, Instant now) {
        return isReserved(username, now) && !byName.get(username).owners().contains(emailHash(email));
    }

    @Override
    public int deleteExpired(Instant now) {
        int before = byName.size();
        byName.values().removeIf(reservation -> !reservation.expiresAt().isAfter(now));
        return before - byName.size();
    }

    /** Confirma lo hecho desde el último {@code commit} o {@code rollback}. */
    void commit() {
        committed = copy(byName);
    }

    /** Deshace lo hecho desde el último {@code commit}. */
    void rollback() {
        byName.clear();
        byName.putAll(copy(committed));
    }

    /** Número de filas (vigentes o no), para comprobar que un cerrojo no queda. */
    int size() {
        return byName.size();
    }

    private static Map<String, Reservation> copy(Map<String, Reservation> source) {
        Map<String, Reservation> copy = new HashMap<>();
        source.forEach((name, reservation) -> copy.put(name, reservation.copy()));
        return copy;
    }
}
