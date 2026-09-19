# Instalar OCR en Windows (Tesseract en español)

DocAnonymizer no incluye Tesseract ni los modelos OCR en su instalador. Para leer imágenes JPEG o PNG, instale por separado un Tesseract de Windows x64 y el idioma español (`spa`) en su equipo.

## Instalación rápida

1. Instale una distribución de Tesseract para Windows x64 y agregue el idioma español.
   Consulte las [instrucciones oficiales de instalación de Tesseract](https://tesseract-ocr.github.io/tessdoc/Installation.html) para las opciones de distribución en Windows. La descarga requiere conexión a Internet; después, el OCR puede usarse sin conexión.
2. Compruebe que Tesseract y `spa` están instalados. Sustituya la ruta por la ruta real de `tesseract.exe`:

   ```powershell
   & 'C:\Program Files\Tesseract-OCR\tesseract.exe' --version
   & 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
   ```

   **Resultado esperado:** el primer comando muestra la versión de Tesseract y el segundo incluye una línea `spa`.
3. Configure una de las opciones siguientes y reinicie DocAnonymizer y cualquier consola abierta.

## Opción recomendada: ruta explícita por usuario

Configure `TESSERACT_COMMAND` como variable de entorno **de usuario** mediante la interfaz de Variables de entorno de Windows:

1. Abra la administración de Variables de entorno de Windows y cree o edite `TESSERACT_COMMAND` para su usuario.
2. Asigne la ruta completa de `tesseract.exe`, por ejemplo `C:\Program Files\Tesseract-OCR\tesseract.exe`.
3. Escriba solo la ruta: no incluya comillas literales en el valor.
4. Cierre y vuelva a abrir DocAnonymizer y las consolas. Si el acceso directo sigue usando un entorno anterior, cierre sesión y vuelva a iniciarla antes de probar otra vez.

Esta opción evita depender de qué directorios estén en `PATH` y no modifica variables globales del sistema.

## Alternativa: añadir Tesseract a `PATH`

También puede añadir la **carpeta** que contiene Tesseract a su `PATH` de usuario mediante la interfaz de Variables de entorno de Windows. Por ejemplo: `C:\Program Files\Tesseract-OCR`.

No añada `tesseract.exe` a `PATH`; añada su carpeta. Reinicie la aplicación y las consolas para que los nuevos procesos reciban el valor actualizado. Con esta opción, compruebe además:

```powershell
tesseract --version
tesseract --list-langs
```

**Resultado esperado:** ambos comandos se ejecutan y la lista de idiomas contiene `spa`.

## Configuración temporal para una consola

Para probar desde una sola sesión de PowerShell sin cambiar variables persistentes:

```powershell
$env:TESSERACT_COMMAND = 'C:\Program Files\Tesseract-OCR\tesseract.exe'
& '<ruta-al-ejecutable-de-DocAnonymizer>\DocAnonymizer.exe'
```

La configuración termina al cerrar esa consola y no configura el acceso directo del escritorio. La ubicación de la aplicación no está garantizada: para encontrar la ruta real, abra las propiedades del acceso directo de DocAnonymizer y consulte su destino; reemplácela en el marcador anterior.

## Prueba de OCR

1. Abra DocAnonymizer después de configurar Tesseract.
2. Procese una imagen JPEG o PNG no sensible que contenga texto en español.
3. Confirme que el OCR reconoce texto y no informa de un idioma o ejecutable ausente.

El procesamiento de OCR es local: esta prueba no carga documentos en ningún servicio. Use una imagen no sensible de todos modos.

## Solución de problemas

| Problema | Qué comprobar |
|---|---|
| No aparece `spa` | Vuelva a instalar o agregue el modelo de idioma español en su instalación local de Tesseract y repita `--list-langs` con la ruta completa. |
| Se usa un ejecutable incorrecto o una instancia antigua | Confirme `TESSERACT_COMMAND` con la ruta correcta, cierre la aplicación y todas las consolas, y vuelva a abrirlas. Si inició desde un acceso directo, cierre sesión y vuelva a iniciarla si conserva variables antiguas. |
| La aplicación parece ignorar la variable o `PATH` | Una instalación antigua puede tener un runtime legado en `app/ocr`, que tiene prioridad por compatibilidad. No lo elimine automáticamente; compruebe primero qué instalación de la aplicación está abriendo y actualícela o solicite soporte si es necesario. |

El instalador de DocAnonymizer no instala Tesseract, modelos OCR ni modifica `TESSERACT_COMMAND`, `PATH` u otras variables de entorno persistentes.
