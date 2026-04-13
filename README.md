# Scratch Mechanic

Android sample app that implements a **scratch-to-reveal** card in **Jetpack Compose**: the user drags on a top “foil” layer; an image underneath appears through the scratched path. When enough of the visible area is covered, the overlay can fade out (“vanish”).

## Demo

https://github.com/user-attachments/assets/ce5239df-3484-481d-be73-eaced10f920c

## What’s in this project

- **`ScratchOverlay`** — Composable that stacks optional **revealed** content under **scratchable** content, handles drag gestures, draws the erase stroke with **`BlendMode.Clear`** on an offscreen layer, and runs the vanish animation.
- **`ScratchState`** — Holds the scratch polyline, grid-based **coverage** (no GPU pixel readback), optional **clip** via Android **`Region`**, and reveal flags.
- **`ScratchOverlayConfig` / `ScratchAreaSpec`** — Brush width, vanish threshold, animation duration, optional **`clipShape`**, and sizing (**match scratchable** vs **fixed `DpSize`**).

## Requirements

- Android Studio / Gradle as in the project wrappers  
- **minSdk** / **compileSdk** as defined in `app/build.gradle.kts`

## Run

```bash
./gradlew :app:installDebug
```

Or open the project in Android Studio and run the **app** configuration.

## License

Apache 2.0
