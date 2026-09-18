# Instalador Windows

Genera un instalador EXE por usuario con Java incluido, acceso directo y entrada
en el menú Inicio. El equipo de destino no necesita instalar Java.

## Generar

En Windows se necesitan JDK 21 con `jpackage` (`JAVA_HOME`), Maven en `PATH` y
WiX Toolset 3.x. El script detecta WiX en `Program Files (x86)` y modifica `PATH`
solo durante su ejecución. Maven trabaja offline: las dependencias deben estar
ya disponibles en la caché local.

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\packaging\windows\build-installer.ps1
```

El script ejecuta `mvn -o package` con pruebas y genera
`target/windows-installer/DocAnonymizer-0.1.0.exe`. Usa un directorio nuevo
`target/windows-input-<identificador>` que contiene únicamente el JAR sombreado;
no empaqueta todo `target`. Estos directorios se conservan para diagnóstico.
`-Plan` muestra los argumentos de jpackage sin compilar ni crear archivos.
El EXE no está firmado; Windows puede mostrar una advertencia de SmartScreen.
La generación, instalación y apertura real del navegador requieren validación
separada; las pruebas unitarias no sustituyen esa comprobación.

## Usar y detener

Instale el EXE y abra **DocAnonymizer** desde el acceso directo. Se mantiene una
consola abierta y, solo después de iniciar el servidor, se abre el navegador en
`http://127.0.0.1:8080/`. El servidor escucha exclusivamente en loopback, sin
usar `DOC_ANONYMIZER_HOST` ni `DOC_ANONYMIZER_PORT`.

Pulse **Ctrl+C en la consola** para terminar el proceso. Cerrar la pestaña del
navegador no detiene el servidor. No hay bandeja del sistema ni servicio.
Detenga el proceso antes de desinstalar o actualizar. No se garantiza terminar
una operación en curso al detenerlo: espere a que finalice antes de salir.

Si falla el navegador, abra manualmente la URL impresa. Si el puerto está
ocupado, cierre la otra instancia o ejecute el lanzador instalado desde una
consola con otro puerto: `DocAnonymizer.exe 8081`. Ante un error de inicio el
proceso termina con código 1 sin abrir el navegador; ejecútelo desde una consola
para conservar el mensaje de error. Cada apertura del acceso directo intenta
iniciar una instancia nueva; no reutiliza procesos existentes.

El punto de entrada de escritorio es independiente. El manifiesto del JAR,
la CLI y el arranque de Docker no cambian.
