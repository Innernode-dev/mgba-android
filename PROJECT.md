# Project: Standalone GBA/GB/GBC Android Emulator (mGBA Core)

## Architecture
A standalone, production-grade Android emulator built on the upstream mGBA emulation core (MPL-2.0) with an arm64-v8a native JNI engine, high-performance EGL/OpenGL ES 3.0/2.0 video renderer, low-latency Google Oboe audio subsystem, and a modern DuckStation-style Jetpack Compose UI with Room DB, high-speed SAF scanning, automated/manual box art, and complete controller support (including Mangmi Air X handheld).

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI (Kotlin)                     │
│  - MainActivity: LibraryScreen (Responsive Cover Grid), Hero Banner    │
│  - Room DB: GameEntity, FolderEntity, ProfileEntity                    │
│  - FastRomScanner (DocumentsContract Cursor batching)                  │
│  - CoverArtManager (WebP 512x512 downsampling, Coil cache)            │
│  - SettingsScreen (Material 3 Video, Audio, Performance, Folders)      │
│  - GamepadProfileManager & InputRemapScreen                            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Intent launch / JNI Callbacks
┌───────────────────────────────────▼────────────────────────────────────┐
│                    EmulationActivity & Touch Overlay                   │
│  - Fullscreen Immersive SurfaceView / GLSurfaceView                    │
│  - Multi-touch Canvas Overlay (D-Pad, A, B, L, R, Start, Select, Turbo)│
│  - Gamepad / Mangmi Air X Event Dispatcher (KeyEvent, MotionEvent)     │
│  - In-game Pause & Quick Save / Quick Load Menu                        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ JNI Bridge (`libmgba_jni.so`)
┌───────────────────────────────────▼────────────────────────────────────┐
│                       Native Engine (C / C++ / NDK)                    │
│  - NativeEmulatorCore: JNI JNIEnv bindings                             │
│  - EGL / OpenGL ES 3.0 Renderer (ANativeWindow, Aspect 3:2/Integer/    │
│    Stretch, Nearest/Bilinear/LCD-Grid GLSL shader)                     │
│  - Google Oboe Audio Engine (AAudio / OpenSL ES, SPSC lock-free buffer)│
│  - Save State Manager (Slots 0..9, mCoreExtractExtdata thumbnail)      │
│  - Battery SRAM Manager (debounced atomic file sync)                   │
│  - Frame Timing Loop (59.7275 FPS pacing, Frame Skip, Fast Forward)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ C Core API (`mCore`)
┌───────────────────────────────────▼────────────────────────────────────┐
│                     Upstream mGBA 0.10.5 Core (MPL-2.0)                │
│  - GBA Emulation Core (ARM7TDMI, PPU, APU, Memory, DMA, Timers)        │
│  - GB/GBC Emulation Core (SM83, PPU, APU, MBC1/2/3/5)                  │
│  - VFS (`VFileOpen`, `VFileFromFD`, `VFileFromConstMemory`)            │
│  - `blip_buf` Band-limited Audio Resampler                             │
└────────────────────────────────────────────────────────────────────────┘
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Upstream mGBA Compilation | Native `libmgba.so` compiled for `arm64-v8a` with Android NDK 26.1 & CMake 3.22 (MPL-2.0) | M1 | R1, R5 |
| 2 | Android Project Scaffolding | Gradle 8.8, AGP 8.5.2, minSdk 26, compileSdk 34, targetSdk 34, Kotlin 1.9, Compose BOM | M1 | R5 |
| 3 | MPL-2.0 License Compliance | Full license text in `assets/licenses/` and third-party notices (`blip_buf`, `inih`) | M1 | R1 |
| 4 | ROM Loading Subsystem | Load `.gba`, `.gb`, `.gbc` from path, SAF FD (`VFileFromFD`), or memory buffer | M2 | R1 |
| 5 | Video Renderer (EGL/GLES) | Presentation on `ANativeWindow`, 240x160 (GBA) and 160x144 (GB/GBC) 32-bit RGBA | M2 | R4 |
| 6 | Aspect Ratio Modes | Original 3:2 (GBA) / 10:9 (GB), Integer Scaling, and Stretched | M2 | R4 |
| 7 | Scaling Filters & Shaders | Nearest Neighbor, Bilinear, and authentic LCD-grid subpixel shader | M2 | R4 |
| 8 | Audio Engine (Oboe/AAudio) | Synchronized, crackle-free 48kHz stereo 16-bit PCM via lock-free SPSC ring buffer | M2 | R4 |
| 9 | Frame Skip & Timing | Hardware scanline skipping and 59.7275 FPS clock synchronization | M2 | R4 |
| 10 | Fast Forward Multipliers | 1.5x, 2x, 4x, 8x with audio drop/mute and display throttling | M2 | R4 |
| 11 | Save States Subsystem | Multi-slot (0..9) fast save/load with embedded screenshot thumbnail extraction | M2 | R1 |
| 12 | Battery SRAM Persistence | `savedataUpdated` hook with debounced atomic disk flush | M2 | R1 |
| 13 | DuckStation Cover Grid | Jetpack Compose responsive grid, dynamic column sizing, game cards | M3 | R2 |
| 14 | "Resume Last Played" Card | Hero banner with last played game, screenshot preview, and quick resume action | M3 | R2 |
| 15 | High-Speed SAF ROM Scanner | Direct `DocumentsContract` cursor query traversing SAF trees in <1s | M3 | R2 |
| 16 | Library Metadata & Management | Room DB, title, game code, region, size, sorting, favorites, recents, search | M3 | R2 |
| 17 | Automated Box Art Matcher | Match ROM filename against online Libretro repository template URLs | M4 | R2 |
| 18 | Manual Box Art Assignment | Context menu picker from device gallery, 512x512 WebP downscaling, cache update | M4 | R2 |
| 19 | Virtual Multi-Touch Overlay | D-Pad, A, B, L, R, Start, Select, Turbo A/B with 30Hz pulse and haptics | M5 | R3 |
| 20 | Touch Overlay Customization | Drag-and-drop editor for button position, size, and opacity | M5 | R3 |
| 21 | Physical Gamepad Support | Bluetooth and USB gamepads via standard Android `KeyEvent` & `MotionEvent` | M5 | R3 |
| 22 | Mangmi Air X Handheld Support| Dual Hall sticks, physical D-pad, face buttons, analog triggers, touch-free UI | M5 | R3 |
| 23 | Touchless UI Focus Navigation| D-Pad and analog stick Compose `FocusManager` navigation with glowing indicators | M5 | R3 |
| 24 | Input Remapping Subsystem | Interactive button remap dialog, profile creation/switching, persistent DataStore | M5 | R3 |
| 25 | Comprehensive Settings UI | Material 3 preferences: video, audio, performance, custom folders, orientation lock | M6 | R4 |
| 26 | Custom Save & State Folders | User-selectable SAF directories for `.sav` and `.ss*` files | M6 | R4 |
| 27 | Per-Game Launch Config | Custom configurations per game with global fallbacks | M6 | R4 |
| 28 | Auto-Save on App Exit | Automatic state/SRAM flush on activity pause/stop | M6 | R1 |
| 29 | Full E2E Test Suite (Tiers 1-4) | Comprehensive opaque-box test harness verifying all 28 features | M7 | AC |
| 30 | Adversarial Coverage Hardening | Tier 5 white-box stress testing and edge-case verification | M7 | AC |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Project Scaffolding & Native mGBA Core Build | Android Gradle project, AGP 8.5.2, NDK 26.1, CMake 3.22, compile `libmgba.so` arm64-v8a, JNI bridge scaffolding, MPL-2.0 notices | none | DONE |
| M2 | Native Emulation Engine & Audio/Video Pipeline | Core lifecycle, ROM loading (.gba/.gb/.gbc), EGL GLES renderer, Aspect Ratios (3:2/Integer/Stretch), Shaders, Oboe audio, SRAM auto-sync, Save States with thumbnails, Frame Skip, Fast Forward | M1 | DONE |
| M3 | DuckStation-Style UI, Room DB & SAF Scanner | Jetpack Compose dark OLED UI, responsive game grid, Resume Last Played hero card, DocumentsContract fast SAF recursive scanner, Room DB schema, filters/search/sorting/favorites | M1 | IN_PROGRESS |
| M4 | Box Art Pipeline & Cover Management | Automated cover matching (Libretro naming templates), manual photo gallery assignment, 512x512 WebP downscaling, Coil cache invalidation, cartridge badge fallbacks | M3 | PLANNED |
| M5 | Controls Subsystem, Touch Overlay, Gamepad & Mangmi Air X | Multi-touch virtual overlay with Turbo A/B, layout editor (pos/size/opacity), physical gamepad mapping, Mangmi Air X handheld profile, touchless Compose focus navigation, profile remapper | M2, M3 | PLANNED |
| M6 | Settings Hierarchy & System Integration | Material 3 settings hierarchy, video/audio/timing controls, custom save/state SAF folders, orientation lock, per-game configs, auto-save on pause/exit | M2, M5 | PLANNED |
| M7 | Final Milestone: 100% E2E Pass & Tier 5 Hardening | Execute full E2E test suite (Tiers 1-4) published by E2E Testing Track, resolve all regressions, conduct Tier 5 adversarial stress testing | M1-M6, TEST_READY | PLANNED |

## Interface Contracts

### M1/M2 Native Bridge (`com.retro.mgba.core.NativeCore`) ↔ Kotlin (`EmulationActivity`)
- `nativeCreate(sampleRate: Int, bufferFrames: Int): Long` -> Returns context pointer.
- `nativeDestroy(handle: Long): Unit`
- `nativeLoadRomPath(handle: Long, path: String): Boolean`
- `nativeLoadRomFd(handle: Long, fd: Int): Boolean`
- `nativeLoadRomBuffer(handle: Long, buffer: ByteBuffer, size: Int): Boolean`
- `nativeUnloadRom(handle: Long): Unit`
- `nativeReset(handle: Long): Unit`
- `nativeRunFrame(handle: Long): Boolean`
- `nativeSetKeys(handle: Long, keyMask: Int): Unit` (Bits 0..9: A, B, Select, Start, Right, Left, Up, Down, R, L)
- `nativeSetSurface(handle: Long, surface: Surface?): Unit`
- `nativeSetSurfaceSize(handle: Long, width: Int, height: Int): Unit`
- `nativeSetAspectRatioMode(handle: Long, mode: Int): Unit` (0: 3:2 / 10:9, 1: Integer, 2: Stretch)
- `nativeSetShaderFilter(handle: Long, filter: Int): Unit` (0: Nearest, 1: Bilinear, 2: LCD-Grid)
- `nativeSetAudioVolume(handle: Long, volumePercent: Int): Unit`
- `nativeSetAudioMute(handle: Long, mute: Boolean): Unit`
- `nativeSaveState(handle: Long, slot: Int, stateDir: String): Boolean`
- `nativeLoadState(handle: Long, slot: Int, stateDir: String): Boolean`
- `nativeExtractStateThumbnail(handle: Long, statePath: String): ByteArray?`
- `nativeFlushSram(handle: Long, sramPath: String): Boolean`
- `nativeSetFrameskip(handle: Long, frameskip: Int): Unit`
- `nativeSetFastForward(handle: Long, multiplier: Float): Unit`

### M3 Scanner & Room DB ↔ M4 Box Art Manager ↔ M3 UI
- `FastRomScanner.scanFolder(treeUri: Uri): Flow<ScanProgress>`
- `GameDao.insertAll(games: List<GameEntity>)`
- `GameDao.observeAll(filter: GameFilter, sort: SortOption): Flow<List<GameEntity>>`
- `CoverArtManager.setManualCover(gameId: String, selectedImageUri: Uri)`
- `CoverArtManager.resolveCover(game: GameEntity): String?`

### M5 Input Subsystem ↔ M2 Native Core & M3 Compose UI
- `InputDevice.SOURCE_GAMEPAD or SOURCE_JOYSTICK` events -> `updateGbaBit(mask: Int, isPressed: Boolean)` -> `NativeCore.nativeSetKeys`
- Joystick threshold (>0.6f / <-0.6f) in `MainActivity` -> `FocusManager.moveFocus(FocusDirection)`

## Code Layout
```
c:\Users\Bypass\Desktop\эмулька\
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   └── licenses/
        │   │       ├── LICENSE.txt
        │   │       ├── blip_buf.txt
        │   │       └── inih.txt
        │   ├── cpp/
        │   │   ├── CMakeLists.txt
        │   │   ├── mgba/               # Upstream mGBA 0.10.5 core source
        │   │   ├── mgba_jni.cpp        # JNI Bridge implementation
        │   │   ├── video/
        │   │   │   ├── egl_renderer.cpp
        │   │   │   ├── egl_renderer.h
        │   │   │   └── shaders.h       # GLSL shaders (Nearest, Bilinear, LCD-Grid)
        │   │   └── audio/
        │   │       ├── oboe_audio.cpp
        │   │       ├── oboe_audio.h
        │   │       └── ring_buffer.h
        │   ├── java/ (or kotlin/)
        │   │   └── com/retro/mgba/
        │   │       ├── MainActivity.kt
        │   │       ├── EmulationActivity.kt
        │   │       ├── core/
        │   │       │   └── NativeCore.kt
        │   │       ├── data/
        │   │       │   ├── db/
        │   │       │   │   ├── AppDatabase.kt
        │   │       │   │   ├── GameEntity.kt
        │   │       │   │   └── GameDao.kt
        │   │       │   ├── scanner/
        │   │       │   │   └── FastRomScanner.kt
        │   │       │   └── covers/
        │   │       │       └── CoverArtManager.kt
        │   │       ├── input/
        │   │       │   ├── GamepadManager.kt
        │   │       │   ├── GamepadProfile.kt
        │   │       │   └── TouchOverlayModel.kt
        │   │       └── ui/
        │   │           ├── theme/
        │   │           ├── library/
        │   │           │   ├── LibraryScreen.kt
        │   │           │   ├── GameGridCard.kt
        │   │           │   └── ResumeHeroCard.kt
        │   │           ├── emulation/
        │   │           │   ├── VirtualTouchOverlay.kt
        │   │           │   └── InGameMenu.kt
        │   │           ├── remapping/
        │   │           │   └── RemapScreen.kt
        │   │           └── settings/
        │   │               └── SettingsScreen.kt
        │   └── res/
        └── test/
            └── ...
```
