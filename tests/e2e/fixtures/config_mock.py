"""Configuration, Input Profile, and Settings Serialization Fixture.
Opaque-box verification for Android KeyEvents, GBA keymasks, Mangmi Air X profiles,
Touch overlay layouts, and Material 3 preferences persistence.
"""

import json
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional


class GbaKeyMask:
    """GBA active-low 10-bit register mask constants (REG_KEYINPUT at 0x04000130)."""
    A = 1 << 0       # 0x001
    B = 1 << 1       # 0x002
    SELECT = 1 << 2  # 0x004
    START = 1 << 3   # 0x008
    RIGHT = 1 << 4   # 0x010
    LEFT = 1 << 5    # 0x020
    UP = 1 << 6      # 0x040
    DOWN = 1 << 7    # 0x080
    R = 1 << 8       # 0x100
    L = 1 << 9       # 0x200


# Standard Android KeyCodes
KEYCODE_DPAD_UP = 19
KEYCODE_DPAD_DOWN = 20
KEYCODE_DPAD_LEFT = 21
KEYCODE_DPAD_RIGHT = 22
KEYCODE_BUTTON_A = 96
KEYCODE_BUTTON_B = 97
KEYCODE_BUTTON_X = 99
KEYCODE_BUTTON_Y = 100
KEYCODE_BUTTON_L1 = 102
KEYCODE_BUTTON_R1 = 103
KEYCODE_BUTTON_L2 = 104
KEYCODE_BUTTON_R2 = 105
KEYCODE_BUTTON_SELECT = 109
KEYCODE_BUTTON_START = 108


@dataclass
class TouchButtonLayout:
    button_id: str
    center_x: float  # Normalized 0.0 .. 1.0
    center_y: float  # Normalized 0.0 .. 1.0
    radius_dp: float
    opacity: float   # 0.0 .. 1.0
    turbo: bool = False
    turbo_frequency_hz: int = 30


@dataclass
class GamepadProfile:
    profile_id: str
    name: str
    is_builtin_handheld: bool
    button_mapping: Dict[int, int]  # Android KeyCode -> GBA KeyMask
    analog_deadzone: float = 0.15
    dpad_threshold: float = 0.60
    swap_ab: bool = False


@dataclass
class EmulatorSettings:
    # Video
    aspect_ratio_mode: int = 0      # 0: 3:2, 1: Integer, 2: Stretch
    shader_filter: int = 0          # 0: Nearest, 1: Bilinear, 2: LCD-Grid
    orientation_lock: str = "AUTO"  # AUTO, PORTRAIT, LANDSCAPE
    resolution_multiplier: int = 1  # 1x, 2x, 3x, 4x

    # Audio
    master_volume: int = 100        # 0 .. 100
    sample_rate: int = 48000        # 44100, 48000
    buffer_frames: int = 192        # Oboe low latency buffer size
    audio_mute: bool = False

    # Timing & Performance
    frame_skip: int = 0             # 0: disabled, 1..4
    fast_forward_multiplier: float = 2.0  # 1.5x, 2.0x, 4.0x, 8.0x
    is_fast_forward: bool = False

    # Paths (SAF URIs)
    custom_save_path: Optional[str] = None
    custom_state_path: Optional[str] = None

    # Lifecycle
    auto_save_on_exit: bool = True

    # Per-Game Overrides (game_id -> dict of overridden fields)
    per_game_configs: Dict[str, Dict[str, any]] = field(default_factory=dict)


def get_default_mangmi_air_x_profile() -> GamepadProfile:
    """Produces the authentic default hardware mapping for Mangmi Air X handheld."""
    mapping = {
        KEYCODE_BUTTON_A: GbaKeyMask.A,
        KEYCODE_BUTTON_B: GbaKeyMask.B,
        KEYCODE_BUTTON_X: GbaKeyMask.A,  # Secondary mapping
        KEYCODE_BUTTON_Y: GbaKeyMask.B,
        KEYCODE_BUTTON_L1: GbaKeyMask.L,
        KEYCODE_BUTTON_R1: GbaKeyMask.R,
        KEYCODE_BUTTON_SELECT: GbaKeyMask.SELECT,
        KEYCODE_BUTTON_START: GbaKeyMask.START,
        KEYCODE_DPAD_UP: GbaKeyMask.UP,
        KEYCODE_DPAD_DOWN: GbaKeyMask.DOWN,
        KEYCODE_DPAD_LEFT: GbaKeyMask.LEFT,
        KEYCODE_DPAD_RIGHT: GbaKeyMask.RIGHT,
    }
    return GamepadProfile(
        profile_id="mangmi_air_x_builtin",
        name="Mangmi Air X Internal Controls",
        is_builtin_handheld=True,
        button_mapping=mapping,
        analog_deadzone=0.12,
        dpad_threshold=0.60,
        swap_ab=False
    )


def serialize_settings(settings: EmulatorSettings) -> str:
    """Serialize settings to JSON for persistent storage."""
    return json.dumps(asdict(settings), indent=2)


def deserialize_settings(json_str: str) -> EmulatorSettings:
    """Deserialize settings from JSON string."""
    d = json.loads(json_str)
    return EmulatorSettings(**d)
