#!/bin/sh
# Opcional, se corre EN LINUX con un JDK 17+: arma Pomi Voz con su propio Java adentro (los jugadores no
# necesitan instalar Java). Deja la app en dist/PomiVoz/ (bin/PomiVoz para abrirla); con --type deb
# o rpm en vez de app-image sale un instalador.
#   ./empaquetar-jpackage.sh            (desde la carpeta del zip, junto a PomiVoz.jar)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
TIPO="${1:-app-image}"
rm -rf "$DIR/dist" "$DIR/.entrada"
mkdir -p "$DIR/.entrada"
cp "$DIR/PomiVoz.jar" "$DIR/.entrada/"
jpackage --type "$TIPO" --name PomiVoz --app-version 1.0.0 \
    --input "$DIR/.entrada" --main-jar PomiVoz.jar \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --add-modules java.desktop,java.net.http,java.logging \
    --dest "$DIR/dist"
rm -rf "$DIR/.entrada"
echo "Listo en $DIR/dist"
