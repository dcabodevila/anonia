# Instalador Windows

Esta guía es para quien genera el instalador. Los clientes deben seguir la [guía de instalación de DocAnonymizer en Windows](INSTALAR-WINDOWS.md), que distingue entre el uso solo con PDF y el requisito previo de Tesseract para imágenes JPEG/PNG.

El instalador EXE por usuario incluye la aplicación y Java, y crea el acceso directo y la entrada de menú Inicio definidos por el empaquetado. En cada instalación o actualización, configura para el usuario actual `TESSERACT_COMMAND` con el valor exacto `C:\Program Files\Tesseract-OCR\tesseract.exe`, reemplazando cualquier valor de usuario existente incluso si Tesseract todavía no está instalado. Al desinstalar, elimina el valor administrado. El instalador no redistribuye ni instala Tesseract ni modelos OCR, no modifica `PATH` ni escribe configuración de máquina; esa dependencia se administra externamente para quienes procesarán imágenes. Tras instalar o actualizar, el cliente debe reiniciar DocAnonymizer o abrir de nuevo el acceso directo.

## Generar

En Windows se necesitan JDK 21 con `jpackage` (`JAVA_HOME`), Maven en `PATH` y WiX Toolset 3.x. El script detecta WiX en `Program Files (x86)` y modifica `PATH` solo durante su ejecución; lo restaura al terminar. Maven trabaja offline: las dependencias deben estar ya disponibles en la caché local.

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\build-installer.ps1
```

El script ejecuta `mvn -o package` con pruebas y genera
`target/windows-installer/DocAnonymizer-0.3.0.exe`. Usa un directorio nuevo
`target/windows-input-<identificador>` que contiene únicamente el JAR sombreado;
por ello jpackage no instala todo `target` ni un directorio `app/ocr`. Estos
directorios se conservan para diagnóstico. `-Plan` muestra los argumentos de
jpackage sin compilar ni crear archivos.

No descargue ni instale software desde este flujo. El EXE no está firmado; Windows
puede mostrar una advertencia de SmartScreen. La generación, instalación y apertura
real del navegador requieren validación separada; las pruebas unitarias no sustituyen
esa comprobación.

## Documentación para clientes

No replique las instrucciones de instalación ni de configuración de OCR en este documento. Envíe a los clientes a [INSTALAR-WINDOWS.md](INSTALAR-WINDOWS.md). Para el detalle de Tesseract administrado externamente, la guía de cliente enlaza a [INSTALAR-OCR.md](INSTALAR-OCR.md).
