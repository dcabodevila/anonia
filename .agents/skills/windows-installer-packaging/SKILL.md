---
name: windows-installer-packaging
description: "Trigger: empaqueta la aplicación, empaqueta la versión, generar instalador Windows. Genera y verifica el instalador local de Windows."
license: Apache-2.0
metadata:
  author: dcabodevila
  version: "1.2"
---

## Activation Contract

Carga esta habilidad cuando se pida empaquetar la aplicación o una versión, o generar un instalador de Windows. Opera solo desde la raíz del repositorio.

## Hard Rules

Lee primero el script: es la fuente de verdad del nombre, versión y directorio de salida vigentes. No instales, firmes, publiques, confirmes, cambies la versión ni hagas commit por una solicitud de empaquetado. No omitas pruebas ni sustituyas el comando canónico con `-DskipTests`. No afirmes que el instalador se instaló, que el lanzador funciona ni que existe un diálogo final pendiente de Sí/No.

## OCR privado prerequisite

El instalador incluye OCR privado bajo `app/ocr`, no cambia `PATH`, `TESSDATA_PREFIX`
ni otra variable de entorno de forma persistente. Antes de empaquetar, exige una fuente
OCR curada indicada con `-OcrBundleRoot` (o, solo para esa ejecución,
`DOC_ANONYMIZER_OCR_BUNDLE`) que contenga:

```text
bundle-manifest.json
tesseract.exe
<runtime dependency>.dll
tessdata/spa.traineddata
licenses/THIRD_PARTY_NOTICES...
licenses/<license evidence files>
```

El manifiesto debe inventariar `runtimeFiles`, `licenseFiles` y
`thirdPartyLicenseEvidence`. No aceptes la instalación local de Tesseract como fuente
lista para distribuir si carece de inventario o evidencia de avisos/licencias de
terceros. La presencia de esos archivos no demuestra cumplimiento de redistribución:
no afirmes conformidad legal, procedencia completa ni que el EXE está listo para
redistribuir mientras falte esa revisión.

## Decision Gates

| Situación | Acción |
| --- | --- |
| Faltan JDK 21, `jpackage`, caché Maven, WiX 3, la fuente OCR curada, su manifiesto, DLL/modelo `spa` o evidencia de terceros | Detén el proceso e informa el requisito. |
| Maven, `jpackage` o el script falla | Conserva el error; no uses un EXE anterior. |
| No hay EXE creado o modificado tras iniciar la ejecución | Declara la verificación fallida. |

## Execution Steps

1. Lee [`../../../packaging/windows/build-installer.ps1`](../../../packaging/windows/build-installer.ps1), [`../../../README.md`](../../../README.md) y [`../../../AGENTS.md`](../../../AGENTS.md).
2. Comprueba `JAVA_HOME` con JDK 21 y `jpackage`, Maven con caché disponible para modo sin red, WiX Toolset 3 (`candle.exe`, `light.exe`) y la fuente OCR curada. Verifica que el manifiesto solo inventaría `tesseract.exe`, DLLs de runtime, `tessdata/spa.traineddata` y licencias bajo `licenses/`, incluida la evidencia `THIRD_PARTY_NOTICES` declarada.
3. Registra la hora de inicio y ejecuta exactamente:
   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\build-installer.ps1 -OcrBundleRoot <ruta-curada>
   ```
   El script ejecuta `mvn -o package` con pruebas, prepara una entrada aislada con el JAR y `ocr/`, y deja que `jpackage` incluya el runtime Java y `app/ocr`.
4. Exige salida cero. Inspecciona el EXE realmente creado o modificado desde la hora registrada; anota ruta, tamaño, fechas, metadatos y `Get-FileHash`. Un EXE es una instantánea estática: vuelve a empaquetar tras cualquier cambio. Distingue el instalador del lanzador ya instalado. No sustituyas esto con una prueba que use ejecutables falsos.

## Output Contract

Informa requisitos comprobados, fuente OCR y manifiesto usados, comando, código de salida y los metadatos/ruta/hash del EXE nuevo. Declara de forma explícita cualquier falta de frescura, fallo o bloqueo de procedencia/licencias, sin atribuir éxito de instalación, funcionamiento real del OCR ni conformidad de redistribución.

## References

- [`../../../packaging/windows/build-installer.ps1`](../../../packaging/windows/build-installer.ps1) — fuente de verdad del empaquetado.
- [`../../../README.md`](../../../README.md) — contexto y requisitos del proyecto.
- [`../../../AGENTS.md`](../../../AGENTS.md) — reglas de desarrollo.
