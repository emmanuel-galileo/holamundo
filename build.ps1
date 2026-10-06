# Fresh classes prevent obsolete bytecode from leaking into the executable JAR.
$ErrorActionPreference = "Stop"
$taskRoot = $PSScriptRoot
$taskTarget = Join-Path $taskRoot "target"
$taskBuild = Join-Path $taskTarget (".build-" + [Guid]::NewGuid().ToString("N"))
$taskClasses = Join-Path $taskBuild "classes"
$taskTests = Join-Path $taskBuild "test-classes"
New-Item -ItemType Directory -Force -Path $taskClasses, $taskTests | Out-Null
try {
    $taskSources = (Get-ChildItem -LiteralPath (Join-Path $taskRoot "src/main/java") -Recurse -Filter "*.java").FullName
    javac -encoding UTF-8 --release 21 -d $taskClasses -cp (Join-Path $taskRoot "lib/*") $taskSources
    if ($LASTEXITCODE -ne 0) { throw "Falló la compilación Java." }
    $taskTestSources = (Get-ChildItem -LiteralPath (Join-Path $taskRoot "src/test/java") -Recurse -Filter "*.java").FullName
    javac -encoding UTF-8 --release 21 -d $taskTests -cp "$taskClasses;$(Join-Path $taskRoot 'lib/*')" $taskTestSources
    if ($LASTEXITCODE -ne 0) { throw "Falló la compilación de pruebas." }
    Push-Location $taskClasses
    try {
        foreach ($taskDependency in @("Java-WebSocket-1.5.4.jar", "slf4j-api-2.0.9.jar", "slf4j-simple-2.0.9.jar")) {
            jar xf (Join-Path $taskRoot "lib/$taskDependency")
            if ($LASTEXITCODE -ne 0) { throw "Falló extracción de $taskDependency." }
        }
    } finally { Pop-Location }
    $taskJar = Join-Path $taskBuild "uhip-server.jar"
    jar cfe $taskJar com.uhip.Main -C $taskClasses .
    if ($LASTEXITCODE -ne 0) { throw "Falló empaquetado JAR." }
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskTarget "uhip-server.jar") -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $taskTarget "test-classes") | Out-Null
    Copy-Item -Path (Join-Path $taskTests "*") -Destination (Join-Path $taskTarget "test-classes") -Recurse -Force
    Write-Host "Listo: target/uhip-server.jar (Java 21, motores de teselas Java y libvips)." -ForegroundColor Green
} finally {
    $taskResolved = [IO.Path]::GetFullPath($taskBuild)
    $taskAllowed = [IO.Path]::GetFullPath($taskTarget) + [IO.Path]::DirectorySeparatorChar
    if (!$taskResolved.StartsWith($taskAllowed, [StringComparison]::OrdinalIgnoreCase)) { throw "Ruta de limpieza fuera de target." }
    Remove-Item -LiteralPath $taskResolved -Recurse -Force
}
