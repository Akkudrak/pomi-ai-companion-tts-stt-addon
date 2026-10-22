# Pomi Voz: voz y oído para Pomi

Complemento del plugin de Paper [Pomi](https://github.com/Akkudrak/pomi-ai-companion-plugin-version).
El cliente vanilla de Minecraft no puede reproducir audio nuevo, así que la voz va en una app de
escritorio aparte. La app lee en voz alta lo que Pomi dice y te deja darle órdenes con la voz.

Tiene dos partes:

1. **La app de escritorio** (cada jugador): para Windows ([windows/](windows/)) y para Linux, Mac o
   Windows con Java ([voz-app/](voz-app/)).
2. **Los servicios de voz en Docker** (una sola vez, junto al servidor): Kokoro (texto a voz) y
   Whisper (voz a texto). Solo el servidor de Paper habla con ellos. Los jugadores no.

```
Jugador (app Pomi Voz) ──TCP 8765──▶ Servidor Paper (plugin Pomi) ──▶ Kokoro  :8880  (texto → voz)
                                                                   └─▶ Whisper :8881  (voz → texto)
```

---

## 1. Servicios de voz en Docker

### Qué necesitas instalar

| | Windows | Linux |
|---|---|---|
| Docker | [Docker Desktop](https://www.docker.com/products/docker-desktop/) con WSL 2 | Docker Engine y el plugin `docker compose` ([docs](https://docs.docker.com/engine/install/)) |
| Solo con GPU NVIDIA | Driver de NVIDIA reciente. Docker Desktop ya trae el soporte de GPU por WSL 2. | Driver de NVIDIA y el [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html), luego `sudo nvidia-ctk runtime configure --runtime=docker && sudo systemctl restart docker` |
| Disco | Unos 10 GB libres para las imágenes (las de GPU pesan más) y los modelos de Whisper | Igual |
| RAM | 8 GB mínimo, 16 GB recomendado | Igual |

Para ver si Docker reconoce la tarjeta gráfica:

```bash
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

### Elige el compose

| Carpeta | Para | Probado en |
|---|---|---|
| [docker/cpu/](docker/cpu/docker-compose.yml) | PC sin tarjeta gráfica NVIDIA | Ryzen 5 5600 (6 núcleos / 12 hilos), sin GPU |
| [docker/gpu/](docker/gpu/docker-compose.yml) | PC con NVIDIA (CUDA) | Ryzen 7 5700 (8 núcleos / 16 hilos), RTX 4060 8 GB, 32 GB RAM |

```bash
cd docker/gpu          # o docker/cpu
docker compose up -d
docker compose logs -f # la primera vez descarga las imágenes; Ctrl+C para salir del log
```

Puertos:

- **8880**: Kokoro, en `/v1/audio/speech`.
- **8881**: Whisper (speaches), en `/v1/audio/transcriptions`.

Los dos servicios **no tienen contraseña**.

- El compose de **CPU** solo escucha en `127.0.0.1`, así que sirve si Paper corre en la misma PC.
- El de **GPU** acepta conexiones de la red. Abre 8880 y 8881 en el firewall solo hacia el servidor de
  Paper o tu red local, nunca hacia internet.

### Modelos

**Texto a voz (Kokoro):** usa el modelo **Kokoro-82M**, que ya viene dentro de la imagen, en CPU y en
GPU, sin descargar nada más. Voces en español:

| Voz | |
|---|---|
| `ef_dora` | Mujer (la de Pomi, por defecto) |
| `em_alex` | Hombre |
| `em_santa` | Hombre, grave |

**Voz a texto (Whisper):** el modelo se elige en el plugin con `whisper-modelo`. Speaches lo descarga
la primera vez y lo guarda en el volumen `whisper-models`.

| Equipo | Modelo recomendado | Otras opciones |
|---|---|---|
| **CPU** (Ryzen 5 5600) | `Systran/faster-whisper-small`: buen español y responde rápido | `Systran/faster-whisper-base` (más rápido, entiende peor), `Systran/faster-whisper-medium` (entiende mejor, unos segundos por frase) |
| **GPU** (RTX 4060 8 GB) | `deepdml/faster-whisper-large-v3-turbo-ct2`: casi la precisión de large-v3, muy rápido, unos 2 GB de video | `Systran/faster-whisper-large-v3` (la mejor precisión, unos 4 GB de video), `Systran/faster-whisper-medium` |

> No uses los modelos `distil-whisper`: solo entienden inglés.
> Con la GPU de 8 GB caben Kokoro y Whisper large-v3-turbo a la vez.

Si al hablar la primera vez el log de speaches dice que el modelo no existe, descárgalo a mano:

```bash
curl -X POST http://localhost:8881/v1/models/Systran/faster-whisper-small
```

### Conectar el plugin

En `plugins/Pomi/config.properties` del servidor de Paper. Después de editarlo, reinicia el servidor
para que tome el cambio de `voz`.

```properties
voz=true
voz-puerto=8765
kokoro-url=http://<ip-de-la-pc-con-docker>:8880/v1/audio/speech
kokoro-voz=ef_dora
kokoro-velocidad=0.95
whisper-url=http://<ip-de-la-pc-con-docker>:8881/v1/audio/transcriptions
# CPU: Systran/faster-whisper-small   ·   GPU: deepdml/faster-whisper-large-v3-turbo-ct2
whisper-modelo=Systran/faster-whisper-small
```

Si Docker corre en la misma PC que Paper, usa `127.0.0.1`. Abre `voz-puerto` (TCP 8765) en el
servidor de Paper: es el único puerto al que se conectan los jugadores.

Para probar los servicios desde la PC de Docker:

```bash
# Debe crear prueba.mp3 con la voz de Pomi
curl -s http://localhost:8880/v1/audio/speech -H "Content-Type: application/json" \
  -d '{"model":"kokoro","input":"Hola, soy Pomi","voice":"ef_dora","response_format":"mp3"}' -o prueba.mp3

# Debe devolver el texto de prueba.mp3
curl -s http://localhost:8881/v1/audio/transcriptions -F file=@prueba.mp3 \
  -F model=Systran/faster-whisper-small -F language=es
```

---

## 2. La app de escritorio

### Uso

1. En el juego: `/p voz` muestra la dirección del servidor y un código de 6 letras (vale 5 minutos).
2. En la app: pon la dirección y el código y dale **Vincular**. Queda vinculada aunque se cierre.
3. Todo lo que Pomi te dice (respuestas de Grok y frases fijas) se oye en la app, con su tono de rana.
4. Con **Escuchar** activado, la app oye tu micrófono sin tecla: di **"Pomi, consigue madera"** (también
   vale "oye Pomi, ..." o "hey Pomi, ...") y se ejecuta como `/p consigue madera`. Si solo dices "Pomi",
   suena una campanita y lo siguiente que digas en 7 segundos es la orden. Lo que no empieza con su
   nombre se ignora. Mientras Pomi habla la app no oye el micrófono, para que Pomi no se oiga a sí misma.
5. O con **tecla para hablar**: en "Tecla" da clic y presiona la tecla o el botón lateral del mouse que
   quieras (Esc = ninguna). Funciona aunque estés en Minecraft: mientras la mantienes graba, y al
   soltarla lo manda como orden, sin decir "Pomi". Elige una tecla que el juego no use.

`/p voz quitar` desvincula tus apps.

La app tiene tono, volumen y silencio para la voz de Pomi. Para el oído tiene:

- La lista de micrófonos.
- El volumen del micrófono (0–400 %).
- Un visor: barras grises = ruido, verdes = voz que se va a mandar, azules = Pomi hablando (ahí no
  se oye el micrófono). La línea amarilla es el umbral.

Si al hablar las barras no pasan la línea, sube el volumen del micrófono. Si el ruido la pasa solo,
bájalo. La app se minimiza a la bandeja.

La conversación se guarda por día junto a la app, en `conversacion-AAAA-MM-DD.txt`.

### Windows

Es un solo `Pomi Voz.exe`, sin instalar nada (usa el .NET Framework 4 que trae Windows). Para
compilarlo con el `csc` de Windows:

```powershell
powershell -ExecutionPolicy Bypass -File windows\crear-app.ps1
```

Queda en `build\app\Pomi Voz.exe`. Si en su carpeta no se puede escribir, la configuración y las
conversaciones van a `%APPDATA%\PomiVoz`.

### Linux (y Mac, o Windows con Java)

Es la misma app, en Java (Swing + Java Sound). Para usarla necesitas Java 17 o más. Para compilarla,
JDK 25.

```bash
./gradlew :voz-app:linux
```

Arma `voz-app/build/distributions/PomiVoz-linux.zip` con:

- `PomiVoz.jar`
- `pomi-voz.sh`: abre la app.
- `instalar.sh`: la agrega al menú de aplicaciones.
- `LEEME.txt`
- `empaquetar-jpackage.sh`: arma una versión con Java incluido.

La tecla para hablar funciona en X11. En Wayland los programas no pueden leer teclas de otras ventanas,
así que ahí usa "Con Pomi". La configuración va en `~/.config/PomiVoz/config.txt`.
