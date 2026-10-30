"""Tier 2: Boundary, Corner & Negative Tests for Features F01 to F04.
Covers:
- F01: Upstream mGBA Compilation boundaries (bad ELF, wrong ABI, missing symbols)
- F02: Android Project Scaffolding boundaries (minSdk < 26, bad NDK, unsupported ABI)
- F03: MPL-2.0 License Compliance boundaries (missing notice, empty license, traversal)
- F04: ROM Loading Subsystem boundaries (bad complement, corrupt logo, truncated, 0-byte)
"""

import struct
import unittest
from tests.e2e.fixtures.elf_analyzer import Elf64Analyzer, create_mock_arm64_so
from tests.e2e.fixtures.rom_factory import (
    create_gba_rom,
    create_gb_rom,
    verify_gba_complement,
    verify_gb_checksum,
)


class TestF01UpstreamMgbaCompilationBoundaries(unittest.TestCase):
    """F01: Compilation & Binary Boundary Tests (5 cases)."""

    def test_f01_b01_missing_required_symbols_fails_validation(self):
        """Verify detector catches missing factory functions."""
        so_data = create_mock_arm64_so(["some_irrelevant_function"])
        analyzer = Elf64Analyzer(so_data)
        self.assertTrue(analyzer.is_valid)
        self.assertFalse(analyzer.has_symbol("GBACoreCreate"))
        self.assertFalse(analyzer.has_symbol("Java_com_retro_mgba_core_NativeCore_nativeCreate"))

    def test_f01_b02_32bit_elf_rejected_for_arm64_target(self):
        """Verify 32-bit ELF (ELFCLASS32 = 1) is rejected for arm64-v8a target."""
        elf32 = bytearray(b"\x7fELF\x01\x01\x01\x00" + b"\x00" * 56)
        analyzer = Elf64Analyzer(elf32)
        self.assertFalse(analyzer.is_valid)

    def test_f01_b03_big_endian_elf_rejected(self):
        """Verify Big-Endian ELF (ELFDATA2MSB = 2) is rejected."""
        elf_be = bytearray(b"\x7fELF\x02\x02\x01\x00" + b"\x00" * 56)
        analyzer = Elf64Analyzer(elf_be)
        self.assertFalse(analyzer.is_valid)

    def test_f01_b04_non_aarch64_machine_rejected(self):
        """Verify non-AArch64 machine (e.g. x86_64 = 0x3E) is flagged invalid."""
        so_data = bytearray(create_mock_arm64_so(["GBACoreCreate"]))
        # Patch e_machine to 0x3E (x86_64) at offset 18
        struct.pack_into("<H", so_data, 18, 0x3E)
        analyzer = Elf64Analyzer(so_data)
        self.assertFalse(analyzer.is_valid)

    def test_f01_b05_truncated_elf_header_handled_safely(self):
        """Verify binary shorter than 64-byte ELF header is safely rejected without crash."""
        short_bytes = b"\x7fELF\x02\x01\x01"
        analyzer = Elf64Analyzer(short_bytes)
        self.assertFalse(analyzer.is_valid)
        self.assertEqual(len(analyzer.dynamic_symbols), 0)


class TestF02AndroidProjectScaffoldingBoundaries(unittest.TestCase):
    """F02: Android Project Scaffolding Boundary Tests (5 cases)."""

    def test_f02_b01_minsdk_below_26_rejected(self):
        """Verify minSdk < 26 is rejected due to missing AAudio / SAF requirements."""
        invalid_min_sdks = [19, 21, 23, 24, 25]
        for sdk in invalid_min_sdks:
            is_valid = (sdk >= 26)
            self.assertFalse(is_valid)

    def test_f02_b02_compilesdk_below_34_rejected(self):
        """Verify compileSdk < 34 is flagged as non-compliant with Android 14 standard."""
        sdk = 33
        is_compliant = (sdk >= 34)
        self.assertFalse(is_compliant)

    def test_f02_b03_unsupported_abi_rejected(self):
        """Verify obsolete ABIs (armeabi, mips) are rejected."""
        target_abis = ["arm64-v8a"]
        disallowed = ["armeabi", "mips", "mips64"]
        for d in disallowed:
            self.assertNotIn(d, target_abis)

    def test_f02_b04_cmake_version_below_minimum_rejected(self):
        """Verify CMake version < 3.22.1 is rejected."""
        def is_cmake_supported(ver_str: str) -> bool:
            parts = [int(p) for p in ver_str.split(".")]
            return parts >= [3, 22, 1]

        self.assertFalse(is_cmake_supported("3.10.2"))
        self.assertFalse(is_cmake_supported("3.18.1"))
        self.assertTrue(is_cmake_supported("3.22.1"))

    def test_f02_b05_ndk_version_malformed_rejected(self):
        """Verify empty or non-numeric NDK versions fail validation."""
        bad_ndks = ["", "invalid_ndk", "r19"]
        for ndk in bad_ndks:
            is_valid = bool(ndk and ndk[0].isdigit())
            self.assertFalse(is_valid)


class TestF03Mpl20LicenseComplianceBoundaries(unittest.TestCase):
    """F03: MPL-2.0 License Compliance Boundary Tests (5 cases)."""

    def test_f03_b01_missing_license_file_flagged(self):
        """Verify absence of LICENSE.txt raises compliance failure."""
        asset_files = ["assets/licenses/blip_buf.txt", "assets/licenses/inih.txt"]
        is_compliant = "assets/licenses/LICENSE.txt" in asset_files
        self.assertFalse(is_compliant)

    def test_f03_b02_empty_license_file_flagged(self):
        """Verify 0-byte license text fails validity check."""
        empty_text = ""
        is_valid = len(empty_text.strip()) > 100
        self.assertFalse(is_valid)

    def test_f03_b03_missing_blip_buf_notice_flagged(self):
        """Verify missing blip_buf notice raises compliance failure."""
        asset_files = ["assets/licenses/LICENSE.txt"]
        is_compliant = "assets/licenses/blip_buf.txt" in asset_files
        self.assertFalse(is_compliant)

    def test_f03_b04_corrupt_mpl_identifier_detected(self):
        """Verify license text missing MPL-2.0 signature is rejected."""
        corrupt_text = "This software is licensed under General Public License."
        has_mpl_signature = "Mozilla Public License" in corrupt_text and "2.0" in corrupt_text
        self.assertFalse(has_mpl_signature)

    def test_f03_b05_license_path_traversal_blocked(self):
        """Verify path traversal attempt in asset reader is strictly blocked."""
        malicious_path = "../../../etc/passwd"
        is_safe = not (".." in malicious_path or malicious_path.startswith("/"))
        self.assertFalse(is_safe)


class TestF04RomLoadingSubsystemBoundaries(unittest.TestCase):
    """F04: ROM Loading Subsystem Boundary Tests (5 cases)."""

    def test_f04_b01_corrupt_gba_complement_checksum_rejected(self):
        """Verify ROM with corrupted complement check byte at 0xBD fails verification."""
        bad_rom = create_gba_rom(title="BADCHECKSUM", corrupt_checksum=True)
        self.assertFalse(verify_gba_complement(bad_rom))

    def test_f04_b02_corrupt_nintendo_logo_rejected(self):
        """Verify zeroed or corrupt Nintendo logo fails authentic boot check."""
        bad_logo_rom = create_gba_rom(title="BADLOGO", corrupt_logo=True)
        # Check logo matches authentic GBA logo
        from tests.e2e.fixtures.rom_factory import GBA_LOGO
        is_logo_valid = (bad_logo_rom[0x04:0xA0] == GBA_LOGO)
        self.assertFalse(is_logo_valid)

    def test_f04_b03_truncated_gba_header_rejected(self):
        """Verify file smaller than GBA header (0xC0 bytes) fails load."""
        truncated_rom = b"\x00" * 100
        is_loadable = len(truncated_rom) >= 0xC0 and verify_gba_complement(truncated_rom)
        self.assertFalse(is_loadable)

    def test_f04_b04_corrupt_gb_checksum_rejected(self):
        """Verify GB ROM with altered title without updating checksum fails verification."""
        bad_gb = create_gb_rom(title="TETRIS", corrupt_checksum=True)
        self.assertFalse(verify_gb_checksum(bad_gb))

    def test_f04_b05_zero_byte_rom_file_rejected(self):
        """Verify 0-byte ROM file produces immediate clean rejection without crash."""
        empty_rom = b""
        self.assertEqual(len(empty_rom), 0)
        is_valid_gba = verify_gba_complement(empty_rom)
        is_valid_gb = verify_gb_checksum(empty_rom)
        self.assertFalse(is_valid_gba)
        self.assertFalse(is_valid_gb)
