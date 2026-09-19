[CmdletBinding()]
param(
    [switch]$Plan
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$target = Join-Path $root 'target'
$jar = Join-Path $target 'doc-anonymizer.jar'
# Directorio nuevo en cada ejecucion: nunca incluye PDFs, informes ni JAR antiguos.
$inputDirectory = Join-Path $target ('windows-input-' + [guid]::NewGuid().ToString('N'))
$outputDirectory = Join-Path $target 'windows-installer'
$packageArguments = @(
    '--type', 'exe', '--name', 'DocAnonymizer', '--app-version', '0.1.0',
    '--vendor', 'DocAnonymizer', '--description', 'Anonimizador local de documentos',
    '--input', $inputDirectory, '--dest', $outputDirectory,
    '--main-jar', 'doc-anonymizer.jar',
    '--main-class', 'com.docanonymizer.adapter.web.DesktopLauncher',
    '--add-modules', 'ALL-MODULE-PATH',
    '--win-per-user-install', '--win-menu', '--win-shortcut', '--win-console',
    '--win-upgrade-uuid', 'ce0936d4-f914-4f47-9052-5b286df59f57'
)

# El plan usa exactamente los argumentos de la compilacion, sin efectos secundarios.
if ($Plan) {
    ConvertTo-Json -InputObject $packageArguments
    exit 0
}

$originalPath = $env:PATH
Push-Location $root
try {
    $null = Get-Command mvn -ErrorAction Stop
    if (-not $env:JAVA_HOME) { throw 'Defina JAVA_HOME con un JDK 21 que incluya jpackage.' }
    $jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
    if (-not (Test-Path -LiteralPath $jpackage)) { throw "No existe $jpackage" }
    if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -or
        -not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
        $wix = Get-ChildItem -Path (Join-Path ${env:ProgramFiles(x86)} 'WiX Toolset v3*') -Directory |
            Where-Object {
                (Test-Path -LiteralPath (Join-Path $_.FullName 'bin/candle.exe')) -and
                (Test-Path -LiteralPath (Join-Path $_.FullName 'bin/light.exe'))
            } | Sort-Object Name -Descending | Select-Object -First 1
        if (-not $wix) { throw 'Instale WiX Toolset 3.x (candle.exe y light.exe) antes de empaquetar.' }
        $env:PATH = (Join-Path $wix.FullName 'bin') + ';' + $env:PATH
    }
    & mvn -o package
    if ($LASTEXITCODE -ne 0) { throw "Maven fallo: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $jar)) { throw "Falta el JAR sombreado: $jar" }
    $null = New-Item -ItemType Directory -Path $inputDirectory
    # La entrada aislada contiene solo el JAR; el OCR se resuelve externamente en ejecucion.
    Copy-Item -LiteralPath $jar -Destination $inputDirectory
    $null = New-Item -ItemType Directory -Path $outputDirectory -Force
    # Sin --runtime-image: jpackage crea e incluye su propio runtime Java.
    & $jpackage @packageArguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage fallo: $LASTEXITCODE" }
    Write-Host "Instalador generado en $outputDirectory"
} finally {
    $env:PATH = $originalPath
    Pop-Location
}
