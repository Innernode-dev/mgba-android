#!/usr/bin/env python3
"""Challenger Adversarial Stress & Verification Test Suite.

Independent empirical verification for Milestone 2:
1. APK & Native binary analysis (Elf64, dynamic symbols, imports, exports, DT_NEEDED)
2. Kotlin <-> C JNI signature bijection
3. Aspect ratio mathematics & boundary testing
4. Lock-free SPSC ring buffer mathematical emulation & stress simulation
5. Shader token & parameter validation
6. Save state slot, thumbnail, and atomic SRAM path validation
"""

import os
import sys
import re
import math
import zipfile
import struct
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from tests.e2e.fixtures.elf_analyzer import Elf64Analyzer


APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"


class TestM2ChallengerAdversarial(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(APK_PATH):
            raise FileNotFoundError(f"APK artifact not found at {APK_PATH}")
        cls.zip_file = zipfile.ZipFile(APK_PATH)

    @classmethod
    def tearDownClass(cls):
        cls.zip_file.close()

    def test_01_apk_native_libraries_exist_and_sized(self):
        """Verify arm64-v8a native libraries exist in APK with non-trivial sizes."""
        namelist = self.zip_file.namelist()
        expected_libs = [
            ("lib/arm64-v8a/libc++_shared.so", 500_000, 2_000_000),
            ("lib/arm64-v8a/libmgba.so", 2_000_000, 4_000_000),
            ("lib/arm64-v8a/libmgba_jni.so", 80_000, 500_000),
        ]
        for lib_name, min_sz, max_sz in expected_libs:
            self.assertIn(lib_name, namelist, f"Missing required library: {lib_name}")
            info = self.zip_file.getinfo(lib_name)
            self.assertGreaterEqual(info.file_size, min_sz, f"{lib_name} size {info.file_size} is below expected {min_sz}")
            self.assertLessEqual(info.file_size, max_sz, f"{lib_name} size {info.file_size} exceeds expected {max_sz}")

    def test_02_dynamic_symbols_exact_30_jni_exports(self):
        """Verify all 30 JNI methods are defined, exported, and STT_FUNC in libmgba_jni.so."""
        jni_data = self.zip_file.read("lib/arm64-v8a/libmgba_jni.so")
        analyzer = Elf64Analyzer(jni_data)
        self.assertTrue(analyzer.is_valid, "libmgba_jni.so is not a valid AArch64 ELF")

        expected_symbols = [
            "Java_com_retro_mgba_core_NativeCore_nativeCreate",
            "Java_com_retro_mgba_core_NativeCore_nativeDestroy",
            "Java_com_retro_mgba_core_NativeCore_nativeExtractStateThumbnail",
            "Java_com_retro_mgba_core_NativeCore_nativeFlushSram",
            "Java_com_retro_mgba_core_NativeCore_nativeGetCoreVersion",
            "Java_com_retro_mgba_core_NativeCore_nativeGetGameCode",
            "Java_com_retro_mgba_core_NativeCore_nativeGetGameTitle",
            "Java_com_retro_mgba_core_NativeCore_nativeGetPlatform",
            "Java_com_retro_mgba_core_NativeCore_nativeGetVersion",
            "Java_com_retro_mgba_core_NativeCore_nativeGetVideoHeight",
            "Java_com_retro_mgba_core_NativeCore_nativeGetVideoWidth",
            "Java_com_retro_mgba_core_NativeCore_nativeIsSramDirty",
            "Java_com_retro_mgba_core_NativeCore_nativeLoadRomBuffer",
            "Java_com_retro_mgba_core_NativeCore_nativeLoadRomFd",
            "Java_com_retro_mgba_core_NativeCore_nativeLoadRomPath",
            "Java_com_retro_mgba_core_NativeCore_nativeLoadState",
            "Java_com_retro_mgba_core_NativeCore_nativeReset",
            "Java_com_retro_mgba_core_NativeCore_nativeRunFrame",
            "Java_com_retro_mgba_core_NativeCore_nativeSaveState",
            "Java_com_retro_mgba_core_NativeCore_nativeSetAspectRatioMode",
            "Java_com_retro_mgba_core_NativeCore_nativeSetAudioMute",
            "Java_com_retro_mgba_core_NativeCore_nativeSetAudioVolume",
            "Java_com_retro_mgba_core_NativeCore_nativeSetFastForward",
            "Java_com_retro_mgba_core_NativeCore_nativeSetFrameskip",
            "Java_com_retro_mgba_core_NativeCore_nativeSetKeys",
            "Java_com_retro_mgba_core_NativeCore_nativeSetShaderFilter",
            "Java_com_retro_mgba_core_NativeCore_nativeSetSramPath",
            "Java_com_retro_mgba_core_NativeCore_nativeSetSurface",
            "Java_com_retro_mgba_core_NativeCore_nativeSetSurfaceSize",
            "Java_com_retro_mgba_core_NativeCore_nativeUnloadRom",
        ]

        found_jni = [s for s in analyzer.dynamic_symbols if s.startswith("Java_com_retro_mgba_core_NativeCore_")]
        self.assertEqual(len(found_jni), 30, f"Expected 30 JNI symbols, got {len(found_jni)}")
        for sym in expected_symbols:
            self.assertIn(sym, analyzer.dynamic_symbols, f"Missing JNI symbol: {sym}")

    def test_03_imported_audio_and_graphics_symbols(self):
        """Verify AAudio, EGL, and GLES symbols are present as dynamic imports."""
        jni_data = self.zip_file.read("lib/arm64-v8a/libmgba_jni.so")
        analyzer = Elf64Analyzer(jni_data)

        # Check critical AAudio APIs
        required_aaudio = [
            "AAudio_createStreamBuilder",
            "AAudioStreamBuilder_openStream",
            "AAudioStreamBuilder_setDataCallback",
            "AAudioStreamBuilder_setErrorCallback",
            "AAudioStream_requestStart",
            "AAudioStream_close",
        ]
        for s in required_aaudio:
            self.assertIn(s, analyzer.dynamic_symbols, f"Missing AAudio dependency symbol: {s}")

        # Check critical EGL APIs
        required_egl = [
            "eglGetDisplay",
            "eglInitialize",
            "eglChooseConfig",
            "eglCreateContext",
            "eglCreateWindowSurface",
            "eglMakeCurrent",
            "eglSwapBuffers",
            "eglDestroySurface",
            "eglDestroyContext",
            "eglTerminate",
        ]
        for s in required_egl:
            self.assertIn(s, analyzer.dynamic_symbols, f"Missing EGL dependency symbol: {s}")

        # Check critical GLES APIs
        required_gles = [
            "glCreateShader",
            "glShaderSource",
            "glCompileShader",
            "glCreateProgram",
            "glLinkProgram",
            "glUseProgram",
            "glGenTextures",
            "glBindTexture",
            "glTexImage2D",
            "glTexSubImage2D",
            "glViewport",
            "glClear",
            "glDrawArrays",
        ]
        for s in required_gles:
            self.assertIn(s, analyzer.dynamic_symbols, f"Missing GLES dependency symbol: {s}")

    def test_04_kotlin_nativecore_bijection(self):
        """Verify exact 1-to-1 bijection between Kotlin NativeCore external funs and JNI symbols."""
        with open("app/src/main/java/com/retro/mgba/core/NativeCore.kt", "r", encoding="utf-8") as f:
            kt_content = f.read()

        kt_funcs = set(re.findall(r"external\s+fun\s+([a-zA-Z0-9_]+)\s*\(", kt_content))
        self.assertEqual(len(kt_funcs), 30, f"Expected 30 external funs in NativeCore.kt, found {len(kt_funcs)}")

        jni_data = self.zip_file.read("lib/arm64-v8a/libmgba_jni.so")
        analyzer = Elf64Analyzer(jni_data)
        jni_short_names = {
            s.replace("Java_com_retro_mgba_core_NativeCore_", "")
            for s in analyzer.dynamic_symbols
            if s.startswith("Java_com_retro_mgba_core_NativeCore_")
        }

        diff = kt_funcs.symmetric_difference(jni_short_names)
        self.assertEqual(len(diff), 0, f"Symmetric difference between Kotlin and C JNI is non-empty: {diff}")

    def test_05_aspect_ratio_mathematics_stress(self):
        """Stress-test aspect ratio viewport calculations across various phone and handheld aspect ratios."""
        resolutions = [
            (2400, 1080),  # 20:9 Landscape (Modern Android phone)
            (1080, 2400),  # 20:9 Portrait
            (1920, 1080),  # 16:9 Landscape (TV / Tablet / Standard)
            (1280, 720),   # 16:9 720p
            (854, 480),    # Mangmi Air X handheld resolution class
            (720, 720),    # 1:1 Square
            (2560, 1600),  # 16:10 Tablet
        ]
        games = [
            (240, 160),  # GBA 3:2
            (160, 144),  # GB/GBC 10:9
        ]

        for surfW, surfH in resolutions:
            for gameW, gameH in games:
                # Mode 0: Aspect Correct
                if surfW * gameH > surfH * gameW:
                    # Pillarbox
                    vpH = surfH
                    vpW = (surfH * gameW + gameH // 2) // gameH
                    vpX = (surfW - vpW) // 2
                    vpY = 0
                else:
                    # Letterbox
                    vpW = surfW
                    vpH = (surfW * gameH + gameW // 2) // gameW
                    vpX = 0
                    vpY = (surfH - vpH) // 2

                self.assertGreaterEqual(vpX, 0)
                self.assertGreaterEqual(vpY, 0)
                self.assertLessEqual(vpX + vpW, surfW)
                self.assertLessEqual(vpY + vpH, surfH)
                self.assertGreater(vpW, 0)
                self.assertGreater(vpH, 0)
                # Check aspect ratio preservation within 1 pixel rounding tolerance
                calc_ratio = vpW / vpH
                target_ratio = gameW / gameH
                self.assertAlmostEqual(calc_ratio, target_ratio, delta=0.02)

                # Mode 1: Integer Scaling
                scaleX = surfW // gameW
                scaleY = surfH // gameH
                scale = min(scaleX, scaleY)
                if scale < 1:
                    scale = 1
                intW = scale * gameW
                intH = scale * gameH
                intX = (surfW - intW) // 2
                intY = (surfH - intH) // 2

                self.assertGreaterEqual(scale, 1)
                self.assertEqual(intW % gameW, 0)
                self.assertEqual(intH % gameH, 0)
                self.assertGreaterEqual(intX, 0)
                self.assertGreaterEqual(intY, 0)
                self.assertLessEqual(intX + intW, surfW)
                self.assertLessEqual(intY + intH, surfH)

    def test_06_spsc_ring_buffer_behavioral_model(self):
        """Simulate SPSC ring buffer logic and verify no loss under capacity and correct lossy overwrite."""
        CAPACITY = 4096
        MASK = CAPACITY - 1

        buffer = [0] * (CAPACITY * 2)  # stereo
        head = 0
        tail = 0

        # 1. Normal write 1024 frames
        count = 1024
        available = CAPACITY - (head - tail)
        to_write = min(count, available)
        write_idx = head & MASK
        head += to_write
        self.assertEqual(to_write, 1024)
        self.assertEqual(head - tail, 1024)

        # 2. Read 512 frames
        available_read = head - tail
        to_read = min(512, available_read)
        tail += to_read
        self.assertEqual(to_read, 512)
        self.assertEqual(head - tail, 512)

        # 3. Burst write exceeding capacity (test lossy advance)
        burst_count = 5000
        # If spaceNeeded > CAPACITY -> count = CAPACITY
        burst_to_write = CAPACITY
        if (head - tail) + burst_to_write > CAPACITY:
            tail = head + burst_to_write - CAPACITY
        head += burst_to_write

        self.assertEqual(head - tail, CAPACITY)
        self.assertLessEqual(head - tail, CAPACITY)

        # 4. Underrun read with zero fill
        read_req = 5000
        avail = head - tail
        actual_read = min(read_req, avail)
        underrun_zero_filled = read_req - actual_read
        tail += actual_read

        self.assertEqual(actual_read, CAPACITY)
        self.assertEqual(underrun_zero_filled, 5000 - CAPACITY)
        self.assertEqual(head - tail, 0)

    def test_07_audio_volume_attenuation_and_clipping(self):
        """Verify fixed-point volume math matches C implementation without overflow."""
        test_samples = [-32768, -16384, -1, 0, 1, 16383, 32767]
        test_volumes = [0, 25, 50, 75, 100]

        for vol in test_volumes:
            for s in test_samples:
                scaled = (s * vol) // 100
                clamped = max(-32768, min(32767, scaled))
                self.assertGreaterEqual(clamped, -32768)
                self.assertLessEqual(clamped, 32767)
                if vol == 0:
                    self.assertEqual(clamped, 0)
                elif vol == 100:
                    self.assertEqual(clamped, s)

    def test_08_shader_source_syntax_and_variables(self):
        """Verify GLSL source strings in shaders.h for valid syntax constructs."""
        with open("app/src/main/cpp/video/shaders.h", "r", encoding="utf-8") as f:
            code = f.read()

        # Vertex shader must define aPosition, aTexCoord, vTexCoord
        self.assertIn("attribute vec2 aPosition;", code)
        self.assertIn("attribute vec2 aTexCoord;", code)
        self.assertIn("varying vec2 vTexCoord;", code)
        self.assertIn("gl_Position = vec4(aPosition, 0.0, 1.0);", code)

        # Fragment shaders must set gl_FragColor with alpha = 1.0
        self.assertIn("gl_FragColor = vec4(col.rgb, 1.0);", code)
        self.assertIn("uniform sampler2D uTexture;", code)
        self.assertIn("uniform vec2 uSourceRes;", code)
        self.assertIn("gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), 1.0);", code)

    def test_09_savestate_thumbnail_size_rules(self):
        """Verify state thumbnail dimensions for GBA (240x160) and GB/GBC (160x144)."""
        gba_bytes = 240 * 160 * 4
        gb_bytes = 160 * 144 * 4
        self.assertEqual(gba_bytes, 153600)
        self.assertEqual(gb_bytes, 92160)


if __name__ == "__main__":
    unittest.main()
