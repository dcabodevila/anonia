# doc-anonymizer

**Versión 0.4.0** — Prototipo: **PDF y fotos → Markdown desidentificado**, 100 % offline.

> **Instalación en Windows:** siga la [guía de instalación para clientes](packaging/windows/INSTALAR-WINDOWS.md). Para PDF con capa de texto, instale la aplicación directamente. Para imágenes JPEG/PNG, instale primero Tesseract OCR administrado externamente con español (`spa`) y valide `--list-langs`; después instale la aplicación. Java está incluido en el instalador. El procesamiento de documentos no usa red externa.

Toma un PDF con capa de texto, detecta identificadores personales, los sustituye por
etiquetas estables (`[PERSONA_001]`, `[DNI_002]`) y verifica sobre el fichero final que
ningún valor detectado sobrevive. Si algún control falla, **no escribe el Markdown**.

Procesa fotos JPEG/PNG con OCR local. No aplica OCR a PDF escaneados, no sale a la red y no genera un PDF.

---

## Interfaz de revisión (Docker)

```bash
mvn -o clean package          # el jar se construye fuera de la imagen
docker compose up -d --build
```

Abre **http://127.0.0.1:8080**. Arrastra un PDF con capa de texto o una foto JPEG/PNG y
verás el documento con las detecciones resaltadas en su sitio.

Para tener un PDF con datos sembrados a mano:

```bash
java -cp target/doc-anonymizer.jar \
     com.docanonymizer.tools.SampleDocumentGenerator ejemplo.pdf
```

Sin Docker: `java -cp target/doc-anonymizer.jar com.docanonymizer.adapter.web.WebServer`
(escucha en 127.0.0.1:8080).

### Qué mirar en la UI

1. **El texto, no la lista.** Las marcas están sobre el documento corrido a propósito. Una
   lista solo enseña lo que el detector encontró; leyendo el texto se ve **lo que se
   escapó**, que es el fallo que importa y el único que ninguna interfaz de aciertos
   revela.
2. **Pulsa una marca** para descartar esa entidad. Se descartan todas sus apariciones a la
   vez, porque son la misma persona.
3. **Prueba a romperlo.** Descarta *una sola* mención de una persona con varias y pulsa
   «Aplicar y verificar»: la puerta de salida bloquea con `C3-TOKENS-DE-NOMBRE`, porque el
   apellido quedó suelto en el texto. Ese es el control que sostiene el sistema.

El contenedor corre con sistema de ficheros de solo lectura, `/tmp` en RAM, sin
privilegios y sin capabilities. El PDF subido se escribe a un temporal —PDFBox trabaja
sobre fichero— que con `tmpfs` nunca toca el disco.

---

## Uso por línea de comandos

```bash
java -jar target/doc-anonymizer.jar ejemplo.pdf
```

Produce `ejemplo.anon.md` (el documento) y `ejemplo.report.md` (informe técnico sin PII).

### Opciones

| Opción | Efecto |
|---|---|
| `-o, --output <fichero>` | Markdown de salida. Por defecto `<entrada>.anon.md` |
| `--report <fichero>` | Informe técnico. Por defecto `<entrada>.report.md` |
| `--show-detections` | Lista las detecciones por consola. **Muestra datos personales en claro**; es el sustituto de la interfaz de revisión, no un modo de diagnóstico |
| `--dry-run` | Procesa y verifica sin escribir nada |

### Reglas locales de detección

En Windows, edite el archivo UTF-8 `%USERPROFILE%\.anonimuse\rules.txt` y quite el `#`
de las líneas que quiera activar. El instalador está diseñado para crear la carpeta y
un archivo de ejemplo con todas las reglas comentadas si aún no existen, y para conservar
el archivo editado al actualizar o desinstalar; **este comportamiento de instalación aún
requiere verificación en una instalación real**. La ruta anterior no se migra automáticamente.
Fuera del instalador, cree el archivo manualmente si lo necesita. También puede indicar
otra ruta al iniciar Java con `-Ddoc.anonymizer.rules=/ruta/rules.txt`. El archivo se lee
una vez al construir el pipeline web o CLI: reinicie la aplicación tras editarlo; no hay
recarga en caliente. Una ruta explícita inexistente o ilegible impide el arranque e indica
la ruta; una línea mal formada informa también su número.

```text
# Ejemplos: quite el # para activar una regla.
# person: Íñigo
# organization: Banco Santander
# term: Oposición
# exclude-person: Íñigo Pérez López
# exclude-organization: Banco Santander
# exclude-term: Oposición
```

Una regla activa por línea: clave minúscula exacta, dos puntos, un espacio y valor no vacío;
las líneas vacías y los comentarios completos se ignoran. Las reglas de inclusión `person: <nombre de pila>` añaden el
nombre al diccionario para detectar nombres completos con apellidos;
`organization: <frase>` y `term: <frase>` detectan frases completas sin coincidir dentro
de palabras. Las exclusiones `exclude-person: <nombre completo>`,
`exclude-organization: <frase>` y `exclude-term: <frase>` evitan esas detecciones.
La comparación normaliza mayúsculas/minúsculas y acentos; los espacios entre palabras
pueden variar. Una exclusión prevalece sobre una inclusión coincidente. La exclusión de
persona afecta a todas las menciones vinculadas de esa entidad (incluidas formas abreviadas),
no solo al texto idéntico; si una mención es ambigua y no puede vincularse con certeza,
no presuponga que quedará excluida. **Las exclusiones son persistentes para esta cuenta y
se aplican a todos los documentos procesados con ese archivo:** el texto excluido puede
seguir apareciendo en claro. Revise el resultado antes de compartirlo. Los términos
explícitos reciben `[TERMINO_###]` y las organizaciones `[ORGANIZACION_###]`. Sin archivo
se mantienen las reglas incluidas; la revisión existente sigue disponible.

### OCR local para fotos JPEG/PNG

Los instaladores Windows nuevos no incluyen ni redistribuyen Tesseract, sus DLL ni el modelo
`spa`. Instale Tesseract y el idioma español por medios administrados por el usuario y
compruebe localmente que la instalación contiene el idioma:

```powershell
& 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
```

La lista debe incluir `spa`. La aplicación ejecuta Tesseract con `-l spa`; no descarga
modelos, no instala software y no envía imágenes a la red.

El resolvedor conserva este orden: la propiedad Java explícita
`doc.anonymizer.tesseract.command`, un bundle legado `app/ocr` junto al JAR, la variable de
entorno `TESSERACT_COMMAND` y, por último, `tesseract.exe` en `PATH`. La propiedad es para
desarrollo. `TESSERACT_COMMAND` puede indicar un ejecutable local o un lanzador de Windows
`.cmd` o `.bat`; los lanzadores se ejecutan mediante el intérprete de comandos de Windows.
El soporte del bundle legado mantiene funcionales instalaciones antiguas, pero no se envía en
los instaladores nuevos.

Para una apertura desde PowerShell con ruta explícita, defina la variable y arranque el
lanzador desde la misma consola:

```powershell
$env:TESSERACT_COMMAND = 'C:\Program Files\Tesseract-OCR\tesseract.exe'
& 'C:\Users\<usuario>\AppData\Local\DocAnonymizer\DocAnonymizer.exe'
```

Esto solo afecta a ese proceso y no configura el acceso directo del escritorio ni variables
persistentes. En cada instalación o actualización, el instalador configura para el usuario
actual `TESSERACT_COMMAND` con el valor exacto
`C:\Program Files\Tesseract-OCR\tesseract.exe`, aunque Tesseract aún no esté instalado, y
reemplaza cualquier valor de usuario anterior. Al desinstalar DocAnonymizer, elimina ese valor
administrado por el instalador. Cierre y vuelva a abrir la aplicación o el acceso directo tras
instalar o actualizar para que el proceso nuevo reciba la variable. El instalador no instala ni
incluye Tesseract, no cambia `PATH` ni `TESSDATA_PREFIX`, y no escribe configuración de
máquina. Los errores de OCR no incluyen el contenido de la imagen ni rutas configuradas.

### Códigos de salida

| Código | Significado |
|---|---|
| `0` | Documento entregado |
| `1` | Error de uso, o documento rechazado en la puerta de entrada |
| `2` | **Verificación bloqueada**: el pipeline terminó, y la puerta de salida decidió no entregar |

El `2` se distingue del `1` a propósito: significa que todo funcionó y aun así el
resultado no era seguro.

---

## El pipeline

```
PDF nativo
  │
  ├─ 1. Extracción por páginas (PDFBox, sortByPosition)
  ├─ 2. Guarda de cordura ──────────────► rechaza si el texto no es fiable
  ├─ 3. Limpieza de cabeceras y pies      (necesita las páginas separadas)
  ├─ 4. Normalización                     (fija el sistema de coordenadas único)
  ├─ 5. Detección + arbitraje de solapes
  ├─ 6. Propagación de entidades + unificación de personas
  ├─ 7. Revisión humana                   (puerto; el prototipo auto-acepta)
  ├─ 8. Seudónimos → sustitución → Markdown
  └─ 9. Verificación del fichero final ──► BLOQUEA o entrega
```

El orden no es negociable: el paso 3 necesita las páginas sin concatenar, el 5 necesita
el texto ya normalizado, y el 9 corre sobre el artefacto exacto que se escribe.

### Por qué cada paso está donde está

**Guarda de cordura (2).** Hay PDF que se renderizan perfectamente y extraen basura,
porque su `/ToUnicode` es incorrecto. Sin esta comprobación no se detectaría nada, la
verificación pasaría —no hay valores que buscar— y se entregaría un fichero ilegible que
el usuario creería anonimizado. Falla cerrado. Los PDF escaneados siguen fuera de alcance;
para fotos JPEG/PNG se usa el OCR local descrito arriba.

**Normalización (4).** Un NIF partido por un guion de fin de línea o un título escrito
`A C T A` no lo captura ninguna expresión regular. El fallo es silencioso: el pipeline
exporta convencido de que no había nada que ocultar.

**Propagación (6).** Es el mecanismo principal de recall. Los detectores anclan a la
persona donde hay una pista fuerte (`D. Juan Pérez López, con DNI…`), y la propagación
persigue el resto de menciones (`el Sr. Pérez López`, `PÉREZ LÓPEZ`, `Pérez`).

**Verificación (9).** Es el único control del sistema que puede fallar de verdad, y por
eso es el que sostiene todo lo demás.

---

## La verificación, y por qué aquí sí sirve

En el diseño original —reconstruir un PDF desde un bitmap enmascarado— los controles de
"no queda texto", "no hay JavaScript", "no hay metadatos heredados" **pasaban siempre por
construcción**. Si insertas solo un bitmap en un PDF nuevo, no puede haber texto. Eran
tautologías presentadas como pruebas de seguridad.

Con salida en texto plano la comprobación es directa: el valor está o no está.

| Control | Qué caza |
|---|---|
| `C1-VALOR-LITERAL` | El valor sigue ahí, ignorando caja y acentos |
| `C2-VALOR-REFORMATEADO` | Reaparece con otra puntuación: `12.345.678-Z` frente a `12345678Z` |
| `C3-TOKENS-DE-NOMBRE` | **La propagación dejó un apellido suelto.** Ningún control de "valor completo" ve esta fuga, porque el nombre completo sí desapareció |
| `C4-COBERTURA-DE-SUSTITUCION` | Una entidad se quedó sin etiqueta |
| `C5-SALIDA-NO-VACIA` | El Markdown salió vacío |

`C3` es el que importa. La política de qué tokens cuentan vive en `NamePolicy`, compartida
por el propagador y el verificador: si divergieran, la puerta bloquearía siempre o dejaría
pasar fugas.

---

## Qué es y qué no es el resultado

El Markdown es un documento **desidentificado**, no anónimo. La diferencia no es
terminológica:

- Las etiquetas mantienen **distinguibles** a las personas, que es justo lo que conserva
  el sentido del documento (quién demandó a quién) y a la vez lo que permite reidentificar
  por estructura. Esto es **seudonimización**.
- Fechas, importes, cargos, localidades pequeñas y hechos singulares permanecen intactos.
  En un documento único pueden bastar para identificar a alguien.
- Texto plano es más fácil de procesar en masa que un PDF, y por tanto más fácil de
  correlacionar. Un PDF rasterizado tiene fricción; un Markdown es un dataset.

No se guarda tabla de correspondencias: la sustitución es irreversible desde la salida.

---

## Limitaciones conocidas

**La revisión humana está simulada.** `AutoAcceptReview` acepta todo. Sirve para medir
sobre un corpus y para tests deterministas; no sustituye a una persona. Los falsos
negativos son invisibles: un detector no encuentra lo que no sabe buscar, y una interfaz
que solo muestra lo detectado nunca enseña lo que se escapó.

**Sobre-ocultación deliberada en nombres.** Se propagan todos los tokens de 4+ caracteres,
incluido el nombre de pila. Así, ocultar "María García Pérez" puede llevarse por delante
un "Virgen del Carmen" legítimo si "Carmen" era el nombre de otra persona detectada. Es la
dirección de fallo correcta para un prototipo, y es exactamente el tipo de decisión que la
revisión humana debe poder revertir.

**Menciones ambiguas sin resolver.** Si "Pérez López" encaja igual con "Juan Pérez López"
y con "Marta Pérez López", `PersonEntityResolver` **no elige**: las deja como entidades
separadas. Resolverlo a la brava afirmaría algo que el documento no dice.

**Fidelidad de maquetación baja, a propósito.** No se reconstruyen tablas ni columnas. Una
tabla mal inferida mueve celdas de sitio y cambia lo que el documento dice; un párrafo de
texto plano ordenado, no.

**Diccionario de nombres corto.** `src/main/resources/gazetteer/nombres-es.txt` es de
prototipo. El sustituto natural es el listado del INE: público, descargable y offline.
Cambiarlo no toca el dominio.

**Direcciones ancladas en el tipo de vía.** Se detecta "Calle Mayor 15" pero no una
dirección escrita sin "Calle", "Avda." o similar. Precisión alta a cambio de recall.

---

## Estructura

```
domain/
  model/     Detection, ExtractedDocument, VerificationReport, Finding…
  port/      TextExtractorPort, DetectorPort, GazetteerPort, ReviewPort
  service/   pipeline, normalización, detección, propagación, verificación
adapter/
  pdf/       PdfBoxTextExtractor
  ocr/       TesseractImageTextExtractor (JPEG/PNG, modelo spa local)
  detector/  regex + validadores + pistas estructurales + diccionario
  gazetteer/ ResourceGazetteer
  review/    AutoAcceptReview
  cli/       Main, TechnicalReport
  PipelineFactory  ← raíz de composición
tools/       SampleDocumentGenerator (corpus de prueba)
```

El dominio no conoce PDFBox, Tesseract ni las expresiones regulares: solo puertos. El OCR
local implementa `TextExtractorPort` sin tocar el núcleo.

---

## Tests

```bash
mvn -o test
```

La suite incluye las pruebas Java y JavaScript del pipeline, la interfaz y el OCR local.
El test que importa es `SecurityCorpusEndToEndTest`: genera un PDF con datos
sembrados, ejecuta el pipeline completo y comprueba que ninguno sobrevive —tampoco
reformateado, ni sin acentos, ni como apellido suelto.

Incluye **controles negativos**, sin los cuales la prueba sería vacua: un pipeline que lo
ocultara todo pasaría cualquier test de fugas siendo inútil. Por eso se exige además que
`87654321A` (forma de DNI, dígito de control inválido → referencia interna, no dato
personal) **siga presente**, y que el contenido legítimo del documento se conserve.

---

## Requisitos

- Java 17+ (probado con 17.0.6; el briefing apuntaba a 21, no disponible en este equipo)
- Maven 3.8+
- Apache PDFBox 3.0.6 — única dependencia de ejecución

Sin red en tiempo de ejecución. Sin telemetría. Sin descarga de modelos.

---

## Siguiente paso

Lo que más valor daría ahora no es más código, sino **medir**: pasar 20 PDF reales de un
solo dominio y contar cuántas personas encuentra y cuántas se escapan. Ese número decide
si hacen falta NER y ONNX, o si reglas + pistas estructurales + diccionario del INE ya
llegan. Sin ese dato, cualquier decisión sobre modelos es una apuesta.
