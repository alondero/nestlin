# JavaFX Pixel-Perfect Scaling (Nearest-Neighbor)

## Problem
`ImageView.isSmooth = false` is **unreliable on the Windows Direct3D pipeline** in JavaFX.
The D3D renderer applies bilinear filtering regardless of the `isSmooth` property, producing
soft/blurry output when scaling pixel art.

This was discovered when the display scale options (1x-4x, Fit) were added to Nestlin —
all scaled output appeared "soft" despite `isSmooth = false` being set.

## Solution
Use `Canvas` + `GraphicsContext.isImageSmoothing = false` instead of `ImageView`.

```kotlin
// ✗ UNRELIABLE on Windows D3D:
val imageView = ImageView(frameImage).apply { isSmooth = false }
imageView.fitWidth = 256.0 * scale
imageView.fitHeight = 240.0 * scale

// ✓ RELIABLE on all pipelines (D3D, OpenGL, Software) since JavaFX 12:
val canvas = Canvas(256.0 * scale, 240.0 * scale)
val gc = canvas.graphicsContext2D.apply { isImageSmoothing = false }
gc.drawImage(frameImage, 0.0, 0.0, canvas.width, canvas.height)
```

## Tradeoffs
- Canvas maintains its own backing buffer at the target resolution (slightly more memory)
- `gc.drawImage` re-rasterizes each frame (slightly more CPU than a GPU texture blit)
- Both costs are negligible for NES-resolution content (256×240)

## JavaFX Rendering Pipelines
- **D3D** — Windows default. `ImageView.isSmooth` is NOT honored.
- **OpenGL** (`-Dprism.order=es2`) — Second-class on Windows, may have other glitches.
- **Software** (`-Dprism.order=sw`) — Always works but slow.
- **No Vulkan backend** exists in JavaFX as of version 21.

## Key Takeaway
For any pixel-art or nearest-neighbor scaling in JavaFX, always use
`Canvas.GraphicsContext.isImageSmoothing` — never rely on `ImageView.isSmooth`.
