package com.docanonymizer.adapter.detector;

/**
 * Fragmentos de patron compartidos por los detectores de nombre y direccion.
 *
 * <p>Estan en un solo sitio a proposito: un nombre lo buscan tres detectores distintos
 * (pista estructural, diccionario y direccion), y tres copias del mismo patron divergen
 * en cuanto alguien arregla un caso en una sola de ellas.
 */
public final class SpanishNamePatterns {

    /**
     * Separador dentro de un nombre: espacios y como mucho UN salto de linea.
     *
     * <p>Un nombre si puede partirse al final de linea ("Juan Perez\nLopez"), pero nunca
     * cruza un parrafo. Usar {@code \s+} a secas permite que una pista al final de un
     * parrafo capture el titulo del parrafo siguiente.
     */
    public static final String SOFT_SPACE = "(?:[ \\t]|\\n(?!\\n))+";

    /** Impide que un patron enganche con el final de otra palabra. */
    public static final String NOT_AFTER_LETTER = "(?<![\\p{L}.])";

    /**
     * Una palabra de nombre propio: capitalizada, o entera en mayusculas, porque los
     * escritos juridicos alternan "Maria Garcia Perez" y "MARIA GARCIA PEREZ" sin criterio.
     */
    public static final String NAME_WORD =
            "(?-i:(?:[A-ZÁÉÍÓÚÜÑ][a-záéíóúüñ]+|[A-ZÁÉÍÓÚÜÑ]{2,}))";

    /** Abbreviated María is valid only as a component of a full name. */
    private static final String MARIA_ABBREVIATION = "Mª";

    /**
     * Particulas que van en minuscula dentro de un nombre o un nombre de via.
     *
     * <p>El orden importa y no es cosmetico: la alternancia de Java es perezosa y se
     * queda con la PRIMERA que encaja. Con "de" antes que "del", el patron consumia el
     * "de" de "Carrer del Beat Oriol" y dejaba una "l" suelta a mitad de palabra, con lo
     * que la salida quedaba en "[DIRECCION_001]l Beat Oriol". Las mas largas van primero
     * y cada una lleva su limite de palabra.
     *
     * <p>Incluye "da", "do" y "dos" del gallego y el "i" catalan: en una direccion
     * espanola aparecen constantemente.
     */
    public static final String PARTICLE =
            "(?:(?:del|de|das|dos|da|do|las|los|la|el|y|i)(?![\\p{L}]))";

    /**
     * Nombre completo: al menos dos palabras. Exigir dos evita que cualquier palabra
     * capitalizada a principio de frase se convierta en un candidato.
     */
    public static final String FULL_NAME = fullName(NAME_WORD);

    /** Lowercase words are accepted only when a structural cue establishes person context. */
    public static final String FULL_NAME_AFTER_CUE =
            fullName("(?:" + NAME_WORD + "|[a-záéíóúüñ]{2,})");

    private static String fullName(String word) {
        String tail = SOFT_SPACE + "(?:" + PARTICLE + SOFT_SPACE + ")?" + word;
        return "(?:" + MARIA_ABBREVIATION + SOFT_SPACE + word + tail
                + "|" + word + SOFT_SPACE + MARIA_ABBREVIATION + tail
                + "|" + word + tail + ")(?:" + tail + "){0,3}";
    }

    /**
     * Tratamientos que preceden a una persona.
     *
     * <p>Va anclado con {@link #NOT_AFTER_LETTER} en cada uso, y no es una precaucion
     * teorica: sin el, la alternativa {@code D\.} casa con el final de la matricula
     * "1234 BCD." y convierte en persona lo que venga detras.
     */
    public static final String HONORIFIC =
            NOT_AFTER_LETTER
                    + "(?:D\\.ª|Dña\\.|Dª|D\\.|Doña|Don|Sr\\.|Sra\\.|Srta\\.|Sres\\."
                    + "|Señor|Señora)";

    /**
     * Tipos de via reconocidos, incluidos los de gallego, catalan y euskera.
     *
     * <p>No es exhaustividad decorativa: una factura real traia "Rua da Muina" y, sin
     * "Rua" en esta lista, la direccion no se detectaba en absoluto. En Espana el tipo de
     * via viene en la lengua cooficial mucho mas a menudo de lo que un catalogo pensado
     * solo en castellano da por supuesto.
     */
    public static final String STREET_TYPE =
            NOT_AFTER_LETTER
                    + "(?:Calle|C/|Avenida|Avda\\.|Av\\.|Plaza|Plza\\.|Pza\\.|Paseo|Camino"
                    + "|Carretera|Ctra\\.|Travesía|Travesia|Ronda|Glorieta|Polígono|Poligono"
                    + "|Rúa|Rua|Carrer|Avinguda|Praça|Praza|Plaça|Camiño|Passeig|Travessera"
                    + "|Kalea|Etorbidea|Errepidea|Urbanización|Urbanizacion|Urb\\.)";

    private SpanishNamePatterns() {
    }
}
