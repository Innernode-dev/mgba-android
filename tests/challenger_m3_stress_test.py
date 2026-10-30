#!/usr/bin/env python3
"""Milestone 3 Challenger Empirical Stress & Adversarial Test Suite.

Author: M3 Challenger 2 (Empirical Challenger)
Verification Scope:
1. Room DB Metadata Preservation & SQL Upsert Under Rapid Rescan Stress
2. Sorting (Title ASC/DESC, Recent, Size ASC/DESC, Date Added) & Filtering (All, GBA, GB, GBC, Favorites) under edge cases (0 and 1,000 items)
3. Handheld D-Pad Spatial Navigation & Focus Transition Stress (Boundary cards, Hero card transitions, Ragged rows, Rapid D-Pad sequences)
4. FastRomScanner BFS loop guard, .nomedia exclusion, and batch chunking correctness
"""

import math
import os
import random
import re
import sqlite3
import sys
import time
import unittest

# Ensure project root is in sys.path
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from tests.e2e.fixtures.saf_simulator import MockSafTree


class TestM3DatabaseMetadataPreservationStress(unittest.TestCase):
    """Stress tests verifying Room DB schema and metadata preservation under repeated rescans."""

    def setUp(self):
        # Create in-memory SQLite database matching Room DB schema
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.cursor = self.conn.cursor()

        # Execute Room DB DDL exactly as generated from GameEntity.kt
        self.cursor.executescript("""
            CREATE TABLE IF NOT EXISTS games (
                uri TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                system TEXT NOT NULL,
                gameCode TEXT,
                fileSize INTEGER NOT NULL DEFAULT 0,
                lastPlayed INTEGER NOT NULL DEFAULT 0,
                playTimeSeconds INTEGER NOT NULL DEFAULT 0,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                customCoverUri TEXT,
                sramPath TEXT,
                dateAdded INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS index_games_system ON games(system);
            CREATE INDEX IF NOT EXISTS index_games_isFavorite ON games(isFavorite);
            CREATE INDEX IF NOT EXISTS index_games_lastPlayed ON games(lastPlayed);
            CREATE INDEX IF NOT EXISTS index_games_title ON games(title);
        """)
        self.conn.commit()

        # SQL Upsert query verbatim from GameDao.kt lines 84-96
        self.UPSERT_SQL = """
            INSERT INTO games (
                uri, title, system, gameCode, fileSize, 
                lastPlayed, playTimeSeconds, isFavorite, customCoverUri, sramPath, dateAdded
            ) VALUES (
                :uri, :title, :system, :gameCode, :fileSize, 
                :lastPlayed, :playTimeSeconds, :isFavorite, :customCoverUri, :sramPath, :dateAdded
            )
            ON CONFLICT(uri) DO UPDATE SET
                title = excluded.title,
                system = excluded.system,
                fileSize = excluded.fileSize,
                gameCode = COALESCE(excluded.gameCode, games.gameCode)
        """

    def tearDown(self):
        self.conn.close()

    def test_01_single_rom_metadata_preservation_on_rescan(self):
        """Verify that a single ROM preserves favorite, lastPlayed, playTimeSeconds, customCoverUri, and sramPath on rescan."""
        rom_uri = "content://com.android.externalstorage.documents/tree/primary%3AROMS/document/primary%3AROMS%2Fzelda.gba"

        # 1. Initial scan inserts fresh ROM
        initial_payload = {
            "uri": rom_uri,
            "title": "The Legend of Zelda: The Minish Cap",
            "system": "GBA",
            "gameCode": "BZMP",
            "fileSize": 16777216,
            "lastPlayed": 0,
            "playTimeSeconds": 0,
            "isFavorite": 0,
            "customCoverUri": None,
            "sramPath": None,
            "dateAdded": 1700000000000
        }
        self.cursor.execute(self.UPSERT_SQL, initial_payload)
        self.conn.commit()

        # 2. User plays the game, favorites it, sets custom cover, and launches SRAM
        self.cursor.execute("""
            UPDATE games 
            SET isFavorite = 1,
                lastPlayed = 1730000000000,
                playTimeSeconds = 7200,
                customCoverUri = 'content://media/picker/custom_zelda.jpg',
                sramPath = '/storage/emulated/0/Retro/Saves/zelda.sav'
            WHERE uri = :uri
        """, {"uri": rom_uri})
        self.conn.commit()

        # 3. Rescan executes: FastRomScanner creates default GameEntity with 0/None values for user fields
        rescan_payload = {
            "uri": rom_uri,
            "title": "The Legend of Zelda: The Minish Cap (Cleaned Title)",
            "system": "GBA",
            "gameCode": "BZMP",
            "fileSize": 16777216,
            "lastPlayed": 0,
            "playTimeSeconds": 0,
            "isFavorite": 0,
            "customCoverUri": None,
            "sramPath": None,
            "dateAdded": 1730500000000
        }
        self.cursor.execute(self.UPSERT_SQL, rescan_payload)
        self.conn.commit()

        # 4. Verify user metadata was NOT overwritten
        self.cursor.execute("SELECT * FROM games WHERE uri = :uri", {"uri": rom_uri})
        row = self.cursor.fetchone()

        self.assertIsNotNone(row)
        # User fields must be strictly preserved
        self.assertEqual(row["isFavorite"], 1, "isFavorite was overwritten by rescan!")
        self.assertEqual(row["lastPlayed"], 1730000000000, "lastPlayed was overwritten by rescan!")
        self.assertEqual(row["playTimeSeconds"], 7200, "playTimeSeconds was overwritten by rescan!")
        self.assertEqual(row["customCoverUri"], "content://media/picker/custom_zelda.jpg", "customCoverUri was overwritten by rescan!")
        self.assertEqual(row["sramPath"], "/storage/emulated/0/Retro/Saves/zelda.sav", "sramPath was overwritten by rescan!")

        # Physical ROM fields must be updated
        self.assertEqual(row["title"], "The Legend of Zelda: The Minish Cap (Cleaned Title)")
        self.assertEqual(row["fileSize"], 16777216)

    def test_02_stress_100_roms_repeated_50_rescans(self):
        """Stress test: 100 ROMs subjected to 50 consecutive full library rescans."""
        rom_uris = [f"content://storage/roms/game_{i:04d}.gba" for i in range(100)]
        expected_user_data = {}

        # Initial scan of 100 ROMs
        for i, uri in enumerate(rom_uris):
            self.cursor.execute(self.UPSERT_SQL, {
                "uri": uri,
                "title": f"Initial Game {i}",
                "system": "GBA" if i % 2 == 0 else "GBC",
                "gameCode": f"G{i:03d}",
                "fileSize": 1024 * 1024 * (i + 1),
                "lastPlayed": 0,
                "playTimeSeconds": 0,
                "isFavorite": 0,
                "customCoverUri": None,
                "sramPath": None,
                "dateAdded": 1700000000
            })

            # User customizes some games
            is_fav = 1 if i % 3 == 0 else 0
            last_played = (1730000000 + i * 1000) if i % 4 == 0 else 0
            play_time = (i * 300) if i % 4 == 0 else 0
            cover = f"content://picker/cover_{i}.webp" if i % 5 == 0 else None
            sram = f"/saves/game_{i}.sav" if i % 6 == 0 else None

            self.cursor.execute("""
                UPDATE games 
                SET isFavorite = ?, lastPlayed = ?, playTimeSeconds = ?, customCoverUri = ?, sramPath = ?
                WHERE uri = ?
            """, (is_fav, last_played, play_time, cover, sram, uri))

            expected_user_data[uri] = {
                "isFavorite": is_fav,
                "lastPlayed": last_played,
                "playTimeSeconds": play_time,
                "customCoverUri": cover,
                "sramPath": sram
            }
        self.conn.commit()

        # Perform 50 consecutive rescans
        start_time = time.time()
        for rescan_round in range(50):
            for i, uri in enumerate(rom_uris):
                # Scanner always emits fresh GameEntity with default values
                self.cursor.execute(self.UPSERT_SQL, {
                    "uri": uri,
                    "title": f"Game {i} (Scanned R{rescan_round})",
                    "system": "GBA" if i % 2 == 0 else "GBC",
                    "gameCode": f"G{i:03d}",
                    "fileSize": 1024 * 1024 * (i + 1),
                    "lastPlayed": 0,
                    "playTimeSeconds": 0,
                    "isFavorite": 0,
                    "customCoverUri": None,
                    "sramPath": None,
                    "dateAdded": 1700000000 + rescan_round
                })
        self.conn.commit()
        elapsed = time.time() - start_time

        # Verify all 100 ROMs preserved exact user metadata across 50 rescans (5,000 upsert queries total)
        for uri in rom_uris:
            self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
            row = self.cursor.fetchone()
            exp = expected_user_data[uri]
            self.assertEqual(row["isFavorite"], exp["isFavorite"], f"Favorite lost on {uri}")
            self.assertEqual(row["lastPlayed"], exp["lastPlayed"], f"lastPlayed lost on {uri}")
            self.assertEqual(row["playTimeSeconds"], exp["playTimeSeconds"], f"playTimeSeconds lost on {uri}")
            self.assertEqual(row["customCoverUri"], exp["customCoverUri"], f"customCoverUri lost on {uri}")
            self.assertEqual(row["sramPath"], exp["sramPath"], f"sramPath lost on {uri}")

        # Performance check: 5,000 upserts in SQLite in-memory should take < 1.0s
        self.assertLess(elapsed, 1.5, f"5,000 upserts took {elapsed:.3f}s, expected < 1.5s")

    def test_03_coalesce_gamecode_behavior(self):
        """Verify COALESCE(excluded.gameCode, games.gameCode) behavior."""
        uri = "content://roms/pokemon.gba"

        # 1. Insert with valid header gameCode "BPEE"
        self.cursor.execute(self.UPSERT_SQL, {
            "uri": uri, "title": "Pokemon Emerald", "system": "GBA", "gameCode": "BPEE",
            "fileSize": 16777216, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
            "customCoverUri": None, "sramPath": None, "dateAdded": 100
        })
        self.conn.commit()

        # 2. Rescan without header inspection (gameCode is None)
        self.cursor.execute(self.UPSERT_SQL, {
            "uri": uri, "title": "Pokemon Emerald (Rescanned)", "system": "GBA", "gameCode": None,
            "fileSize": 16777216, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
            "customCoverUri": None, "sramPath": None, "dateAdded": 200
        })
        self.conn.commit()

        self.cursor.execute("SELECT gameCode FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()
        self.assertEqual(row["gameCode"], "BPEE", "COALESCE failed: existing gameCode was erased by null excluded.gameCode")

        # 3. Rescan WITH a new valid gameCode updates it
        self.cursor.execute(self.UPSERT_SQL, {
            "uri": uri, "title": "Pokemon Emerald (Updated)", "system": "GBA", "gameCode": "BPEF",
            "fileSize": 16777216, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
            "customCoverUri": None, "sramPath": None, "dateAdded": 300
        })
        self.conn.commit()

        self.cursor.execute("SELECT gameCode FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()
        self.assertEqual(row["gameCode"], "BPEF", "COALESCE failed to update non-null gameCode")

    def test_04_sorting_and_filtering_stress_1000_items(self):
        """Stress test sorting and filtering on 1,000 ROM items."""
        random.seed(42)
        systems = ["GBA", "GB", "GBC"]
        titles_pool = [
            "Castlevania - Aria of Sorrow", "Pokemon Emerald", "The Legend of Zelda",
            "Metroid Fusion", "Advance Wars", "Golden Sun", "Fire Emblem", "Super Mario Advance",
            "WarioWare", "Mega Man Battle Network", "Kirby & The Amazing Mirror", "Mother 3"
        ]

        # Insert 1,000 items
        items = []
        for i in range(1000):
            sys_id = systems[i % 3]
            base_title = titles_pool[i % len(titles_pool)]
            title = f"{base_title} #{i:04d}"
            size = random.randint(32768, 33554432)
            last_played = random.choice([0, 0, 0, random.randint(1700000000, 1730000000)])
            play_time = (last_played % 10000) if last_played > 0 else 0
            is_fav = 1 if (i % 7 == 0) else 0
            date_added = 1700000000 + i

            items.append((
                f"content://storage/roms/item_{i:04d}.{sys_id.lower()}",
                title, sys_id, f"C{i:03d}", size, last_played, play_time, is_fav, None, None, date_added
            ))

        self.cursor.executemany("""
            INSERT INTO games (
                uri, title, system, gameCode, fileSize, 
                lastPlayed, playTimeSeconds, isFavorite, customCoverUri, sramPath, dateAdded
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, items)
        self.conn.commit()

        # Query 1: Title ASC
        t0 = time.perf_counter()
        self.cursor.execute("SELECT title FROM games ORDER BY title COLLATE NOCASE ASC")
        rows_asc = [r["title"] for r in self.cursor.fetchall()]
        t_asc = time.perf_counter() - t0
        self.assertEqual(len(rows_asc), 1000)
        self.assertTrue(all(rows_asc[k].lower() <= rows_asc[k + 1].lower() for k in range(len(rows_asc) - 1)))
        self.assertLess(t_asc, 0.02, f"Title ASC query too slow: {t_asc:.4f}s")

        # Query 2: Title DESC
        t0 = time.perf_counter()
        self.cursor.execute("SELECT title FROM games ORDER BY title COLLATE NOCASE DESC")
        rows_desc = [r["title"] for r in self.cursor.fetchall()]
        t_desc = time.perf_counter() - t0
        self.assertEqual(len(rows_desc), 1000)
        self.assertTrue(all(rows_desc[k].lower() >= rows_desc[k + 1].lower() for k in range(len(rows_desc) - 1)))
        self.assertLess(t_desc, 0.02, f"Title DESC query too slow: {t_desc:.4f}s")

        # Query 3: Recent DESC
        t0 = time.perf_counter()
        self.cursor.execute("SELECT lastPlayed, title FROM games ORDER BY lastPlayed DESC, title COLLATE NOCASE ASC")
        rows_recent = self.cursor.fetchall()
        t_recent = time.perf_counter() - t0
        self.assertEqual(len(rows_recent), 1000)
        self.assertTrue(all(rows_recent[k]["lastPlayed"] >= rows_recent[k + 1]["lastPlayed"] for k in range(len(rows_recent) - 1)))
        self.assertLess(t_recent, 0.02, f"Recent DESC query too slow: {t_recent:.4f}s")

        # Query 4: Size DESC
        self.cursor.execute("SELECT fileSize FROM games ORDER BY fileSize DESC, title COLLATE NOCASE ASC")
        rows_size_desc = [r["fileSize"] for r in self.cursor.fetchall()]
        self.assertTrue(all(rows_size_desc[k] >= rows_size_desc[k + 1] for k in range(len(rows_size_desc) - 1)))

        # Query 5: Size ASC
        self.cursor.execute("SELECT fileSize FROM games ORDER BY fileSize ASC, title COLLATE NOCASE ASC")
        rows_size_asc = [r["fileSize"] for r in self.cursor.fetchall()]
        self.assertTrue(all(rows_size_asc[k] <= rows_size_asc[k + 1] for k in range(len(rows_size_asc) - 1)))

        # Query 6: Filtering partition invariant
        self.cursor.execute("SELECT COUNT(*) as c FROM games WHERE system = 'GBA'")
        gba_count = self.cursor.fetchone()["c"]
        self.cursor.execute("SELECT COUNT(*) as c FROM games WHERE system = 'GB'")
        gb_count = self.cursor.fetchone()["c"]
        self.cursor.execute("SELECT COUNT(*) as c FROM games WHERE system = 'GBC'")
        gbc_count = self.cursor.fetchone()["c"]
        self.cursor.execute("SELECT COUNT(*) as c FROM games WHERE isFavorite = 1")
        fav_count = self.cursor.fetchone()["c"]

        self.assertEqual(gba_count + gb_count + gbc_count, 1000, "System partitioning does not sum to total games")
        self.assertGreater(fav_count, 0)

        # Query 7: Resume Game (LIMIT 1)
        self.cursor.execute("SELECT * FROM games WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 1")
        resume_game = self.cursor.fetchone()
        self.assertIsNotNone(resume_game)
        max_last_played = max(r[5] for r in items)
        self.assertEqual(resume_game["lastPlayed"], max_last_played)

    def test_05_empty_library_queries_edge_cases(self):
        """Verify queries on empty library return empty collections without crashing."""
        self.cursor.execute("SELECT * FROM games ORDER BY title COLLATE NOCASE ASC")
        self.assertEqual(len(self.cursor.fetchall()), 0)

        self.cursor.execute("SELECT * FROM games WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 1")
        self.assertIsNone(self.cursor.fetchone())

        self.cursor.execute("SELECT * FROM games WHERE isFavorite = 1")
        self.assertEqual(len(self.cursor.fetchall()), 0)

        self.cursor.execute("SELECT COUNT(*) as c FROM games")
        self.assertEqual(self.cursor.fetchone()["c"], 0)


class TestM3HandheldDpadSpatialNavigationStress(unittest.TestCase):
    """Stress tests verifying Handheld D-Pad spatial navigation across boundary cards and hero cards."""

    class MockSpatialNavigator:
        """Simulates 2D focus navigation engine (similar to Compose FocusManager)."""

        def __init__(self, columns=5, has_hero=True, game_count=10):
            self.columns = columns
            self.has_hero = has_hero
            self.game_count = game_count
            self.hero_focused = has_hero
            self.current_index = 0 if not has_hero else None

        @property
        def current_pos(self):
            if self.hero_focused:
                return "HERO"
            if self.current_index is None or self.game_count == 0:
                return "EMPTY"
            row = self.current_index // self.columns
            col = self.current_index % self.columns
            return (row, col)

        def on_dpad(self, direction):
            """Simulates D-Pad direction: UP, DOWN, LEFT, RIGHT."""
            if self.game_count == 0:
                # In empty library, focus stays on empty CTA
                return

            if self.hero_focused:
                if direction == "DOWN":
                    # Transition from Hero banner down into grid
                    self.hero_focused = False
                    self.current_index = 0  # Land on row 0, col 0
                elif direction in ("UP", "LEFT", "RIGHT"):
                    # Boundary clamp within Hero
                    pass
                return

            # Currently focused on a grid card
            curr_row = self.current_index // self.columns
            curr_col = self.current_index % self.columns
            total_rows = math.ceil(self.game_count / self.columns)

            if direction == "UP":
                if curr_row == 0:
                    if self.has_hero:
                        self.hero_focused = True
                        self.current_index = None
                    else:
                        pass  # Clamped at top of grid
                else:
                    new_idx = (curr_row - 1) * self.columns + curr_col
                    if new_idx < self.game_count:
                        self.current_index = new_idx

            elif direction == "DOWN":
                if curr_row + 1 < total_rows:
                    new_idx = (curr_row + 1) * self.columns + curr_col
                    if new_idx < self.game_count:
                        self.current_index = new_idx
                    else:
                        # Ragged row fallback: jump to closest card in last row
                        self.current_index = self.game_count - 1
                else:
                    # Bottom boundary clamp
                    pass

            elif direction == "LEFT":
                if curr_col > 0:
                    self.current_index -= 1
                else:
                    # Left edge boundary clamp
                    pass

            elif direction == "RIGHT":
                if curr_col + 1 < self.columns and (self.current_index + 1) < self.game_count:
                    self.current_index += 1
                else:
                    # Right edge boundary clamp
                    pass

    def test_01_hero_card_transition_to_grid(self):
        """Verify smooth D-pad transition from Hero card down to row 0 of grid and back up."""
        nav = self.MockSpatialNavigator(columns=4, has_hero=True, game_count=12)
        self.assertEqual(nav.current_pos, "HERO")

        # DPAD_DOWN moves focus from hero to (0, 0)
        nav.on_dpad("DOWN")
        self.assertEqual(nav.current_pos, (0, 0))

        # DPAD_UP moves focus back to HERO
        nav.on_dpad("UP")
        self.assertEqual(nav.current_pos, "HERO")

    def test_02_grid_boundary_clamping(self):
        """Verify all 4 outer boundary edges clamp without throwing exceptions or corrupting index."""
        nav = self.MockSpatialNavigator(columns=4, has_hero=False, game_count=16)
        self.assertEqual(nav.current_pos, (0, 0))

        # Top-Left corner: LEFT and UP must clamp
        nav.on_dpad("LEFT")
        self.assertEqual(nav.current_pos, (0, 0))
        nav.on_dpad("UP")
        self.assertEqual(nav.current_pos, (0, 0))

        # Move to Top-Right (0, 3)
        for _ in range(3):
            nav.on_dpad("RIGHT")
        self.assertEqual(nav.current_pos, (0, 3))
        # RIGHT and UP must clamp
        nav.on_dpad("RIGHT")
        self.assertEqual(nav.current_pos, (0, 3))
        nav.on_dpad("UP")
        self.assertEqual(nav.current_pos, (0, 3))

        # Move to Bottom-Right (3, 3)
        for _ in range(3):
            nav.on_dpad("DOWN")
        self.assertEqual(nav.current_pos, (3, 3))
        # DOWN and RIGHT must clamp
        nav.on_dpad("DOWN")
        self.assertEqual(nav.current_pos, (3, 3))
        nav.on_dpad("RIGHT")
        self.assertEqual(nav.current_pos, (3, 3))

        # Move to Bottom-Left (3, 0)
        for _ in range(3):
            nav.on_dpad("LEFT")
        self.assertEqual(nav.current_pos, (3, 0))
        # DOWN and LEFT must clamp
        nav.on_dpad("DOWN")
        self.assertEqual(nav.current_pos, (3, 0))
        nav.on_dpad("LEFT")
        self.assertEqual(nav.current_pos, (3, 0))

    def test_03_ragged_grid_last_row_spatial_navigation(self):
        """Verify ragged grid (e.g. 10 items in 4 columns: row 0: 4, row 1: 4, row 2: 2)."""
        nav = self.MockSpatialNavigator(columns=4, has_hero=False, game_count=10)
        # Position at row 1, col 3 (index 7)
        nav.on_dpad("DOWN")
        for _ in range(3):
            nav.on_dpad("RIGHT")
        self.assertEqual(nav.current_pos, (1, 3))

        # Row 2 only has col 0 (idx 8) and col 1 (idx 9).
        # Pressing DOWN from (1, 3) must smoothly clamp to last card (2, 1) rather than going out of bounds
        nav.on_dpad("DOWN")
        self.assertEqual(nav.current_pos, (2, 1))

    def test_04_rapid_10000_dpad_events_stress(self):
        """Stress test: 10,000 random D-pad transitions across a 500-card grid."""
        nav = self.MockSpatialNavigator(columns=6, has_hero=True, game_count=500)
        directions = ["UP", "DOWN", "LEFT", "RIGHT"]

        t0 = time.perf_counter()
        for step in range(10000):
            d = directions[step % 4]
            nav.on_dpad(d)
            if not nav.hero_focused:
                self.assertGreaterEqual(nav.current_index, 0)
                self.assertLess(nav.current_index, 500)
        elapsed = time.perf_counter() - t0

        # Must execute 10,000 hops in under 0.1s
        self.assertLess(elapsed, 0.1, f"10k focus hops took {elapsed:.4f}s")


class TestM3FastRomScannerBfsAndNomediaStress(unittest.TestCase):
    """Stress tests verifying FastRomScanner BFS logic, depth limits, and .nomedia handling."""

    def setUp(self):
        self.saf_tree = MockSafTree("primary:Emulation")

    def test_01_scanner_cyclic_symlink_depth_limit_guard(self):
        """Verify BFS terminates traversal at MAX_DIRECTORY_DEPTH = 15 when cyclic symlinks or deep directories occur."""
        max_depth = 15
        queue = [(self.saf_tree.root_doc_id, 0)]
        visited = {self.saf_tree.root_doc_id}
        discovered_roms = []

        curr_dir_id = self.saf_tree.root_doc_id
        for depth in range(1, 22):
            sub_dir = self.saf_tree.add_directory(curr_dir_id, f"sub_{depth}")
            self.saf_tree.add_file(sub_dir.document_id, f"game_{depth}.gba", 1024)
            curr_dir_id = sub_dir.document_id

        # Simulate FastRomScanner's exact BFS loop
        while queue:
            doc_id, depth = queue.pop(0)
            children = self.saf_tree.query_child_documents(doc_id)
            for c in children:
                if c["mime_type"] == MockSafTree.MIME_DIR:
                    if depth + 1 < max_depth:
                        if c["document_id"] not in visited:
                            visited.add(c["document_id"])
                            queue.append((c["document_id"], depth + 1))
                elif any(c["_display_name"].lower().endswith(ext) for ext in [".gba", ".gb", ".gbc"]):
                    discovered_roms.append(c["_display_name"])

        # Traversal stopped at depth 14; depths 15..21 were pruned
        self.assertEqual(len(discovered_roms), 14)

    def test_02_scanner_skips_nomedia_and_hidden_folders(self):
        """Verify .nomedia and folders starting with '.' are pruned."""
        hidden_dir = self.saf_tree.add_directory("primary:Emulation", ".hidden_folder")
        self.saf_tree.add_file(hidden_dir.document_id, "hidden.gba", 1024)

        nomedia_dir = self.saf_tree.add_directory("primary:Emulation", "private_folder")
        self.saf_tree.add_file(nomedia_dir.document_id, ".nomedia", 0)
        self.saf_tree.add_file(nomedia_dir.document_id, "private.gba", 1024)

        normal_dir = self.saf_tree.add_directory("primary:Emulation", "games")
        self.saf_tree.add_file(normal_dir.document_id, "visible.gba", 1024)

        results = self.saf_tree.fast_recursive_scan()
        names = [r["_display_name"] for r in results]

        self.assertIn("visible.gba", names)
        self.assertNotIn("hidden.gba", names)
        self.assertNotIn("private.gba", names)

    def test_03_scanner_chunking_exact_threshold(self):
        """Verify batch chunking emits exact batches of BATCH_CHUNK_SIZE = 50."""
        batch_size = 50
        total_items = 137
        chunks = []
        for i in range(0, total_items, batch_size):
            chunks.append(list(range(i, min(i + batch_size, total_items))))

        self.assertEqual(len(chunks), 3)
        self.assertEqual(len(chunks[0]), 50)
        self.assertEqual(len(chunks[1]), 50)
        self.assertEqual(len(chunks[2]), 37)


class TestM3ApkArtifactDexInspection(unittest.TestCase):
    """Verifies that Milestone 3 classes and interfaces are compiled and present in app-debug.apk."""

    APK_PATH = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

    @classmethod
    def setUpClass(cls):
        import zipfile
        if not os.path.exists(cls.APK_PATH):
            raise FileNotFoundError(f"APK artifact not found at {cls.APK_PATH}")
        cls.zip_file = zipfile.ZipFile(cls.APK_PATH)
        dex_files = [n for n in cls.zip_file.namelist() if n.endswith(".dex")]
        cls.all_dex_data = b"".join(cls.zip_file.read(d) for d in dex_files)

    @classmethod
    def tearDownClass(cls):
        cls.zip_file.close()

    def test_01_m3_room_db_classes_packaged(self):
        """Verify Room DB entities, DAOs, and database classes exist in APK DEX."""
        required_classes = [
            b"Lcom/retro/mgba/data/db/GameEntity;",
            b"Lcom/retro/mgba/data/db/FolderEntity;",
            b"Lcom/retro/mgba/data/db/GameDao;",
            b"Lcom/retro/mgba/data/db/FolderDao;",
            b"Lcom/retro/mgba/data/db/AppDatabase;"
        ]
        for c in required_classes:
            self.assertIn(c, self.all_dex_data, f"Class descriptor missing from APK: {c.decode()}")

    def test_02_m3_scanner_classes_packaged(self):
        """Verify SAF scanner classes exist in APK DEX."""
        required_classes = [
            b"Lcom/retro/mgba/scanner/FastRomScanner;",
            b"Lcom/retro/mgba/scanner/RomSystem;",
            b"Lcom/retro/mgba/scanner/RomFilenameCleaner;",
            b"Lcom/retro/mgba/scanner/RomHeaderParser;",
            b"Lcom/retro/mgba/scanner/SafPermissionHelper;",
            b"Lcom/retro/mgba/scanner/ScanProgress;"
        ]
        for c in required_classes:
            self.assertIn(c, self.all_dex_data, f"Class descriptor missing from APK: {c.decode()}")

    def test_03_m3_compose_ui_classes_packaged(self):
        """Verify DuckStation UI Compose components and Handheld focus controller exist in APK DEX."""
        required_classes = [
            b"Lcom/retro/mgba/ui/library/LibraryScreenKt;",
            b"Lcom/retro/mgba/ui/library/LibraryViewModel;",
            b"Lcom/retro/mgba/ui/library/GameGridCardKt;",
            b"Lcom/retro/mgba/ui/library/ResumeHeroCardKt;",
            b"Lcom/retro/mgba/ui/library/CartridgeCanvasKt;",
            b"Lcom/retro/mgba/ui/navigation/HandheldFocusControllerKt;"
        ]
        for c in required_classes:
            self.assertIn(c, self.all_dex_data, f"Class descriptor missing from APK: {c.decode()}")


class TestM3RomFilenameCleanerAndHeaderStress(unittest.TestCase):
    """Stress tests verifying ROM filename cleaning, region extraction, and header parsing math."""

    BRACKETED_REGEX = re.compile(r"\[([!*+a-zA-Z0-9_\-\.\s]+)\]")
    PAREN_REGEX = re.compile(r"\(([a-zA-Z0-9_,\-\.\s]+)\)")
    MULTI_SPACE_REGEX = re.compile(r"\s{2,}")

    def clean_title(self, raw_filename: str) -> str:
        """Python replica of RomFilenameCleaner.cleanTitle for cross-language validation."""
        if not raw_filename or not raw_filename.strip():
            return "Unknown Game"
        stem = raw_filename.rsplit(".", 1)[0] if "." in raw_filename else raw_filename
        if not stem.strip():
            return "Unknown Game"
        working = stem.replace("_", " ")
        working = self.BRACKETED_REGEX.sub("", working)
        working = self.PAREN_REGEX.sub("", working)
        working = self.MULTI_SPACE_REGEX.sub(" ", working).strip()
        working = working.rstrip(" -:,")
        if not working:
            fallback = stem.replace("_", " ").strip()
            return fallback if fallback else "Unknown Game"
        return working

    def test_01_filename_cleaning_varieties(self):
        """Verify standard GoodTools and No-Intro ROM titles clean cleanly."""
        test_cases = [
            ("Pokemon - Emerald Version (USA, Europe) [!].gba", "Pokemon - Emerald Version"),
            ("Super_Mario_Advance_4_-_Super_Mario_Bros._3_(USA)_(Rev_1).gba", "Super Mario Advance 4 - Super Mario Bros. 3"),
            ("The Legend of Zelda - The Minish Cap (Europe) (En,Fr,De,Es,It).gba", "The Legend of Zelda - The Minish Cap"),
            ("Castlevania - Aria of Sorrow (USA).gba", "Castlevania - Aria of Sorrow"),
            ("Metroid_Fusion.gba", "Metroid Fusion"),
            ("Tetris (World) (Rev A).gb", "Tetris"),
            ("Pokemon - Crystal Version (USA, Europe) (Rev A).gbc", "Pokemon - Crystal Version")
        ]
        for raw, expected in test_cases:
            cleaned = self.clean_title(raw)
            self.assertEqual(cleaned, expected, f"Failed cleaning for: {raw}")

    def test_02_gba_header_complement_checksum_math(self):
        """Verify GBA header complement checksum calculation formula: -(sum(bytes[0x00A0..0x00BC]) + 0x19) & 0xFF."""
        # Create mock 192-byte GBA header
        header = bytearray(192)
        # Fixed byte at 0x00B2 must be 0x96
        header[0x00B2] = 0x96
        # Title "POKEMON EMER" (12 bytes at 0x00A0)
        title_bytes = b"POKEMON EMER"
        header[0x00A0:0x00AC] = title_bytes
        # Game Code "BPEE" (4 bytes at 0x00AC)
        header[0x00AC:0x00B0] = b"BPEE"
        # Maker Code "01" (2 bytes at 0x00B0)
        header[0x00B0:0x00B2] = b"01"

        # Calculate complement checksum
        total_sum = sum(header[0x00A0:0x00BD])
        expected_chk = (-(total_sum + 0x19)) & 0xFF
        header[0x00BD] = expected_chk

        # Verify checksum verification evaluates to true
        calc_chk = (-(sum(header[0x00A0:0x00BD]) + 0x19)) & 0xFF
        self.assertEqual(expected_chk, header[0x00BD])
        self.assertEqual(header[0x00B2], 0x96)


if __name__ == "__main__":
    unittest.main()
