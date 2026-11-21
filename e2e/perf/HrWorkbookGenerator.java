import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes synthetic HR-style workbooks (employee roster, payroll and absences) of increasing size.
 *
 * <p>Every value is generated from a fixed seed: names are random combinations of common Spanish
 * given names and surnames, and DNI/NIE, NSS and IBAN values are random numbers with valid
 * control digits. No real personal data is used. Run with the packaged JAR on the class path:
 *
 * <pre>
 * java -cp target/doc-anonymizer.jar e2e/perf/HrWorkbookGenerator.java &lt;outDir&gt; [rows...]
 * </pre>
 *
 * <p>{@code rows} is the number of employees (roster rows). The payroll sheet repeats every
 * employee and the absences sheet one in five, so names and IDs reappear across sheets as in a
 * real HR export. Default sizes: 50 150 300 600 1000 3000. Writes {@code hr-<rows>.xlsx} and
 * {@code hr-<rows>.xls}.
 */
class HrWorkbookGenerator {

    static final String[] GIVEN = {"Antonio", "José", "Manuel", "Francisco", "David", "Juan",
        "Javier", "Daniel", "Carlos", "Alejandro", "Miguel", "Rafael", "Pablo", "Sergio", "Fernando",
        "Jorge", "Alberto", "Luis", "Álvaro", "Adrián", "María", "Carmen", "Ana", "Isabel", "Laura",
        "Cristina", "Marta", "Lucía", "Elena", "Paula", "Sara", "Raquel", "Pilar", "Beatriz", "Nuria",
        "Silvia", "Irene", "Patricia", "Rocío", "Andrea", "María José", "Juan Carlos", "José Luis",
        "Ana Belén", "Miguel Ángel"};
    static final String[] SURNAMES = {"García", "Rodríguez", "González", "Fernández", "López",
        "Martínez", "Sánchez", "Pérez", "Gómez", "Martín", "Jiménez", "Ruiz", "Hernández", "Díaz",
        "Moreno", "Muñoz", "Álvarez", "Romero", "Alonso", "Gutiérrez", "Navarro", "Torres",
        "Domínguez", "Vázquez", "Ramos", "Gil", "Ramírez", "Serrano", "Blanco", "Molina", "Morales",
        "Suárez", "Ortega", "Delgado", "Castro", "Ortiz", "Rubio", "Marín", "Sanz", "Iglesias",
        "Medina", "Garrido", "Cortés", "Castillo", "Santos", "Lozano", "Guerrero", "Cano", "Prieto",
        "Méndez", "Cruz", "Calvo", "Gallego", "Vidal", "León", "Márquez", "Herrera", "Peña"};
    static final String[] STREETS = {"Calle Mayor", "Avenida de la Constitución", "Calle Real",
        "Paseo de la Castellana", "Calle de Alcalá", "Calle San Bernardo", "Avenida de América",
        "Calle Gran Vía", "Plaza de España", "Calle del Carmen", "Calle Princesa", "Ronda de Toledo"};
    static final String[][] CITIES = {{"28013", "Madrid"}, {"08001", "Barcelona"},
        {"46002", "Valencia"}, {"41004", "Sevilla"}, {"50001", "Zaragoza"}, {"29005", "Málaga"},
        {"48001", "Bilbao"}, {"15003", "A Coruña"}, {"47001", "Valladolid"}, {"30001", "Murcia"}};
    static final String[] DEPARTMENTS = {"Recursos Humanos", "Administración", "Comercial",
        "Logística", "Producción", "Calidad", "Sistemas", "Atención al Cliente", "Compras",
        "Dirección Financiera"};
    static final String[] POSITIONS = {"Técnico", "Técnico Senior", "Administrativo", "Responsable",
        "Analista", "Operario", "Coordinador", "Auxiliar", "Jefe de Equipo", "Consultor"};
    static final String[] ABSENCES = {"Vacaciones", "Baja por enfermedad común",
        "Asuntos propios", "Permiso por matrimonio", "Formación", "Baja por accidente laboral"};
    static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    /** Small deterministic generator so every run writes byte-identical cell values. */
    static final class Seed {
        private long state;
        Seed(long seed) { state = seed; }
        int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) Long.remainderUnsigned(state >>> 17, bound);
        }
        long digits(int count) {
            long value = 0;
            for (int i = 0; i < count; i++) value = value * 10 + next(10);
            return value;
        }
        <T> T pick(T[] values) { return values[next(values.length)]; }
    }

    record Employee(String id, String given, String surname1, String surname2, String document,
            String nss, String iban, String email, String phone, String address, String department,
            String position, String hired, String birth, int salary, String manager) {
        String fullName() { return given + " " + surname1 + " " + surname2; }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "e2e/.runtime/hr-fixtures");
        List<Integer> sizes = new ArrayList<>();
        for (int i = 1; i < args.length; i++) sizes.add(Integer.parseInt(args[i]));
        if (sizes.isEmpty()) sizes.addAll(List.of(50, 150, 300, 600, 1000, 3000));
        Files.createDirectories(out);
        for (int rows : sizes) {
            List<Employee> staff = employees(rows, new Seed(20_261_120L + rows));
            write(new XSSFWorkbook(), staff, out.resolve("hr-" + rows + ".xlsx"));
            write(new HSSFWorkbook(), staff, out.resolve("hr-" + rows + ".xls"));
            System.out.println("hr-" + rows + ": " + rows + " employees");
        }
    }

    static List<Employee> employees(int rows, Seed seed) {
        List<Employee> staff = new ArrayList<>();
        LocalDate base = LocalDate.of(2005, 1, 1);
        for (int i = 0; i < rows; i++) {
            String given = seed.pick(GIVEN);
            String s1 = seed.pick(SURNAMES);
            String s2 = seed.pick(SURNAMES);
            String document = seed.next(5) == 0 ? nie(seed) : dni(seed);
            String[] city = seed.pick(CITIES);
            String address = seed.pick(STREETS) + " " + (1 + seed.next(150)) + ", "
                    + (1 + seed.next(8)) + "º " + (char) ('A' + seed.next(4)) + ", " + city[0] + " "
                    + city[1];
            String email = ascii(given.split(" ")[0] + "." + s1 + "." + s2).toLowerCase(Locale.ROOT)
                    + "@empresa-ficticia.es";
            String phone = "6" + seed.next(10) + seed.next(10) + " " + String.format(Locale.ROOT, "%03d",
                    seed.next(1000)) + " " + String.format(Locale.ROOT, "%03d", seed.next(1000));
            String manager = staff.isEmpty() ? "" : staff.get(seed.next(Math.min(staff.size(), 25)))
                    .fullName();
            staff.add(new Employee(String.format(Locale.ROOT, "EMP-%06d", 1000 + i), given, s1, s2, document,
                    nss(seed), iban(seed), email, phone, address, seed.pick(DEPARTMENTS),
                    seed.pick(POSITIONS), base.plusDays(seed.next(7000)).format(DATE),
                    LocalDate.of(1960, 1, 1).plusDays(seed.next(15000)).format(DATE),
                    18_000 + seed.next(42_000), manager));
        }
        return staff;
    }

    static String dni(Seed seed) {
        long number = 10_000_000L + seed.next(89_999_999);
        return String.format(Locale.ROOT, "%08d", number) + DNI_LETTERS.charAt((int) (number % 23));
    }

    static String nie(Seed seed) {
        int prefix = seed.next(3);
        long number = seed.digits(7);
        long control = prefix * 10_000_000L + number;
        return "XYZ".charAt(prefix) + String.format(Locale.ROOT, "%07d", number)
                + DNI_LETTERS.charAt((int) (control % 23));
    }

    /** Spanish social security number: 2-digit province, 8 digits, control = value mod 97. */
    static String nss(Seed seed) {
        int province = 1 + seed.next(52);
        long number = seed.digits(8);
        long control = (province * 100_000_000L + number) % 97;
        return String.format(Locale.ROOT, "%02d/%08d/%02d", province, number, control);
    }

    /** Spanish IBAN with valid CCC control digits and ISO 13616 check digits. */
    static String iban(Seed seed) {
        String bank = String.format(Locale.ROOT, "%04d", seed.digits(4));
        String branch = String.format(Locale.ROOT, "%04d", seed.digits(4));
        String account = String.format(Locale.ROOT, "%010d", seed.digits(10));
        String ccc = bank + branch + cccDigit("00" + bank + branch) + cccDigit(account) + account;
        BigInteger numeric = new BigInteger(ccc + "142800");
        int check = 98 - numeric.mod(BigInteger.valueOf(97)).intValue();
        String iban = "ES" + String.format(Locale.ROOT, "%02d", check) + ccc;
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < iban.length(); i += 4) {
            if (i > 0) grouped.append(' ');
            grouped.append(iban, i, Math.min(i + 4, iban.length()));
        }
        return grouped.toString();
    }

    static int cccDigit(String ten) {
        int[] weights = {1, 2, 4, 8, 5, 10, 9, 7, 3, 6};
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (ten.charAt(i) - '0') * weights[i];
        int digit = 11 - sum % 11;
        return digit == 11 ? 0 : digit == 10 ? 1 : digit;
    }

    static String ascii(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    static void write(Workbook workbook, List<Employee> staff, Path target) throws Exception {
        try (workbook; OutputStream out = Files.newOutputStream(target)) {
            Sheet roster = workbook.createSheet("Plantilla");
            header(roster, "ID Empleado", "Nombre", "Primer apellido", "Segundo apellido",
                    "Nombre completo", "DNI/NIE", "Nº Seguridad Social", "IBAN", "Email",
                    "Teléfono", "Dirección", "Departamento", "Puesto", "Fecha alta",
                    "Fecha nacimiento", "Responsable");
            int r = 1;
            for (Employee e : staff) {
                row(roster, r++, e.id(), e.given(), e.surname1(), e.surname2(), e.fullName(),
                        e.document(), e.nss(), e.iban(), e.email(), e.phone(), e.address(),
                        e.department(), e.position(), e.hired(), e.birth(), e.manager());
            }
            Sheet payroll = workbook.createSheet("Nóminas");
            header(payroll, "ID Empleado", "Empleado", "DNI/NIE", "Nº Seguridad Social", "IBAN",
                    "Periodo", "Salario bruto anual", "Bruto mensual", "IRPF %", "Líquido");
            r = 1;
            for (Employee e : staff) {
                double monthly = Math.round(e.salary() / 14.0 * 100) / 100.0;
                double irpf = e.salary() < 25_000 ? 12 : e.salary() < 40_000 ? 18 : 24;
                Row row = payroll.createRow(r++);
                String[] text = {e.id(), e.fullName(), e.document(), e.nss(), e.iban(), "10/2026"};
                for (int c = 0; c < text.length; c++) row.createCell(c).setCellValue(text[c]);
                row.createCell(6).setCellValue(e.salary());
                row.createCell(7).setCellValue(monthly);
                row.createCell(8).setCellValue(irpf);
                row.createCell(9).setCellValue(Math.round(monthly * (100 - irpf)) / 100.0);
            }
            Sheet absences = workbook.createSheet("Ausencias");
            header(absences, "ID Empleado", "Empleado", "DNI/NIE", "Tipo", "Desde", "Hasta",
                    "Observaciones");
            r = 1;
            for (int i = 0; i < staff.size(); i += 5) {
                Employee e = staff.get(i);
                String approver = e.manager().isEmpty() ? "Dirección de Personas" : e.manager();
                row(absences, r++, e.id(), e.fullName(), e.document(), ABSENCES[i % ABSENCES.length],
                        e.hired(), e.hired(), "Aprobado por " + approver + ". Contacto: " + e.phone());
            }
            workbook.write(out);
        }
    }

    static void header(Sheet sheet, String... names) { row(sheet, 0, names); }

    static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
    }
}
