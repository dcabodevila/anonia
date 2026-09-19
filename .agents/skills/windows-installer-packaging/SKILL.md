---
name: windows-installer-packaging
description: "Trigger: empaqueta la aplicación, empaqueta la versión, generar instalador Windows. Genera y verifica el instalador local de Windows."
license: Apache-2.0
metadata:
  author: dcabodevila
  version: "1.3"
---

## Activation Contract

Carga esta habilidad cuando se pida empaquetar la aplicación o una versión, o generar
un instalador de Windows. Opera solo desde la raíz del repositorio.

## Hard Rules

Lee primero el script: es la fuente de verdad del nombre, versión y directorio de salida
vigentes. No instales, firmes, publiques, confirmes, cambies la versión ni hagas commit
por una solicitud de empaquetado. No omitas pruebas ni sustituyas el comando canónico con
`-DskipTests`. No afirmes que el instalador se instaló, que el lanzador funciona ni que
existe un diálogo final pendiente de Sí/No.

## OCR externo prerequisite

El instalador incluye la aplicación y el runtime Java, pero no Tesseract, DLL de OCR ni
modelos. No solicites `-OcrBundleRoot`, `DOC_ANONYMIZER_OCR_BUNDLE`, manifiestos ni
evidencia de redistribución para empaquetar. No instales ni descargues Tesseract desde
este flujo.

El usuario administra su instalación local de Tesseract y el idioma español (`spa`). Si
va a probar OCR en el equipo de destino, debe validar localmente:

```powershell
& 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
```

La salida debe incluir `spa`. Esta validación no demuestra que el instalador se haya
instalado ni que el OCR funcione desde el acceso directo.

## Decision Gates

| Situación | Acción |
| --- | --- |
| Faltan JDK 21, `jpackage`, caché Maven o WiX 3 | Detén el proceso e informa el requisito. |
| Se solicita prueba OCR y falta Tesseract local o `spa` | Detén esa prueba e informa el requisito administrado por el usuario; no instales ni descargues software. |
| Maven, `jpackage` o el script falla | Conserva el error; no uses un EXE anterior. |
| No hay EXE creado o modificado tras iniciar la ejecución | Declara la verificación fallida. |

## Execution Steps

1. Lee [`../../../packaging/windows/build-installer.ps1`](../../../packaging/windows/build-installer.ps1), [`../../../README.md`](../../../README.md) y [`../../../AGENTS.md`](../../../AGENTS.md).
2. Comprueba `JAVA_HOME` con JDK 21 y `jpackage`, Maven con caché disponible para modo sin red y WiX Toolset 3 (`candle.exe`, `light.exe`). El script puede añadir WiX a `PATH` solo durante su ejecución y debe restaurarlo después.
3. Registra la hora de inicio y ejecuta exactamente:
   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\build-installer.ps1
   ```
   El script ejecuta `mvn -o package` con pruebas, prepara una entrada aislada con solo el JAR y deja que `jpackage` incluya el runtime Java. No incorpora `app/ocr` al instalador.
4. Exige salida cero. Inspecciona el EXE realmente creado o modificado desde la hora registrada; anota ruta, tamaño, fechas, metadatos y `Get-FileHash`. Un EXE es una instantánea estática: vuelve a empaquetar tras cualquier cambio. Distingue el instalador del lanzador ya instalado. No sustituyas esto con una prueba que use ejecutables falsos.
5. Para una prueba manual de OCR, el usuario puede usar `TESSERACT_COMMAND` para una apertura desde PowerShell o administrar la carpeta de Tesseract en `PATH`. `$env:TESSERACT_COMMAND = 'C:\ruta\tesseract.exe'` solo afecta a esa consola y no configura el acceso directo; tras cambiar el `PATH` persistente, reinicia el proceso o acceso directo. El instalador no cambia variables de entorno persistentes.

## Output Contract

Informa requisitos comprobados, comando, código de salida y los metadatos/ruta/hash del
EXE nuevo. Declara de forma explícita cualquier falta de frescura, fallo o bloqueo de
herramientas, sin atribuir éxito de instalación, funcionamiento real del OCR ni
configuración persistente de Tesseract.

## References

- [`../../../packaging/windows/build-installer.ps1`](../../../packaging/windows/build-installer.ps1) — fuente de verdad del empaquetado.
- [`../../../README.md`](../../../README.md) — contexto y requisitos del proyecto.
- [`../../../AGENTS.md`](../../../AGENTS.md) — reglas de desarrollo.
