package com.docanonymizer.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** Genera un corpus PDF con nombres inventados, agrupados por tarea de recall. */
public final class ForeignNameCorpusGenerator {

    public record NameCase(String name, String sentence) {
    }

    public record CaseGroup(String taskId, List<NameCase> cases) {
        public CaseGroup {
            cases = List.copyOf(cases);
        }
    }

    public static final List<CaseGroup> GROUPS = List.of(
            new CaseGroup("T1", List.of(
                    new NameCase("François Dupont", "Comparece D. François Dupont para ratificar el escrito."),
                    new NameCase("Małgorzata Wiśniewska", "Comparece Dña. Małgorzata Wiśniewska para aportar prueba."),
                    new NameCase("Jean-Pierre O'Neal", "Declara D. Jean-Pierre O'Neal que recibió la citación."),
                    new NameCase("Sean McDonald", "Interviene D. Sean McDonald en calidad de testigo."),
                    new NameCase("Zoë Ştefănescu", "Comparece Dña. Zoë Ştefănescu para formular alegaciones."),
                    new NameCase("Søren Ødegaard", "Declara D. Søren Ødegaard que acepta la notificación."),
                    new NameCase("FRANÇOIS LEFÈVRE", "Comparece D. FRANÇOIS LEFÈVRE para firmar el acta."))),
            new CaseGroup("T3", List.of(
                    new NameCase("Kowalski", "Comparece el Sr. Kowalski para ratificar la demanda."),
                    new NameCase("Nowak", "Comparece la sra. Nowak para aportar documentación."),
                    new NameCase("SCHMIDT", "Interviene el SR. SCHMIDT en calidad de testigo."),
                    new NameCase("Bianchi", "Declara la Sra Bianchi que recibió el requerimiento."),
                    new NameCase("Giuseppe Rossi", "Comparece don Giuseppe Rossi para prestar declaración."),
                    new NameCase("Ingrid Larsen", "Comparece DOÑA Ingrid Larsen para ratificar el escrito."),
                    new NameCase("Olga Petrova", "Declara dña. Olga Petrova que acepta la notificación."),
                    new NameCase("Aiko Tanaka", "Comparece Dña Aiko Tanaka para formular alegaciones."),
                    new NameCase("Ahmed Benali", "Interviene d. Ahmed Benali en calidad de testigo."),
                    new NameCase("Viktor Horvath", "Comparece DON Viktor Horvath para aportar prueba."),
                    new NameCase("Novak", "Declara Sr Novak que recibió la citación."))),
            new CaseGroup("T4", List.of(
                    new NameCase("Oleksandr Kovalenko", "Consta en autos, según manifestó Oleksandr Kovalenko, la entrega."),
                    new NameCase("Mohamed El Amrani", "Según declaró Mohamed El Amrani, procede el archivo."),
                    new NameCase("Ionut Popescu", "Según manifestó Ionut Popescu, se cumplió el plazo."),
                    new NameCase("Emily Johnson", "Según declaró Emily Johnson, se entregó el escrito."),
                    new NameCase("Siobhan Murphy", "Según manifestó Siobhan Murphy, procede la devolución."),
                    new NameCase("Wei Zhang", "Según declaró Wei Zhang, se recibió la resolución."),
                    new NameCase("Thiago Oliveira", "Según manifestó Thiago Oliveira, se abonó la cantidad."))),
            new CaseGroup("T5", List.of(
                    new NameCase("Xiomara García Pérez", "Según declaró Xiomara García Pérez, procede el archivo."),
                    new NameCase("Brayden López Ruiz", "Según manifestó Brayden López Ruiz, se cumplió el plazo."))),
            new CaseGroup("T6", List.of(
                    new NameCase("Rosa Martínez Gil", "Según declaró Rosa Martínez Gil, se entregó el escrito."),
                    new NameCase("Paz Herrero Soto", "Según manifestó Paz Herrero Soto, procede el archivo."),
                    new NameCase("Victoria Sánchez Mora", "Según declaró Victoria Sánchez Mora, se cumplió el plazo."),
                    new NameCase("Grace Fernández Vidal", "Según manifestó Grace Fernández Vidal, se abonó la cantidad."))));

    public static final List<String> NEGATIVE_CONTROLS = List.of(
            "Juzgado de Primera Instancia", "Tribunal Supremo", "Real Decreto",
            "Santiago de Compostela", "Código Civil", "Fundamentos de Derecho",
            "Antecedentes de Hecho");

    // No son personas; otros detectores pueden redactarlos con su tipo correcto.
    public static final List<String> NON_PERSON_CONTROLS = List.of(
            "Banco Santander", "Avenida de Castilla",
            "Hospital Clínico San Carlos", "Comunidad de Madrid");

    public static final List<String> CONTROL_SENTENCES = List.of(
            "El Juzgado de Primera Instancia tiene competencia para conocer del asunto.",
            "Se invoca la doctrina del Tribunal Supremo en materia contractual.",
            "Resultan aplicables el Real Decreto y el Código Civil.",
            "La sede judicial se encuentra en 15701 Santiago de Compostela.",
            "Se incorporan los Antecedentes de Hecho y los Fundamentos de Derecho.",
            "La entidad Banco Santander presenta el informe.",
            "La via publica se denomina Avenida de Castilla.",
            "El centro sanitario Hospital Clínico San Carlos emite el informe.",
            "La Comunidad de Madrid publica la convocatoria.");

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length > 0 ? args[0] : "nombres-extranjeros.pdf");
        generate(output);
        System.out.println("Corpus generado en: " + output.toAbsolutePath());
    }

    public static void generate(Path output) throws IOException {
        // La fuente incluida en PDFBox permite conservar letras fuera de WinAnsi.
        try (PDDocument document = new PDDocument();
                InputStream input = ForeignNameCorpusGenerator.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            if (input == null) {
                throw new IOException("No se encuentra LiberationSans-Regular en PDFBox.");
            }
            PDType0Font font = PDType0Font.load(document, input);
            for (CaseGroup group : GROUPS) {
                writePage(document, font, "Corpus " + group.taskId(),
                        group.cases().stream().map(NameCase::sentence).toList());
            }
            writePage(document, font, "Controles negativos", CONTROL_SENTENCES);
            document.save(output.toFile());
        }
    }

    private static void writePage(PDDocument document, PDType0Font font,
            String title, List<String> lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(font, 10);
            content.setLeading(18);
            content.newLineAtOffset(50, 780);
            content.showText(title);
            content.newLine();
            content.newLine();
            for (String line : lines) {
                content.showText(line);
                content.newLine();
            }
            content.endText();
        }
    }

    private ForeignNameCorpusGenerator() {
    }
}
