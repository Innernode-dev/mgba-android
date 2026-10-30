#ifndef RETRO_VIDEO_SHADERS_H
#define RETRO_VIDEO_SHADERS_H

// Vertex shader compatible with GLSL ES 1.00 (OpenGL ES 2.0 & 3.0)
static const char* const kVertexShaderSource =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "varying vec2 vTexCoord;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
    "    vTexCoord = aTexCoord;\n"
    "}\n";

// Passthrough fragment shader for Nearest Neighbor (Filter 0) and Bilinear (Filter 1)
// Explicitly forces alpha to 1.0 since mGBA 32-bit pixel buffer has alpha = 0x00
static const char* const kPassthroughFragmentShaderSource =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "void main() {\n"
    "    vec4 col = texture2D(uTexture, vTexCoord);\n"
    "    gl_FragColor = vec4(col.rgb, 1.0);\n"
    "}\n";

// Authentic LCD-grid subpixel matrix shader (Filter 2)
// Simulates physical GBA/GBC TFT screen borders and vertical RGB subpixel triads
static const char* const kLcdGridFragmentShaderSource =
    "precision mediump float;\n"
    "varying vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "uniform vec2 uSourceRes;\n" // vec2(240.0, 160.0) or vec2(160.0, 144.0)
    "void main() {\n"
    "    vec4 col = texture2D(uTexture, vTexCoord);\n"
    "    vec2 pixel = vTexCoord * uSourceRes;\n"
    "    vec2 cell = fract(pixel);\n"
    "\n"
    "    // Smooth grid border darkening between LCD pixel cells\n"
    "    vec2 border = smoothstep(vec2(0.0), vec2(0.12), cell) *\n"
    "                  smoothstep(vec2(1.0), vec2(0.88), cell);\n"
    "    float grid = border.x * border.y;\n"
    "    float gridFactor = mix(0.72, 1.0, grid);\n"
    "\n"
    "    // Vertical RGB subpixel triad simulation\n"
    "    float subX = cell.x * 3.0;\n"
    "    float subPixel = fract(subX);\n"
    "    float subGap = smoothstep(0.0, 0.15, subPixel) * smoothstep(1.0, 0.85, subPixel);\n"
    "    float subGapFactor = mix(0.85, 1.0, subGap);\n"
    "\n"
    "    vec3 subpixelMask = vec3(1.0);\n"
    "    if (subX < 1.0) {\n"
    "        subpixelMask = vec3(1.15, 0.90, 0.90);\n" // Red stripe
    "    } else if (subX < 2.0) {\n"
    "        subpixelMask = vec3(0.90, 1.15, 0.90);\n" // Green stripe
    "    } else {\n"
    "        subpixelMask = vec3(0.90, 0.90, 1.15);\n" // Blue stripe
    "    }\n"
    "\n"
    "    vec3 finalRgb = col.rgb * gridFactor * subGapFactor * subpixelMask;\n"
    "    gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), 1.0);\n"
    "}\n";

#endif // RETRO_VIDEO_SHADERS_H
