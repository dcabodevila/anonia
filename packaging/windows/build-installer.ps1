[CmdletBinding()]
param([switch]$Plan, [switch]$Msi)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$target = Join-Path $root 'target'
$jar = Join-Path $target 'doc-anonymizer.jar'
# Directorio nuevo en cada ejecucion: nunca incluye PDFs, informes ni JAR antiguos.
$inputDirectory = Join-Path $target ('windows-input-' + [guid]::NewGuid().ToString('N'))
$outputDirectory = Join-Path $target 'windows-installer'
$extension = if ($Msi) { 'msi' } else { 'exe' }
$generatedInstaller = Join-Path $outputDirectory "anonimuse-0.3.1.$extension"
$installer = Join-Path $outputDirectory "anonimuse-installer.$extension"
# jpackage output is renamed only after checking both selected-format paths.
$resourceTemplateDirectory = Join-Path $PSScriptRoot 'jpackage-resources'
$resourceDirectory = Join-Path $target ('windows-resources-' + [guid]::NewGuid().ToString('N'))
$sourceLogo = Join-Path $root 'src/main/resources/web/anonimuse-logo.png'
$icon = Join-Path $PSScriptRoot 'anonimuse-logo.ico'
if (-not (Test-Path -LiteralPath $icon)) {
    throw "Falta el ICO derivado del logo fuente ${sourceLogo}: $icon"
}
$packageArguments = @(
    '--type', $extension, '--name', 'anonimuse', '--app-version', '0.3.1',
    '--icon', $icon,
    '--vendor', 'DocAnonymizer', '--description', 'Anonimizador local de documentos',
    '--input', $inputDirectory, '--dest', $outputDirectory,
    '--resource-dir', $resourceDirectory,
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
    if ((Test-Path -LiteralPath $generatedInstaller) -or (Test-Path -LiteralPath $installer)) {
        throw "Ya existe un instalador en $outputDirectory. Muévalo antes de generar uno nuevo."
    }
    $null = New-Item -ItemType Directory -Path $resourceDirectory
    $samplePath = Join-Path $resourceDirectory 'rules-example.txt'
    $overridePath = Join-Path $resourceDirectory 'overrides.wxi'
    Copy-Item -LiteralPath (Join-Path $resourceTemplateDirectory 'rules-example.txt') -Destination $samplePath
    $escapedSample = [System.Security.SecurityElement]::Escape($samplePath)
    $escapedOverride = [System.Security.SecurityElement]::Escape($overridePath)
    [IO.File]::WriteAllText($overridePath, "<?xml version=`"1.0`" encoding=`"utf-8`"?>`n<Include><?define JpRulesSource=`"$escapedSample`"?></Include>`n")
    $wixTemplate = [IO.File]::ReadAllText((Join-Path $resourceTemplateDirectory 'main.wxs'))
    [IO.File]::WriteAllText((Join-Path $resourceDirectory 'main.wxs'),
        $wixTemplate.Replace('STAGED_RULES_OVERRIDE', $escapedOverride))
    $null = New-Item -ItemType Directory -Path $inputDirectory
    # La entrada aislada contiene solo el JAR; el OCR se resuelve externamente en ejecucion.
    Copy-Item -LiteralPath $jar -Destination $inputDirectory
    $null = New-Item -ItemType Directory -Path $outputDirectory -Force
    # Sin --runtime-image: jpackage crea e incluye su propio runtime Java.
    & $jpackage @packageArguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage fallo: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $generatedInstaller -PathType Leaf)) {
        throw "jpackage no genero el instalador esperado: $generatedInstaller"
    }
    Move-Item -LiteralPath $generatedInstaller -Destination $installer -ErrorAction Stop
    Write-Host "Instalador generado en $installer"
} finally {
    $env:PATH = $originalPath
    Pop-Location
}
