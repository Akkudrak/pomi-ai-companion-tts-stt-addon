#!/bin/sh
# Agrega Pomi Voz al menú de aplicaciones (solo para tu usuario). Se corre una vez, desde esta carpeta.
DIR="$(cd "$(dirname "$0")" && pwd)"
chmod +x "$DIR/pomi-voz.sh"
mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/pomi-voz.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Pomi Voz
Comment=Oye y háblale a Pomi, la ranita del servidor de Minecraft
Exec="$DIR/pomi-voz.sh"
Path=$DIR
Icon=audio-input-microphone
Terminal=false
Categories=Game;AudioVideo;
DESKTOP
echo "Listo: busca \"Pomi Voz\" en tus aplicaciones (o corre $DIR/pomi-voz.sh)."
