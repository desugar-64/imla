# Imla - (Experimental) GPU-Accelerated Blurring for Android Jetpack Compose UI

> ⚠️ **Disclaimer**:
> This project is experimental and not intended for use in production applications.

## Description

Imla (Ukrainian for "Haze", pronounced [ˈimlɑ] (eem-lah)) is an experimental project exploring
GPU-accelerated view blurring on Android. It aims to implement efficient blurring effects using
OpenGL, targeting devices from Android 6 (API 23) onwards.

The project serves as a playground for experimenting with GPU rendering and post-processing effects,
with the potential to evolve into a full-fledged library in the future.

## Features

- Gamma corrected blurring;
- Adjustable blur radius;
- Color tinting of blurred areas;
- Blending with a noise mask for a frosted glass effect;
- Setting blurring masks for gradient blur effects;
- Supports Android 6 (API 23) onwards.

## Demo

<table>
    <thead>
        <tr>
            <th colspan=2><b>Pixel 6</b></th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td><img width="300" alt="Gradient blur demo" src="demo/gradient_blur.webp"></td>
            <td><img width="300" alt="Blur demo" src="demo/p6_blur_demo.webp"></td>
        </tr>
        <tr>
            <td><img width="300" alt="Neat blur algorithm bug" src="demo/blur_bug.webp"></td>
            <td><img width="300" alt="Noise blend demo" src="demo/p6_noise_blend_demo.webp"></td>
        </tr>
        <tr>
            <td><img width="300" alt="Mosaic blur demo" src="demo/mosaic_blur.webp"></td>
            <td><img width="300" alt="Gamma corrected blur" src="demo/gamma_corrected_blur.webp"></td>
        </tr>
    </tbody>
</table>


| **Nexus 5**                                                                                                                                      |
|--------------------------------------------------------------------------------------------------------------------------------------------------|
| <img width="600" alt="Live blur demo" src="demo/nexus_5_demo.webp">                                                                               |
| <img width="600" alt="Blur gamma correction side-by-side" src="https://github.com/user-attachments/assets/85ad6c09-de0d-4bbd-89f5-a11a6aa8ac98"> |
| <img width="600" alt="Mosaic blur" src="https://github.com/user-attachments/assets/71d81431-a2cf-4aca-bb0d-d469ced53cee">                        |

## Current Renderer Work

The active renderer work is a scratch prototype under the `scene2` package. It
is intentionally smaller than the removed Renderer 2 audit/design stack.

Current prototype flow:

1. `Modifier.sceneSource()` records Compose root content into a `GraphicsLayer`.
2. On pre-draw, the source layer is synchronously captured on the main thread
   into a `HardwareBuffer`.
3. `SceneRenderer` submits an immutable render snapshot through a latest-only
   vsync scheduler.
4. `SceneGlOwner` imports the buffer on the GL thread and presents it to the
   host surface.
5. `Modifier.sceneSlot { ... }` registers prototype child slots through a
   scene registry. Slot content can be captured, imported, transformed, and
   drawn as a GL texture.
6. Scene2 backdrop slots now run through prepare, separable blur, composite,
   stencil clip, tint, material noise, progressive mask, and accumulated-scene
   sampling paths.

See [doc/scene2-scratch-renderer-status.md](doc/scene2-scratch-renderer-status.md)
for the current status, implemented pieces, non-goals, and next step.

## Rendering Abstraction

The project reuses the OpenGL abstractions from another experimental
project: [desugar-64/android-opengl-renderer](https://github.com/desugar-64/android-opengl-renderer).
This repo is a playground to learn graphics and OpenGL, including some convenient abstractions
for setting up OpenGL data structures and calling various OpenGL functions.

The current implementation uses a fully dynamic renderer, which pushes vertex data each frame. While
this approach offers flexibility, it introduces some performance overhead. Future iterations aim to
optimize this aspect of the rendering pipeline.

## Performance

Current performance metrics for the blur effect on a Pixel 6 device:

- `BlurEffect#applyEffect`: ~1.19ms
- Scene composite/render pass timing varies by demo scene.

| Trace                                                                                                  |
|--------------------------------------------------------------------------------------------------------|
| ![trace_blur_effect](https://github.com/user-attachments/assets/add113c5-4ccf-4ff4-a8c1-37fa404e8048)  |
| ![trace_total_pass00](https://github.com/user-attachments/assets/78e8a4c5-43ec-4fc6-b0eb-89c6a77a1042) |
| ![trace_total_pass01](https://github.com/user-attachments/assets/d97a629d-b683-4868-9229-c09331954a5d) |

These timings indicate that the blur effect and rendering process are relatively fast, but there's
still room for optimization.

## Future Plans

- [x] Render root Compose content through OpenGL in the scratch scene path;
- [x] Capture and draw prototype child slot content as a GL texture;
- [x] Validate rotated slot geometry and root-space backdrop sampling;
- [x] Replace the backdrop debug pass with real scene2 blur passes;
- [x] Add progressive masks, stencil clips, tint, noise, and cumulative backdrop
  sampling to the scratch scene path;
- [ ] Add automatic cumulative dependency optimization and metrics.

## Contributing

This project is open to suggestions and contributions. Feel free to open issues
or submit pull requests on GitHub.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Development Updates

For project development updates and history, refer
to [this Twitter thread](https://x.com/desugar_64/status/1787633739117277669).
