# Build script for UHIP Ultra-Resolution Image Server
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   Compilando Servidor UHIP (Java 21)...                  " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$targetClasses = "target/classes"
if (!(Test-Path $targetClasses)) {
    New-Item -ItemType Directory -Force -Path $targetClasses | Out-Null
}

$sources = (Get-ChildItem -Recurse -Filter "*.java" src/main/java | Select-Object -ExpandProperty FullName)
Write-Host "Compilando $($sources.Count) archivos Java..." -ForegroundColor Yellow
javac -d $targetClasses -cp "lib/*;src/main/java" $sources

if ($LASTEXITCODE -ne 0) {
    Write-Host "Error durante la compilacion de Java." -ForegroundColor Red
    exit 1
}

Write-Host "Empaquetando target/uhip-server.jar..." -ForegroundColor Yellow
Push-Location $targetClasses
jar xf ../../lib/Java-WebSocket-1.5.4.jar
jar xf ../../lib/slf4j-api-2.0.9.jar
jar xf ../../lib/slf4j-simple-2.0.9.jar
Remove-Item -Recurse -Force META-INF/*.SF, META-INF/*.DSA, META-INF/*.RSA -ErrorAction SilentlyContinue
Pop-Location

jar cfe target/uhip-server.jar com.uhip.Main -C $targetClasses .

if ($LASTEXITCODE -eq 0) {
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "   Compilacion exitosa: target/uhip-server.jar             " -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
} else {
    Write-Host "Error empaquetando JAR." -ForegroundColor Red
    exit 1
}
