#!/bin/sh
# Abre Pomi Voz. Necesita Java 17 o más (sudo apt install openjdk-17-jre, dnf install java-17-openjdk, ...).
DIR="$(cd "$(dirname "$0")" && pwd)"
if ! command -v java >/dev/null 2>&1; then
    echo "Pomi Voz necesita Java 17 o más. En Ubuntu/Debian: sudo apt install openjdk-17-jre" >&2
    command -v zenity >/dev/null 2>&1 && zenity --error --text="Pomi Voz necesita Java 17 o más.\nUbuntu/Debian: sudo apt install openjdk-17-jre"
    exit 1
fi
exec java -jar "$DIR/PomiVoz.jar" "$@"
