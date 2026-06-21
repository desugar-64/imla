Advanced Architectural Correction and Optimization Strategies for Mobile Graphics on Android OpenGL ES 3.0
1. Introduction and Architectural Paradigm Correction
The optimization of real-time graphics engines for the Android ecosystem necessitates a fundamental decoupling from the architectural assumptions that have historically governed desktop and console GPU development. The user’s specific inquiry regarding the correction of research for "mobile GPU architecture" and "Android OpenGL ES 3.0" implies a critical divergence in the approach to rendering pipelines. This report serves as an exhaustive technical analysis and corrective framework, dismantling the erroneous application of immediate-mode rendering (IMR) techniques—standard in desktop environments—when applied to the tile-based deferred rendering (TBDR) architectures that dominate the mobile System-on-Chip (SoC) landscape.
The distinction between mobile and desktop rendering is not merely a question of raw computational throughput or thermal envelopes; it is a fundamental difference in memory hierarchy and data flow. Mobile GPUs—exemplified by the Qualcomm Adreno, ARM Mali, and Imagination Technologies PowerVR series—are engineered with a primary directive: the minimization of external memory bandwidth. In a mobile device, accessing Dynamic Random Access Memory (DRAM) is the single most energy-expensive operation, consuming orders of magnitude more power than local Arithmetic Logic Unit (ALU) operations.
Consequently, research that prioritizes ALU reduction at the cost of increased texture fetches or render target switches is fundamentally flawed in a mobile context.Furthermore, the specific constraint of the OpenGL ES 3.0 specification introduces rigorous boundaries on available tooling. Unlike later iterations or desktop counterparts, OpenGL ES 3.0 lacks the generalized compute capabilities that modern desktop techniques rely upon. This necessitates a "return to basics" regarding pipeline management, forcing developers to exploit the rasterization pipeline for tasks that would otherwise be offloaded to compute shaders. This report will systematically correct these misconceptions, providing a blueprint for high-performance graphics engine design on Android devices.
2. The Compute Shader Fallacy in OpenGL ES 3.0
A persistent error in cross-platform graphics research is the assumption that modern GPGPU (General Purpose GPU) techniques are universally available across all "modern" APIs. The user’s query specifically targets OpenGL ES 3.0, a specification that, while supporting advanced rendering features like instancing and transform feedback, explicitly excludes compute shaders.
2.1 Specification Analysis and Versioning
The research material clarifies a distinct boundary in the OpenGL ES roadmap. The OpenGL ES 3.0 specification was designed to bring handheld graphics into parity with the OpenGL 3.x desktop functionality regarding the rendering pipeline—enhancing visual fidelity through occlusion queries, multiple render targets (MRT), and instanced rendering.
However, it does not include the compute pipeline. Compute shaders, defined by the glDispatchCompute command and the presence of Shader Storage Buffer Objects (SSBOs), were introduced only in OpenGL ES 3.1.
This distinction is non-trivial. 
The compute shader paradigm allows for arbitrary writes to memory (scatter/gather) and the utilization of shared local memory (SLM) to cache texture reads between threads, which is a primary optimization strategy for convolution filters on desktop GPUs.
In the absence of this capability, the "correction" for Android OpenGL ES 3.0 research must redirect focus back to the fragment shader. All image processing must be mapped to screen-space quads. This reintroduces the constraints of the rasterization pipeline:
Output Limitation: A fragment shader can only write to the specific pixel coordinate it is assigned (though MRT allows writing to multiple buffers at that coordinate). It cannot write to neighbors.Input Limitation: While random access reads (via texture2D) are permitted, they do not benefit from the programmable cache coherence that local group memory in compute shaders provides.Parallelism: Execution is strictly bound by the rasterizer's traversal of the screen tiles, rather than the flexible workgroup dispatch of a compute pipeline.
Therefore, any "optimized" blur algorithm proposed for this platform must be evaluated solely on its efficiency as a fragment shader, utilizing standard texture sampling and framebuffer operations. The "fast compute shader blur" is a phantom on this platform; the reality is the "optimized fragment shader blur."
3. Mobile GPU Architecture: The Tile-Based Imperative
To correctly optimize for Android, one must first internalize the mechanics of Tile-Based Rendering (TBR) and Tile-Based Deferred Rendering (TBDR). Unlike desktop GPUs, which typically rasterize geometry immediately to a full-frame framebuffer in VRAM (Immediate Mode Rendering), mobile GPUs subdivide the screen into small on-chip tiles (e.g., 16x16 or 32x32 pixels).
3.1 The Tile Buffer and On-Chip Memory (GMEM)
The defining feature of mobile architecture is the presence of fast, on-chip memory. In Qualcomm Adreno documentation, this is often referred to as "GMEM" (Graphics Memory, though confusingly used to refer to on-chip memory in some contexts and system memory in others depending on the era; modern usage implies the fast tile buffer). In generic terms, it is the "Tile Buffer".
The rendering process proceeds in two distinct phases:Binning (Tiling) Pass: The GPU processes the geometry for the entire scene (vertex shading) to determine which primitives intersect which tiles. It builds a display list for each tile.Rendering Pass: The GPU iterates through the tiles. For each tile, it loads the necessary geometry, executes the fragment shaders, and accumulates the color/depth data entirely within the high-speed local memory. Only when the tile is complete is the final result written back to system RAM.This architecture has profound implications for optimization that contradict desktop IMR wisdom.
3.1.1 Bandwidth Conservation
On an IMR architecture, if a pixel is shaded three times (overdraw), it results in three read-modify-write transactions to video memory. On a TBR architecture, these intermediate operations happen in the tile buffer.
If a pixel is overwritten, the intermediate value is discarded locally without ever touching the system memory bus. This provides a massive bandwidth saving for scenes with high depth complexity, provided the application does not force the GPU to resolve intermediate states.
3.1.2 The Cost of Context Switching
Switching render targets (Frame Buffer Objects - FBOs) is a heavyweight operation on mobile. In desktop OpenGL, binding a new FBO changes a pointer. On mobile, binding a new FBO forces the GPU to "flush" the current tile to memory (Resolve) and potentially prepare the new FBO. This breaks the pipelining efficiency that TBR relies upon.
If an algorithm requires frequent switching between FBOs (e.g., a naive ping-pong blur), it effectively linearizes the tile processing, forcing frequent stalls and memory flushes.
3.2 Vendor-Specific Optimizations and Nuances
A robust research report must distinguish between the major GPU vendors, as their implementation of tiling varies.
3.2.1 Qualcomm Adreno: FlexRender and Binning
Adreno GPUs utilize a sophisticated binning architecture. Adreno has a specific optimization where it can switch between "Binning" mode (TBR) and "Direct" mode (IMR) depending on the workload, though TBR is the default for complex 3D. LRZ (Low Resolution Z): Adreno constructs a low-resolution depth buffer during the binning phase to reject occluded geometry early. To maximize this, research must recommend disabling writes to the color buffer during depth pre-passes. If the application fails to clear or invalidate a framebuffer before rendering, the Adreno driver assumes the previous content is needed and pulls it from slow system RAM into the fast GMEM tile. This is a performance killer.
3.2.2 ARM Mali: Transaction Elimination and Forward Pixel Kill
Mali architectures (Midgard, Bifrost, Valhall) introduce specific bandwidth saving technologies.Transaction Elimination (TE): The GPU calculates a CRC signature (hash) for the tile's color data. If the signature matches the previous frame's tile at that location, the write to system memory is skipped entirely.
This is crucial for static UI elements or skyboxes.Forward Pixel Kill (FPK): This technique allows the GPU to discard threads (fragments) that are currently executing if a newer, opaque primitive is rasterized that covers the same pixel.18 This provides some implicit hidden surface removal without a full depth pre-pass, correcting the assumption that sorting is strictly required for performance, though front-to-back sorting remains a best practice.
3.2.3 Imagination PowerVR: HSR (Hidden Surface Removal)
PowerVR takes deferred rendering further with dedicated hardware for HSR. It delays fragment shading until all geometry for the tile has been processed, ensuring that only the single visible pixel is shaded (Zero Overdraw).
Sorting Implication: On PowerVR, sorting opaque geometry is largely redundant because the hardware handles visibility determination perfectly before shading. However, sorting by state (e.g., shader, texture) is still recommended to reduce driver overhead.FBO Grouping: 
4. Bandwidth Optimization: The Framebuffer Lifecycle
The single most critical optimization for Android OpenGL ES 3.0 is the management of framebuffer attachments. This area requires the most significant "correction" from desktop-centric research.
4.1 The "Invalidate" Command: glInvalidateFramebuffer
When the GPU finishes rendering a tile, it writes the data to main memory (Resolve). When it starts a new frame or a new pass, it must decide what to do with the existing data in the tile buffer. By default, OpenGL assumes the application needs the previous contents, forcing the GPU to read the framebuffer from DRAM into the tile memory (Unresolve or Load). This "GMEM Load" is disastrous for performance if the application intends to clear the screen anyway.
The Correction: The research must emphasize the use of glInvalidateFramebuffer (OpenGL ES 3.0+) or glDiscardFramebufferEXT (OpenGL ES 2.0 extension). These commands explicitly tell the driver that the contents of the framebuffer attachments (color, depth, stencil) are transient and do not need to be preserved.21Usage Pattern 1: Early InvalidationCall glInvalidateFramebuffer immediately after binding an FBO if you intend to overwrite it (e.g., via glClear). This tells the driver "Do not load the previous frame's data from DRAM."Adreno Specifics: This prevents the "Unresolve" penalty.
Mali Specifics: This allows the tile buffer to start in an uninitialized state, saving the read bandwidth.
Usage Pattern 2: Late InvalidationCall glInvalidateFramebuffer immediately after the render pass finishes for attachments that are not needed for the next pass.Depth/Stencil Buffers: In 99% of 3D rendering, the depth buffer is only needed during the frame generation. Once the color image is finalized, the depth data is useless. Invalidating the depth attachment at the end of the frame prevents the GPU from writing those megabytes of depth data back to system RAM.
Intermediate Color Buffers: In a ping-pong blur chain, once an intermediate texture has been sampled by the subsequent pass, it can be invalidated (though this is tricky in OpenGL ES 3.0 strict pipelining; typically, this applies to the depth attachment of the intermediate FBOs).
4.2 Grouping and Serialization
In a desktop renderer, one might interleave rendering to a shadow map and the main screen. On mobile, this is inefficient.Correct Approach: Render the Shadow Map FBO to completion. Then unbind. Then bind the Main FBO. Render to completion.Why: This ensures that the tile memory is flushed and reused sequentially. Interleaving causes the driver to potentially thrash the tile buffer contents, forcing spills to system memory (similar to register spilling on a CPU).
5. Algorithmic Correction: The Gaussian Blur
The user query highlights "correct this research" in the context of blur algorithms. Standard implementations of Gaussian blur are notoriously inefficient on mobile hardware due to their high texture fetch counts and dependent texture reads.
5.1 The Mathematical Inefficiency of Standard Gaussian
A mathematically perfect Gaussian blur requires calculating a weighted average of pixels surrounding a target pixel. For a kernel of radius $R$, a naive implementation requires $(2R+1)^2$ texture fetches per pixel. Even separated into horizontal and vertical passes (reducing complexity to $2 \times (2R+1)$), the bandwidth cost remains prohibitive for large radii.4For a modest blur radius of 30 pixels (common for bloom effects), a separated Gaussian blur requires 61 texture fetches per pixel per pass.Bandwidth Calculation: For a 1080p screen (1920x1080 $\approx$ 2 million pixels), a 61-tap filter reads $2 \times 10^6 \times 61 \approx 122$ million texels per frame. At 60 FPS, this is billions of fetches.ALU vs. Tex: On mobile, texture units are often shared or lower frequency than ALUs. Saturing the texture unit with 61 fetches creates a massive latency bottleneck that the ALU cannot hide.
5.2 Optimization 1: Exploiting Hardware Linear Sampling
A crucial correction to standard research is the exploitation of the GPU's hardware linear interpolator. By sampling at coordinates exactly between two texels, the hardware returns the weighted average of those texels essentially for "free" (single cycle fetch).Mechanism:Instead of fetching texel $i$ and texel $i+1$ separately with weights $w_i$ and $w_{i+1}$, one can fetch at coordinate $x + \text{offset}$, where the offset is determined by the ratio of the weights.4The offset $O$ from the center of the first texel is calculated as:$$O = \frac{w_{i+1}}{w_i + w_{i+1}}$$The new combined weight $W_{new}$ is:$$W_{new} = w_i + w_{i+1}$$Result: This reduces the number of texture fetches by half. A 9-tap Gaussian blur can be approximated with just 5 hardware linear taps.Implementation Note: This requires GL_LINEAR filtering to be enabled on the texture parameters. The research must explicitly state that GL_NEAREST will break this optimization.
5.3 Optimization 2: Dual Kawase Blur – The Mobile Standard
The most significant "correction" for mobile blur research is the shift from Gaussian to the Dual Kawase algorithm. Originally presented by Masaki Kawase and refined by ARM engineers for mobile, this technique is specifically designed to maximize blur radius while minimizing bandwidth.
5.3.1 The Algorithm
The Dual Kawase approach replaces the "large kernel" paradigm with a "multi-pass, multi-resolution" paradigm.Downsample Loop: The image is iteratively downsampled (Full $\to$ Half $\to$ Quarter $\to$ Eighth...). Each downsample pass applies a small fixed kernel (typically 4 or 5 taps).Upsample Loop: The image is iteratively upsampled and blended with the previous resolution. This upsample step acts as a second blur filter.
5.3.2 Why It Wins on Mobile
Bandwidth Reduction: Because the subsequent passes are performed on buffers that are 1/4, 1/16, and 1/64 the size of the screen, the total number of pixels processed decays geometrically.Gaussian (Multipass): Processing $2 \times N$ pixels for every pass.Dual Kawase: Processing $N + N/4 + N/16 + \dots$ pixels. The series converges to $\approx 1.33 N$.Fixed Kernel: It uses a small, fixed kernel at each pass. The "blur" radius increases because the texels themselves represent larger areas of the screen (the "footprint" of the texel grows).
Visual Quality: While not mathematically identical to a Gaussian (it approximates a Gaussian distribution via the central limit theorem as passes accumulate), the visual result is a smooth, high-quality bokeh-like blur that is perceptually superior for effects like bloom.
5.3.3 Comparison Table: Gaussian vs. Dual Kawase on Mobile
FeatureSeparated Gaussian (Linear)Dual KawasePasses2 (Horizontal + Vertical)4-6 (Downsample + Upsample chain)Texture FetchesHigh ($N \times \text{Radius}$)Low ($N \times \text{Small Constant}$)BandwidthExtremely High (Full Res read/write)Low (Rapidly decreasing buffer sizes)FBO SwitchesLow (1 switch)Moderate (Requires efficient batching)VisualsExact GaussianBokeh-like, CinematicSuitabilityDesktop / Compute ShaderMobile / Tile-BasedResearch Correction: Any mobile research proposing a standard 7x7 or larger Gaussian blur loop in a fragment shader should be corrected to recommend Dual Kawase or, at minimum, a downsampled Gaussian approach.6. Stability in High Dynamic Range: The Karis AverageWhen implementing bloom or blur on High Dynamic Range (HDR) content, a standard downsample can result in "fireflies"—single bright pixels that flicker and smear uncontrollably.
6.1 The Firefly Artifact
In HDR rendering, a pixel might have a value of $(100.0, 100.0, 100.0)$ representing a bright light source. If this pixel is surrounded by black pixels $(0,0,0)$, a standard linear average (box filter) during downsampling will spread this high energy to all neighbors, creating a large, unstable bright blob. As the camera moves, sub-pixel aliasing causes this bright spot to "snap" between downsample bins, creating a flickering strobing effect.
6.2 The Karis Average Correction
Developed by Brian Karis (Epic Games) for Unreal Engine 4 and popularized in Call of Duty: Advanced Warfare research, this technique modifies the first downsample pass.Mechanism: Instead of a simple average, the shader calculates a weighted average where the weight is the inverse of the luma (brightness).32$$w_i = \frac{1}{1 + \text{Luma}(c_i)}$$$$\text{Result} = \frac{\sum (c_i \times w_i)}{\sum w_i}$$Effect: This effectively acts as a "soft suppressor" for extremely bright pixels during the downsampling phase. The energy is conserved but damped, preventing the single-pixel outlier from dominating the average.Mobile Application: This is critical for mobile HDR pipelines where floating point precision (FP16) might exacerbate flickering due to range clamping issues. Snippet 33 provides the exact HLSL/GLSL code structure for this, which should be adapted to the ES 3.0 context.
7. Texture Formats and Precision Strategies
A critical aspect of Android OpenGL ES 3.0 research is selecting the correct texture format. Desktop research often defaults to GL_RGBA16F (64 bits per pixel) or GL_RGBA32F (128 bits per pixel) for HDR. On mobile, these formats devour bandwidth.
7.1 The GL_R11F_G11F_B10F Format
Introduced in OpenGL ES 3.0, GL_R11F_G11F_B10F is a packed floating-point format that stores an RGB value in a single 32-bit integer.8Structure: 11 bits for Red (float), 11 bits for Green (float), 10 bits for Blue (float). No Alpha channel.Advantage: It provides floating-point dynamic range (values > 1.0) for HDR rendering but consumes exactly the same bandwidth as a standard GL_RGBA8 texture (32 bpp).
Mobile Implication: This is the "magic bullet" for mobile HDR. It allows for bloom, tone mapping, and high-contrast lighting without the 2x bandwidth penalty of half-float (GL_RGBA16F) textures.35Correction: Research using GL_RGB16F for mobile render targets should be corrected to use GL_R11F_G11F_B10F wherever the alpha channel is unnecessary.
7.2 Extension Chaos: GL_EXT_color_buffer_float
A nuance of OpenGL ES 3.0 is that while GL_R11F_G11F_B10F is a required texture format (for sampling), it is not guaranteed to be color-renderable in the core specification. Renderability often requires the GL_EXT_color_buffer_float extension.
Ecosystem Analysis:Android 7.0+ (OpenGL ES 3.2): This format is renderable by core specification.Android 4.3 - 6.0 (OpenGL ES 3.0/3.1): Renderability depends on the extension.Adreno: generally supports it.Mali: Older drivers (Mali-400/Mali-T series) are stricter and may not support rendering to this format, even if they support sampling from it.Fallback Strategy: Robust research must include a fallback path. If GL_EXT_color_buffer_float is missing, the engine might need to fall back to GL_RGB10_A2 (unsigned normalized) or standard GL_RGB8 with an encoding scheme (like RGBM or RGBE) to simulate HDR.
7.3 Shader Precision: mediump vs highp
One of the most distinct differences between desktop GLSL and mobile GLSL ES is the handling of precision qualifiers. On desktop GPUs, mediump and lowp are often treated effectively as highp (32-bit float). On mobile, specifically ARM Mali and to some extent Qualcomm Adreno, using lower precision directly translates to performance gains.
7.3.1 The ALU Factor
Mali GPUs: These architectures often have dedicated 16-bit ALU paths. Performing calculations in mediump (FP16) can effectively double the throughput compared to highp (FP32).
Energy consumption is also significantly reduced.Adreno GPUs: While Adreno has a unified shader architecture, register pressure is a key factor. Using highp variables requires more register space. If a shader uses too many registers, the GPU can spawn fewer "waves" (threads) in parallel, reducing occupancy and ability to hide texture latency.
7.3.2 The "Correction" for Shader Code
Research or code snippets ported from desktop often default to:OpenGL Shading Languageprecision highp float;
Correction: For mobile optimization, the default in fragment shaders should be:OpenGL Shading Languageprecision mediump float;
highp should only be used for:Position calculations (vertex shader) to prevent geometry jitter.Texture coordinates (varying) to prevent sampling artifacts.41Depth comparisons or exponential functions where precision loss results in visible banding.
8. Vertex Shader Offloading: Dependent Texture Reads
Snippet 42 and 43 highlight a micro-optimization that can yield macro results: moving texture coordinate calculations to the vertex shader.
8.1 Dependent vs. Independent ReadsIndependent Read: The texture coordinate is a varying passed directly from the vertex shader. The GPU knows the coordinate before the fragment shader starts executing ALU instructions.Benefit: The Texture Management Unit (TMU) can "prefetch" the texel data while the ALU is setting up the thread. Latency is hidden.Dependent Read: The texture coordinate is calculated inside the fragment shader (e.g., vec2 uv = v_texCoord + offset * i;).Cost: The TMU cannot fetch the data until the ALU has computed the coordinate. The pipeline stalls waiting for the memory fetch.
8.2 The Optimization
For a fixed kernel blur (e.g., 5-tap linear), instead of calculating the 5 offsets in the fragment shader loop:Vertex Shader: Calculate v_texCoord0, v_texCoord1, v_texCoord2, etc.Varyings: Pass these as 5 separate varying vec2 vectors.Fragment Shader: Simply call texture2D(u_tex, v_texCoord0).Constraint: Mobile GPUs have a limit on the number of varyings (vectors), typically 16 to 32. This technique consumes varyings rapidly. It is ideal for small kernels (Dual Kawase) but impractical for large loops. This reinforces the Dual Kawase choice: small kernels allow for vertex-offloaded coordinates, maximizing prefetch efficiency.
9. Case Study: Implementing an Optimized Mobile Bloom
To synthesize these insights, we construct a theoretical "Corrected" pipeline for a Bloom effect on Android OpenGL ES 3.0.
9.1 The "Old" / "Incorrect" Research PathRender Scene: Render to GL_RGBA16F FBO. Thresholding: Use a Compute Shader (impossible in ES 3.0) or a high-bandwidth full-screen pass to extract bright spots.Blur: Apply a two-pass Gaussian blur with a large radius (e.g., 50 pixels).Horizontal Pass: 101 taps.Vertical Pass: 101 taps.Composite: Additive blending to the main buffer.Critique:Memory: RGBA16F uses double the bandwidth of R11G11B10F.API: Compute shader fails on ES 3.0.Performance: 50-pixel radius Gaussian is unrunnable on mobile due to massive texture fetch count.Artifacts: Lack of firefly mitigation.
9.2 The "Corrected" Mobile Path
Render Scene: Render to GL_R11F_G11F_B10F FBO (checking GL_EXT_color_buffer_float).Downsample & Threshold (Pass 1):Render to an FBO 1/2 or 1/4 the size.Shader: Apply Karis Average to suppress fireflies during this downsample.
Precision: Use mediump for color, highp for threshold math if necessary.Dual Kawase Blur Chain:Downsample Loop: Blit to 1/8, 1/16, 1/32 size textures. Each step applies a small 5-tap kernel (exploiting linear sampling).Upsample Loop: Blit back up to 1/16, 1/8, 1/4, blending with the previous downsampled results.Efficiency: The blurs happen on tiny textures (e.g., 64x64 pixels). Bandwidth usage is negligible compared to full-screen.Composite: One final pass to add the blurred bloom texture to the main scene.Clean Up: Call glInvalidateFramebuffer on all intermediate FBOs immediately after use to prevent GMEM resolves.2110. ConclusionOptimizing for Android OpenGL ES 3.0 is an exercise in constraint management. The freedom of desktop development—infinite bandwidth, massive ALU budgets, and unified memory architectures—does not exist here. The "correction" of standard research involves three primary pillars:Architecture-Aware Algorithms: Replacing Gaussian Blurs with Dual Kawase or Linear-Sampled approximations to respect the bandwidth limitations of Tile-Based Deferred Renderers.API Reality Checks: Acknowledging that Compute Shaders are absent in ES 3.0 and relying on optimized Fragment Shader pipelines instead.Data Type Discipline: Rigorously using GL_R11F_G11F_B10F for HDR and mediump precision for shaders to maximize the utility of the available bits and cycles.By adhering to these principles, developers can achieve console-class visual effects (Bloom, HDR, Depth of Field) on constrained mobile hardware without compromising frame stability or battery life.
11. Appendix: Technical Reference Data
11.1 Mobile Texture Format Support Matrix (Android ES 3.0)
For reference, the following formats are critical for the discussed optimizations.FormatInternal Format HexType EnumChannelsBits/PixelUsageR11F_G11F_B10F0x8C3AUNSIGNED_INT_10F_11F_11F_REVRGB (No Alpha)32HDR Color Buffer (Primary Recommendation)RGB10_A20x8059UNSIGNED_INT_2_10_10_10_REVRGB + Alpha32High Precision LDR or Limited HDR. Good if Alpha needed.RGBA16F0x881AHALF_FLOATRGBA64"True" HDR. High Bandwidth. Avoid if possible.SRGB8_ALPHA80x8C43UNSIGNED_BYTERGBA32
Gamma-correct Albedo/Diffuse textures.
11.2 Vendor-Specific Optimization Flags
Qualcomm Adreno:Fast-Z: Disable color writes (glColorMask(0,0,0,0)) during depth pre-pass.LRZ: Do not switch direction of depth test (keep GL_LESS or GL_GREATER consistent).ARM Mali:Transaction Elimination: Ensure background is cleared or static UI is aligned to 16x16 tile boundaries.FPK: Minimize blend-enabled draw calls. Opaque geometry works best.Imagination PowerVR:HSR: Submit opaque geometry first (any order, but generally standard). Do not sort opaque geometry by depth; sort by material to reduce state changes.TBR: Group FBO renders strictly.
11.3 Code Snippet: Optimized Linear Sampling Offset
To implement the linear sampling correction described in Section 5.2:OpenGL Shading Language// GLSL ES 3.0 - Vertex Shader
// Pre-calculate offsets to avoid dependent reads in fragment shader
uniform vec2 u_texelSize;
out vec2 v_blurCoords[1];

void main() {
    // Standard position calculation...
    gl_Position =...;
    
    vec2 center = a_texCoord;
    
    // Offsets derived from Pascal's triangle for 9-tap approximation
    // Weights: 1, 4, 6, 4, 1 -> Linear weights shift offsets
    float offset1 = 1.3846153846; 
    float offset2 = 3.2307692308;
    
    v_blurCoords = center;
    v_blurCoords[2] = center + u_texelSize * offset1;
    v_blurCoords[3] = center - u_texelSize * offset1;
    v_blurCoords[4] = center + u_texelSize * offset2;
    v_blurCoords[5] = center - u_texelSize * offset2;
}
This code snippet encapsulates the core "correction": moving math to the vertex shader and exploiting linear filtering weights.
End of Report.


High-Performance Blur Optimization for Android OpenGL ES 3.0

1 Executive Summary & RecommendationFor an Android OpenGL ES 3.0 environment where Compute Shaders are unavailable and "Dual Kawase" quality is insufficient, the recommended solution is the Scaled-Gaussian Hybrid with Hardware Linear Sampling.
This approach resolves the performance/quality deadlock by addressing the root cause: Memory Bandwidth.
Performance: It runs on a downscaled buffer (typically 1/2 resolution) and uses hardware linear filtering to halve the number of texture fetches.
Quality: Unlike the blocky artifacts of Dual Kawase, it uses a true separable Gaussian kernel, maintaining a smooth, perfectly round distribution.
Configurability: It retains the standard $\sigma$ (sigma) parameter, allowing precise, continuous control over blur strength.

2. The Architectural Context: Why Mobile is DifferentTo optimize effectively, we must look beyond the shader code and understand the physical hardware of mobile GPUs (Adreno, Mali, PowerVR).
2.1 Texture Swizzling & Morton OrderOn a CPU, memory is linear (row-major). On a GPU, textures are not stored row-by-row. They use Morton Order (Z-order curves) or similar swizzling patterns.
The Concept: Memory is organized into small 2D tiles (e.g., 4x4 or 8x8 pixel blocks) that are stored contiguously in VRAM.
The Benefit: This ensures that pixel $(x, y)$ is close in memory to both $(x+1, y)$ and $(x, y+1)$.
Implication for Blur: A separable Gaussian blur traverses pixels horizontally and then vertically. Because of swizzling, both passes enjoy high cache coherency. If textures were linear, the vertical pass would thrash the cache by jumping huge strides (width * bytes_per_pixel) for every neighbor.
2.2 Coherent vs. Incoherent SamplingCoherent Sampling: When adjacent threads (pixels) read from adjacent memory addresses. This maximizes Spatial Locality. The GPU fetches a cache line (e.g., 64 bytes) and all threads in the wave use it.
Incoherent Sampling: When adjacent threads read from widely scattered memory addresses.
The Danger: If your blur radius is massive (e.g., > 64 pixels) on a high-res texture, your offsets might exceed the size of the L1/L2 cache tiles. This causes "cache thrashing," where the GPU constantly evicts and re-fetches data.
Solution: This is why Downscaling (Step 1 of the recommended algorithm) is non-negotiable. It brings the sample points closer together in memory space, restoring coherency.

3. The Recommended Algorithm: Scaled-Gaussian Hybrid
This pipeline consists of three passes designed to maximize the hardware's strengths.
Step 1: The Downsample PassRender the full-screen image to a 50% (1/2) size framebuffer.
Bandwidth: Reduces total pixel processing by 75%.
Mipmapping: Generating mipmaps (glGenerateMipmap) acts as "The Cache's Best Friend." Lower mip levels fit entirely into the GPU's L2 cache, making subsequent sampling nearly instantaneous.
Step 2: The Linear-Sampled Gaussian (The "Free" Speedup)Apply a separable Gaussian blur (Horizontal Pass, then Vertical Pass) on this smaller texture. We optimize the kernel to use Hardware Linear Sampling.The Math: By sampling exactly between two texels with GL_LINEAR enabled, the TMU (Texture Management Unit) returns the average. We adjust our weights to mathematically simulate two discrete samples with one hardware fetch.
Gain: A 9-tap Gaussian kernel normally requires 9 fetches. With linear sampling, it requires only 5 fetches.
Step 3: The Upsample PassRender the blurred result back to the screen using linear interpolation.

4. Optimization Deep Dive: Eliminating Bottlenecks
4.1 The "Dependent Texture Read" TrapThis is the single most common performance killer on mobile shaders.
Independent Read: Texture coordinates are passed as varying from the Vertex Shader. The GPU's Prefetcher can fetch the texture data from memory before the Fragment Shader even starts running. The latency is hidden.
Dependent Read: Texture coordinates are calculated inside the Fragment Shader (e.g., uv + vec2(0.01, 0.0)). The Prefetcher cannot run because it doesn't know the address yet. The shader stalls, waits for the ALU to calculate the coordinate, then stalls again waiting for the texture fetch.
The Fix: Calculate all blur offsets in the Vertex Shader.
4.2 Channel Packing (Bandwidth Reduction)Every texture() call is a transaction with memory. If you need auxiliary data (Roughness, Metallic, etc.), do not store them in separate textures.
Bad (4 Fetches):OpenGL Shading Languagevec4 albedo = texture(uAlbedo, uv);
float rough = texture(uRoughness, uv).r;
float metal = texture(uMetallic, uv).r;
float ao = texture(uAO, uv).r;
Good (2 Fetches):OpenGL Shading Languagevec4 albedo = texture(uAlbedo, uv);
// ORM = Occlusion, Roughness, Metallic packed in RGB
vec4 orm = texture(uORM, uv); 
4.3 Texture AtlasingPros: Related textures share cache space. If you draw multiple sprites from the same atlas, the cache stays "warm."
Cons for Blur: Blurring an atlas is dangerous. You must carefully manage padding and clamp-to-edge logic, otherwise, the blur kernel will pull in pixels from the neighboring sprite in the atlas (Bleeding Artifacts). For full-screen blurs, use standalone textures.
5. Implementation Code
5.1 Optimized Vertex Shader (Pre-calculated Offsets)This moves math out of the fragment shader to avoid Dependent Texture Reads.
OpenGL Shading Language#version 300 es
layout(location = 0) in vec4 a_position;
layout(location = 1) in vec2 a_texCoord;

uniform vec2 u_texelSize; // (1.0/width, 1.0/height) * direction
// Output 5 coordinates for a 9-tap blur (Standard Gaussian)
out vec2 v_blurCoords[1]; 

void main() {
    gl_Position = a_position;
    vec2 center = a_texCoord;
    
    // Offsets for Linear Sampling (9 taps -> 5 fetches)
    // 1.3846... is the optimized offset to sample 2 texels at once
    float off1 = 1.3846153846; 
    float off2 = 3.2307692308;
    
    v_blurCoords = center;
    v_blurCoords[2] = center + u_texelSize * off1;
    v_blurCoords[3] = center - u_texelSize * off1;
    v_blurCoords[4] = center + u_texelSize * off2;
    v_blurCoords[5] = center - u_texelSize * off2;
}
5.2 Optimized Fragment Shader (Linear Sampling)Using mediump is critical for ALU speed on Mali/Adreno.OpenGL Shading Language#version 300 es
precision mediump float; // Double-speed ALU on many mobile GPUs

uniform sampler2D u_texture;
in vec2 v_blurCoords[1];
out vec4 o_color;

void main() {
    // Weights derived for Linear Sampling
    float weight0 = 0.2270270270;
    float weight1 = 0.3162162162;
    float weight2 = 0.0702702703;

    // 5 Fetches cover 9 pixels of kernel data
    vec4 sum = texture(u_texture, v_blurCoords) * weight0;
    sum += texture(u_texture, v_blurCoords[2]) * weight1;
    sum += texture(u_texture, v_blurCoords[3]) * weight1;
    sum += texture(u_texture, v_blurCoords[4]) * weight2;
    sum += texture(u_texture, v_blurCoords[5]) * weight2;

    o_color = sum;
}
6. Mobile-Specific ChecklistTo ensure 60 FPS performance, strictly adhere to these platform constraints:Use GL_R11F_G11F_B10F: For HDR, use this 32-bit format instead of GL_RGBA16F (64-bit). It cuts memory bandwidth in half.
glInvalidateFramebuffer: Call this immediately after you are done with an FBO (e.g., after the Horizontal pass). This tells the Tile-Based Renderer not to write that temporary tile back to main memory, saving massive bandwidth.
Clamp to Edge: Always use GL_CLAMP_TO_EDGE. It prevents the GPU from fetching useless cache lines from the opposite side of the texture.
Avoid Dynamic Loop Limits: Loops in mobile shaders should ideally have constant iterations (unrolled by the compiler). Dynamic loops can prevent prefetching optimizations.

7. Summary of StrategyDownscale input to 1/2 size (use R11G11B10F).
Pass 1: Horizontal Blur using Vertex-Calculated Linear Sampling (Avoid dependent reads). Invalidate previous FBO (Save bandwidth).
Pass 2: Vertical Blur using the same shader.Invalidate previous FBO.Upscale to screen.


This report consolidates the "Bounded Blur" and "Premultiplied Alpha" requirements into a concrete, high-performance pipeline optimized for Android OpenGL ES 3.0 mobile architectures.

### **1. Recommended Algorithm: The "Renormalized Separable Gaussian"**

To satisfy the requirements for **Bounded Edges** (no dark halos at clip borders) and **Variable Blur Strength**, you cannot use standard Dual Kawase. You must use a **Two-Pass Separable Gaussian Blur** with **Dynamic Weight Renormalization**.

**The Pipeline:**

1. **Downsample (1x Pass):** Blit input to a 50% resolution `GL_RGBA8` (LDR) or `GL_RGBA16F` (HDR) texture.
2. **Horizontal Blur (1x Pass):** Gaussian blur X-axis. Accumulate valid samples only. Renormalize.
3. **Vertical Blur (1x Pass):** Gaussian blur Y-axis. Accumulate valid samples only. Renormalize.
4. **Upsample (1x Pass):** Linear blit back to screen/destination.

---

### **2. Concrete Shader Implementation**

This implementation integrates **Linear Sampling** (speed), **Vertex Offsetting** (latency hiding), and **Renormalization** (edge correctness).

#### **A. Vertex Shader (Optimizing Texture Reads)**

**Constraint:** Mobile GPUs (Adreno/Mali) suffer from "Dependent Texture Read" latency. Calculating UVs in the fragment shader breaks texture pre-fetching.
**Fix:** Pre-calculate offsets in the Vertex Shader.

```glsl
#version 300 es
// Vertex Shader
layout(location = 0) in vec4 a_position;
layout(location = 1) in vec2 a_texCoord;

uniform vec2 u_texelSize;   // (1.0/width, 1.0/height) * direction (1,0) or (0,1)
uniform float u_blurRadius; // Standard deviation (sigma)

// Send 3 pairs of coordinates (Central + 2 offsets) to cover 5 linear taps (9 actual pixels)
out vec2 v_uv0;
out vec2 v_uv1;
out vec2 v_uv2;
out vec2 v_uv3;
out vec2 v_uv4;

void main() {
    gl_Position = a_position;
    
    // Linear Sampling Optimization:
    // We fetch 2 texels at once by sampling between them.
    // Offsets 1.4 and 3.2 are standard approximations for a ~9-tap Gaussian.
    vec2 off1 = u_texelSize * 1.3846153846 * u_blurRadius;
    vec2 off2 = u_texelSize * 3.2307692308 * u_blurRadius;

    v_uv0 = a_texCoord;
    v_uv1 = a_texCoord + off1;
    v_uv2 = a_texCoord - off1;
    v_uv3 = a_texCoord + off2;
    v_uv4 = a_texCoord - off2;
}

```

#### **B. Fragment Shader (The "Bounded" Logic)**

**Constraint:** Standard blur darkens edges because sampling outside the clip rect returns transparent black (0,0,0,0).
**Fix:** Check bounds. Accumulate weights *only* for valid pixels. Divide final color by total valid weight.

```glsl
#version 300 es
// Fragment Shader
precision mediump float; // Critical for ALU performance on Mali

uniform sampler2D u_texture;
uniform vec4 u_clipRect; // (minX, minY, maxX, maxY) in UV space [0-1]

// Weights for Linear Sampling (Sum = 1.0)
const float w0 = 0.227027;
const float w1 = 0.316216;
const float w2 = 0.070270;

in vec2 v_uv0;
in vec2 v_uv1;
in vec2 v_uv2;
in vec2 v_uv3;
in vec2 v_uv4;

out vec4 o_color;

// Helper to check if UV is inside the UI clipping rectangle
float isValid(vec2 uv) {
    // Returns 1.0 if inside, 0.0 if outside. 
    // Uses step() for branchless execution (faster on GPU).
    vec2 s = step(u_clipRect.xy, uv) - step(u_clipRect.zw, uv);
    return s.x * s.y; 
}

void main() {
    vec4 accColor = vec4(0.0);
    float accWeight = 0.0;

    // --- Tap 0 (Center) ---
    float valid = isValid(v_uv0);
    accColor += texture(u_texture, v_uv0) * w0 * valid;
    accWeight += w0 * valid;

    // --- Tap 1 & 2 ---
    valid = isValid(v_uv1);
    accColor += texture(u_texture, v_uv1) * w1 * valid;
    accWeight += w1 * valid;

    valid = isValid(v_uv2);
    accColor += texture(u_texture, v_uv2) * w1 * valid;
    accWeight += w1 * valid;

    // --- Tap 3 & 4 ---
    valid = isValid(v_uv3);
    accColor += texture(u_texture, v_uv3) * w2 * valid;
    accWeight += w2 * valid;

    valid = isValid(v_uv4);
    accColor += texture(u_texture, v_uv4) * w2 * valid;
    accWeight += w2 * valid;

    // --- Renormalization ---
    // Prevent divide by zero if all pixels are clipped
    if (accWeight > 0.0) {
        o_color = accColor / accWeight;
    } else {
        o_color = vec4(0.0); // Fully clipped
    }
}

```

---

### **3. Handling Alpha (Premultiplication)**

To avoid "Dark/Bright Fringes" (halo artifacts):

1. **Input Requirement:** Your source texture **must be Premultiplied Alpha**.
* If your source is straight alpha (e.g., standard PNG), you must multiply RGB by A in the **Downsample Pass** shader: `color.rgb *= color.a;`.


2. **Blur Processing:** The shader above simply sums `RGBA`. Since the input is premultiplied, this is mathematically correct. It blends "amount of color" rather than "color value."
3. **Renormalization:** The line `o_color = accColor / accWeight;` handles the edge weighting.
4. **Unpremultiply (Optional):**
* If your UI composition pipeline expects **Straight Alpha**, you must "unmultiply" at the very end of the second blur pass (or the upsample pass):


```glsl
if (o_color.a > 0.001) {
    o_color.rgb /= o_color.a; // Restore Straight Alpha
}

```


* *Note:* Keeping it premultiplied is generally faster and safer for the compositor.



---

### **4. Mobile Architecture Quirks (Critical Checklist)**

To hit 60 FPS on Android, you must adhere to these hardware realities:

**1. Bandwidth is the Bottleneck (Tile-Based Rendering)**

* **Problem:** Switching FBOs (Horizontal -> Vertical) causes the GPU to write the tile to main memory and read it back.
* **Quirk Solution:** Use **`glInvalidateFramebuffer`**.
* After drawing the Horizontal Pass, call `glInvalidateFramebuffer` on its depth/stencil attachments (if any).
* If you are overwriting the whole buffer (which blur does), call `glInvalidateFramebuffer` on the *target* FBO immediately after binding it to prevent the GPU from "restoring" the old content from main memory.



**2. Texture Format Selection**

* **Problem:** The efficient `GL_R11F_G11F_B10F` format **has no Alpha channel**.
* **Constraint:** You explicitly need Alpha for "Bounded Blur" logic.
* **Solution:**
* **Standard UI:** Use `GL_RGBA8` (32-bit). It is native and fast.
* **HDR UI:** You *must* use `GL_RGBA16F` (64-bit). This doubles bandwidth usage. Minimize the resolution of your blur buffer (e.g., 1/4 screen size) to compensate.



**3. Precision Optimization**

* **Quirk:** Adreno and Mali ALUs often run twice as fast on `mediump` (16-bit float) compared to `highp` (32-bit).
* **Solution:** Use `precision mediump float;` in your fragment shader. Only use `highp` for the Texture Coordinates in the Vertex Shader if you see jittering on large screens.

**4. Texture Cache "Swizzling"**

* **Quirk:** GPUs store textures in Morton Order (Z-curve) for cache locality.
* **Solution:** This is automatic, BUT it fails if you use **dependent texture reads** (calculating UVs in the frag shader). By using the **Vertex Shader offset method** (Section 2A), you ensure the GPU's Texture Prefetcher can look ahead and load the correct cache lines before the fragment shader needs them.

**5. Texture Arrays vs Atlases**

* **Quirk:** Blurring a Sprite inside a Texture Atlas causes bleeding from neighbors.
* **Solution:** Do **not** blur a sub-region of an atlas. Use **`GL_TEXTURE_2D_ARRAY`** if you need to batch multiple UI blur elements. This allows `GL_CLAMP_TO_EDGE` to work natively on each layer, which is required for the "Bounded" logic to work without complex math.
