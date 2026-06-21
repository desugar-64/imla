# GL-Only Premium Effects

The ideas below assume Compose UI arrives as a flat texture mapped onto quads; each entry is intended for GL/shader implementation rather than Canvas/Compose-only approaches.

## Post Processing
| Name | Description | OpenGL ES hint | Cost |
| --- | --- | --- | --- |
| Color Grading LUT | 2D LUT presets with per-layer intensity for branded looks that static color filters can’t match. | Sample 16/32 LUT texture in fragment shader; blend LUT result with base color via uniform weight. | Cheap–Medium |
| Tone Mapping + Bloom Clamp | HDR-style rolloff plus subtle glow on highlights to avoid clipped whites while keeping punch. | Downsample bright pass, Kawase blur it, then recompose with exposure curve on final quad. | Medium |
| Chromatic Aberration | Subtle RGB channel separation toward edges for depth cues. | Offset UVs per channel in fragment based on radial distance; single quad pass. | Cheap |
| Halation Edge Glow | Film-like reddish glow around bright edges requiring multipass detection. | Sobel bright edges to a buffer, blur, additively blend back over scene. | Medium |
| Procedural Film Grain | Blue-noise grain animated over time to hide banding without tiled bitmaps. | Hash-based noise or tiled blue-noise lookup with time offset in fragment. | Cheap |
| Luminance-Preserving Vignette | Edge darken that preserves midtones via per-pixel luminance shaping. | Compute luma, apply curved radial falloff mask in fragment shader. | Cheap |

## Composition / Blending
| Name | Description | OpenGL ES hint | Cost |
| --- | --- | --- | --- |
| Advanced Blend Modes | Screen/Overlay/Soft Light per-layer like creative tools. | Implement blend math in compositor shader; optional MRT to avoid extra passes. | Medium |
| Light Shafts (God Rays) | Rays from bright masked regions for hero banners. | Epipolar/radial blur from brightness mask toward light origin then additive blend. | Medium–Expensive |
| Dynamic Soft Shadows | True soft drop/inner shadows respecting rounded shapes and transforms. | Build SDF mask in FBO, separable blur once, composite with distance-based alpha. | Medium |
| Anisotropic Bloom | Directionally stretched bloom for cinematic highlights. | Directional separable blur on bright pass with orientation uniform. | Medium |
| Reflection/Sheen Stripe | Animated reflective streak across surfaces tied to tilt/rotation. | Procedural gradient with Fresnel-like curve in fragment; additive blend. | Cheap |

## Geometry / Distortion
| Name | Description | OpenGL ES hint | Cost |
| --- | --- | --- | --- |
| Refraction Glass | Refracts background through a normal map for glassy chips. | Sample scene texture with normal-map UV offsets; normals authored or procedural. | Medium |
| Ripple / Water Distortion | Touch-driven ripples that bend background content. | Maintain small heightmap FBO, blur it, use gradients to offset sampling UVs. | Medium |
| Fisheye / Barrel Warp | Lens-like warp for hero cards not feasible without per-pixel UV math. | Apply radial distortion function to UVs in fragment shader. | Cheap |
| Magnifier Loupe | Circular magnify region with edge falloff and chroma-correct zoom. | Masked UV scaling plus vignette in fragment; optional chroma tweak. | Cheap |
| Parallax Layer Stack | Multi-plane backgrounds reacting to scroll/tilt. | Per-layer UV offsets from depth weights; batched quad renderer with shared sampler. | Cheap–Medium |
| Tilted 3D Card with Rim Light | Perspective tilt plus rim/specular driven by pseudo-normal. | Vertex tilt on quad; fragment computes rim from view vector dot pseudo-normal. | Cheap |
| Heat Haze | Wavy distortion over hot areas (e.g., loading shimmer). | Time-evolving noise normal map offsets UVs before sampling scene. | Cheap |

## Motion / Reactivity
| Name | Description | OpenGL ES hint | Cost |
| --- | --- | --- | --- |
| Velocity Trails | Moving elements leave fading trails using history beyond Canvas. | Store previous frame in small FBO, decay, composite with current pose. | Medium |
| Motion Smear | Directional smear along velocity vector for fast gestures. | Sample scene along velocity direction multiple taps in fragment; jitter samples. | Medium |
| Reactive Specular Highlight | Glossy hotspot that follows touch/tilt on surfaces. | Compute incidence from touch vector, render moving Gaussian lobe in fragment. | Cheap |
| Temporal Glitch / Scanline | Brief scanline shifts and RGB splits for transitions. | Time-based line offset and channel shift in fragment gated by mask. | Cheap |

## Dev Tools / Diagnostics
| Name | Description | OpenGL ES hint | Cost |
| --- | --- | --- | --- |
| GPU Fill Heatmap | Visualize overdraw/fill hotspots beyond stock Compose. | Accumulate per-pixel writes via MRT/stencil then render false-color overlay. | Medium |
| Mask/UV Debug View | On-demand UV gradient and mask alpha visualization per layer. | Swap in debug shader to draw quads with UV/mask encoding. | Cheap |
| Shader Hot Reload | Live swap shader sources/LUTs without restart. | Recompile programs at runtime; on failure, fall back to previous program. | Medium |
| Effect Timing Overlay | On-screen timing bars per pass using GPU timers. | Use EXT_disjoint_timer_query where available, render mini HUD with results. | Medium–Expensive |
