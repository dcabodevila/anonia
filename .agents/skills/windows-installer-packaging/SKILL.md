---
name: windows-installer-packaging
description: "Trigger: empaqueta la aplicación, empaqueta la versión, generar instalador Windows, regenerar MSI. Genera y verifica MSI local; EXE solo a petición explícita."
license: Apache-2.0
metadata:
  author: dcabodevila
  version: "1.4"
---

## Activation Contract

Empaqueta/regenera desde la raíz. MSI predeterminado del agente con `-Msi`; EXE solo a petición explícita, sin cambiar el predeterminado del script.

## Hard Rules

- Lee script y [ciclo de vida](references/installer-lifecycle.md); aplica todas sus reglas, incluido OCR/entorno de usuario.
- Generar no autoriza instalar ni abrir. Comprueba versión instalada/MSI; pregunta antes de aumentarla o reinstalar ambiguamente la misma versión. Nunca aumentes automáticamente.
- No firmes, publiques, hagas commit, descargues, mates procesos, desinstales ni reinicies implícitamente. Conserva productos ajenos y reglas personales.
- Archiva sin sobrescribir, con nombre único/hash. Script canónico con pruebas, nunca `-DskipTests`; conserva errores, no sustituyas por artefactos antiguos/ejecutables falsos.
- Tesseract/DLL/modelos externos: no descargues ni instales; no solicites parámetros de bundle/manifiestos/evidencia de redistribución (referencia).

## Decision Gates

| Situación | Acción |
| --- | --- |
| Falta JDK 21/jpackage, caché Maven o WiX 3 | Detén e informa. |
| Colisión de archivos | Archiva; no implica aumentar versión. |
| Misma versión/ambigüedad | Pregunta antes de instalar/aumentar. |
| Fallo o artefacto no fresco | Declara fallo, conserva diagnóstico. |
| Instalación/apertura sin permiso | Termina tras generar. |
| Instalación: 0 / 3010 | Verifica / informa reinicio pendiente; no reinicies. |
| 1618 u otro fallo | Detén, sin matar procesos/reintentar automáticamente. |
| OCR sin Tesseract/`spa` | Detén esa prueba; requisito del usuario. |

## Execution Steps

1. Lee referencias; comprueba requisitos, versiones y colisiones.
2. Registra inicio; ejecuta:
   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File './packaging/windows/build-installer.ps1' -Msi
   ```
   EXE explícito: omite solo `-Msi`. En Bash protege rutas y código PowerShell con comillas simples; no expandas `$` con comillas dobles.
3. Exige salida cero, frescura, ruta/tamaño/fechas/hash; MSI: ProductVersion/ProductCode/UpgradeCode. Es una instantánea; cambios requieren regeneración solicitada.
4. Solo con permiso explícito, aplica referencia: `msiexec` con log, `/passive /norestart`, versión/ruta/JAR instalado, lanzador real, listener/health y URL del navegador. Build/pruebas/Java del repositorio no prueban runtime instalado.

## Output Contract

Informa permisos/requisitos/versiones, archivo archivado, comando/salida, metadatos/hash/frescura. Separa generación/instalación/apertura/OCR; declara fallos, omisiones y reinicio pendiente. No inventes éxito OCR, persistencia ni diálogo Sí/No.

## References

- [Ciclo de vida](references/installer-lifecycle.md) — lectura obligatoria: permisos, instalación, OCR.
- [Script](../../../packaging/windows/build-installer.ps1) — fuente de verdad, con pruebas.
- [README](../../../README.md), [AGENTS](../../../AGENTS.md) — requisitos/reglas.
