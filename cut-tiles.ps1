# Tile cutting script using TileCutter
Write-Host "Ejecutando TileCutter..." -ForegroundColor Cyan

if ($args.Count -eq 0) {
    Write-Host "Uso: .\cut-tiles.ps1 <ruta-imagen> [directorio-salida]"
    Write-Host "O para generar dataset sintetico: .\cut-tiles.ps1 --synthetic [maxZoom] [directorio-salida]"
    Write-Host "Generando dataset sintetico por defecto (Zooms 0-4 en 'tiles')..." -ForegroundColor Yellow
    java -cp "target/classes;lib/*" com.uhip.tools.TileCutter --synthetic 4 tiles
} else {
    java -cp "target/classes;lib/*" com.uhip.tools.TileCutter $args
}
