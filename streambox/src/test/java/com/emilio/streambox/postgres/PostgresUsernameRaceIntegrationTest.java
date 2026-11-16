package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailMessage;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.repository.UsernameReservationRepository;
import com.emilio.streambox.service.RegistrationService;
import com.emilio.streambox.service.UserService;

/**
 * <b>Carrera entre el nombre de una cuenta pendiente y una reserva de
 * nombre</b> contra <b>PostgreSQL real</b> (hallazgo MEDIA de la revisión del
 * arreglo de NV-2).
 *
 * <h2>El fallo</h2>
 * <p>
 * El nombre de una cuenta pendiente lo protege el {@code UNIQUE} de
 * {@code users.username}; el de una reserva ({@code username_reservations}),
 * otra tabla. Una reserva sin confirmar es invisible para las demás
 * transacciones, así que con un registro {@code (N, V)}, donde {@code V} ya
 * tiene cuenta (solo reserva {@code N}), y a la vez otro registro
 * {@code (N, Z)} con un email nuevo o un {@code PATCH /api/users/me} a
 * {@code N}, <b>los dos salían bien</b> (202 y 202, o 202 y 200). Si {@code V}
 * no tenía cuenta, los dos insertaban {@code N} en {@code users} y el
 * {@code UNIQUE} frenaba siempre a uno. Ver salir bien los dos delataba que
 * {@code V} tenía cuenta.
 * </p>
 *
 * <h2>Cómo se fuerza el orden (sin depender del azar)</h2>
 * <p>
 * La primera petición se detiene <b>dentro de su transacción</b>, después de
 * hacer todo su trabajo y antes de confirmar: un espía de
 * {@link EmailDispatcher} (el registro programa su correo al final) o de
 * {@link UserRepository#saveAndFlush} (el cambio de nombre guarda al final) la
 * retiene con un cerrojo de Java. Entonces se lanza la segunda y se espera a
 * que termine (sin el arreglo, no ve nada y acaba) o a que se quede esperando
 * un bloqueo de PostgreSQL (con el arreglo, espera la fila del nombre). Solo
 * entonces se suelta la primera. Cada test prueba un orden; los dos órdenes de
 * cada variante cubren las dos formas de cruzarse.
 * </p>
 *
 * <p>
 * Con el arreglo, la segunda decide con lo que confirmó la primera y el
 * resultado es siempre el de una ejecución en serie: <b>exactamente una</b> de
 * las dos sale bien. Datos con commit real, limpiados antes y después. Sin
 * Docker la clase se omite.
 * </p>
 */
class PostgresUsernameRaceIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";

    /** Espera máxima de cada paso (evita colgar la suite si algo se bloquea). */
    private static final long TIMEOUT_SECONDS = 20;

    private static final String OK = "OK";
    private static final String CONFLICT = "409";

    @Autowired private RegistrationService registrationService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private EmailDispatcher emailDispatcher;
    @MockitoSpyBean private UserRepository spiedUsers;
    @MockitoSpyBean private UsernameReservationRepository spiedReservations;

    private ExecutorService pool;

    /** Llegada de la primera petición al punto de pausa. */
    private CountDownLatch paused;

    /** Permiso para que la primera petición siga y confirme. */
    private CountDownLatch resume;

    /** Destinatario del correo que retiene la primera petición (registro), o {@code null}. */
    private volatile String pauseOnEmailTo;

    /** Id de la cuenta cuyo {@code saveAndFlush} retiene la primera petición (cambio de nombre), o {@code null}. */
    private volatile Long pauseOnSaveOf;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        pool = Executors.newFixedThreadPool(3);
        paused = new CountDownLatch(1);
        resume = new CountDownLatch(1);
        pauseOnEmailTo = null;
        pauseOnSaveOf = null;

        doAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            if (message.to().equals(pauseOnEmailTo)) {
                pauseHere();
            }
            return invocation.callRealMethod();
        }).when(emailDispatcher).sendAfterCommit(any());

        // El espía envuelve un proxy de Spring Data (una interfaz): callRealMethod
        // no sirve; su respuesta por defecto es la que delega en el repositorio real.
        Answer<?> realRepository = mockingDetails(spiedUsers).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object saved = realRepository.answer(invocation);
            User user = invocation.getArgument(0);
            if (user.getId() != null && user.getId().equals(pauseOnSaveOf)) {
                pauseHere();
            }
            return saved;
        }).when(spiedUsers).saveAndFlush(any());
    }

    @AfterEach
    void tearDown() {
        resume.countDown();
        pool.shutdownNow();
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Variante 1: dos registros con el mismo nombre
    // ------------------------------------------------------------------

    /**
     * Primero {@code (N, V)} con {@code V} ya registrado (solo reserva
     * {@code N}); mientras no confirma, {@code (N, Z)} con {@code Z} nuevo. Sin
     * el arreglo, {@code (N, Z)} no veía la reserva, creaba su pendiente y los
     * dos respondían 202. Con él, espera a la reserva y da 409.
     */
    @Test
    void dosRegistrosConElMismoNombreNuncaDan202Y202SiElPrimeroEsDeUnEmailRegistrado() throws Exception {
        String existing = saveUser("v1_titular", Role.USER).getEmail();
        pauseOnEmailTo = existing; // el aviso «Ya tienes una cuenta», al final de su transacción

        List<String> outcomes = race(() -> register("carrera_v1a", existing),
                () -> register("carrera_v1a", "z1a@nuevo.test"));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertTrue(userRepository.findByEmail("z1a@nuevo.test").isEmpty(), "no se creó la pendiente de Z");
    }

    /**
     * El orden contrario: primero {@code (N, Z)} con {@code Z} nuevo (crea la
     * pendiente {@code N} y no confirma); después {@code (N, V)}. Sin el
     * arreglo, {@code (N, V)} no veía la pendiente, reservaba {@code N} y los
     * dos respondían 202. Con él, espera y da el 409 del nombre de la
     * pendiente.
     */
    @Test
    void dosRegistrosConElMismoNombreNuncaDan202Y202SiElPrimeroEsDeUnEmailNuevo() throws Exception {
        String existing = saveUser("v1b_titular", Role.USER).getEmail();
        pauseOnEmailTo = "z1b@nuevo.test"; // el correo de verificación de la pendiente nueva

        List<String> outcomes = race(() -> register("carrera_v1b", "z1b@nuevo.test"),
                () -> register("carrera_v1b", existing));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("carrera_v1b", userRepository.findByEmail("z1b@nuevo.test").orElseThrow().getUsername());
    }

    // ------------------------------------------------------------------
    // Variante 2: un registro y un cambio de nombre al mismo nombre
    // ------------------------------------------------------------------

    /**
     * Primero el registro {@code (N, V)} con {@code V} ya registrado; mientras
     * no confirma, otra cuenta (verificada, del atacante) se cambia el nombre a
     * {@code N}. Sin el arreglo, el cambio no veía la reserva y salían bien los
     * dos (202 y 200). Con él, el cambio espera y da 409 (la reserva es de
     * otro email).
     */
    @Test
    void unRegistroYUnCambioDeNombreNuncaSalenBienLosDosSiElRegistroVaPrimero() throws Exception {
        String existing = saveUser("v2a_titular", Role.USER).getEmail();
        User attacker = saveUser("v2a_atacante", Role.USER);
        pauseOnEmailTo = existing;

        List<String> outcomes = race(() -> register("carrera_v2a", existing),
                () -> rename(attacker, "carrera_v2a"));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("v2a_atacante", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
    }

    /**
     * El orden contrario: primero el cambio de nombre a {@code N} (sin
     * confirmar); después el registro {@code (N, V)}. Sin el arreglo, el
     * registro no veía el nombre nuevo de la cuenta, reservaba {@code N} y
     * salían bien los dos. Con él, el registro espera y da el 409 del nombre.
     * Además, el cambio de nombre no deja ninguna reserva: su fila de cerrojo
     * se quitó antes de confirmar (y el registro, que esperaba para insertar
     * esa fila, pudo crearla y después se deshizo con su 409).
     */
    @Test
    void unRegistroYUnCambioDeNombreNuncaSalenBienLosDosSiElCambioVaPrimero() throws Exception {
        String existing = saveUser("v2b_titular", Role.USER).getEmail();
        User attacker = saveUser("v2b_atacante", Role.USER);
        pauseOnSaveOf = attacker.getId();

        List<String> outcomes = race(() -> rename(attacker, "carrera_v2b"),
                () -> register("carrera_v2b", existing));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("carrera_v2b", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM username_reservations WHERE username = ?",
                Integer.class, "carrera_v2b"), "el cambio de nombre no deja reserva");
    }

    // ------------------------------------------------------------------
    // Revisión de QA: la otra mitad de cada comparación y dos PATCH a la vez
    // ------------------------------------------------------------------

    /**
     * La otra mitad de la variante 2 (revisión de QA): el registro que va
     * primero es de un email <b>nuevo</b> (crea la pendiente {@code N} y no
     * confirma). El cambio de nombre a {@code N} espera y da 409, igual que
     * cuando el registro es de un email con cuenta
     * ({@link #unRegistroYUnCambioDeNombreNuncaSalenBienLosDosSiElRegistroVaPrimero}):
     * mismo resultado en los dos casos, que es lo que impide el oráculo.
     */
    @Test
    void unRegistroConEmailNuevoYUnCambioDeNombreDanLoMismoQueConUnEmailRegistrado() throws Exception {
        User attacker = saveUser("v3a_atacante", Role.USER);
        pauseOnEmailTo = "z3a@nuevo.test";

        List<String> outcomes = race(() -> register("carrera_v3a", "z3a@nuevo.test"),
                () -> rename(attacker, "carrera_v3a"));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("v3a_atacante", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
        assertEquals("carrera_v3a", userRepository.findByEmail("z3a@nuevo.test").orElseThrow().getUsername());
    }

    /**
     * El orden contrario con un email nuevo: primero el cambio de nombre (sin
     * confirmar), después el registro {@code (N, Z)}. El registro espera la
     * fila del nombre y da 409, como con un email registrado
     * ({@link #unRegistroYUnCambioDeNombreNuncaSalenBienLosDosSiElCambioVaPrimero}),
     * sin crear la pendiente ni dejar reserva.
     */
    @Test
    void unCambioDeNombreYUnRegistroConEmailNuevoDanLoMismoQueConUnEmailRegistrado() throws Exception {
        User attacker = saveUser("v3b_atacante", Role.USER);
        pauseOnSaveOf = attacker.getId();

        List<String> outcomes = race(() -> rename(attacker, "carrera_v3b"),
                () -> register("carrera_v3b", "z3b@nuevo.test"));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("carrera_v3b", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
        assertTrue(userRepository.findByEmail("z3b@nuevo.test").isEmpty(), "no se creó la pendiente de Z");
        assertEquals(0, reservationRows("carrera_v3b"), "ni el cambio ni el registro deshecho dejan reserva");
    }

    /**
     * Dos {@code PATCH /api/users/me} al mismo nombre libre a la vez (sugerido
     * por {@code backend}). Los dos crean la fila de cerrojo del nombre: el
     * segundo espera al primero en el {@code INSERT}, y cuando el primero la
     * quita y confirma, la crea él, ve el nombre ya en la cuenta del primero y
     * da 409. Comprueba que el cerrojo no provoca un interbloqueo ni un 500
     * (antes de él, el {@code UNIQUE} de {@code users.username} ya frenaba al
     * segundo) y que, al terminar, no queda ninguna fila de reserva: ni la del
     * ganador ni la del perdedor, que se deshizo con su 409.
     */
    @Test
    void dosCambiosDeNombreAlMismoNombreALaVezSoloGanaUnoYNoDejanReserva() throws Exception {
        User first = saveUser("v4_primera", Role.USER);
        User second = saveUser("v4_segunda", Role.USER);
        pauseOnSaveOf = first.getId();

        List<String> outcomes = race(() -> rename(first, "carrera_v4"), () -> rename(second, "carrera_v4"));

        assertEquals(List.of(OK, CONFLICT), outcomes);
        assertEquals("carrera_v4", userRepository.findById(first.getId()).orElseThrow().getUsername());
        assertEquals("v4_segunda", userRepository.findById(second.getId()).orElseThrow().getUsername());
        assertEquals(0, reservationRows("carrera_v4"), "«Editar perfil» no deja reservas");
    }

    /**
     * Dos registros a la vez con el <b>mismo email nuevo</b> y nombres
     * distintos: el segundo pierde la carrera por el email (su {@code INSERT
     * ... ON CONFLICT DO NOTHING} espera al primero y no crea nada), vuelve al
     * punto de guardado sin soltar su nombre y repite la decisión, que ya ve la
     * cuenta del primero. Los dos responden 202, queda una sola cuenta (con el
     * nombre del primero) y el nombre del segundo queda reservado a ese email,
     * como en cualquier registro con un email que ya tiene cuenta. (Antes de
     * MEDIA-A, este camino deshacía la transacción y volvía a reservar en otra:
     * ver {@link #conUnEmailNuevoLaCarreraPorElEmailNoSueltaElNombreDelPerdedor}.)
     */
    @Test
    void quienPierdeLaCarreraPorElEmailRespondeIgualYDejaSuNombreReservado() throws Exception {
        pauseOnEmailTo = "z5@nuevo.test";

        List<String> outcomes = race(() -> register("carrera_v5a", "z5@nuevo.test"),
                () -> register("carrera_v5b", "z5@nuevo.test"));

        assertEquals(List.of(OK, OK), outcomes);
        assertEquals("carrera_v5a", userRepository.findByEmail("z5@nuevo.test").orElseThrow().getUsername());
        assertTrue(userRepository.findByUsername("carrera_v5b").isEmpty());
        assertEquals(1, reservationRows("carrera_v5b"), "el nombre del perdedor queda reservado");
        assertEquals(CONFLICT, register("carrera_v5b", "otro5@nuevo.test"));
    }

    // ------------------------------------------------------------------
    // MEDIA-A: el registro no suelta el nombre a mitad de su decisión
    // ------------------------------------------------------------------

    /**
     * Referencia de MEDIA-A, con {@code V} <b>verificado</b>: el atacante
     * (cuenta propia {@code A}) envía a la vez {@code (N1, V)} y
     * {@code (N2, V)} y, detrás, {@code PATCH /api/users/me {username: N2}}
     * desde {@code A}, que espera por la fila de {@code N2}. Los dos registros
     * responden 202 y reservan su nombre, así que el cambio de nombre da 409.
     * Los dos tests siguientes exigen exactamente esto mismo cuando {@code V}
     * no tiene cuenta verificada.
     */
    @Test
    void conUnEmailVerificadoDosRegistrosYUnCambioDeNombreDetrasDan202202Y409() throws Exception {
        String verified = saveUser("v6_titular", Role.USER).getEmail();
        User attacker = saveUser("v6_atacante", Role.USER);
        pauseOnEmailTo = verified; // el aviso «Ya tienes una cuenta» del primero

        List<String> outcomes = raceBehindName("carrera_v6b",
                () -> register("carrera_v6a", verified),
                () -> register("carrera_v6b", verified),
                () -> rename(attacker, "carrera_v6b"));

        assertEquals(List.of(OK, OK, CONFLICT), outcomes);
        assertEquals("v6_atacante", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
    }

    /**
     * MEDIA-A, carrera por el email: {@code V} es <b>nuevo</b>. El primero
     * crea la pendiente de {@code V} y no confirma; el segundo choca con ella
     * en {@code users.email}. Antes, el segundo deshacía su transacción (y con
     * ella la reserva de {@code N2}) y volvía a reservar en otra: el
     * {@code PATCH}, que esperaba por {@code N2}, entraba en medio y daba 200
     * (y el registro, 409). Ese 200 delataba que {@code V} no tenía cuenta.
     * Ahora el segundo conserva {@code N2} durante toda su decisión y sigue el
     * camino «el email ya tiene cuenta»: el mismo 202, 202 y 409 que con
     * {@code V} verificado.
     */
    @Test
    void conUnEmailNuevoLaCarreraPorElEmailNoSueltaElNombreDelPerdedor() throws Exception {
        User attacker = saveUser("v7_atacante", Role.USER);
        String fresh = "v7@nuevo.test";
        pauseOnEmailTo = fresh; // el correo de verificación de la pendiente del primero

        List<String> outcomes = raceBehindName("carrera_v7b",
                () -> register("carrera_v7a", fresh),
                () -> register("carrera_v7b", fresh),
                () -> rename(attacker, "carrera_v7b"));

        assertEquals(List.of(OK, OK, CONFLICT), outcomes);
        assertEquals("v7_atacante", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
        assertEquals("carrera_v7a", userRepository.findByEmail(fresh).orElseThrow().getUsername());
        assertEquals(1, reservationRows("carrera_v7b"), "el nombre del perdedor queda reservado");
    }

    /**
     * MEDIA-A, cuenta que cambia mientras se bloquea: {@code V} tiene una
     * pendiente <b>sustituible</b>. El primero la bloquea, la borra, crea la
     * suya y no confirma; el segundo esperaba ese bloqueo y, al conseguirlo, la
     * fila ya no existe. Antes, deshacía toda la transacción para repetirla y
     * el {@code PATCH} se quedaba {@code N2} en medio (200). Ahora repite la
     * decisión sin soltar {@code N2} (vuelve a un punto de guardado) y ve la
     * pendiente nueva: 202, 202 y 409, como con {@code V} verificado.
     */
    @Test
    void conUnaPendienteSustituibleLaCuentaQueCambiaNoSueltaElNombre() throws Exception {
        User attacker = saveUser("v8_atacante", Role.USER);
        String victim = "v8@pendiente.test";
        User stale = saveReplaceablePending("v8_vieja", victim);
        pauseOnEmailTo = victim; // el correo de verificación de la pendiente que la sustituye

        List<String> outcomes = raceBehindName("carrera_v8b",
                () -> register("carrera_v8a", victim),
                () -> register("carrera_v8b", victim),
                () -> rename(attacker, "carrera_v8b"));

        assertEquals(List.of(OK, OK, CONFLICT), outcomes);
        assertEquals("v8_atacante", userRepository.findById(attacker.getId()).orElseThrow().getUsername());
        User replaced = userRepository.findByEmail(victim).orElseThrow();
        assertEquals("carrera_v8a", replaced.getUsername());
        assertTrue(userRepository.findById(stale.getId()).isEmpty(), "la sustituible se borró una sola vez");
        assertEquals(1, reservationRows("carrera_v8b"), "el nombre del segundo queda reservado");
    }

    /**
     * Carrera de tres: {@code first} se detiene dentro de su transacción;
     * {@code second} se queda esperando un bloqueo (el email o la cuenta que
     * tiene {@code first}) <b>después</b> de reclamar {@code gatedName};
     * {@code third} se queda esperando la fila de {@code gatedName}. Entonces
     * se suelta {@code first}. Devuelve los tres resultados en ese orden.
     *
     * <p>
     * <b>La compuerta</b> hace determinista el fallo del código antiguo: allí
     * {@code second} deshacía su transacción y volvía a llamar a
     * {@code reserve(gatedName)} en otra nueva, en carrera con {@code third}.
     * La segunda llamada a {@code reserve} con ese nombre espera aquí a que
     * {@code third} termine, que es el orden que delataba el email. Con el
     * arreglo, {@code reserve} se llama una sola vez y la compuerta no actúa.
     * </p>
     */
    private List<String> raceBehindName(String gatedName, Callable<String> first, Callable<String> second,
            Callable<String> third) throws Exception {
        CountDownLatch thirdDone = new CountDownLatch(1);
        AtomicInteger reserveCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (gatedName.equals(invocation.getArgument(0)) && reserveCalls.incrementAndGet() > 1) {
                thirdDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(spiedReservations).reserve(anyString(), anyString(), any(), any());

        Future<String> firstResult = pool.submit(first);
        assertTrue(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "la primera petición no llegó a la pausa");
        Future<String> secondResult = pool.submit(second);
        awaitWaitingForLocks(1, secondResult);
        Future<String> thirdResult = pool.submit(() -> {
            try {
                return third.call();
            } finally {
                thirdDone.countDown();
            }
        });
        awaitWaitingForLocks(2, thirdResult);
        resume.countDown();

        String thirdOutcome = result(thirdResult);
        return List.of(result(firstResult), result(secondResult), thirdOutcome);
    }

    /**
     * Espera a que haya al menos {@code expected} conexiones esperando un
     * bloqueo. Si la petición recién lanzada termina antes, el test falla: en
     * este escenario tiene que esperar (las dos ramas, con y sin el arreglo).
     */
    private void awaitWaitingForLocks(int expected, Future<?> launched) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (launched.isDone()) {
                throw new AssertionError("la petición terminó sin esperar ningún bloqueo");
            }
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no se llegó a " + expected + " conexiones esperando un bloqueo");
    }

    /**
     * Cuenta pendiente <b>sustituible</b>: creada hace dos días por el registro
     * (su único token de verificación es de su fecha de alta) y con ese token
     * caducado hace un día sin usar.
     */
    private User saveReplaceablePending(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        User saved = userRepository.saveAndFlush(user);
        Instant createdAt = Instant.now().minus(Duration.ofDays(2));
        jdbc.update("UPDATE users SET created_at = ?, email_verified_at = NULL WHERE id = ?",
                Timestamp.from(createdAt), saved.getId());
        jdbc.update("INSERT INTO account_tokens (user_id, purpose, token_hash, created_at, expires_at)"
                        + " VALUES (?, 'EMAIL_VERIFICATION', ?, ?, ?)",
                saved.getId(), String.format("%064x", saved.getId()), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(Duration.ofDays(1))));
        return saved;
    }

    /** Filas de {@code username_reservations} con ese nombre (vigentes o no). */
    private int reservationRows(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM username_reservations WHERE username = ?",
                Integer.class, username);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Lanza {@code first}, espera a que se detenga dentro de su transacción,
     * lanza {@code second}, espera a que termine o a que se quede esperando un
     * bloqueo, suelta a {@code first} y devuelve los dos resultados en ese
     * orden.
     */
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        Future<String> firstResult = pool.submit(first);
        assertTrue(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "la primera petición no llegó a la pausa");

        Future<String> secondResult = pool.submit(second);
        awaitDoneOrWaitingForLock(secondResult);
        resume.countDown();

        return List.of(result(firstResult), result(secondResult));
    }

    /** Punto de pausa de la primera petición (solo la primera vez). */
    private void pauseHere() throws InterruptedException {
        if (paused.getCount() == 0) {
            return;
        }
        paused.countDown();
        if (!resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("nadie soltó la pausa");
        }
    }

    /**
     * Espera a que la petición termine (sin el arreglo, no espera a nadie) o a
     * que alguna conexión de la base de datos esté esperando un bloqueo (con
     * el arreglo, la fila del nombre). La primera petición está parada en Java,
     * no en un bloqueo, así que no cuenta.
     */
    private void awaitDoneOrWaitingForLock(Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return;
            }
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("la segunda petición ni terminó ni se quedó esperando un bloqueo");
    }

    private String register(String username, String email) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(PASSWORD);
        try {
            registrationService.register(request);
            return OK;
        } catch (UserAlreadyExistsException e) {
            return CONFLICT;
        }
    }

    private String rename(User user, String username) {
        try {
            userService.updateProfile(user.getId(), new UpdateProfileRequest(username, null, null, null, null));
            return OK;
        } catch (UserAlreadyExistsException e) {
            return CONFLICT;
        }
    }

    /** Resultado de un hilo; si lanzó otra excepción (un 500 en la vida real), el test falla con ella. */
    private static String result(Future<String> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("La petición terminó con un error: " + e.getCause(), e.getCause());
        }
    }
}
