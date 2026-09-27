# RPG Translator (Android)

Traduce el texto de proyectos de **RPG Maker MV/MZ** y **Ren'Py** de forma
automática — modo "traduce y listo": no corre el juego, solo procesa los
archivos de texto y genera una copia traducida.

**Solo traduce texto.** No copia imágenes, audio, `js/`, `Save/` ni ningún
otro asset — cópialos tú aparte, sin traducir, a la misma carpeta de salida
para tener el proyecto completo y jugable.

## Motores de traducción

- **En línea** — endpoint público (no oficial) de Google Translate, no
  requiere API key. Soporta detección automática de idioma (`auto`), que se
  hace una sola vez al inicio de la corrida (no en cada frase) para fijar un
  idioma de origen consistente durante todo el proceso.
- **Local / sin internet** — [ML Kit de Google](https://developers.google.com/ml-kit/language/translation),
  los mismos modelos del modo sin conexión de la app oficial. El modelo del
  idioma se descarga una sola vez (~30MB, necesita internet solo esa
  primera vez); después, cada traducción es local. **No soporta `auto`** —
  hay que indicar el idioma de origen real (ej. `ja` para japonés).

## Qué se traduce

- **RPG Maker MV/MZ**: nombres, descripciones, perfiles y apodos de actores;
  nombres/descripciones de clases, habilidades, objetos, armas, armaduras,
  enemigos y estados (incluye `message1`-`message4` de habilidades y
  estados); vocabulario del sistema (`System.json`); y en eventos —
  `Show Text` (401), `Show Scrolling Text` (405/105), `Show Choices` (102) y
  el eco de texto en `When [choice]` (402). Los códigos de control
  (`\V[1]`, `\C[2]`, `\N[3]`, etc.) se protegen antes de traducir para que no
  se rompan. Scripts y llamadas a plugins (355/356) no se tocan.
- **Ren'Py**: líneas de diálogo (`personaje "texto"` / `"texto"` narración) y
  opciones de menú (`"texto":`), protegiendo `[variables]` y `{tags}`.
  **Limitación conocida:** el reconocimiento es por expresión regular línea
  por línea, no un parser/AST real del lenguaje — no cubre diálogo
  multi-línea, concatenación de strings ni bloques ATL complejos.

## Arquitectura (resumen técnico)

- **Corrutinas** (no hilos crudos) para toda la concurrencia — cancelación
  cooperativa y limpia.
- **WorkManager** ejecuta la traducción como foreground service con
  notificación — sobrevive a que cierres la app o se vaya a segundo plano
  (importante en fabricantes con gestión de batería agresiva como
  Xiaomi/HyperOS o Samsung). Si reabres la app mientras corre, se reengancha
  sola al trabajo en curso.
- **Caché SQLite con checkpoints incrementales** — cada ~50 traducciones
  nuevas se guarda a disco, no solo al final. Si la app se cierra o truena a
  la mitad, lo ya traducido no se pierde.
- **Rate limiter (token bucket) + semáforo de red** — limitan cuántas
  solicitudes por segundo y cuántas simultáneas se mandan, para no
  saturar el servicio de traducción, sin importar cuántos archivos se
  procesen en paralelo.
- **`TranslatorConfig`** centraliza idiomas, concurrencia, reintentos y
  tamaños en un solo lugar en vez de números sueltos repartidos en el código.
- Tests unitarios (JUnit) cubren la protección/restauración de códigos de
  control y el parser de líneas de Ren'Py.

### Por qué no hay "batching" de solicitudes

El endpoint no oficial de Google Translate no tiene un formato de lote
confiable para mandar 30-50 strings independientes en una sola solicitud sin
arriesgarse a que el traductor reordene o mezcle contenido entre ellos. En
vez de eso, la velocidad se gana con paralelismo (varios archivos a la vez,
acotado por semáforo) + caché — más lento que un batching real, pero sin
riesgo de corromper traducciones.

## Uso

1. Elige el motor (RPG Maker o Ren'Py) y el modo (en línea o local).
2. Elige la carpeta del proyecto y la carpeta de salida.
3. Ajusta idioma origen/destino.
4. Toca **Traducir**. Puedes cerrar la app — sigue corriendo en segundo
   plano (notificación con el progreso); al reabrirla, se reengancha sola.
5. **Cancelar** detiene lo que falta sin perder lo ya traducido.
   En modo local, la cancelación es *best effort*: una traducción ya en
   curso (bloqueante, hasta 30s) no se puede interrumpir a la mitad.
6. **Copiar log** para guardar o compartir el resultado.

## Compilar

Sube la carpeta a un repo de GitHub y corre el workflow **Build APK** en la
pestaña Actions (corre los tests unitarios primero, luego compila) —
descarga el artefacto `rpg-translator-debug-apk`.
