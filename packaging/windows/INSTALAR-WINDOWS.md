# Instalar DocAnonymizer en Windows

Recibirá el instalador `DocAnonymizer-0.2.0.exe`. Elija la ruta antes de instalar:

- **Solo documentos PDF con capa de texto:** instale DocAnonymizer directamente.
- **Imágenes JPEG o PNG:** primero instale y configure Tesseract OCR administrado externamente con el idioma español (`spa`); después instale DocAnonymizer.

El instalador incluye Java. No incluye, descarga ni configura Tesseract ni sus modelos OCR.

## Instalación rápida

### Si solo procesará PDF

1. Localice el archivo recibido `DocAnonymizer-0.2.0.exe`.
2. Ejecute el instalador y siga las indicaciones de Windows.
3. Cuando Windows finalice el asistente, la instalación habrá terminado. Después, inicie DocAnonymizer manualmente y confirme que se abre correctamente antes de usarlo para procesar PDF.

### Si procesará JPEG o PNG

1. **Antes de instalar DocAnonymizer**, instale una distribución de Tesseract para Windows y agregue el idioma español (`spa`) mediante el procedimiento administrado por su organización.
2. Compruebe la instalación desde PowerShell, ajustando la ruta si Tesseract se instaló en otra ubicación:

   ```powershell
   & 'C:\Program Files\Tesseract-OCR\tesseract.exe' --list-langs
   ```

   **Resultado esperado:** la lista incluye `spa`. Si no aparece, complete la configuración de Tesseract antes de continuar.
3. Ejecute `DocAnonymizer-0.2.0.exe` y siga las indicaciones de Windows.
4. Configure cómo DocAnonymizer encuentra el ejecutable de Tesseract si su equipo lo requiere. Consulte la [configuración completa de Tesseract](INSTALAR-OCR.md).

## Qué incluye cada componente

| Componente | Cómo se obtiene | Para qué se necesita |
|---|---|---|
| DocAnonymizer y Java | El instalador `DocAnonymizer-0.2.0.exe` | Procesar PDF con capa de texto y ejecutar la aplicación. |
| Tesseract y `spa` | Instalación externa administrada por el usuario u organización | Extraer texto de imágenes JPEG/PNG. |

El procesamiento de documentos se realiza localmente y no usa servicios ni conexiones externas de red. Tesseract tampoco se instala automáticamente al instalar DocAnonymizer.

## Si algo no funciona

| Situación | Acción segura |
|---|---|
| Solo necesita PDF | No instale Tesseract; continúe con el instalador de DocAnonymizer. |
| Va a procesar JPEG/PNG y `spa` no aparece | Revise o agregue el idioma español en Tesseract y repita `--list-langs`. |
| Tesseract está instalado en otra carpeta | Use la ruta real de `tesseract.exe` al validar y siga la guía de configuración. |
| Windows muestra una advertencia al abrir el instalador | Consulte con el equipo que le proporcionó el instalador antes de continuar. |
| Necesita configurar una variable de entorno o `PATH` | Siga [INSTALAR-OCR.md](INSTALAR-OCR.md); el instalador no modifica esas variables. |

## Más información

- [Configuración completa de Tesseract OCR en Windows](INSTALAR-OCR.md)
- [README del proyecto](../../README.md)
