param(
    [string]$IdeHome = $env:ALAS_PREVIEW_IDE_HOME,
    [string]$BuildDirectory
)

$ErrorActionPreference = 'Stop'
if (-not $IdeHome) { throw 'Set ALAS_PREVIEW_IDE_HOME to the PyCharm 2026.3 installation directory, or pass -IdeHome.' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $BuildDirectory) {
    $latest = Get-ChildItem -LiteralPath (Join-Path $repoRoot '.cache/pycharm') -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'asset-root.txt') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw 'Build with -Test before running IDE checks.' }
    $BuildDirectory = $latest.FullName
}
$ideRoot = (Resolve-Path -LiteralPath $IdeHome).Path
$buildRoot = (Resolve-Path -LiteralPath $BuildDirectory).Path
$assetRoot = [IO.File]::ReadAllText((Join-Path $buildRoot 'asset-root.txt')).Trim()
$sandbox = Join-Path $buildRoot ('ide-check-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$pluginLib = Join-Path $sandbox 'plugins/autoepicseven-asset-preview/lib'
$testLib = Join-Path $sandbox 'plugins/autoepicseven-asset-preview-checks/lib'
$testClasses = Join-Path $buildRoot 'test-classes'
if (-not (Test-Path -LiteralPath (Join-Path $testClasses 'autoepicseven/preview/IdeChecks.class'))) {
    throw 'Build with -Test before running IDE checks.'
}
New-Item -ItemType Directory -Path $pluginLib, $testLib, (Join-Path $testClasses 'META-INF') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $buildRoot 'package/autoepicseven-asset-preview/lib/asset-preview.jar') -Destination $pluginLib
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'tests/plugin.xml') -Destination (Join-Path $testClasses 'META-INF/plugin.xml')
[IO.Compression.ZipFile]::CreateFromDirectory($testClasses, (Join-Path $testLib 'checks.jar'))
$metadata = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $ideRoot 'product-info.json') | ConvertFrom-Json
$launch = $metadata.launch | Where-Object { $_.os -eq 'Windows' -and $_.arch -eq 'amd64' } | Select-Object -First 1
$bootClasspath = ($launch.bootClassPathJarNames | ForEach-Object { Join-Path $ideRoot "lib/$_" }) -join ';'
$jvmArguments = @('-Xmx1536m', '-ea')
$jvmArguments += $launch.additionalJvmArguments | ForEach-Object { $_.Replace('%IDE_HOME%', $ideRoot) }
$jvmArguments += @(
    '-Djava.awt.headless=true', '-Didea.headless.enable.statistics=false',
    '-Didea.initially.ask.config=false', '-Didea.required.plugins.id=autoepicseven.asset.preview.checks',
    "-Didea.home.path=$ideRoot", "-Didea.config.path=$sandbox/config", "-Didea.system.path=$sandbox/system",
    "-Didea.log.path=$sandbox/log", "-Didea.plugins.path=$sandbox/plugins", '-Dsplash=false',
    '-classpath', $bootClasspath, $launch.mainClass, 'asset-preview-checks', $assetRoot, "$sandbox/project"
)
$argumentFile = Join-Path $sandbox 'ide-run.args'
$quoted = $jvmArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($argumentFile, $quoted, [Text.UTF8Encoding]::new($false))
& (Join-Path $ideRoot $launch.javaExecutablePath) "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw "IDE checks failed; logs: $sandbox/log" }
