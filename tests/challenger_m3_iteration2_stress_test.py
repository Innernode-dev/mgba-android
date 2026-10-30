#!/usr/bin/env python3
"""Milestone 3 Challenger 1 (Iteration 2) Adversarial Stress Test Suite.

Author: M3 Challenger 1
Scope:
1. Region Extraction Stress (RomFilenameCleaner.extractRegion parsing across standard and edge-case tags)
2. Database Schema & Persistence of 'region' in GameEntity
3. GameDao @Transaction upsertScannedGame Stress (New ROM insertion, user metadata preservation across rescans)
4. APK DEX Bytecode Analysis for GameEntity.region field and GameDao transaction methods
"""

import math
import os
import random
import re
import sqlite3
import sys
import time
import unittest
import zipfile

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)


class RomFilenameCleanerPython:
    """Exact Python reproduction of RomFilenameCleaner.kt for empirical testing."""

    BRACKETED_TAG_PATTERN = re.compile(r"\[([!*+a-zA-Z0-9_\-\.\s]+)\]", re.IGNORECASE)
    PAREN_TAG_PATTERN = re.compile(r"\(([a-zA-Z0-9_,\-\.\s]+)\)")
    MULTI_SPACE_PATTERN = re.compile(r"\s{2,}")

    REGION_KEYWORDS = {
        "USA": "USA",
        "US": "USA",
        "U": "USA",
        "EUROPE": "EUR",
        "EUR": "EUR",
        "EU": "EUR",
        "E": "EUR",
        "JAPAN": "JPN",
        "JAP": "JPN",
        "J": "JPN",
        "WORLD": "WLD",
        "W": "WLD",
        "USA, EUROPE": "USA/EUR",
        "USA,EUR": "USA/EUR",
        "EN,FR,DE,ES,IT": "EUR",
        "EN,FR,DE": "EUR",
        "EN,JA": "WLD",
        "GERMANY": "GER",
        "FRANCE": "FRA",
        "SPAIN": "SPA",
        "ITALY": "ITA",
        "AUSTRALIA": "AUS"
    }

    @classmethod
    def clean_title(cls, raw_filename: str) -> str:
        if not raw_filename or not raw_filename.strip():
            return "Unknown Game"
        stem = raw_filename.rsplit(".", 1)[0] if "." in raw_filename else raw_filename
        if not stem.strip():
            return "Unknown Game"
        working = stem.replace("_", " ")
        working = cls.BRACKETED_TAG_PATTERN.sub("", working)
        working = cls.PAREN_TAG_PATTERN.sub("", working)
        working = cls.MULTI_SPACE_PATTERN.sub(" ", working).strip()
        working = working.rstrip(" -:,")
        if not working:
            fallback = stem.replace("_", " ").strip()
            return fallback if fallback else "Unknown Game"
        return working

    @classmethod
    def extract_region(cls, raw_filename: str) -> str | None:
        for match in cls.PAREN_TAG_PATTERN.finditer(raw_filename):
            content = match.group(1).strip() if match.group(1) else ""
            if not content:
                continue
            upper = content.upper()
            if upper in cls.REGION_KEYWORDS:
                return cls.REGION_KEYWORDS[upper]

            tokens = [t.strip() for t in re.split(r"[,/ ]+", upper) if t.strip()]
            for token in tokens:
                if token in cls.REGION_KEYWORDS:
                    return cls.REGION_KEYWORDS[token]
        return None

    @classmethod
    def extract_revision(cls, raw_filename: str) -> str | None:
        for match in cls.PAREN_TAG_PATTERN.finditer(raw_filename):
            content = match.group(1).strip() if match.group(1) else ""
            if not content:
                continue
            lower = content.lower()
            if lower.startswith("rev") or lower.startswith("v1.") or lower.startswith("v2."):
                return content
        return None


class TestRegionExtractionStress(unittest.TestCase):
    """Adversarial stress testing of RomFilenameCleaner region extraction."""

    def test_01_primary_regions(self):
        """Verify extraction of primary geographic regions."""
        cases = [
            ("Pokemon - Emerald Version (USA).gba", "USA"),
            ("The Legend of Zelda - The Minish Cap (Europe).gba", "EUR"),
            ("Mother 3 (Japan).gba", "JPN"),
            ("Tetris (World).gb", "WLD"),
            ("Super Mario Advance (USA, Europe).gba", "USA/EUR"),
            ("Advance Wars (USA,EUR).gba", "USA/EUR"),
            ("Golden Sun (Germany).gba", "GER"),
            ("Metroid Fusion (France).gba", "FRA"),
            ("Fire Emblem (Spain).gba", "SPA"),
            ("Castlevania (Italy).gba", "ITA"),
            ("Crash Bandicoot (Australia).gba", "AUS"),
        ]
        for filename, expected in cases:
            extracted = RomFilenameCleanerPython.extract_region(filename)
            self.assertEqual(extracted, expected, f"Failed for {filename}: got {extracted}, expected {expected}")

    def test_02_multilingual_language_codes_to_region(self):
        """Verify European and World multi-language tags map to appropriate regions."""
        cases = [
            ("Rayman 3 (En,Fr,De,Es,It).gba", "EUR"),
            ("WarioWare, Inc. (En,Fr,De).gba", "EUR"),
            ("Sonic Advance (En,Ja).gba", "WLD"),
        ]
        for filename, expected in cases:
            extracted = RomFilenameCleanerPython.extract_region(filename)
            self.assertEqual(extracted, expected, f"Failed for {filename}: got {extracted}, expected {expected}")

    def test_03_short_code_tags(self):
        """Verify single-letter and short regional code tags."""
        cases = [
            ("Game (U).gba", "USA"),
            ("Game (US).gba", "USA"),
            ("Game (E).gba", "EUR"),
            ("Game (EU).gba", "EUR"),
            ("Game (J).gba", "JPN"),
            ("Game (JAP).gba", "JPN"),
            ("Game (W).gba", "WLD"),
        ]
        for filename, expected in cases:
            extracted = RomFilenameCleanerPython.extract_region(filename)
            self.assertEqual(extracted, expected, f"Failed for {filename}: got {extracted}, expected {expected}")

    def test_04_multiple_parenthetical_tags(self):
        """Verify extraction when multiple parenthetical tags (revisions, languages, regions) co-exist."""
        cases = [
            ("Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Rev 1).gba", "USA"),
            ("The Legend of Zelda - The Minish Cap (Europe) (En,Fr,De,Es,It).gba", "EUR"),
            ("Final Fantasy VI Advance (Rev 1) (Europe).gba", "EUR"),
            ("Pokemon FireRed (Beta) (USA).gba", "USA"),
            ("Donkey Kong Country (World) (v1.1) [!].gba", "WLD"),
        ]
        for filename, expected in cases:
            extracted = RomFilenameCleanerPython.extract_region(filename)
            self.assertEqual(extracted, expected, f"Failed for {filename}: got {extracted}, expected {expected}")

    def test_05_no_region_tag_fallback(self):
        """Verify that filenames without region tags return None."""
        cases = [
            ("Metroid_Fusion.gba", None),
            ("Castlevania.gbc", None),
            ("Kirby (Rev 1).gba", None),
            ("Game [!].gb", None),
            ("PlainGameName.gba", None),
        ]
        for filename, expected in cases:
            extracted = RomFilenameCleanerPython.extract_region(filename)
            self.assertIsNone(extracted, f"Expected None for {filename}, got {extracted}")


class TestGameDaoTransactionUpsertStress(unittest.TestCase):
    """Stress testing the GameDao @Transaction upsertScannedGame logic."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.cursor = self.conn.cursor()

        # Schema matching GameEntity.kt with region column
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
                dateAdded INTEGER NOT NULL,
                region TEXT NOT NULL DEFAULT 'USA'
            );
            CREATE INDEX IF NOT EXISTS index_games_system ON games(system);
            CREATE INDEX IF NOT EXISTS index_games_isFavorite ON games(isFavorite);
            CREATE INDEX IF NOT EXISTS index_games_lastPlayed ON games(lastPlayed);
            CREATE INDEX IF NOT EXISTS index_games_title ON games(title);
        """)
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def upsert_scanned_game(self, game: dict):
        """Simulates Room DAO @Transaction upsertScannedGame(game: GameEntity)."""
        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (game["uri"],))
        existing = self.cursor.fetchone()
        if existing is None:
            # insert(game)
            self.cursor.execute("""
                INSERT OR REPLACE INTO games (
                    uri, title, system, gameCode, fileSize,
                    lastPlayed, playTimeSeconds, isFavorite,
                    customCoverUri, sramPath, dateAdded, region
                ) VALUES (
                    :uri, :title, :system, :gameCode, :fileSize,
                    :lastPlayed, :playTimeSeconds, :isFavorite,
                    :customCoverUri, :sramPath, :dateAdded, :region
                )
            """, game)
        else:
            # updateScannedMetadata(...)
            self.cursor.execute("""
                UPDATE games 
                SET title = :title, 
                    system = :system, 
                    fileSize = :fileSize, 
                    gameCode = COALESCE(:gameCode, gameCode), 
                    region = COALESCE(:region, region) 
                WHERE uri = :uri
            """, {
                "uri": game["uri"],
                "title": game["title"],
                "system": game["system"],
                "fileSize": game["fileSize"],
                "gameCode": game["gameCode"],
                "region": game["region"]
            })

    def test_01_insert_new_rom_with_region(self):
        """Verify inserting a new scanned ROM populates all fields including region."""
        game = {
            "uri": "content://tree/primary%3AROMS/document/primary%3AROMS%2Fzelda_eur.gba",
            "title": "The Legend of Zelda: The Minish Cap",
            "system": "GBA",
            "gameCode": "BZMP",
            "fileSize": 16777216,
            "lastPlayed": 0,
            "playTimeSeconds": 0,
            "isFavorite": 0,
            "customCoverUri": None,
            "sramPath": None,
            "dateAdded": 1700000000000,
            "region": "EUR"
        }
        self.upsert_scanned_game(game)
        self.conn.commit()

        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (game["uri"],))
        row = self.cursor.fetchone()
        self.assertIsNotNone(row)
        self.assertEqual(row["title"], "The Legend of Zelda: The Minish Cap")
        self.assertEqual(row["system"], "GBA")
        self.assertEqual(row["region"], "EUR")
        self.assertEqual(row["gameCode"], "BZMP")
        self.assertEqual(row["fileSize"], 16777216)
        self.assertEqual(row["isFavorite"], 0)
        self.assertEqual(row["lastPlayed"], 0)

    def test_02_rescan_preserves_user_state(self):
        """Verify rescanning existing ROM preserves favorite, lastPlayed, playTime, customCover, and sramPath."""
        uri = "content://tree/primary%3AROMS/document/primary%3AROMS%2Fmother3.gba"

        # 1. First scan
        initial = {
            "uri": uri,
            "title": "Mother 3",
            "system": "GBA",
            "gameCode": "A33J",
            "fileSize": 33554432,
            "lastPlayed": 0,
            "playTimeSeconds": 0,
            "isFavorite": 0,
            "customCoverUri": None,
            "sramPath": None,
            "dateAdded": 1700000000000,
            "region": "JPN"
        }
        self.upsert_scanned_game(initial)
        self.conn.commit()

        # 2. User plays the game, marks favorite, assigns custom cover and SRAM save
        self.cursor.execute("""
            UPDATE games
            SET isFavorite = 1,
                lastPlayed = 1730000000000,
                playTimeSeconds = 18000,
                customCoverUri = 'content://media/picker/mother3_art.png',
                sramPath = '/storage/emulated/0/Saves/mother3.sav'
            WHERE uri = ?
        """, (uri,))
        self.conn.commit()

        # 3. Subsequent rescan occurs (FastRomScanner creates GameEntity with default user fields)
        rescan = {
            "uri": uri,
            "title": "Mother 3 (Updated Title)",
            "system": "GBA",
            "gameCode": None,  # Header not re-read
            "fileSize": 33554432,
            "lastPlayed": 0,   # Scanner default
            "playTimeSeconds": 0, # Scanner default
            "isFavorite": 0,   # Scanner default
            "customCoverUri": None, # Scanner default
            "sramPath": None,  # Scanner default
            "dateAdded": 1730500000000,
            "region": "JPN"
        }
        self.upsert_scanned_game(rescan)
        self.conn.commit()

        # 4. Verify user data was strictly preserved
        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()

        self.assertEqual(row["isFavorite"], 1, "isFavorite was overwritten by rescan!")
        self.assertEqual(row["lastPlayed"], 1730000000000, "lastPlayed was overwritten by rescan!")
        self.assertEqual(row["playTimeSeconds"], 18000, "playTimeSeconds was overwritten by rescan!")
        self.assertEqual(row["customCoverUri"], "content://media/picker/mother3_art.png", "customCoverUri overwritten!")
        self.assertEqual(row["sramPath"], "/storage/emulated/0/Saves/mother3.sav", "sramPath overwritten!")
        self.assertEqual(row["dateAdded"], 1700000000000, "dateAdded was altered!")

        # Physical ROM data was updated
        self.assertEqual(row["title"], "Mother 3 (Updated Title)")
        # gameCode was preserved via COALESCE
        self.assertEqual(row["gameCode"], "A33J")
        self.assertEqual(row["region"], "JPN")

    def test_03_rescan_updates_region_when_renamed(self):
        """Verify that if a user renames ROM file to add region tag, rescan updates region properly."""
        uri = "content://tree/primary%3AROMS/document/primary%3AROMS%2Fgame.gba"

        # Initially scanned with default region "USA"
        self.upsert_scanned_game({
            "uri": uri, "title": "Game", "system": "GBA", "gameCode": "AGBE",
            "fileSize": 8388608, "lastPlayed": 5000, "playTimeSeconds": 100,
            "isFavorite": 1, "customCoverUri": None, "sramPath": None,
            "dateAdded": 1000, "region": "USA"
        })
        self.conn.commit()

        # Rescan detects "(Europe)" in new filename
        self.upsert_scanned_game({
            "uri": uri, "title": "Game", "system": "GBA", "gameCode": "AGBE",
            "fileSize": 8388608, "lastPlayed": 0, "playTimeSeconds": 0,
            "isFavorite": 0, "customCoverUri": None, "sramPath": None,
            "dateAdded": 2000, "region": "EUR"
        })
        self.conn.commit()

        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()
        self.assertEqual(row["region"], "EUR", "Region was not updated on rescan")
        self.assertEqual(row["isFavorite"], 1, "User favorite lost during region update")
        self.assertEqual(row["lastPlayed"], 5000, "User lastPlayed lost during region update")

    def test_04_stress_500_roms_20_rescan_cycles(self):
        """High-load stress test: 500 ROMs subjected to 20 consecutive library rescans."""
        rom_uris = [f"content://storage/roms/game_{i:04d}.gba" for i in range(500)]
        expected_user_data = {}

        # 1. Insert 500 ROMs
        for i, uri in enumerate(rom_uris):
            region = ["USA", "EUR", "JPN", "WLD"][i % 4]
            self.upsert_scanned_game({
                "uri": uri,
                "title": f"Game Title {i}",
                "system": "GBA" if i % 2 == 0 else "GBC",
                "gameCode": f"G{i:03d}",
                "fileSize": 1024 * 1024 * (i + 1),
                "lastPlayed": 0,
                "playTimeSeconds": 0,
                "isFavorite": 0,
                "customCoverUri": None,
                "sramPath": None,
                "dateAdded": 1700000000 + i,
                "region": region
            })

            # User customizes some games
            is_fav = 1 if i % 3 == 0 else 0
            last_played = (1730000000 + i * 100) if i % 5 == 0 else 0
            play_time = (i * 60) if last_played > 0 else 0
            cover = f"content://picker/cover_{i}.webp" if i % 7 == 0 else None
            sram = f"/saves/game_{i}.sav" if i % 11 == 0 else None

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
                "sramPath": sram,
                "region": region
            }
        self.conn.commit()

        # 2. Perform 20 rescans of all 500 ROMs (10,000 upsert operations total)
        t0 = time.perf_counter()
        for rescan_round in range(20):
            for i, uri in enumerate(rom_uris):
                self.upsert_scanned_game({
                    "uri": uri,
                    "title": f"Game Title {i} (v{rescan_round})",
                    "system": "GBA" if i % 2 == 0 else "GBC",
                    "gameCode": None,  # no header inspection during fast scan
                    "fileSize": 1024 * 1024 * (i + 1),
                    "lastPlayed": 0,
                    "playTimeSeconds": 0,
                    "isFavorite": 0,
                    "customCoverUri": None,
                    "sramPath": None,
                    "dateAdded": 1700000000,
                    "region": expected_user_data[uri]["region"]
                })
        self.conn.commit()
        elapsed = time.perf_counter() - t0

        # 3. Verify all 500 ROMs maintain exact user state
        for uri in rom_uris:
            self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
            row = self.cursor.fetchone()
            exp = expected_user_data[uri]
            self.assertEqual(row["isFavorite"], exp["isFavorite"])
            self.assertEqual(row["lastPlayed"], exp["lastPlayed"])
            self.assertEqual(row["playTimeSeconds"], exp["playTimeSeconds"])
            self.assertEqual(row["customCoverUri"], exp["customCoverUri"])
            self.assertEqual(row["sramPath"], exp["sramPath"])
            self.assertEqual(row["region"], exp["region"])

        # 10,000 transactions should execute in < 2.0s
        self.assertLess(elapsed, 2.0, f"10k upsert transactions took {elapsed:.3f}s")


class TestDexPackagingOfRemediations(unittest.TestCase):
    """Verifies that compiled classes in app-debug.apk contain remediated fields and methods."""

    APK_PATH = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(cls.APK_PATH):
            raise unittest.SkipTest(f"APK artifact not yet built at {cls.APK_PATH}")
        with zipfile.ZipFile(cls.APK_PATH) as z:
            dex_files = [n for n in z.namelist() if n.endswith(".dex")]
            cls.all_dex_data = b"".join(z.read(d) for d in dex_files)

    def test_01_game_entity_has_region_field(self):
        """Verify GameEntity class definition in DEX contains the 'region' string."""
        self.assertIn(b"Lcom/retro/mgba/data/db/GameEntity;", self.all_dex_data)
        self.assertIn(b"region", self.all_dex_data)

    def test_02_game_dao_has_upsert_scanned_game(self):
        """Verify GameDao contains upsertScannedGame and updateScannedMetadata methods in DEX."""
        self.assertIn(b"upsertScannedGame", self.all_dex_data)
        self.assertIn(b"updateScannedMetadata", self.all_dex_data)

    def test_03_rom_filename_cleaner_has_extract_region(self):
        """Verify RomFilenameCleaner contains extractRegion method in DEX."""
        self.assertIn(b"extractRegion", self.all_dex_data)


if __name__ == "__main__":
    unittest.main()
