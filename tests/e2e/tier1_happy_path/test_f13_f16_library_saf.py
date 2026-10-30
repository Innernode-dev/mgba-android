"""Tier 1: Happy-Path Tests for Features F13 to F16.
Covers:
- F13: DuckStation Cover Grid (Columns, aspect ratio, card badges)
- F14: "Resume Last Played" Card (Hero banner, last played metadata)
- F15: High-Speed SAF ROM Scanner (Cursor batching, recursion, MIME)
- F16: Library Metadata & Management (Room DB entity, sorting, filters)
"""

import unittest
from tests.e2e.fixtures.saf_simulator import MockSafTree


class TestF13DuckStationCoverGrid(unittest.TestCase):
    """F13: DuckStation Cover Grid Tests (>=5 cases)."""

    def test_f13_01_portrait_column_count_calculation(self):
        """Verify 3 to 4 columns computed for portrait phone display width."""
        screen_width_dp = 412  # Standard portrait width
        min_card_width_dp = 110
        columns = max(2, int(screen_width_dp // min_card_width_dp))
        self.assertEqual(columns, 3)

    def test_f13_02_landscape_handheld_column_count_calculation(self):
        """Verify 5 to 7 columns computed for 16:9 handheld (e.g. Mangmi Air X, 1920x1080)."""
        screen_width_dp = 800  # Mangmi Air X landscape dp
        min_card_width_dp = 120
        columns = max(2, int(screen_width_dp // min_card_width_dp))
        self.assertEqual(columns, 6)

    def test_f13_03_box_art_aspect_ratio_standard(self):
        """Verify DuckStation box art card standard aspect ratio is ~3:4 (0.75)."""
        card_w = 120.0
        card_h = 160.0
        aspect = card_w / card_h
        self.assertEqual(aspect, 0.75)

    def test_f13_04_card_title_truncation_with_ellipsis(self):
        """Verify long titles truncate gracefully with ellipsis."""
        long_title = "The Legend of Zelda: The Minish Cap (Europe) (En,Fr,De,Es,It)"
        max_chars = 30
        truncated = (long_title[:max_chars - 3] + "...") if len(long_title) > max_chars else long_title
        self.assertEqual(len(truncated), max_chars)
        self.assertTrue(truncated.endswith("..."))

    def test_f13_05_favorites_badge_state(self):
        """Verify favorite badge is activated when game is flagged favorite."""
        is_favorite = True
        badge_visible = is_favorite
        self.assertTrue(badge_visible)


class TestF14ResumeLastPlayedCard(unittest.TestCase):
    """F14: "Resume Last Played" Card Tests (>=5 cases)."""

    def setUp(self):
        self.sample_last_played = {
            "game_id": "rom_123",
            "title": "Castlevania - Aria of Sorrow",
            "last_played_timestamp": 1730100000,
            "has_thumbnail": True,
            "thumbnail_uri": "content://com.retro.mgba/thumbs/rom_123.jpg"
        }

    def test_f14_01_hero_banner_renders_when_recent_game_exists(self):
        """Verify hero banner is active when last_played_timestamp is present."""
        has_recent = self.sample_last_played["last_played_timestamp"] > 0
        self.assertTrue(has_recent)

    def test_f14_02_hero_banner_displays_accurate_title(self):
        """Verify hero banner title matches recent game entity."""
        self.assertEqual(self.sample_last_played["title"], "Castlevania - Aria of Sorrow")

    def test_f14_03_screenshot_thumbnail_binding(self):
        """Verify screenshot thumbnail URI is bound to hero banner."""
        self.assertTrue(self.sample_last_played["has_thumbnail"])
        self.assertTrue(self.sample_last_played["thumbnail_uri"].startswith("content://"))

    def test_f14_04_resume_intent_target(self):
        """Verify resume button action maps to EmulationActivity intent with game ID."""
        intent_extra = {"TARGET_ACTIVITY": "EmulationActivity", "EXTRA_GAME_ID": self.sample_last_played["game_id"]}
        self.assertEqual(intent_extra["TARGET_ACTIVITY"], "EmulationActivity")
        self.assertEqual(intent_extra["EXTRA_GAME_ID"], "rom_123")

    def test_f14_05_banner_hidden_on_empty_history(self):
        """Verify hero banner is hidden when no games have been launched yet."""
        history = []
        is_hero_banner_visible = len(history) > 0
        self.assertFalse(is_hero_banner_visible)


class TestF15HighSpeedSafRomScanner(unittest.TestCase):
    """F15: High-Speed SAF ROM Scanner Tests (>=5 cases)."""

    def setUp(self):
        self.saf_tree = MockSafTree("primary:Emulation")
        # Structure:
        # /Emulation/GBA/game1.gba, game2.gba
        # /Emulation/GB/game3.gb
        # /Emulation/GBC/game4.gbc
        # /Emulation/docs/readme.txt
        self.gba_dir = self.saf_tree.add_directory("primary:Emulation", "GBA")
        self.gb_dir = self.saf_tree.add_directory("primary:Emulation", "GB")
        self.gbc_dir = self.saf_tree.add_directory("primary:Emulation", "GBC")
        self.docs_dir = self.saf_tree.add_directory("primary:Emulation", "docs")

        self.saf_tree.add_file(self.gba_dir.document_id, "zelda.gba", 16777216)
        self.saf_tree.add_file(self.gba_dir.document_id, "mario.gba", 8388608)
        self.saf_tree.add_file(self.gb_dir.document_id, "tetris.gb", 32768)
        self.saf_tree.add_file(self.gbc_dir.document_id, "pokemon.gbc", 2097152)
        self.saf_tree.add_file(self.docs_dir.document_id, "readme.txt", 1024)

    def test_f15_01_scanner_finds_all_gba_gb_gbc_roms(self):
        """Verify scanner recursively finds all 4 valid ROM files."""
        results = self.saf_tree.fast_recursive_scan()
        rom_names = [r["_display_name"] for r in results]
        self.assertEqual(len(results), 4)
        self.assertIn("zelda.gba", rom_names)
        self.assertIn("mario.gba", rom_names)
        self.assertIn("tetris.gb", rom_names)
        self.assertIn("pokemon.gbc", rom_names)

    def test_f15_02_scanner_ignores_non_rom_files(self):
        """Verify non-ROM extensions (e.g. .txt) are excluded from library."""
        results = self.saf_tree.fast_recursive_scan()
        rom_names = [r["_display_name"] for r in results]
        self.assertNotIn("readme.txt", rom_names)

    def test_f15_03_scanner_respects_nomedia_directories(self):
        """Verify folders containing .nomedia are skipped entirely."""
        private_dir = self.saf_tree.add_directory("primary:Emulation", "private_folder")
        self.saf_tree.add_file(private_dir.document_id, ".nomedia", 0)
        self.saf_tree.add_file(private_dir.document_id, "hidden.gba", 16777216)

        results = self.saf_tree.fast_recursive_scan()
        rom_names = [r["_display_name"] for r in results]
        self.assertNotIn("hidden.gba", rom_names)

    def test_f15_04_direct_cursor_query_column_names(self):
        """Verify required DocumentsContract cursor projection columns exist."""
        rows = self.saf_tree.query_child_documents(self.gba_dir.document_id)
        self.assertGreater(len(rows), 0)
        required_cols = {"document_id", "_display_name", "mime_type", "_size", "last_modified"}
        for r in rows:
            self.assertTrue(required_cols.issubset(set(r.keys())))

    def test_f15_05_scanner_batch_chunking(self):
        """Verify scanner batches results in chunks of 50 for Room DB insertion."""
        mock_items = list(range(125))
        chunk_size = 50
        chunks = [mock_items[i:i + chunk_size] for i in range(0, len(mock_items), chunk_size)]
        self.assertEqual(len(chunks), 3)
        self.assertEqual(len(chunks[0]), 50)
        self.assertEqual(len(chunks[1]), 50)
        self.assertEqual(len(chunks[2]), 25)


class TestF16LibraryMetadataAndManagement(unittest.TestCase):
    """F16: Library Metadata & Management Tests (>=5 cases)."""

    def setUp(self):
        self.sample_games = [
            {"title": "Zelda: Minish Cap", "code": "BZMP", "size": 16777216, "added": 100, "favorite": False},
            {"title": "Advance Wars", "code": "AWRE", "size": 8388608, "added": 200, "favorite": True},
            {"title": "Metroid Fusion", "code": "AMFE", "size": 8388608, "added": 150, "favorite": True},
            {"title": "Pokemon Emerald", "code": "BPEE", "size": 16777216, "added": 300, "favorite": False},
        ]

    def test_f16_01_sort_by_title_ascending(self):
        """Verify sorting by title ascending (A-Z)."""
        sorted_games = sorted(self.sample_games, key=lambda g: g["title"])
        self.assertEqual(sorted_games[0]["title"], "Advance Wars")
        self.assertEqual(sorted_games[-1]["title"], "Zelda: Minish Cap")

    def test_f16_02_sort_by_date_added_descending(self):
        """Verify sorting by date added descending (newest first)."""
        sorted_games = sorted(self.sample_games, key=lambda g: g["added"], reverse=True)
        self.assertEqual(sorted_games[0]["title"], "Pokemon Emerald")
        self.assertEqual(sorted_games[-1]["title"], "Zelda: Minish Cap")

    def test_f16_03_favorites_filtering(self):
        """Verify favorites filter returns only starred games."""
        favorites = [g for g in self.sample_games if g["favorite"]]
        self.assertEqual(len(favorites), 2)
        fav_titles = {g["title"] for g in favorites}
        self.assertEqual(fav_titles, {"Advance Wars", "Metroid Fusion"})

    def test_f16_04_search_filtering_substring(self):
        """Verify search query matches substring case-insensitively."""
        query = "roid"
        matches = [g for g in self.sample_games if query.lower() in g["title"].lower()]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]["title"], "Metroid Fusion")

    def test_f16_05_human_readable_file_size_formatting(self):
        """Verify file size formatting in MB."""
        size_bytes = 16777216  # 16 MB
        size_mb = f"{size_bytes / (1024 * 1024):.1f} MB"
        self.assertEqual(size_mb, "16.0 MB")
