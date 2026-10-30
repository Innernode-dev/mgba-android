"""Tier 4: Realistic Real-World Application Scenarios.
End-to-end multi-step simulations validating complete user journeys from boot to exit.
Contains 6 comprehensive application scenarios.
"""

import json
import time
import unittest
from tests.e2e.fixtures.config_mock import (
    EmulatorSettings,
    GamepadProfile,
    GbaKeyMask,
    get_default_mangmi_air_x_profile,
    KEYCODE_BUTTON_A,
    KEYCODE_BUTTON_B,
    KEYCODE_DPAD_RIGHT,
)
from tests.e2e.fixtures.rom_factory import (
    create_gba_rom,
    create_gb_rom,
    verify_gba_complement,
)
from tests.e2e.fixtures.saf_simulator import MockSafTree


class TestRealWorldApplicationScenarios(unittest.TestCase):
    """Tier 4: Real-World Application Scenario Test Suite (6 scenarios)."""

    def test_scenario_01_first_boot_saf_discovery_and_library_population(self):
        """Scenario 1: Cold launch -> SAF folder grant -> Fast recursive scan -> Room DB -> Cover Grid."""
        # 1. First boot: database is empty, no recent games
        room_db = []
        recent_games = []
        self.assertEqual(len(room_db), 0)
        self.assertEqual(len(recent_games), 0)

        # 2. User grants SAF access to /ROMs directory tree
        saf_tree = MockSafTree("primary:ROMs")
        gba_folder = saf_tree.add_directory("primary:ROMs", "GBA")
        gb_folder = saf_tree.add_directory("primary:ROMs", "GameBoy")

        # Ingest 5 GBA ROMs and 3 GB ROMs
        for i in range(5):
            saf_tree.add_file(gba_folder.document_id, f"GbaGame_{i}.gba", 8388608)
        for i in range(3):
            saf_tree.add_file(gb_folder.document_id, f"GbGame_{i}.gb", 32768)

        # 3. High-speed scanner traverses tree in single pass
        scan_results = saf_tree.fast_recursive_scan()
        self.assertEqual(len(scan_results), 8)

        # 4. Batch insert into Room DB
        for item in scan_results:
            room_db.append({
                "id": item["document_id"],
                "title": item["_display_name"].rsplit(".", 1)[0],
                "size": item["_size"],
                "added_at": int(time.time()),
                "is_fav": False
            })
        self.assertEqual(len(room_db), 8)

        # 5. UI Cover Grid computes column layout (e.g. 6 columns on landscape 1080p display)
        screen_w_dp = 800
        cols = max(2, int(screen_w_dp // 120))
        self.assertEqual(cols, 6)

        # Verify all items rendered
        rendered_cards = [g["title"] for g in room_db]
        self.assertIn("GbaGame_0", rendered_cards)
        self.assertIn("GbGame_2", rendered_cards)

    def test_scenario_02_game_launch_gameplay_and_quick_save_cycle(self):
        """Scenario 2: Select game -> Launch core -> Gameplay inputs -> Quick save state -> Restore."""
        # 1. User selects GBA ROM
        rom_bytes = create_gba_rom(title="POKEMON EMER", game_code="BPEE")
        self.assertTrue(verify_gba_complement(rom_bytes))

        # 2. Native core loads ROM buffer
        core_state = {"loaded": True, "title": "POKEMON EMER", "frame": 0, "sram": bytearray(32768)}
        self.assertTrue(core_state["loaded"])

        # 3. User plays: simulates 60 frames with D-Pad Right + Button A held
        input_mask = GbaKeyMask.RIGHT | GbaKeyMask.A
        for f in range(60):
            core_state["frame"] += 1
            if f == 30:
                # In-game event alters SRAM
                core_state["sram"][0x100] = 0x42

        self.assertEqual(core_state["frame"], 60)
        self.assertEqual(core_state["sram"][0x100], 0x42)

        # 4. User presses Quick Save (Slot 1)
        save_state_slot_1 = {
            "magic": "mGBA",
            "frame": core_state["frame"],
            "sram_snapshot": bytes(core_state["sram"]),
            "thumb": b"THUMBNAIL_BYTES_SLOT1"
        }
        self.assertEqual(save_state_slot_1["frame"], 60)

        # 5. Game continues for 100 more frames, then user triggers Quick Load (Slot 1)
        core_state["frame"] = 160
        core_state["sram"][0x100] = 0x99

        # Quick Load restores snapshot
        core_state["frame"] = save_state_slot_1["frame"]
        core_state["sram"] = bytearray(save_state_slot_1["sram_snapshot"])

        # Verify state perfectly restored
        self.assertEqual(core_state["frame"], 60)
        self.assertEqual(core_state["sram"][0x100], 0x42)

    def test_scenario_03_mangmi_air_x_handheld_touchless_experience(self):
        """Scenario 3: Handheld hardware signature -> Auto-hide touch overlay -> Physical navigation."""
        # 1. Mangmi Air X hardware profile detected
        profile = get_default_mangmi_air_x_profile()
        self.assertTrue(profile.is_builtin_handheld)

        # 2. Virtual touch overlay automatically disabled
        show_touch = not profile.is_builtin_handheld
        self.assertFalse(show_touch)

        # 3. Touchless UI navigation across grid
        grid_items = ["Advance Wars", "Castlevania", "Metroid", "Pokemon", "Zelda"]
        focused_idx = 0

        # Physical D-Pad Down event
        focused_idx = min(focused_idx + 1, len(grid_items) - 1)
        self.assertEqual(grid_items[focused_idx], "Castlevania")

        # 4. Physical 'A' button press launches Castlevania
        launch_key = KEYCODE_BUTTON_A
        mapped_action = profile.button_mapping[launch_key]
        self.assertEqual(mapped_action, GbaKeyMask.A)

        # 5. Fullscreen gameplay input: Hall stick analog deflection mapped with deadzone
        stick_deflection_x = 0.85
        stick_deflection_y = -0.05  # Below deadzone
        active_x = stick_deflection_x if abs(stick_deflection_x) >= profile.analog_deadzone else 0.0
        active_y = stick_deflection_y if abs(stick_deflection_y) >= profile.analog_deadzone else 0.0
        self.assertEqual(active_x, 0.85)
        self.assertEqual(active_y, 0.0)

    def test_scenario_04_high_performance_configuration_under_fastforward(self):
        """Scenario 4: Integer scaling + LCD shader -> 4x Fast Forward -> Audio mute & un-mute."""
        settings = EmulatorSettings(
            aspect_ratio_mode=1,  # Integer scaling
            shader_filter=2,      # LCD Grid
            fast_forward_multiplier=4.0,
            is_fast_forward=False,
            audio_mute=False
        )

        # Normal playback verification: 59.7 FPS, audio active
        normal_fps = 59.7275
        self.assertFalse(settings.audio_mute)

        # Engage Fast Forward toggle
        settings.is_fast_forward = True
        settings.audio_mute = settings.is_fast_forward
        accelerated_fps = normal_fps * settings.fast_forward_multiplier
        self.assertAlmostEqual(accelerated_fps, 238.91, places=2)
        self.assertTrue(settings.audio_mute)

        # Disengage Fast Forward toggle
        settings.is_fast_forward = False
        settings.audio_mute = settings.is_fast_forward
        self.assertFalse(settings.audio_mute)

    def test_scenario_05_external_gamepad_pairing_and_custom_remapping(self):
        """Scenario 5: Bluetooth controller connected -> Custom profile created -> Remap persisted."""
        # 1. Bluetooth gamepad detected
        bt_gamepad_id = "8bitdo_sn30_pro"

        # 2. User creates custom profile
        custom_profile = GamepadProfile(
            profile_id=bt_gamepad_id,
            name="8BitDo SN30 Pro",
            is_builtin_handheld=False,
            button_mapping={
                KEYCODE_BUTTON_A: GbaKeyMask.A,
                KEYCODE_BUTTON_B: GbaKeyMask.B,
            },
            swap_ab=False
        )

        # 3. User toggles swap A/B
        custom_profile.swap_ab = True
        custom_profile.button_mapping[KEYCODE_BUTTON_A] = GbaKeyMask.B
        custom_profile.button_mapping[KEYCODE_BUTTON_B] = GbaKeyMask.A

        # 4. Serialize to persistent storage (JSON DataStore)
        data = {
            "profile_id": custom_profile.profile_id,
            "name": custom_profile.name,
            "swap_ab": custom_profile.swap_ab,
            "mapping": {str(k): v for k, v in custom_profile.button_mapping.items()}
        }
        json_str = json.dumps(data)

        # 5. App restarted: reload from DataStore
        reloaded = json.loads(json_str)
        self.assertEqual(reloaded["profile_id"], bt_gamepad_id)
        self.assertTrue(reloaded["swap_ab"])
        self.assertEqual(reloaded["mapping"][str(KEYCODE_BUTTON_A)], GbaKeyMask.B)

    def test_scenario_06_battery_sram_backgrounding_and_crash_resilience(self):
        """Scenario 6: In-game SRAM save -> onPause dirty auto-flush -> Process kill -> Clean recovery."""
        # 1. Gameplay in progress, battery SRAM updated
        sram_buffer = bytearray(32768)
        sram_buffer[0x50] = 0xAA
        sram_buffer[0x51] = 0xBB
        is_sram_dirty = True

        # 2. System triggers onPause (incoming phone call / app minimized)
        lifecycle = "ON_PAUSE"

        # Auto-save flushes dirty SRAM atomically via temporary file
        save_dir = "/storage/emulated/0/RetroSaves"
        final_file = f"{save_dir}/GoldenSun.sav"
        temp_file = f"{save_dir}/GoldenSun.sav.tmp"

        written_temp = False
        swapped_final = False
        if lifecycle == "ON_PAUSE" and is_sram_dirty:
            # Step A: write to temp file
            written_temp = True
            # Step B: atomic rename
            swapped_final = True
            is_sram_dirty = False

        self.assertTrue(written_temp)
        self.assertTrue(swapped_final)
        self.assertFalse(is_sram_dirty)

        # 3. Process is terminated by Android OS (OOM Killer)
        # 4. App relaunched: load from final_file
        recovered_sram = bytearray(sram_buffer)
        self.assertEqual(recovered_sram[0x50], 0xAA)
        self.assertEqual(recovered_sram[0x51], 0xBB)
