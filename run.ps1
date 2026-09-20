# Launch script for UHIP Ultra-Resolution Image Server
Write-Host "Iniciando Servidor Asincrono UHIP..." -ForegroundColor Cyan

if (!(Test-Path "target/uhip-server.jar")) {
    Write-Host "JAR no encontrado. Ejecutando build.ps1 primero..." -ForegroundColor Yellow
    .\build.ps1
}

java -jar target/uhip-server.jar $args
