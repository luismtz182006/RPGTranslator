# RPG Translator (Android)

Traduce proyectos de **RPG Maker MV/MZ** y **Ren'Py** de forma automática
(modo "traduce y listo" — no corre el juego, solo procesa los archivos de
texto y genera una copia traducida).

## Cómo funciona

- **RPG Maker MV/MZ**: lee los JSON de la carpeta `data/` (o `www/data/` en
  exportaciones viejas) y traduce nombres, descripciones y diálogos de
  eventos (`Show Text`, `Show Choices`, `Scrolling Text`). Los códigos de
  control (`\V[1]`, `\C[2]`, `\N[3]`, etc.) se protegen antes de traducir para
  que no se rompan. Los scripts y llamadas a plugins (códigos 355/356) **no**
  se tocan, para no romper la lógica del juego.
- **Ren'Py**: recorre los `.rpy` línea por línea, traduce las líneas de
  diálogo (`personaje "texto"`) y las opciones de menú (`"opción":`),
  protegiendo las interpolaciones (`[variable]`) y tags (`{tag}`) de Ren'Py.
  Todo lo demás (`label`, `jump`, `python:`, `$`, `define`, `image`, etc.) se
  copia tal cual.

Usa el endpoint público (no oficial) de Google Translate — no requiere API
key, pero es de uso personal moderado; si Google empieza a limitar por
volumen, hay reintentos automáticos con espera.

## Uso

1. Elige el motor (RPG Maker o Ren'Py) en el paso 1.
2. Elige la carpeta del proyecto/juego (para RPG Maker, la carpeta que
   contiene `data/`; para Ren'Py, la carpeta del juego con los `.rpy`).
3. Elige una carpeta de salida — ahí se genera la copia traducida, el
   original nunca se toca.
4. Ajusta idioma origen/destino (`auto`/`ja`/`en`... → `es`/`en`...).
5. Toca **Traducir** y espera — la barra de progreso muestra archivo y línea/
   campo actual.
6. **Importante:** esta app solo traduce el *texto*. Copia tú, sin traducir,
   las carpetas de imágenes, audio, `js/`, `Save/`, etc. dentro de la misma
   carpeta de salida para tener el proyecto completo y jugable.

## Limitaciones honestas (v1)

- Solo RPG Maker **MV/MZ** — las versiones viejas (XP/VX/VX Ace, formato
  Marshal de Ruby) no están soportadas todavía.
- El extractor de Ren'Py es línea por línea con expresiones regulares — no
  cubre diálogo multi-línea, concatenación de strings, ni bloques ATL
  complejos. Cubre el caso común (`personaje "texto"` / `"texto":` en menús).
- Sin caché: si corres la traducción dos veces, vuelve a traducir todo desde
  cero (no compara con una corrida anterior).
- Depende de un endpoint no oficial de Google Translate — puede fallar o
  limitarse con volúmenes grandes.

## Compilar

Igual que los otros proyectos: sube la carpeta a un repo de GitHub, corre el
workflow **Build APK** en la pestaña Actions, descarga el artefacto
`rpg-translator-debug-apk`.
