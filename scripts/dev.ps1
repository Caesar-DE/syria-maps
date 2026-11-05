# Docker-less development server on Windows (in-memory storage, synthetic fixture).
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\dev.ps1
$ErrorActionPreference = "Stop"
$backend = Join-Path $PSScriptRoot "..\backend"
Push-Location $backend
try {
    if (-not (Test-Path .venv)) { py -3.12 -m venv .venv }
    .\.venv\Scripts\python.exe -m pip install -q -r requirements-dev.txt
    $env:SYRIA_MAPS_STORAGE = "memory"
    if (-not $env:SYRIA_MAPS_PUBLIC_BASE_URL) { $env:SYRIA_MAPS_PUBLIC_BASE_URL = "http://localhost:8000" }
    .\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --reload --port 8000
} finally {
    Pop-Location
}
