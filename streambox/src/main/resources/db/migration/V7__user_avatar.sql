-- ===================================================================
-- V7: icono de perfil (avatar) del usuario.
--
-- POR QUÉ
--   El usuario puede elegir un icono de perfil de entre una lista de avatares
--   predefinidos (no sube imágenes). En la base de datos solo se guarda la
--   CLAVE del avatar elegido (por ejemplo 'rocket' o 'cat'); el frontend
--   traduce esa clave a un icono y un color. users.avatar = NULL significa
--   «sin avatar»: la interfaz muestra la inicial del nombre, como hasta ahora.
--
-- POR QUÉ NO HAY UN CHECK CON LA LISTA DE CLAVES
--   La lista blanca de avatares válidos vive en el backend (la valida la API
--   en PATCH /api/users/me). Si estuviera también en un CHECK, añadir un
--   avatar nuevo exigiría una migración y un despliegue coordinado de base de
--   datos y aplicación, para un dato que no afecta a la integridad de ninguna
--   relación. Un valor antiguo que el backend deje de reconocer no rompe nada:
--   el frontend vuelve a mostrar la inicial.
--
-- DEFENSA MÍNIMA: ck_users_avatar (solo el FORMATO de la clave)
--   Lo que sí se exige en la base de datos es que, si hay avatar, sea una clave
--   con forma de clave: de 1 a 32 caracteres de [a-z0-9-]. Así ningún camino
--   (un script, un INSERT a mano, un fallo futuro del validador) puede guardar
--   una cadena vacía, espacios, mayúsculas, una URL o HTML en un campo que la
--   interfaz usa como clave. Cualquier avatar futuro con nombre en minúsculas y
--   guiones cabe sin migración. Como en V4 y V6, el juego de caracteres se
--   comprueba con TRANSLATE (cada carácter válido pasa a '0' y solo una clave
--   válida queda en una fila de ceros de su misma longitud) y no con
--   expresiones regulares, que no son portables entre PostgreSQL y H2. No se
--   usa LOWER: hay caracteres Unicode (el signo Kelvin, U+212A) que en
--   minúscula se convierten en una letra ASCII y se colarían.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - NO MODIFICA NINGUNA FILA: la columna nace vacía (NULL) para todos los
--     usuarios existentes, que seguirán viendo su inicial. No borra nada.
--   - Bloqueos: ADD COLUMN sin valor por defecto solo cambia metadatos; el
--     ADD CONSTRAINT CHECK recorre users una vez, y con la columna entera a
--     NULL se cumple siempre. Son milisegundos.
--   - Una base adoptada (anterior a Flyway) recibe exactamente lo mismo: la
--     tabla users existe desde V1 y no había ninguna columna con este nombre.
--   - Tras aplicarla, una versión ANTERIOR de la aplicación sigue arrancando
--     (Hibernate no valida columnas que no conoce) e ignora el avatar.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

ALTER TABLE users ADD COLUMN avatar VARCHAR(32);

ALTER TABLE users ADD CONSTRAINT ck_users_avatar CHECK (
    avatar IS NULL
    OR (CHAR_LENGTH(avatar) BETWEEN 1 AND 32
        AND TRANSLATE(avatar,
                      'abcdefghijklmnopqrstuvwxyz0123456789-',
                      REPEAT('0', 37)) = REPEAT('0', CHAR_LENGTH(avatar))));
