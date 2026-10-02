# Ciclo de vida del instalador Windows

Referencia obligatoria de la habilidad: generar, instalar y abrir son permisos y evidencias separados. Una solicitud genérica de empaquetado autoriza solo la generación; no autoriza instalación ni lanzamiento.

## Antes de generar

- Lee el script para conocer nombre, versión y rutas vigentes; no fijes una versión nueva por inferencia. Comprueba versiones del producto instalado y MSI disponible mediante consultas de solo lectura limitadas a Anonimuse: registro/MSI ProductInfo y base de datos MSI (ProductVersion, ProductCode, UpgradeCode). No uses consultas que reparen productos, como `Win32_Product`.
- Si la versión coincide o la intención de reinstalación es ambigua, pregunta antes de instalar; ofrece conservar el artefacto sin instalar, reinstalación explícita o aumento de versión autorizado. Nunca aumentes automáticamente para resolver una colisión de archivos.
- EXE y MSI son formatos alternativos del mismo producto con identidad de actualización compartida, no instalaciones independientes. Limita cualquier inspección a productos relacionados; no quites otros productos.
- Antes de generar, conserva ambos nombres del formato seleccionado que ya existan (nombre versionado y destino estable) en un archivo/directorio de archivo único, sin sobrescribir ningún destino. Registra ruta, tamaño, fecha y SHA-256; comprueba el hash conservado. No toques el otro formato. Conserva también logs e inputs aislados para diagnóstico.
- Requisitos: `JAVA_HOME` con JDK 21/jpackage, Maven y caché offline, WiX 3 con `candle.exe` y `light.exe`. Falta de requisitos bloquea; no instales ni descargues herramientas. El script puede añadir WiX a `PATH` temporalmente y debe restaurarlo al terminar.
- El comando canónico ejecuta `mvn -o package` con pruebas; no lo sustituyas por `-DskipTests`. Solo el JAR sombreado entra en el directorio aislado; jpackage incorpora Java, no `app/ocr`.
- Exige salida cero y artefacto realmente creado/modificado después del inicio. Inspecciona ruta, tamaño, fechas, metadatos y `Get-FileHash`; para MSI registra ProductVersion/ProductCode/UpgradeCode. Una instantánea antigua, un test con ejecutables falsos o un build satisfactorio no prueban instalación ni ejecución real.

## Instalar: solo con autorización explícita

1. Confirma alcance, versión e identidad del MSI fresco y destino del usuario actual. Conserva evidencia no sensible de las reglas personales existentes (hash/fecha/tamaño), sin leer su contenido. No modifiques `%USERPROFILE%\.anonimuse\rules.txt`: se crea con ejemplo comentado si falta y debe conservarse en actualizaciones/desinstalaciones; no hay migración automática desde la ruta anterior. No afirmes verificación de creación/conservación sin evidencia real.
2. Examina concurrencia del instalador sin matar procesos. No ejecutes desinstalaciones separadas, reparaciones, limpieza de productos ni reinicios implícitos. Una actualización normal del producto relacionado es la operación autorizada; ambigüedad exige detenerse.
3. Usa un log único y espera el resultado real; la forma autorizada es:
   ```powershell
   msiexec.exe /i '<MSI fresco, ruta absoluta>' /passive /norestart /L*v '<log único, ruta absoluta>'
   ```
   Las rutas son marcadores, no un comando listo para copiar. Ejecuta con `Start-Process -Wait -PassThru` y argumentos correctamente entrecomillados para recoger el código real; no confundas la creación del proceso con instalación exitosa. En Git Bash protege código PowerShell con comillas simples o invoca un archivo: Bash no debe expandir `$env`, `$_` ni otras variables. Convierte rutas Windows con `cygpath -w` cuando corresponda.
4. `0`: instalación terminada. `3010`: instalación terminada con reinicio pendiente; informa y no reinicies, ni declares runtime plenamente verificado si depende de ese reinicio. `1618`: otra instalación en curso; detén e informa, sin matar procesos ni reintentar automáticamente. Cualquier otro fallo bloquea: conserva log/código y pide dirección, sin usar un artefacto anterior.
5. Comprueba versión/registro del producto relacionado, ruta real del lanzador instalado y JAR instalado (versión y hash frente al build). Confirma conservación de reglas con evidencia equivalente. No atribuyas esos resultados a las pruebas unitarias.

## Abrir y verificar: solo con autorización explícita

- Inicia el ejecutable instalado, no `java` del repositorio, y conserva PID/diagnósticos. Si la aplicación ya está abierta, no la mates ni la reinicies automáticamente: distingue el proceso existente del recién instalado y pide autorización si impide la prueba.
- Comprueba el listener loopback del lanzador, puerto realmente asignado (18080 o fallback libre), respuesta health HTTP 200 y contenido esperado. Verifica además el navegador real: URL/documento y UI del producto en el mismo puerto. Un listener o health aislado no prueba apertura del navegador.
- Si falla, conserva error y detén; no declares éxito por el build ni inventes un diálogo final pendiente de Sí/No. Deja la aplicación como el usuario indique; empaquetar no autoriza cerrarla.

## OCR externo y entorno del usuario

El instalador incluye aplicación y Java, pero no Tesseract, DLL de OCR ni modelos. No solicites `-OcrBundleRoot`, `DOC_ANONYMIZER_OCR_BUNDLE`, manifiestos ni evidencia de redistribución. No instales ni descargues Tesseract desde este flujo.

En cada instalación/actualización establece `TESSERACT_COMMAND` **solo para el usuario actual** como `C:\Program Files\Tesseract-OCR\tesseract.exe`, reemplazando el valor de usuario existente, incluso si Tesseract no está instalado. Al desinstalar elimina ese valor administrado; esto describe el ciclo, no autoriza desinstalar. No modifica `PATH` ni configuración de máquina. Tras instalar/actualizar, cerrar y abrir de nuevo el proceso o acceso directo permite heredar el entorno; requiere permiso explícito, nunca mates procesos.

El usuario administra Tesseract local y español (`spa`). Solo si pide probar OCR, valida localmente:

```powershell
& 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
```

Exige `spa`; si falta, detén esa prueba e informa el requisito, sin descargar software. Esto no prueba instalación del producto ni OCR desde el acceso directo. Para una ruta alternativa temporal, `$env:TESSERACT_COMMAND = 'C:\ruta\tesseract.exe'` afecta solo esa consola; no atribuyas configuración persistente a esa asignación. No proceses documentos personales sin autorización explícita.

## Informe

Separa requisitos, permisos, versión/identidad, archivo previo archivado, comando y código, ruta/metadatos/hash/frescura, instalación, launcher/JAR, listener/health/navegador y OCR. Indica comprobaciones omitidas/no autorizadas, fallos y reinicio pendiente; conserva evidencia sin contenido personal. No firmes, publiques ni hagas commit por este flujo.
