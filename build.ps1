# Builds dist\Insta-recording.exe. Needs: pip install -r requirements.txt
param([string]$Source = "$PSScriptRoot\insta_recording.py", [string]$Dist = "$PSScriptRoot\dist")

$ErrorActionPreference = "Stop"
$bt = Join-Path $env:TEMP "insta_build"
New-Item -ItemType Directory -Force "$bt\ff" | Out-Null
$ffmpeg = python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"
Copy-Item $ffmpeg "$bt\ff\ffmpeg.exe" -Force

$ErrorActionPreference = "Continue"  # PyInstaller logs to stderr; $LASTEXITCODE reports failure
python -m PyInstaller --noconfirm --onefile --windowed --name "Insta-recording" `
    --icon "$PSScriptRoot\icon.ico" `
    --add-binary "$bt\ff\ffmpeg.exe;." --add-data "$PSScriptRoot\icon.ico;." `
    --hidden-import pyaudiowpatch --exclude-module numpy `
    --workpath "$bt\work" --specpath "$bt" --distpath $Dist $Source
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }
