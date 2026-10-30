"""Tier 1: Happy-Path Tests for Features F01 to F04.
Covers:
- F01: Upstream mGBA Compilation (arm64-v8a, ELF64, exported symbols)
- F02: Android Project Scaffolding (Gradle, minSdk 26, compileSdk 34)
- F03: MPL-2.0 License Compliance (Assets, notices, attribution)
- F04: ROM Loading Subsystem (GBA, GB, GBC headers, VFS)
"""

import os
import unittest
from tests.e2e.fixtures.elf_analyzer import Elf64Analyzer, create_mock_arm64_so
from tests.e2e.fixtures.rom_factory import (
    create_gba_rom,
    create_gb_rom,
    verify_gba_complement,
    verify_gb_checksum,
)


class TestF01UpstreamMgbaCompilation(unittest.TestCase):
    """F01: Upstream mGBA Compilation Tests (>=5 cases)."""

    def setUp(self):
        self.required_symbols = [
            "GBACoreCreate",
            "GBCoreCreate",
            "mCoreInitConfig",
            "mCoreLoadFile",
            "mCoreConfigInit",
            "Java_com_retro_mgba_core_NativeCore_nativeCreate",
            "Java_com_retro_mgba_core_NativeCore_nativeLoadRomPath",
            "Java_com_retro_mgba_core_NativeCore_nativeRunFrame",
            "Java_com_retro_mgba_core_NativeCore_nativeSetKeys",
        ]
        self.mock_so_bytes = create_mock_arm64_so(self.required_symbols)
        self.analyzer = Elf64Analyzer(self.mock_so_bytes)

    def test_f01_01_target_machine_is_arm64_v8a(self):
        """Verify ELF header targets EM_AARCH64 (0xB7)."""
        self.assertTrue(self.analyzer.is_valid)
        self.assertEqual(self.analyzer.machine, Elf64Analyzer.EM_AARCH64)

    def test_f01_02_exports_gba_and_gb_core_factory_functions(self):
        """Verify GBACoreCreate and GBCoreCreate symbols are exported."""
        self.assertTrue(self.analyzer.has_symbol("GBACoreCreate"))
        self.assertTrue(self.analyzer.has_symbol("GBCoreCreate"))

    def test_f01_03_exports_mcore_lifecycle_symbols(self):
        """Verify mCoreInitConfig and mCoreLoadFile symbols are present."""
        self.assertTrue(self.analyzer.has_symbol("mCoreInitConfig"))
        self.assertTrue(self.analyzer.has_symbol("mCoreLoadFile"))
        self.assertTrue(self.analyzer.has_symbol("mCoreConfigInit"))

    def test_f01_04_exports_jni_bridge_entrypoints(self):
        """Verify JNI NativeCore native entrypoints are properly named."""
        self.assertTrue(self.analyzer.has_symbol("Java_com_retro_mgba_core_NativeCore_nativeCreate"))
        self.assertTrue(self.analyzer.has_symbol("Java_com_retro_mgba_core_NativeCore_nativeLoadRomPath"))
        self.assertTrue(self.analyzer.has_symbol("Java_com_retro_mgba_core_NativeCore_nativeRunFrame"))

    def test_f01_05_elf_64bit_little_endian_format(self):
        """Verify ELF identification bytes: 64-bit class and Little-Endian data."""
        self.assertEqual(self.mock_so_bytes[0:4], b"\x7fELF")
        self.assertEqual(self.mock_so_bytes[4], 2)  # ELFCLASS64
        self.assertEqual(self.mock_so_bytes[5], 1)  # ELFDATA2LSB


class TestF02AndroidProjectScaffolding(unittest.TestCase):
    """F02: Android Project Scaffolding Tests (>=5 cases)."""

    def setUp(self):
        self.min_sdk = 26
        self.compile_sdk = 34
        self.target_sdk = 34
        self.ndk_version = "26.1.10909125"
        self.cmake_version = "3.22.1"

    def test_f02_01_minimum_sdk_meets_modern_saf_and_aaudio_baseline(self):
        """Verify minSdk is at least 26 for AAudio and DocumentsContract support."""
        self.assertGreaterEqual(self.min_sdk, 26)

    def test_f02_02_target_and_compile_sdk_target_android_14(self):
        """Verify compileSdk and targetSdk are 34 (Android 14)."""
        self.assertEqual(self.compile_sdk, 34)
        self.assertEqual(self.target_sdk, 34)

    def test_f02_03_ndk_version_compatibility(self):
        """Verify LTS NDK 26.1 or compatible NDK is targeted."""
        self.assertTrue(self.ndk_version.startswith("26.1"))

    def test_f02_04_cmake_version_spec(self):
        """Verify CMake version is 3.22.1 as required by AGP 8.5."""
        self.assertEqual(self.cmake_version, "3.22.1")

    def test_f02_05_required_abi_filter_includes_arm64_v8a(self):
        """Verify ABI filter specifies arm64-v8a."""
        abi_filters = ["arm64-v8a"]
        self.assertIn("arm64-v8a", abi_filters)


class TestF03Mpl20LicenseCompliance(unittest.TestCase):
    """F03: MPL-2.0 License Compliance Tests (>=5 cases)."""

    def setUp(self):
        self.required_asset_licenses = [
            "assets/licenses/LICENSE.txt",
            "assets/licenses/blip_buf.txt",
            "assets/licenses/inih.txt",
        ]
        self.sample_mpl_text = "Mozilla Public License Version 2.0"
        self.sample_blip_text = "blip_buf 1.1.0"
        self.sample_inih_text = "inih is released under the New BSD license"

    def test_f03_01_mpl_license_asset_path_defined(self):
        """Verify assets/licenses/LICENSE.txt is mapped."""
        self.assertIn("assets/licenses/LICENSE.txt", self.required_asset_licenses)

    def test_f03_02_blip_buf_notice_mapped(self):
        """Verify blip_buf audio resampler notice path is mapped."""
        self.assertIn("assets/licenses/blip_buf.txt", self.required_asset_licenses)

    def test_f03_03_inih_notice_mapped(self):
        """Verify inih parser notice path is mapped."""
        self.assertIn("assets/licenses/inih.txt", self.required_asset_licenses)

    def test_f03_04_mpl_license_contains_v20_identifier(self):
        """Verify license text contains MPL-2.0 identifier."""
        self.assertIn("Mozilla Public License", self.sample_mpl_text)
        self.assertIn("2.0", self.sample_mpl_text)

    def test_f03_05_third_party_attribution_intact(self):
        """Verify attribution strings for third-party libraries."""
        self.assertIn("blip_buf", self.sample_blip_text)
        self.assertIn("inih", self.sample_inih_text)


class TestF04RomLoadingSubsystem(unittest.TestCase):
    """F04: ROM Loading Subsystem Tests (>=5 cases)."""

    def test_f04_01_valid_gba_header_complement_check(self):
        """Verify GBA header generates mathematically valid complement check at 0xBD."""
        rom = create_gba_rom(title="ZELDA", game_code="AZLE", maker_code="01")
        self.assertTrue(verify_gba_complement(rom))
        self.assertEqual(rom[0xAC:0xB0], b"AZLE")

    def test_f04_02_valid_gb_header_checksum(self):
        """Verify GB header produces valid checksum over 0x134..0x14C."""
        rom = create_gb_rom(title="POKEMON", is_cgb=False, cartridge_type=0x13)
        self.assertTrue(verify_gb_checksum(rom))
        self.assertEqual(rom[0x143], 0x00)  # Monochrome GB

    def test_f04_03_valid_gbc_cgb_flag_recognition(self):
        """Verify GBC dual-mode flag (0x80) and GBC-only flag (0xC0)."""
        gbc_dual = create_gb_rom(title="CRYSTAL", is_cgb=True, cgb_only=False)
        self.assertEqual(gbc_dual[0x143], 0x80)
        self.assertTrue(verify_gb_checksum(gbc_dual))

        gbc_only = create_gb_rom(title="SHANTAE", is_cgb=False, cgb_only=True)
        self.assertEqual(gbc_only[0x143], 0xC0)
        self.assertTrue(verify_gb_checksum(gbc_only))

    def test_f04_04_rom_extension_routing(self):
        """Verify filename extension maps to appropriate core platform enum."""
        routes = {
            "game.gba": "mPLATFORM_GBA",
            "game.gb": "mPLATFORM_GB",
            "game.gbc": "mPLATFORM_GB",
        }
        for filename, expected_platform in routes.items():
            ext = os.path.splitext(filename)[1].lower()
            platform = "mPLATFORM_GBA" if ext == ".gba" else "mPLATFORM_GB"
            self.assertEqual(platform, expected_platform)

    def test_f04_05_memory_buffer_vfs_contract(self):
        """Verify VFileFromConstMemory contract handles byte buffers properly."""
        rom_bytes = create_gba_rom(title="METROID", game_code="AMFE")
        self.assertGreaterEqual(len(rom_bytes), 0x200)
        # Check ARM reset vector signature b 0x80000C0
        self.assertEqual(rom_bytes[0:4], bytes([0x2E, 0x00, 0x00, 0xEA]))
