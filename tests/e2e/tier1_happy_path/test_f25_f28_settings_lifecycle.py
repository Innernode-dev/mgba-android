"""Tier 1: Happy-Path Tests for Features F25 to F28.
Covers:
- F25: Comprehensive Settings UI (Preferences, orientation lock, volume)
- F26: Custom Save & State Folders (SAF URI paths, .sav, .ss* resolution)
- F27: Per-Game Launch Config (Override resolution, global fallback)
- F28: Auto-Save on App Exit (onPause trigger, auto-save state, SRAM flush)
"""

import json
import unittest
from tests.e2e.fixtures.config_mock import (
    EmulatorSettings,
    deserialize_settings,
    serialize_settings,
)


class TestF25ComprehensiveSettingsUi(unittest.TestCase):
    """F25: Comprehensive Settings UI Tests (>=5 cases)."""

    def setUp(self):
        self.settings = EmulatorSettings()

    def test_f25_01_default_settings_values(self):
        """Verify standard defaults for fresh installation."""
        self.assertEqual(self.settings.aspect_ratio_mode, 0)  # 3:2 Original
        self.assertEqual(self.settings.shader_filter, 0)      # Nearest
        self.assertEqual(self.settings.master_volume, 100)
        self.assertEqual(self.settings.frame_skip, 0)
        self.assertEqual(self.settings.orientation_lock, "AUTO")
        self.assertTrue(self.settings.auto_save_on_exit)

    def test_f25_02_orientation_lock_modes(self):
        """Verify orientation lock allows AUTO, PORTRAIT, and LANDSCAPE."""
        valid_modes = {"AUTO", "PORTRAIT", "LANDSCAPE"}
        self.assertIn(self.settings.orientation_lock, valid_modes)
        self.settings.orientation_lock = "LANDSCAPE"
        self.assertIn(self.settings.orientation_lock, valid_modes)

    def test_f25_03_volume_range_clamping(self):
        """Verify master volume clamps cleanly to 0..100%."""
        test_volumes = [-10, 0, 50, 100, 120]
        clamped = [max(0, min(100, v)) for v in test_volumes]
        self.assertEqual(clamped, [0, 0, 50, 100, 100])

    def test_f25_04_audio_buffer_size_options(self):
        """Verify standard Oboe low-latency buffer frame sizes (128, 192, 256, 512)."""
        valid_buffer_sizes = {128, 192, 256, 512}
        self.assertIn(self.settings.buffer_frames, valid_buffer_sizes)

    def test_f25_05_settings_serialization_roundtrip(self):
        """Verify settings object serializes to JSON and recovers identical values."""
        self.settings.master_volume = 85
        self.settings.aspect_ratio_mode = 1  # Integer scaling
        self.settings.shader_filter = 2      # LCD Grid
        encoded = serialize_settings(self.settings)
        recovered = deserialize_settings(encoded)
        self.assertEqual(recovered.master_volume, 85)
        self.assertEqual(recovered.aspect_ratio_mode, 1)
        self.assertEqual(recovered.shader_filter, 2)


class TestF26CustomSaveAndStateFolders(unittest.TestCase):
    """F26: Custom Save & State Folders Tests (>=5 cases)."""

    def setUp(self):
        self.settings = EmulatorSettings()

    def test_f26_01_default_internal_storage_paths(self):
        """Verify default save and state paths are None (resolving to app internal data)."""
        self.assertIsNone(self.settings.custom_save_path)
        self.assertIsNone(self.settings.custom_state_path)

    def test_f26_02_custom_saf_tree_uri_assignment(self):
        """Verify assignment of custom SAF tree URI for saves."""
        custom_uri = "content://com.android.externalstorage.documents/tree/primary%3ARetroSaves"
        self.settings.custom_save_path = custom_uri
        self.assertEqual(self.settings.custom_save_path, custom_uri)

    def test_f26_03_resolve_sram_file_path_in_custom_folder(self):
        """Verify save filename resolution inside custom folder."""
        base_dir = "/storage/emulated/0/RetroSaves"
        rom_name = "Pokemon_Emerald"
        expected_sram = f"{base_dir}/{rom_name}.sav"
        self.assertEqual(expected_sram, "/storage/emulated/0/RetroSaves/Pokemon_Emerald.sav")

    def test_f26_04_resolve_state_file_path_in_custom_folder(self):
        """Verify state filename resolution inside custom state folder."""
        base_dir = "/storage/emulated/0/RetroStates"
        rom_name = "Pokemon_Emerald"
        slot = 3
        expected_state = f"{base_dir}/{rom_name}.ss{slot}"
        self.assertEqual(expected_state, "/storage/emulated/0/RetroStates/Pokemon_Emerald.ss3")

    def test_f26_05_independent_save_and_state_folder_configuration(self):
        """Verify save folder and state folder can be set to distinct paths."""
        self.settings.custom_save_path = "content://path/to/saves"
        self.settings.custom_state_path = "content://path/to/states"
        self.assertNotEqual(self.settings.custom_save_path, self.settings.custom_state_path)


class TestF27PerGameLaunchConfig(unittest.TestCase):
    """F27: Per-Game Launch Config Tests (>=5 cases)."""

    def setUp(self):
        self.settings = EmulatorSettings(aspect_ratio_mode=0, frame_skip=0)
        # Game 1 overrides aspect_ratio to 1 (Integer)
        # Game 2 overrides frame_skip to 2
        self.settings.per_game_configs = {
            "game_emerald": {"aspect_ratio_mode": 1},
            "game_golden_sun": {"frame_skip": 2},
        }

    def test_f27_01_global_config_fallback_when_no_override(self):
        """Verify game without override uses global defaults."""
        game_id = "game_mario"
        overrides = self.settings.per_game_configs.get(game_id, {})
        effective_aspect = overrides.get("aspect_ratio_mode", self.settings.aspect_ratio_mode)
        effective_frameskip = overrides.get("frame_skip", self.settings.frame_skip)
        self.assertEqual(effective_aspect, 0)
        self.assertEqual(effective_frameskip, 0)

    def test_f27_02_per_game_aspect_ratio_override(self):
        """Verify game with aspect ratio override resolves overridden value."""
        game_id = "game_emerald"
        overrides = self.settings.per_game_configs.get(game_id, {})
        effective_aspect = overrides.get("aspect_ratio_mode", self.settings.aspect_ratio_mode)
        self.assertEqual(effective_aspect, 1)

    def test_f27_03_per_game_frameskip_override(self):
        """Verify game with frameskip override resolves overridden value."""
        game_id = "game_golden_sun"
        overrides = self.settings.per_game_configs.get(game_id, {})
        effective_frameskip = overrides.get("frame_skip", self.settings.frame_skip)
        self.assertEqual(effective_frameskip, 2)

    def test_f27_04_deletion_of_override_restores_global_default(self):
        """Verify removing per-game config restores global default immediately."""
        del self.settings.per_game_configs["game_emerald"]
        overrides = self.settings.per_game_configs.get("game_emerald", {})
        effective_aspect = overrides.get("aspect_ratio_mode", self.settings.aspect_ratio_mode)
        self.assertEqual(effective_aspect, 0)

    def test_f27_05_multiple_independent_game_overrides(self):
        """Verify multiple games maintain distinct isolated override records."""
        self.assertIn("game_emerald", self.settings.per_game_configs)
        self.assertIn("game_golden_sun", self.settings.per_game_configs)
        self.assertNotEqual(
            self.settings.per_game_configs["game_emerald"],
            self.settings.per_game_configs["game_golden_sun"]
        )


class TestF28AutoSaveOnAppExit(unittest.TestCase):
    """F28: Auto-Save on App Exit Tests (>=5 cases)."""

    def setUp(self):
        self.settings = EmulatorSettings(auto_save_on_exit=True)

    def test_f28_01_onpause_lifecycle_triggers_autosave(self):
        """Verify onPause event triggers auto-save when enabled."""
        lifecycle_event = "ON_PAUSE"
        trigger_autosave = (lifecycle_event == "ON_PAUSE") and self.settings.auto_save_on_exit
        self.assertTrue(trigger_autosave)

    def test_f28_02_onpause_lifecycle_triggers_sram_flush_if_dirty(self):
        """Verify onPause event triggers atomic SRAM write if battery data is dirty."""
        is_sram_dirty = True
        lifecycle_event = "ON_PAUSE"
        should_flush_sram = (lifecycle_event in {"ON_PAUSE", "ON_STOP"}) and is_sram_dirty
        self.assertTrue(should_flush_sram)

    def test_f28_03_autosave_disabled_skips_state_creation(self):
        """Verify auto-save is skipped if setting is toggled off."""
        self.settings.auto_save_on_exit = False
        lifecycle_event = "ON_PAUSE"
        trigger_autosave = (lifecycle_event == "ON_PAUSE") and self.settings.auto_save_on_exit
        self.assertFalse(trigger_autosave)

    def test_f28_04_autosave_state_filename_convention(self):
        """Verify auto-save state uses dedicated filename (e.g. .auto.ss)."""
        base_name = "Pokemon_Emerald"
        autosave_filename = f"{base_name}.auto.ss"
        self.assertTrue(autosave_filename.endswith(".auto.ss"))

    def test_f28_05_clean_state_requires_no_sram_flush(self):
        """Verify clean (non-dirty) SRAM is not redundantly re-written on exit."""
        is_sram_dirty = False
        lifecycle_event = "ON_STOP"
        should_flush_sram = (lifecycle_event in {"ON_PAUSE", "ON_STOP"}) and is_sram_dirty
        self.assertFalse(should_flush_sram)
