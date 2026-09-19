# Instalador Windows

Genera un instalador EXE por usuario con Java incluido, acceso directo y entrada
en el menú Inicio. El instalador incluye solo la aplicación y el runtime Java: **no
redistribuye ni instala Tesseract ni modelos OCR**. El equipo de destino no necesita
instalar Java, pero necesita un Tesseract local con español (`spa`) para procesar
fotos JPEG/PNG.

## Generar

En Windows se necesitan JDK 21 con `jpackage` (`JAVA_HOME`), Maven en `PATH` y
WiX Toolset 3.x. El script detecta WiX en `Program Files (x86)` y modifica `PATH`
solo durante su ejecución; lo restaura al terminar. Maven trabaja offline: las
dependencias deben estar ya disponibles en la caché local.

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\build-installer.ps1
```

El script ejecuta `mvn -o package` con pruebas y genera
`target/windows-installer/DocAnonymizer-0.1.0.exe`. Usa un directorio nuevo
`target/windows-input-<identificador>` que contiene únicamente el JAR sombreado;
por ello jpackage no instala todo `target` ni un directorio `app/ocr`. Estos
directorios se conservan para diagnóstico. `-Plan` muestra los argumentos de
jpackage sin compilar ni crear archivos.

No descargue ni instale software desde este flujo. El EXE no está firmado; Windows
puede mostrar una advertencia de SmartScreen. La generación, instalación y apertura
real del navegador requieren validación separada; las pruebas unitarias no sustituyen
esa comprobación.

## Configurar OCR en el equipo de destino

Instale Tesseract y el idioma español por medios administrados por el usuario. Antes
de usar la aplicación, compruebe localmente que `spa` está disponible:

```powershell
& 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
```

La lista debe incluir `spa`. La aplicación no realiza descargas ni instalaciones automáticas.

### Ruta explícita para una apertura desde PowerShell

Defina `TESSERACT_COMMAND` solo para ese proceso y lance el ejecutable desde la misma consola.
La variable es temporal y no configura el acceso directo ni variables persistentes de Windows.

### Alternativa: PATH administrado por el usuario

El usuario puede administrar Tesseract en su `PATH`; cierre y vuelva a abrir la aplicación para
que el proceso nuevo herede el cambio. El instalador no modifica `PATH`, `TESSERACT_COMMAND`,
`TESSDATA_PREFIX` ni otra variable de entorno persistente.

## Usar y detener

Instale el EXE y abra **DocAnonymizer** desde el acceso directo. Se mantiene una
consola abierta y, solo después de iniciar el servidor, se abre el navegador en
`http://127.0.0.1:8080/`. El servidor escucha exclusivamente en loopback, sin
usar `DOC_ANONYMIZER_HOST` ni `DOC_ANONYMIZER_PORT`.

Pulse **Ctrl+C en la consola** para terminar el proceso. Cerrar la pestaña del
navegador no detiene el servidor. No hay bandeja del sistema ni servicio.
Detenga el proceso antes de desinstalar o actualizar. No se garantiza terminar
una operación en curso al detenerlo: espere a que finalice antes de salir.

Si falla el navegador, abra manualmente la URL impresa. Si el puerto está ocupado,
cierre la otra instancia o ejecute el lanzador instalado desde una consola con otro
puerto: `DocAnonymizer.exe 8081`. Ante un error de inicio el proceso termina con
código 1 sin abrir el navegador; ejecútelo desde una consola para conservar el
mensaje de error. Cada apertura del acceso directo intenta iniciar una instancia
nueva; no reutiliza procesos existentes.

El punto de entrada de escritorio es independiente. El manifiesto del JAR, la CLI y
el arranque de Docker no cambian.
