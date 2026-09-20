@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cut-tiles.ps1" %*
