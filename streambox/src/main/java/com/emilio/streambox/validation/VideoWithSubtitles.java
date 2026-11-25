package com.emilio.streambox.validation;

/**
 * Petición que lleva la URL de un vídeo y la de sus subtítulos
 * ({@code MovieRequest} y {@code EpisodeRequest}).
 *
 * <p>
 * Existe para que {@link SubtitlesRequireVideo} se escriba una sola vez y sirva
 * para las dos peticiones: un validador de clase recibe el objeto entero y
 * necesita un tipo común con los dos campos. Los {@code record} ya tienen estos
 * métodos (son los de acceso a sus componentes), así que basta con declarar que
 * implementan la interfaz.
 * </p>
 */
public interface VideoWithSubtitles {

    /**
     * URL del vídeo tal como llegó (puede ser {@code null} o estar en blanco).
     *
     * @return URL del vídeo
     */
    String videoUrl();

    /**
     * URL de los subtítulos tal como llegó (puede ser {@code null} o estar en blanco).
     *
     * @return URL de los subtítulos
     */
    String subtitlesUrl();
}
