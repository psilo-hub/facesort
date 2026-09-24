# Face Sort

![GitHub commit activity](https://img.shields.io/github/commit-activity/t/psilo-hub/facesort)
![GitHub last commit](https://img.shields.io/github/last-commit/psilo-hub/facesort)
![GitHub Downloads](https://img.shields.io/github/downloads/psilo-hub/facesort/total)
![GitHub Release Date](https://img.shields.io/github/release-date/psilo-hub/facesort)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Platform: Windows · Linux · macOS](https://img.shields.io/badge/Platform-Windows%20%E2%80%A2%20Linux%20%E2%80%A2%20macOS-informational.svg)]()
[![Download](https://img.shields.io/badge/Download-latest%20release-blue.svg)](https://github.com/psilo-hub/facesort/releases)

> [English](README.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · Español · [Русский](README.ru.md) · [中文](README.zh.md)

Una **aplicación de escritorio** multiplataforma que ordena tu colección de fotos
por personas. Detecta caras en tus fotos, agrupa automáticamente las similares y
te permite etiquetar a cada persona con un nombre; después puedes revisar,
fusionar y limpiar tu biblioteca. Todo se ejecuta localmente en tu ordenador:
**tus fotos nunca salen de tu máquina.**

## Funciones

- **Detección automática de caras** al importar fotos: cada cara detectada se
  guarda como una miniatura lista para etiquetar.
- **Agrupación automática de caras sin nombre**: las caras similares se agrupan
  en clusters para que puedas nombrar todas las fotos de una misma persona de una
  sola vez.
- **Tres formas sencillas de etiquetar caras:**
  - **Poner un nombre a una cara**: avanza por los grupos de caras sin nombre,
    primero los más grandes.
  - **Etiquetar cara al azar**: recorre caras sin nombre aleatorias y etiqueta
    las que selecciones.
  - **Añadir caras a un nombre**: elige a una persona y etiqueta las caras sin
    nombre más similares a ella.
- **Detección de duplicados**: compara tus personas nombradas y te permite
  fusionar nombres que resultan ser la misma persona.
- **Explorar y revisar**: ve a todas las personas de un vistazo, entra en las
  fotos que las contienen, abre los originales en tu visor y quita etiquetas.
- **Importación inteligente**: escanea subcarpetas de forma recursiva y omite las
  fotos ya importadas (por contenido, no por nombre de archivo). Reimportar una
  misma carpeta es un no-op y la misma foto nunca se almacena dos veces.
- **Filtro de ruta**: limita el etiquetado a una carpeta o nombre de archivo
  específico.
- **Eliminar por prefijo de ruta**: en la pestaña *Importar*, elimina todos los
  fotos y vídeos importados cuya ruta almacenada comience por el prefijo que
  introduzcas. Un diálogo muestra primero cuántas imágenes, vídeos, miniaturas y
  subimágenes de caras se eliminarían; no se borra nada hasta que lo confirmes.
  Los vídeos se eliminan junto con los fotogramas extraídos de ellos.
- **Renombrar y desetiquetar**: corrige un error tipográfico en todas partes a la
  vez, o quita una cara de un nombre.
- **Exportar las fotos de una persona**: en *Añadir caras a un nombre*, elige a
  una persona y copia cada foto que la contenga a una carpeta de tu elección (las
  fotos cuyo archivo original ya no existe se exportan como miniaturas).
- **Descarga de modelos en el primer arranque**: los modelos integrados de
  reconocimiento facial se descargan una vez (con ventana de progreso) y se
  guardan en caché localmente.
- **Comprobación automática de actualizaciones**: te avisa cuando hay una nueva
  versión disponible y muestra las notas de la versión.
- **Pestaña de comentarios**: envía informes de errores y peticiones de funciones
  directamente desde la aplicación.
- **Después, totalmente offline**: la detección y la comparación se ejecutan
  localmente en tu CPU.

## Requisitos

- **Java 17 o superior** (un JDK, no solo un JRE)
- Un sistema de escritorio de **64 bits**: Windows, Linux (x86_64 / aarch64)
  o macOS (Intel / Apple Silicon)
- Se recomiendan unos cuantos GB de RAM libre para colecciones grandes
- **FFmpeg** — incluido en la aplicación (mediante ffmpeg4j); no hay nada que instalar

## Descarga

Descarga la última versión desde la página de
[**Releases**](https://github.com/psilo-hub/facesort/releases): elige el jar para
tu plataforma (`facesort-<platform>.jar`). Hay jars precompilados para todas las
plataformas en cada versión. No requiere compilación ni configuración.

### Primer arranque

1. Asegúrate de que **Java 17+** esté instalado (`java -version`).
2. Ejecuta el jar: `java -jar facesort-<platform>.jar` (o haz doble clic).
3. En el primer arranque, Face Sort descarga sus modelos de reconocimiento facial
   y muestra una ventana de progreso. Esto necesita conexión a internet una vez.

Después, todo funciona sin conexión.

## Cómo usarlo

La ventana principal es un conjunto de pestañas. Trabájalas aproximadamente en
este orden:

1. **Importar imágenes**: pulsa *Examinar…* para elegir una carpeta de fotos
   (JPG, JPEG, PNG, BMP, GIF, WebP; las subcarpetas se escanean de forma
   recursiva) y luego pulsa **Importar**. Cada cara detectada se guarda junto con
   su embedding y una miniatura. Puedes detener una importación en cualquier
   momento; los archivos ya importados se conservan. Reimportar la misma carpeta
   más tarde solo registra los archivos nuevos.
   Para eliminar registros importados cuya ruta almacenada comienza por un
   prefijo determinado (por ejemplo, tras borrar los archivos originales de una
   carpeta), pulsa **Eliminar por prefijo de ruta…** – un diálogo muestra primero
   cuántas imágenes, vídeos, miniaturas y subimágenes de caras se eliminarían y
   luego pide tu confirmación.

2. **Poner un nombre a una cara**: la aplicación agrupa todas las caras sin nombre
   y muestra el representante del cluster más grande. Escribe un nombre (los
   nombres existentes se detectan mientras escribes), pulsa **Etiquetar**; a
   continuación se ofrecen las caras similares restantes del cluster para que las
   etiquetes en la misma pasada. Pasa al siguiente cluster con *Siguiente cluster*.

3. **Etiquetar cara al azar**: una muestra aleatoria de caras sin nombre. Haz clic
   en las caras para seleccionarlas, escribe un nombre y pulsa **Etiquetar
   selección**. Usa el campo *prefijo de ruta* para limitar la muestra a una
   carpeta o archivo concretos.

4. **Añadir caras a un nombre**: selecciona a una persona a la izquierda; la
   aplicación ordena cada cara sin nombre por su similitud con el embedding medio
   de esa persona y muestra los mejores candidatos. Selecciona varias y pulsa
   **Etiquetar selección**: haz clic en las caras una a una o mantén pulsada la
   tecla **Mayús** y haz clic en la primera y la última cara para seleccionar todo
   el rango entre ellas. Usa la casilla *«Excluir caras más cercanas a otro
   nombre»* para ofrecer solo caras cuya mejor coincidencia es la persona
   seleccionada, y el campo *prefijo de ruta* para filtrar por carpeta. También
   puedes **Renombrar…** a cualquier persona aquí, o pulsar **Exportar
   imágenes…** para copiar todas las fotos de la persona seleccionada a una
   carpeta de tu elección (los originales que falten se exportan como
   miniaturas). Al hacer clic derecho en una cara candidata sin nombre, elige
   *Etiquetar con otro nombre* para etiquetarla con un nombre distinto del
   ofrecido: el diálogo previsualiza los nombres existentes con su cara más
   representativa y la similitud con el embedding medio del nombre.

5. **Eliminar duplicados**: pulsa **Iniciar** para comparar pares de nombres por
   similitud. En cada par decide: *Son duplicados* (a continuación, elige qué
   nombre sobrevive: todas las caras se fusionan), *No son duplicados* (se
   recuerda permanentemente) o *Omitir*.

6. **Ver**: explora tu colección etiquetada. Cada persona es una tarjeta con una
   cara representativa y el número de caras etiquetadas. Un cuadro de búsqueda
   filtra las tarjetas por nombre mientras escribes, para que la lista siga siendo
   manejable incluso con cientos de personas. Haz clic en una tarjeta
   para ver las imágenes que contienen a esa persona; haz clic en una imagen para
   abrir el original, o usa el menú contextual para *Desetiquetar* esa persona.

7. **Ajustes**: ajusta la detección de caras, el agrupamiento, la importación y
   los parámetros de los modelos; consulta la información de cada control. Los
   cambios se aplican con **Guardar** o se restablecen con **Restablecer valores
   predeterminados**.

8. **Comentarios**: envía un informe de error o una petición de función a los
   desarrolladores.

En toda la aplicación puedes hacer clic derecho en cualquier cara o miniatura
para **Abrir original** en el visor de imágenes predeterminado de tu sistema, y al
pasar el cursor sobre una cara se muestra la ruta de su imagen de origen.

### Flujo de trabajo sugerido

```
Importar una carpeta  →  Poner un nombre a una cara / Etiquetar cara al azar / Añadir caras a un nombre (etiquetar personas)
                      →  Eliminar duplicados (fusionar nombres de persona duplicados)
                      →  Ver (revisar, abrir originales, quitar errores)
```

## Dónde se almacenan tus datos

Todo vive en una carpeta `config/` junto a la aplicación (se crea en el primer
arranque): cópiala para hacer una copia de seguridad de tu biblioteca.

| Ruta | Finalidad |
|------|-----------|
| `config/facesort.db` | Tu biblioteca: fotos, caras, nombres, etiquetas |
| `config/facesort-config.json` | Tus ajustes |
| `config/latest-release.json` | Caché usada por la comprobación de actualizaciones |
| Caché de modelos FaceAI | Modelos descargados: por defecto `~/.djl.ai/cache` (Linux/macOS) o `%USERPROFILE%\.djl.ai\cache` (Windows); configurable mediante el ajuste *FaceAI cache dir* |

Todos los ajustes pueden cambiarse en la pestaña **Ajustes** (cada control tiene
una información explicativa); se guardan en `config/facesort-config.json`.
También puedes cambiar el **idioma** de la interfaz allí.

Además de los parámetros de detección y agrupación, también se pueden ajustar
los presupuestos de importación: la calidad JPEG de las miniaturas y recortes de
rostro guardados, el número máximo de fotogramas muestreados por vídeo y el
tamaño del recorte de rostro, para equilibrar la importación entre calidad y
velocidad.

## Licencia

Distribuida bajo la [Licencia MIT](LICENSE).