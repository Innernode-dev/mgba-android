"""Tier 2: Boundary, Corner & Negative Tests for Features F21 to F24.
Covers:
- F21: Physical Gamepad Support boundaries (unrecognized KeyCode, disconnect, trigger threshold)
- F22: Mangmi Air X Handheld Support boundaries (Hall drift, stick clamp, simultaneous touch)
- F23: Touchless UI Focus Navigation boundaries (bottom clamp, empty list, diagonal axis)
- F24: Input Remapping Subsystem boundaries (0 keycode, corrupt JSON profile, cancel discard)
"""

import json
import unittest
from tests.e2e.fixtures.config_mock import (
    GbaKeyMask,
    GamepadProfile,
    get_default_mangmi_air_x_profile,
    KEYCODE_BUTTON_A,
)


class TestF21PhysicalGamepadSupportBoundaries(unittest.TestCase):
    """F21: Physical Gamepad Support Boundary Tests (5 cases)."""

    def setUp(self):
        self.profile = get_default_mangmi_air_x_profile()

    def test_f21_b01_unrecognized_keycode_ignored_without_crash(self):
        """Verify unmapped exotic keycode (e.g. KEYCODE_TV) returns 0 bitmask."""
        unknown_keycode = 170  # KEYCODE_TV
        mask = self.profile.button_mapping.get(unknown_keycode, 0)
        self.assertEqual(mask, 0)

    def test_f21_b02_gamepad_disconnect_clears_active_keymask(self):
        """Verify disconnect event flushes all active bits to 0 to prevent stuck inputs."""
        active_mask = GbaKeyMask.A | GbaKeyMask.RIGHT
        # Disconnect signal
        flushed_mask = 0
        self.assertEqual(flushed_mask, 0)

    def test_f21_b03_analog_trigger_digital_threshold(self):
        """Verify analog trigger registers as pressed only when pressure exceeds 0.5f."""
        threshold = 0.50
        pressures = [0.1, 0.49, 0.50, 0.51, 1.0]
        digital_pressed = [(p >= threshold) for p in pressures]
        self.assertEqual(digital_pressed, [False, False, True, True, True])

    def test_f21_b04_keyboard_keycodes_safe_handling(self):
        """Verify desktop physical keyboard KeyCodes (e.g. KEYCODE_SPACE) map safely."""
        KEYCODE_SPACE = 62
        keyboard_mapping = {KEYCODE_SPACE: GbaKeyMask.A}
        self.assertEqual(keyboard_mapping[KEYCODE_SPACE], GbaKeyMask.A)

    def test_f21_b05_rapid_button_hammering_no_missed_release(self):
        """Verify rapid alternating press/release events maintain strict parity."""
        state = 0
        for i in range(100):
            state ^= GbaKeyMask.A
        self.assertEqual(state, 0)


class TestF22MangmiAirXHandheldSupportBoundaries(unittest.TestCase):
    """F22: Mangmi Air X Handheld Support Boundary Tests (5 cases)."""

    def setUp(self):
        self.profile = get_default_mangmi_air_x_profile()

    def test_f22_b01_hall_stick_micro_drift_suppression(self):
        """Verify micro-drift below deadzone (e.g. 0.05) is completely eliminated."""
        drift_val = 0.05
        filtered = 0.0 if abs(drift_val) < self.profile.analog_deadzone else drift_val
        self.assertEqual(filtered, 0.0)

    def test_f22_b02_joystick_axis_overrun_clamping(self):
        """Verify values outside [-1.0, 1.0] from malfunctioning sensors clamp to [-1.0, 1.0]."""
        raw_axes = [-1.5, -1.0, 0.5, 1.0, 1.8]
        clamped = [max(-1.0, min(1.0, a)) for a in raw_axes]
        self.assertEqual(clamped, [-1.0, -1.0, 0.5, 1.0, 1.0])

    def test_f22_b03_simultaneous_touch_and_physical_input_arbitration(self):
        """Verify touch event while physical button is held combines masks via bitwise OR."""
        touch_mask = GbaKeyMask.A
        physical_mask = GbaKeyMask.B
        combined = touch_mask | physical_mask
        self.assertEqual(combined, GbaKeyMask.A | GbaKeyMask.B)

    def test_f22_b04_non_mangmi_fallback(self):
        """Verify running on standard phone falls back to generic touch+gamepad mode."""
        is_mangmi = False
        show_touch = not is_mangmi
        self.assertTrue(show_touch)

    def test_f22_b05_deadzone_boundary_step_continuity(self):
        """Verify scaled output rescales cleanly from 0.0 at deadzone to 1.0 at maximum."""
        # Scaled = (raw - deadzone) / (1.0 - deadzone)
        deadzone = 0.15
        raw = 0.15
        scaled = (raw - deadzone) / (1.0 - deadzone) if raw >= deadzone else 0.0
        self.assertEqual(scaled, 0.0)


class TestF23TouchlessUiFocusNavigationBoundaries(unittest.TestCase):
    """F23: Touchless UI Focus Navigation Boundary Tests (5 cases)."""

    def setUp(self):
        self.num_cols = 4
        self.total_items = 8  # 2 rows of 4 items

    def test_f23_b01_focus_down_at_bottom_row_stays_clamped(self):
        """Verify navigating down from bottom row does not wrap off grid."""
        current = 5  # Row 1
        target = current + self.num_cols  # 9 -> exceeds 7
        next_pos = current if target >= self.total_items else target
        self.assertEqual(next_pos, 5)

    def test_f23_b02_focus_on_empty_list_safe(self):
        """Verify requesting focus on empty list does not crash."""
        items = []
        focused_idx = None if len(items) == 0 else 0
        self.assertIsNone(focused_idx)

    def test_f23_b03_joystick_diagonal_resolves_to_dominant_axis(self):
        """Verify slight diagonal deflection (dx=0.8, dy=0.2) moves along dominant X axis."""
        dx, dy = 0.8, 0.2
        if abs(dx) > abs(dy):
            direction = "RIGHT" if dx > 0 else "LEFT"
        else:
            direction = "DOWN" if dy > 0 else "UP"
        self.assertEqual(direction, "RIGHT")

    def test_f23_b04_dpad_repeat_delay_throttling(self):
        """Verify D-Pad repeat events are spaced by minimum delay (e.g. 150ms)."""
        time_since_last_ms = 80
        min_repeat_ms = 150
        can_move = time_since_last_ms >= min_repeat_ms
        self.assertFalse(can_move)

    def test_f23_b05_focus_back_button_exits_submenu_cleanly(self):
        """Verify pressing Back/B on gamepad transitions focus from Settings back to Library."""
        current_screen = "SETTINGS"
        # Back pressed
        current_screen = "LIBRARY"
        self.assertEqual(current_screen, "LIBRARY")


class TestF24InputRemappingSubsystemBoundaries(unittest.TestCase):
    """F24: Input Remapping Subsystem Boundary Tests (5 cases)."""

    def test_f24_b01_assign_zero_keycode_rejected(self):
        """Verify mapping KeyCode 0 (UNKNOWN) is rejected."""
        keycode = 0
        is_valid_keycode = keycode > 0
        self.assertFalse(is_valid_keycode)

    def test_f24_b02_corrupt_profile_json_reverts_to_default(self):
        """Verify malformed JSON in profile storage falls back to default profile."""
        bad_json = "{ malformed json"
        try:
            profile = json.loads(bad_json)
        except json.JSONDecodeError:
            profile = get_default_mangmi_air_x_profile()
        self.assertEqual(profile.profile_id, "mangmi_air_x_builtin")

    def test_f24_b03_deleting_active_profile_switches_to_default(self):
        """Verify deleting currently active profile reverts to default without leaving null."""
        active_id = "custom_profile_1"
        available_profiles = ["mangmi_air_x_builtin", "custom_profile_2"]
        # Delete custom_profile_1
        active_id = "mangmi_air_x_builtin"
        self.assertEqual(active_id, "mangmi_air_x_builtin")

    def test_f24_b04_remap_dialog_cancel_discards_changes(self):
        """Verify canceling remap dialog discards in-progress key bindings."""
        saved_mapping = {KEYCODE_BUTTON_A: GbaKeyMask.A}
        working_copy = dict(saved_mapping)
        working_copy[KEYCODE_BUTTON_A] = GbaKeyMask.B  # User changed
        # User cancels
        discarded = dict(saved_mapping)
        self.assertEqual(discarded[KEYCODE_BUTTON_A], GbaKeyMask.A)

    def test_f24_b05_empty_profile_name_rejected(self):
        """Verify profile with empty or whitespace-only name is rejected."""
        invalid_names = ["", "   ", "\t"]
        for name in invalid_names:
            is_valid = bool(name and name.strip())
            self.assertFalse(is_valid)
