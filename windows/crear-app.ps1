# Compila "Pomi Voz.exe": un solo ejecutable, sin instalar nada (usa el .NET Framework 4 que trae
# Windows). Se compila con el csc de Windows, igual que el instalador del mod.
# Uso: powershell -ExecutionPolicy Bypass -File windows\crear-app.ps1

$ErrorActionPreference = 'Stop'
$App = Split-Path -Parent $MyInvocation.MyCommand.Path
$Salida = Join-Path (Split-Path -Parent $App) 'build\app'
$Csc = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $Csc)) { throw "No está el compilador de C# de Windows: $Csc" }

New-Item -ItemType Directory -Force $Salida | Out-Null
$exe = Join-Path $Salida 'Pomi Voz.exe'
if (Test-Path $exe) { Remove-Item $exe -Force }
& $Csc /nologo /target:winexe /platform:anycpu /optimize+ /codepage:65001 "/out:$exe" `
    /reference:System.Windows.Forms.dll /reference:System.Drawing.dll (Join-Path $App 'PomiVoz.cs')
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) { throw 'No se pudo compilar Pomi Voz.' }
Write-Host "Listo: $exe"
