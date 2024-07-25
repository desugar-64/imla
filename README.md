# Imla - Experimental GPU-Accelerated Blurring for Android Jetpack Compose

> ⚠️ **IMPORTANT**: This project is experimental and not intended for use in production
> applications.

## Description

Imla (Ukrainian for "Haze", pronounced [ˈimlɑ] (eem-lah)) is an experimental project exploring GPU-accelerated view blurring on
Android. It aims to implement efficient blurring effects using OpenGL, targeting devices from
Android 6 onwards.

The project serves as a playground for experimenting with GPU rendering and post-processing effects,
with the potential to evolve into a full-fledged library in the future.

## Features

- Gamma corrected blurring;
- Adjustable blur radius;
- Color tinting of blurred areas;
- Blending with noise mask for a frosted glass effect;
- Blurring masks for gradient blur effects;
- Supports Android 6+.

## Demo
Pixel 6

https://github.com/user-attachments/assets/a421bca8-efdf-4e18-b737-df45f7bdaadf

https://github.com/user-attachments/assets/40b64436-fb2a-423b-b2ae-88f72a88e06a

https://github.com/user-attachments/assets/d27ca925-30ab-4772-847b-51df57ef92b0

https://github.com/user-attachments/assets/8ebce81b-f143-4028-a7c3-7f146bdb9e1d

https://github.com/user-attachments/assets/088709c5-5a0b-491c-b61f-2b45e37408f2

https://github.com/user-attachments/assets/3f63c8f8-3739-41ab-b736-3937fad32a3e

Nexus 5(2013)

https://github.com/user-attachments/assets/3802d8ee-6c10-4a9f-8826-4a8041a88730

|                                                                                                                                                  |                                                                                                 |
|--------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| <img width="640" alt="blur_gamma_correction_side_by_side" src="https://github.com/user-attachments/assets/85ad6c09-de0d-4bbd-89f5-a11a6aa8ac98"> | ![mosaic_blur](https://github.com/user-attachments/assets/71d81431-a2cf-4aca-bb0d-d469ced53cee) |

## How It Works

Imla uses a combination of `GraphicsLayer` from Jetpack Compose and OpenGL ES 3.0 to achieve fast,
GPU-accelerated blurring:

1. A specified view is rendered as a background texture using `Surface` and `SurfaceTexture`(
   see [RenderableRootLayer.kt](imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/RenderableRootLayer.kt)).
2. The texture is copied to a post-processing framebuffer.
3. A [BackdropBlur](imla/src/main/java/dev/serhiiyaremych/imla/ui/BackdropBlur.kt) composable wraps
   child elements that need a blurred background.
4. The blurred texture is rendered as a SurfaceView background to the wrapped elements, creating the
   illusion of a blurred backdrop.

The post-processing pipeline includes:

1. Downsampling the background
   texture, [RenderableRootLayer.kt](imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/RenderableRootLayer.kt);
2. Applying a two-pass blur algorithm with gamma
   correction, [BlurEffect](imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/postprocessing/blur/BlurEffect.kt);
3. Blending with a noise texture for a frosted glass
   effect, [NoiseEffect](imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/postprocessing/noise/NoiseEffect.kt);
4. Optional application of a mask for progressive or gradient blur
   effects, [MaskEffect](imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/postprocessing/mask/MaskEffect.kt).

Importantly, all blur color processing is performed in linear color space, with appropriate gamma
decoding and encoding applied to ensure colors blend naturally, preserving vibrancy and contrast.

## Rendering Abstraction

I’m reusing the OpenGL abstractions from another one of my
projects: [android-opengl-renderer](https://github.com/desugar-64/android-opengl-renderer). This
project where I was learning graphics rendering, specifically OpenGL, built convenient abstractions
for setting up OpenGL data structures and calling various OpenGL functions.

The current implementation uses a fully dynamic renderer, which pushes vertex data each frame. While
this approach offers flexibility, it introduces some performance overhead. Future iterations aim to
optimize this aspect of the rendering pipeline.

## Performance

Current performance metrics for the blur effect on a Pixel 6 device:

- BlurEffect#applyEffect: ~1.19ms
- RenderObject#onRender : ~4.842ms

| Trace                                                                                                  |
|--------------------------------------------------------------------------------------------------------|
| ![trace_blur_effect](https://github.com/user-attachments/assets/add113c5-4ccf-4ff4-a8c1-37fa404e8048)  |
| ![trace_total_pass00](https://github.com/user-attachments/assets/78e8a4c5-43ec-4fc6-b0eb-89c6a77a1042) |
| ![trace_total_pass01](https://github.com/user-attachments/assets/d97a629d-b683-4868-9229-c09331954a5d) |

These timings indicate that the blur effect and rendering process are relatively fast, but there's
still room for optimization.

## Future Plans

- Implement Dual Kawase Blurring Filter for improved performance;
- Optimize the rendering pipeline and OpenGL abstractions;
- Address synchronization issues between the main thread and OpenGL thread.

## Contributing

This project is open to suggestions and recommendations for improvements. Feel free to open issues
or submit pull requests on GitHub.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Development Updates

For project development updates and history, follow this Twitter thread:
[https://x.com/desugar_64/status/1787633739117277669](https://x.com/desugar_64/status/1787633739117277669)
