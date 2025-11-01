# create_preview

Tooling for generating preview images and short videos for Brown Dust II mods that use Spine skeletons.

## Prerequisites
- Java 17 or newer (the Gradle build config uses a Java 11 toolchain, but the runtime works best on a current JDK).
- FFmpeg bindings are bundled through JavaCV, no native FFmpeg installation is required.

## Building

```bash
./gradlew clean build
```

The runnable fat JAR is produced at `build/libs/create_preview-0.1.1.jar`.

## Usage

Run the tool with Java. On JDK 17+ you may need to allow native access for LWJGL and JavaCV:

```bash
java --enable-native-access=ALL-UNNAMED -jar build/libs/create_preview-0.1.1.jar [options]
```

You can either point the tool at a folder that contains the `.atlas`, `.skel/.json`, and texture PNGs, or specify each asset explicitly.

### Common options

- `--folder <dir>`: Directory with the Spine assets (atlas, skeleton, textures). The tool searches automatically for the first matching files.
- `--atlas <file>` / `--skeleton <file>` / `--texture <file|dir>`: Explicit asset paths when not using `--folder`.
- `--output <file>`: Where to write the preview PNG (default is `<atlas-stem>-preview.png`).
- `--scale <float>`: Apply scale to the skeleton when loading.
- `--skin <name>`: Skin to apply (comma separated to merge multiple skins).
- `--animation <name|file>`: Animation to preview or path to a separate skeleton/JSON containing animations.
- `--time <seconds>`: Start time offset inside the animation.
- `--width` / `--height`: Force the output image size (pixels).
- `--video-seconds <seconds>` and `--fps <int>`: Enable MP4 export and control duration/fps.
- `--video-loop auto|off|N`: Loop-aware video length. `auto` captures one perfect cycle, `N` captures N full cycles, `off` (default) uses `--video-seconds`.
- `--video-output <file>`: Destination MP4 path (defaults to the PNG name with `.mp4`).
- `--keep-frames`: Keep intermediate PNG frames when rendering video.

Run without arguments (or with invalid ones) to see the full usage text.

## Examples

Generate a still preview from a mod folder:

```bash
java --enable-native-access=ALL-UNNAMED \
     -jar build/libs/create_preview-0.1.1.jar \
     --folder "/path/to/mod-assets" \
     --output preview.png
```

Render a 5 second looping MP4 (and keep the generated frames):

```bash
java --enable-native-access=ALL-UNNAMED \
     -jar build/libs/create_preview-0.1.1.jar \
     --atlas "/path/to/char.atlas" \
     --skeleton "/path/to/char.skel" \
     --texture "/path/to/char_textures" \
     --skin Default \
     --animation idle \
     --video-seconds 5 \
     --fps 30 \
     --video-output preview.mp4 \
     --video-loop auto \
     --keep-frames
```

## Logging and troubleshooting

The CLI prints informative log lines (prefixed with `[create-preview]`) describing which assets are loaded and where output files are written. Errors such as missing files or incompatible Spine exports are reported with actionable messages before the tool exits.

If you encounter issues with Spine skeletons, verify that the asset was exported for Spine runtime version `4.1.0`, which matches the runtime bundled in this project.
