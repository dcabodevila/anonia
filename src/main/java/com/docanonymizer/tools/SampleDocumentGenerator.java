package com.docanonymizer.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Genera un PDF nativo de prueba con datos personales SEMBRADOS.
 *
 * <p>Es el corpus minimo del prototipo, y esta construido para ejercitar precisamente los
 * casos que rompen un pipeline ingenuo:
 *
 * <ul>
 *   <li>titulo escrito letra a letra ("A C T A"), que ninguna regex ve sin normalizar;
 *   <li>palabra partida por guion al final de linea;
 *   <li>cabecera y pie repetidos en las tres paginas;
 *   <li>persona anclada por pista estructural y luego mencionada solo por apellidos;
 *   <li>la misma persona en mayusculas, para comprobar el plegado sin acentos ni caja;
 *   <li>persona sin tratamiento, alcanzable solo por diccionario;
 *   <li>identificadores validos por digito de control junto a un numero que se le parece.
 * </ul>
 *
 * <p>Todos los datos son inventados. Los identificadores son validos como algoritmo pero
 * no corresponden a ninguna persona: son los ejemplos canonicos de documentacion.
 */
public final class SampleDocumentGenerator {

    private static final float MARGIN_LEFT = 50;
    private static final float TOP = 780;
    private static final float LEADING = 15;
    private static final float FONT_SIZE = 11;

    private static final String HEADER = "DESPACHO JURIDICO EJEMPLO S.L.P.";
    private static final String FOOTER_TEMPLATE = "Pagina %d de %d - documento de prueba";

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length > 0 ? args[0] : "ejemplo.pdf");
        generate(output);
        System.out.println("PDF de ejemplo generado en: " + output.toAbsolutePath());
    }

    public static void generate(Path output) throws IOException {
        List<List<String>> pages = List.of(pageOne(), pageTwo(), pageThree());

        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages.size(); i++) {
                writePage(document, pages.get(i), i + 1, pages.size());
            }
            document.save(output.toFile());
        }
    }

    private static void writePage(
            PDDocument document, List<String> lines, int pageNumber, int totalPages)
            throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
            content.setLeading(LEADING);
            content.beginText();
            content.newLineAtOffset(MARGIN_LEFT, TOP);

            content.showText(HEADER);
            content.newLine();
            content.newLine();

            for (String line : lines) {
                if (!line.isEmpty()) {
                    content.showText(line);
                }
                content.newLine();
            }

            content.newLine();
            content.showText(String.format(FOOTER_TEMPLATE, pageNumber, totalPages));
            content.endText();
        }
    }

    private static List<String> pageOne() {
        return List.of(
                // Titulo espaciado letra a letra: sin normalizacion no lo lee nadie.
                "A C T A   D E   M A N I F E S T A C I O N E S",
                "",
                "En Madrid, a 14 de marzo de 2024, comparece ante mi D. Juan Perez",
                "Lopez, mayor de edad, con DNI 12345678Z, con domicilio en Calle Mayor",
                "15, C.P. 28013 Madrid, telefono 612 345 678 y correo electronico",
                "juan.perez@example.com.",
                "",
                "El compareciente manifiesta que el expediente numero 20240415 se",
                // Guion de fin de linea: parte la palabra en dos trozos.
                "encuentra pendiente de resolucion, y que las manifesta-",
                "ciones recogidas en el presente documento son ciertas.");
    }

    private static List<String> pageTwo() {
        return List.of(
                "A N T E C E D E N T E S   D E   H E C H O",
                "",
                // Mencion solo por apellidos: la propagacion tiene que alcanzarla.
                "PRIMERO.- El Sr. Perez Lopez suscribio con fecha 2 de enero de 2024",
                "un contrato de arrendamiento sobre la finca sita en Avenida de la",
                "Constitucion 42.",
                "",
                // La misma persona en mayusculas.
                "SEGUNDO.- Consta en autos que JUAN PEREZ LOPEZ abono la cantidad",
                "pactada mediante transferencia a la cuenta ES9121000418450200051332,",
                "titularidad de la parte arrendadora.",
                "",
                // Persona sin tratamiento: solo alcanzable por diccionario de nombres.
                "TERCERO.- Segun declara Ana Ruiz Molina, la entrega de llaves se",
                "produjo el mismo dia, en presencia del vehiculo matricula 1234 BCD.");
    }

    private static List<String> pageThree() {
        return List.of(
                "F U N D A M E N T O S   D E   D E R E C H O",
                "",
                "UNICO.- Resultan de aplicacion los articulos 1543 y siguientes del",
                "Codigo Civil.",
                "",
                // NIE valido, y a continuacion un numero que se parece a un DNI pero
                // cuya letra de control NO cuadra: el validador debe descartarlo.
                "Interviene asimismo D. Carlos Diaz Vega, con NIE X1234567L, en",
                "representacion de la mercantil. El numero de referencia interna",
                "87654321A no identifica a ninguna persona y no debe ser ocultado.",
                "",
                "Y para que asi conste, se extiende la presente acta.");
    }

    private SampleDocumentGenerator() {
    }
}
