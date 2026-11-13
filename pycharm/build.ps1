param(
    [string]$IdeHome = $env:ALAS_PREVIEW_IDE_HOME,
    [switch]$Test,
    [string]$AssetRepository
)

$ErrorActionPreference = 'Stop'
if (-not $IdeHome) { throw 'Set ALAS_PREVIEW_IDE_HOME to the PyCharm 2026.3 installation directory, or pass -IdeHome.' }
$ideRoot = (Resolve-Path -LiteralPath $IdeHome).Path
$pluginRoot = $PSScriptRoot
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $pluginRoot '..')).Path
$metadata = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $ideRoot 'product-info.json') | ConvertFrom-Json
if ($metadata.productCode -notin @('PY', 'PC') -or $metadata.buildNumber -notmatch '^263\.') {
    throw 'This plugin targets PyCharm 2026.3 (build 263).'
}
$compiler = Join-Path $ideRoot 'jbr/bin/javac.exe'
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = (Get-Command javac -ErrorAction Stop).Source }
$runtime = Join-Path $ideRoot 'jbr/bin/java.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$buildRoot = Join-Path $repoRoot ".cache/pycharm/$stamp"
$classes = Join-Path $buildRoot 'classes'
$staging = Join-Path $buildRoot 'package/autoepicseven-asset-preview/lib'
$distribution = Join-Path $repoRoot 'dist/pycharm'
New-Item -ItemType Directory -Path $classes, $staging, $distribution -Force | Out-Null
$jars = Get-ChildItem -LiteralPath (Join-Path $ideRoot 'lib'), (Join-Path $ideRoot 'plugins/python-ce/lib') -Filter '*.jar' -Recurse -File
$classpath = ($jars.FullName -join ';').Replace('\', '/')
$sources = Get-ChildItem -LiteralPath (Join-Path $pluginRoot 'src/main/java') -Filter '*.java' -Recurse -File
$arguments = @('-encoding', 'UTF-8', '--release', '25', '-classpath', ('"' + $classpath + '"'), '-d', ('"' + $classes.Replace('\', '/') + '"'))
$arguments += $sources.FullName | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
$argumentFile = Join-Path $buildRoot 'compile.args'
[IO.File]::WriteAllLines($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
& $compiler "@$argumentFile"
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed.' }
Copy-Item -LiteralPath (Join-Path $pluginRoot 'src/main/resources/META-INF') -Destination $classes -Recurse
$pluginJar = Join-Path $staging 'asset-preview.jar'
[IO.Compression.ZipFile]::CreateFromDirectory($classes, $pluginJar)
$pluginMetadata = [xml](Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $pluginRoot 'src/main/resources/META-INF/plugin.xml'))
$version = $pluginMetadata.'idea-plugin'.version
$archive = Join-Path $distribution "autoepicseven-asset-preview-$version-$stamp.zip"
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $buildRoot 'package'), $archive)

if ($Test) {
    $testClasses = Join-Path $buildRoot 'test-classes'
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $testSources = Get-ChildItem -LiteralPath (Join-Path $pluginRoot 'tests') -Filter '*.java' -Recurse -File
    $testArguments = @('-encoding', 'UTF-8', '--release', '25', '-classpath', ('"' + $classes.Replace('\', '/') + ';' + $classpath + '"'), '-d', ('"' + $testClasses.Replace('\', '/') + '"'))
    $testArguments += $testSources.FullName | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
    $testArgumentFile = Join-Path $buildRoot 'test-compile.args'
    [IO.File]::WriteAllLines($testArgumentFile, $testArguments, [Text.UTF8Encoding]::new($false))
    & $compiler "@$testArgumentFile"
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    $fixtureRoot = Join-Path $buildRoot 'fixture'
    & $runtime '-Djava.awt.headless=true' '-classpath' $testClasses 'autoepicseven.preview.FixtureProject' $fixtureRoot
    if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation failed.' }
    $assetRoot = if ($AssetRepository) { (Resolve-Path -LiteralPath $AssetRepository).Path } else { $fixtureRoot }
    [IO.File]::WriteAllText((Join-Path $buildRoot 'asset-root.txt'), $assetRoot, [Text.UTF8Encoding]::new($false))
    $testRuntimeArgs = @('-ea', '-Djava.awt.headless=true', '-classpath', ('"' + $testClasses.Replace('\', '/') + ';' + $classes.Replace('\', '/') + '"'), 'autoepicseven.preview.PreviewChecks', ('"' + $assetRoot.Replace('\', '/') + '"'))
    $runtimeArgumentFile = Join-Path $buildRoot 'test-run.args'
    [IO.File]::WriteAllLines($runtimeArgumentFile, $testRuntimeArgs, [Text.UTF8Encoding]::new($false))
    & $runtime "@$runtimeArgumentFile"
    if ($LASTEXITCODE -ne 0) { throw 'Image checks failed.' }
}
Write-Output "Plugin archive: $archive"
Write-Output "Build directory: $buildRoot"
