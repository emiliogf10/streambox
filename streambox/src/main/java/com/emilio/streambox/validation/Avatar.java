package com.emilio.streambox.validation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lista blanca de los avatares predefinidos que un usuario puede elegir como
 * icono de perfil ({@code PATCH /api/users/me}). Es el <b>único sitio</b> del
 * backend con las claves válidas: el validador {@link AvatarChoiceValidator}
 * y la documentación OpenAPI ({@code AvatarOpenApiCustomizer}) se generan a
 * partir de aquí.
 *
 * <p>
 * <b>Qué se guarda.</b> En {@code users.avatar} solo va la {@link #key() clave}
 * ({@code "rocket"}, {@code "cat"}...), nunca una imagen ni una URL: el
 * frontend ({@code frontend/src/lib/avatars.ts}, con las mismas 12 claves)
 * la traduce a un icono y un color. {@code NULL} significa «sin avatar» y la
 * interfaz enseña la inicial del nombre.
 * </p>
 *
 * <p>
 * <b>Por qué la lista está aquí y no en la base de datos.</b> Un {@code CHECK}
 * con las claves obligaría a escribir una migración (y a desplegar base de
 * datos y aplicación a la vez) cada vez que se añade un avatar, para un dato
 * que no participa en ninguna relación. La base de datos solo exige el
 * <em>formato</em> ({@code ck_users_avatar} de la {@code V7}: de 1 a 32
 * caracteres de {@code [a-z0-9-]}), que cumplen todas estas claves y
 * cualquiera futura escrita igual. Añadir un avatar es, por tanto, añadir una
 * constante aquí y su icono en el frontend.
 * </p>
 *
 * <p>
 * <b>Quitar un avatar</b> no rompe a quien ya lo tenga guardado: la API lo
 * sigue devolviendo tal cual en {@code UserResponse.avatar} (el frontend
 * pinta la inicial si no conoce la clave); lo único que deja de admitirse es
 * volver a elegirlo.
 * </p>
 *
 * <p>
 * Es un {@code enum} (y no un {@code Set<String>} suelto) para que cada clave
 * sea una constante con nombre que el compilador conoce, y el orden de
 * declaración es el que se publica en OpenAPI (el mismo que el selector del
 * frontend).
 * </p>
 */
public enum Avatar {

    ROCKET("rocket"),
    GHOST("ghost"),
    CAT("cat"),
    DOG("dog"),
    STAR("star"),
    FLAME("flame"),
    LEAF("leaf"),
    MOON("moon"),
    SUN("sun"),
    ZAP("zap"),
    MUSIC("music"),
    CROWN("crown");

    /** Índice por clave para que la comprobación no recorra la lista cada vez. */
    private static final Map<String, Avatar> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Avatar::key, Function.identity()));

    /** Claves en el orden de declaración (inmutable). */
    private static final List<String> KEYS = Arrays.stream(values()).map(Avatar::key).toList();

    private final String key;

    Avatar(String key) {
        this.key = key;
    }

    /**
     * Clave que se guarda en {@code users.avatar} y que viaja en el JSON.
     *
     * @return la clave, en minúsculas
     */
    public String key() {
        return key;
    }

    /**
     * Indica si un texto es exactamente la clave de un avatar de la lista.
     *
     * <p>
     * La comparación es exacta a propósito: no se recorta ni se pasa a
     * minúsculas. {@code "Cat"} o {@code " cat"} son errores del cliente, y
     * normalizarlos en silencio haría que la API aceptara algo distinto de lo
     * que documenta (como con {@code StrictJsonScalarsConfig}).
     * </p>
     *
     * @param key texto recibido (puede ser {@code null})
     * @return {@code true} si es una clave de la lista
     */
    public static boolean isKnownKey(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /**
     * Todas las claves válidas, en el orden de declaración.
     *
     * @return lista inmutable de claves
     */
    public static List<String> keys() {
        return KEYS;
    }
}
