# Tile cutting script using native Java VipsTileSlicer
Write-Host "Iniciando Cortador de Teselas Nativo UHIP..." -ForegroundColor Cyan

if (!(Test-Path "target/uhip-server.jar")) {
    Write-Host "JAR no encontrado. Compilando primero..." -ForegroundColor Yellow
    .\build.ps1
}

if ($args.Count -eq 0) {
    java -jar target/uhip-server.jar --slice
} else {
    java -jar target/uhip-server.jar --slice $args
}
