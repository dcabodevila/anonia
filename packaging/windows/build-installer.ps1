[CmdletBinding()]
param(
    [switch]$Plan,
    [string]$OcrBundleRoot = $env:DOC_ANONYMIZER_OCR_BUNDLE
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

function Normalize-BundlePath([string]$RelativePath) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        throw 'El inventario OCR contiene una ruta vacia o absoluta.'
    }
    $normalized = $RelativePath.Replace('\', '/')
    if ($normalized -match '(^|/)\.\.?(/|$)') {
        throw "El inventario OCR contiene una ruta no permitida: $RelativePath"
    }
    return $normalized
}

function Assert-NoReparsePoint([string]$Path, [string]$Description) {
    $currentPath = [IO.Path]::GetFullPath($Path)
    while ($true) {
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "El bundle OCR no puede incluir enlaces, junctions ni reparse points: $Description"
        }
        $parent = [IO.Directory]::GetParent($currentPath)
        if ($null -eq $parent -or $parent.FullName -eq $currentPath) {
            return
        }
        $currentPath = $parent.FullName
    }
}

function Copy-BundleFile([string]$BundleRoot, [string]$RelativePath, [string]$DestinationRoot) {
    $source = [IO.Path]::GetFullPath((Join-Path $BundleRoot $RelativePath))
    $rootWithSeparator = $BundleRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $source.StartsWith($rootWithSeparator, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Falta el archivo inventariado del bundle OCR: $RelativePath"
    }
    Assert-NoReparsePoint -Path $source -Description $RelativePath
    $destination = Join-Path $DestinationRoot $RelativePath
    $destinationParent = Split-Path -Parent $destination
    $null = New-Item -ItemType Directory -Path $destinationParent -Force
    Copy-Item -LiteralPath $source -Destination $destination
}

function Stage-OcrBundle([string]$BundleRoot, [string]$InputDirectory) {
    if ([string]::IsNullOrWhiteSpace($BundleRoot)) {
        throw 'Indique -OcrBundleRoot con una fuente OCR curada que contenga bundle-manifest.json.'
    }
    $bundleRootPath = [IO.Path]::GetFullPath($BundleRoot)
    if (-not (Test-Path -LiteralPath $bundleRootPath -PathType Container)) {
        throw "No existe la fuente OCR curada: $bundleRootPath"
    }
    Assert-NoReparsePoint -Path $bundleRootPath -Description $bundleRootPath
    $manifestPath = Join-Path $bundleRootPath 'bundle-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Falta bundle-manifest.json en la fuente OCR curada: $bundleRootPath"
    }
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    } catch {
        throw "bundle-manifest.json no es valido: $($_.Exception.Message)"
    }

    $runtimeFiles = @($manifest.runtimeFiles | ForEach-Object { Normalize-BundlePath $_ })
    $licenseFiles = @($manifest.licenseFiles | ForEach-Object { Normalize-BundlePath $_ })
    $thirdPartyLicenseEvidence = Normalize-BundlePath ([string]$manifest.thirdPartyLicenseEvidence)
    if ($runtimeFiles.Count -eq 0 -or $licenseFiles.Count -eq 0) {
        throw 'bundle-manifest.json debe inventariar runtimeFiles y licenseFiles no vacios.'
    }
    if ($runtimeFiles -notcontains 'tesseract.exe' -or
        $runtimeFiles -notcontains 'tessdata/spa.traineddata' -or
        -not ($runtimeFiles | Where-Object { $_ -match '^[^/]+\.dll$' })) {
        throw 'El inventario OCR debe incluir tesseract.exe, DLLs y tessdata/spa.traineddata.'
    }
    foreach ($runtimeFile in $runtimeFiles) {
        if ($runtimeFile -ne 'tesseract.exe' -and
            $runtimeFile -ne 'tessdata/spa.traineddata' -and
            $runtimeFile -notmatch '^[^/]+\.dll$') {
            throw "El inventario OCR solo puede incluir tesseract.exe, DLLs y tessdata/spa.traineddata: $runtimeFile"
        }
    }
    foreach ($licenseFile in $licenseFiles) {
        if (-not $licenseFile.StartsWith('licenses/', [StringComparison]::OrdinalIgnoreCase)) {
            throw "La evidencia de licencia OCR debe instalarse bajo licenses/: $licenseFile"
        }
    }
    if (-not $thirdPartyLicenseEvidence.StartsWith('licenses/THIRD_PARTY_NOTICES', [StringComparison]::OrdinalIgnoreCase) -or
        $licenseFiles -notcontains $thirdPartyLicenseEvidence) {
        throw 'El manifiesto debe identificar thirdPartyLicenseEvidence bajo licenses/THIRD_PARTY_NOTICES*.'
    }

    $ocrDirectory = Join-Path $InputDirectory 'ocr'
    $null = New-Item -ItemType Directory -Path $ocrDirectory -Force
    foreach ($runtimeFile in $runtimeFiles) {
        Copy-BundleFile $bundleRootPath $runtimeFile $ocrDirectory
    }
    foreach ($licenseFile in $licenseFiles) {
        Copy-BundleFile $bundleRootPath $licenseFile $ocrDirectory
    }
}

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
    Copy-Item -LiteralPath $jar -Destination $inputDirectory
    Stage-OcrBundle -BundleRoot $OcrBundleRoot -InputDirectory $inputDirectory
    $null = New-Item -ItemType Directory -Path $outputDirectory -Force
    # Sin --runtime-image: jpackage crea e incluye su propio runtime Java.
    & $jpackage @packageArguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage fallo: $LASTEXITCODE" }
    Write-Host "Instalador generado en $outputDirectory"
} finally {
    $env:PATH = $originalPath
    Pop-Location
}
