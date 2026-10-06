# Same slicing flow, selectable Java or embedded libvips engine.
Write-Host "Iniciando Cortador de Teselas UHIP (Java / libvips)..." -ForegroundColor Cyan

if (!(Test-Path "target/uhip-server.jar")) {
    Write-Host "JAR no encontrado. Compilando primero..." -ForegroundColor Yellow
    .\build.ps1
}

java -jar target/uhip-server.jar --slice $args
exit $LASTEXITCODE
