package com.emilio.streambox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Programa la limpieza periódica de las reservas de nombre caducadas
 * ({@link UsernameReservations}, tablas {@code username_reservations} y
 * {@code username_reservation_owners}).
 *
 * <p>
 * Una reserva caducada ya no cuenta (las consultas comparan con
 * {@code expires_at}), y el registro borra la de un nombre en cuanto alguien
 * lo vuelve a pedir. Pero los nombres que nadie vuelve a pedir se quedarían en
 * la tabla para siempre: cada {@code interval} (1 h) se borran todas las que
 * ya caducaron. Así la limpieza no cambia ninguna respuesta de la API.
 * </p>
 *
 * <p>
 * Mismo patrón que {@code RefreshTokenCleanupConfig}: {@link SchedulingConfigurer}
 * para tomar el intervalo de {@link UsernameReservationCleanupProperties} ya
 * validado, y {@code @EnableScheduling} aquí (y no en la clase principal) para
 * que con {@code enabled=false} no quede ningún planificador en marcha por su
 * culpa. Repetir {@code @EnableScheduling} en dos configuraciones es inofensivo:
 * Spring registra su infraestructura una sola vez.
 * </p>
 *
 * <p>
 * Con varias réplicas cada una ejecuta su limpieza; es inofensivo (el
 * {@code DELETE} es idempotente), solo hace trabajo repetido.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "streambox.username-reservations.cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class UsernameReservationCleanupConfig implements SchedulingConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsernameReservationCleanupConfig.class);

    private final UsernameReservations reservations;

    private final UsernameReservationCleanupProperties properties;

    /**
     * Crea la configuración.
     *
     * @param reservations servicio que hace el borrado
     * @param properties   intervalo y espera inicial
     */
    public UsernameReservationCleanupConfig(UsernameReservations reservations,
            UsernameReservationCleanupProperties properties) {
        this.reservations = reservations;
        this.properties = properties;
    }

    /**
     * Registra la tarea con retardo fijo (la siguiente empieza
     * {@code interval} después de que acabe la anterior: nunca se solapan).
     *
     * @param registrar registro de tareas de Spring
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::cleanUp, properties.interval(),
                properties.initialDelay()));
    }

    /**
     * Una pasada de limpieza.
     *
     * <p>
     * Un fallo (base de datos caída, tiempo de espera) solo deja un aviso en el
     * log y la siguiente pasada lo vuelve a intentar: las reservas caducadas
     * que no se borren no cambian ninguna respuesta. Se captura aquí, en vez de
     * dejarlo al gestor de errores de Spring, para que el comportamiento no
     * dependa de cómo esté configurado el planificador.
     * </p>
     */
    void cleanUp() {
        try {
            int deleted = reservations.deleteExpired();
            if (deleted > 0) {
                LOGGER.info("Limpieza de reservas de nombre: {} caducadas borradas", deleted);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("No se pudieron borrar las reservas de nombre caducadas; se reintentará en la siguiente "
                    + "pasada", e);
        }
    }
}
