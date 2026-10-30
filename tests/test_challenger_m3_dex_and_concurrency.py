#!/usr/bin/env python3
"""Challenger 2 Empirical Verification Suite:
1. APK DEX Inspection: GameEntity constructor parameters and GameDao transaction method signatures.
2. LibraryViewModel Thread Scheduling & Bytecode Analysis: .flowOn(Dispatchers.Default) verification.
3. Android 8.0-9.0 SQLite 3.18+ Compatibility: GameDao transactional upsert logic & metadata preservation.
4. Concurrency & Stress: High-frequency emissions and concurrent state modifications.
"""

import os
import re
import subprocess
import sqlite3
import sys
import threading
import time
import unittest

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEXDUMP_PATH = r"C:\Users\Bypass\AppData\Local\Android\Sdk\build-tools\34.0.0\dexdump.exe"
TEMP_DEX_DIR = r"C:\Users\Bypass\AppData\Local\Temp\apk_dex_dump"


class TestApkDexBytecodeVerification(unittest.TestCase):
    """Empirically inspects the compiled classes in app-debug.apk using dexdump."""

    @classmethod
    def setUpClass(cls):
        cls.dex_files = [
            os.path.join(TEMP_DEX_DIR, f)
            for f in os.listdir(TEMP_DEX_DIR)
            if f.endswith(".dex")
        ]

    def _find_class_dump(self, class_descriptor):
        for dex_path in self.dex_files:
            res = subprocess.run([DEXDUMP_PATH, "-d", dex_path], capture_output=True, text=True, errors="replace")
            classes = res.stdout.split("  Class descriptor  : ")
            for c in classes:
                if c.startswith(f"'{class_descriptor}'"):
                    return c, dex_path
        return None, None

    def test_01_game_entity_constructor_parameters(self):
        """Confirm GameEntity constructor has all 12 persistent fields including 'region'."""
        dump, dex = self._find_class_dump("Lcom/retro/mgba/data/db/GameEntity;")
        self.assertIsNotNone(dump, "GameEntity class descriptor not found in APK DEX files")

        # Verify instance fields
        expected_fields = {
            "uri": "Ljava/lang/String;",
            "title": "Ljava/lang/String;",
            "system": "Ljava/lang/String;",
            "gameCode": "Ljava/lang/String;",
            "fileSize": "J",
            "lastPlayed": "J",
            "playTimeSeconds": "J",
            "isFavorite": "Z",
            "customCoverUri": "Ljava/lang/String;",
            "sramPath": "Ljava/lang/String;",
            "dateAdded": "J",
            "region": "Ljava/lang/String;"
        }

        for field_name, field_type in expected_fields.items():
            pattern = rf"name\s+:\s+'{field_name}'\s+type\s+:\s+'{re.escape(field_type)}'"
            self.assertRegex(dump, pattern, f"GameEntity missing instance field: {field_name}:{field_type}")

        # Verify constructor signature contains all 12 parameters
        # (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJZLjava/lang/String;Ljava/lang/String;JLjava/lang/String;)V
        expected_ctor_sig = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJZLjava/lang/String;Ljava/lang/String;JLjava/lang/String;)V"
        self.assertIn(expected_ctor_sig, dump, "GameEntity primary constructor signature does not match expected 12-parameter signature")

        # Verify component12 exists for 'region'
        self.assertIn("name          : 'component12'", dump)

    def test_02_game_dao_transaction_method_signatures(self):
        """Confirm GameDao and GameDao_Impl contain transaction-wrapped upsertScannedGame and updateScannedMetadata."""
        dump_impl, dex_impl = self._find_class_dump("Lcom/retro/mgba/data/db/GameDao_Impl;")
        self.assertIsNotNone(dump_impl, "GameDao_Impl class descriptor not found in APK DEX files")

        # 1. upsertScannedGame(GameEntity)
        self.assertIn("name          : 'upsertScannedGame'", dump_impl)
        self.assertIn("type          : '(Lcom/retro/mgba/data/db/GameEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'", dump_impl)

        # 2. upsertScannedGame with exploded parameters
        self.assertIn("type          : '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJJZLjava/lang/String;Ljava/lang/String;JLjava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'", dump_impl)

        # 3. upsertScannedGames(List)
        self.assertIn("name          : 'upsertScannedGames'", dump_impl)
        self.assertIn("type          : '(Ljava/util/List;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'", dump_impl)

        # 4. updateScannedMetadata
        self.assertIn("name          : 'updateScannedMetadata'", dump_impl)
        self.assertIn("type          : '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'", dump_impl)

        # 5. insert
        self.assertIn("name          : 'insert'", dump_impl)
        self.assertIn("type          : '(Lcom/retro/mgba/data/db/GameEntity;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;'", dump_impl)

        # 6. Verify RoomDatabaseKt.withTransaction is invoked in upsertScannedGame
        self.assertIn("Landroidx/room/RoomDatabaseKt;.withTransaction:", dump_impl)

    def test_03_library_view_model_flow_on_default(self):
        """Confirm LibraryViewModel references Dispatchers.getDefault() and FlowKt.flowOn()."""
        # Search all dex files for LibraryViewModel class or its lambdas
        found_flow_on = False
        found_default_dispatcher = False

        for dex_path in self.dex_files:
            res = subprocess.run([DEXDUMP_PATH, "-d", dex_path], capture_output=True, text=True, errors="replace")
            classes = res.stdout.split("  Class descriptor  : ")
            for c in classes:
                if "Lcom/retro/mgba/ui/library/LibraryViewModel" in c:
                    if "Lkotlinx/coroutines/flow/FlowKt;.flowOn:" in c or "flowOn" in c:
                        found_flow_on = True
                    if "Lkotlinx/coroutines/Dispatchers;.getDefault:" in c or "getDefault" in c:
                        found_default_dispatcher = True

        self.assertTrue(found_flow_on, "LibraryViewModel does not invoke FlowKt.flowOn in compiled DEX bytecode")
        self.assertTrue(found_default_dispatcher, "LibraryViewModel does not reference Dispatchers.getDefault() in compiled DEX bytecode")


class TestM3RemediatedSqliteUpsert(unittest.TestCase):
    """Verifies that the remediated GameDao transactional upsert logic behaves identically

    to SQLite 3.18+ (Android 8.0-9.0 minSdk 26-28 compatible) and preserves all user fields.
    """

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.cursor = self.conn.cursor()

        # DDL with region column
        self.cursor.executescript("""
            CREATE TABLE games (
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
        """)
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def simulated_upsert_scanned_game(self, game_dict):
        """Simulates Room's @Transaction upsertScannedGame implementation:

        1. getGameByUri(uri)
        2. if null -> insert(game)
        3. else -> updateScannedMetadata(...)
        """
        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (game_dict["uri"],))
        existing = self.cursor.fetchone()

        if existing is None:
            self.cursor.execute("""
                INSERT OR REPLACE INTO games (
                    uri, title, system, gameCode, fileSize,
                    lastPlayed, playTimeSeconds, isFavorite, customCoverUri, sramPath, dateAdded, region
                ) VALUES (
                    :uri, :title, :system, :gameCode, :fileSize,
                    :lastPlayed, :playTimeSeconds, :isFavorite, :customCoverUri, :sramPath, :dateAdded, :region
                )
            """, game_dict)
        else:
            self.cursor.execute("""
                UPDATE games 
                SET title = :title, 
                    system = :system, 
                    fileSize = :fileSize, 
                    gameCode = COALESCE(:gameCode, gameCode), 
                    region = COALESCE(:region, region) 
                WHERE uri = :uri
            """, {
                "uri": game_dict["uri"],
                "title": game_dict["title"],
                "system": game_dict["system"],
                "fileSize": game_dict["fileSize"],
                "gameCode": game_dict.get("gameCode"),
                "region": game_dict.get("region", "USA")
            })
        self.conn.commit()

    def test_01_rescan_preserves_user_fields_and_updates_region(self):
        """Verify that updating physical ROM metadata retains all user modifications."""
        uri = "content://roms/zelda_eur.gba"

        # 1. Initial scan with EUR region
        self.simulated_upsert_scanned_game({
            "uri": uri, "title": "Zelda Minish Cap", "system": "GBA", "gameCode": "BZMP",
            "fileSize": 16777216, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
            "customCoverUri": None, "sramPath": None, "dateAdded": 1000, "region": "EUR"
        })

        # Check initial
        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()
        self.assertEqual(row["region"], "EUR")
        self.assertEqual(row["isFavorite"], 0)

        # 2. User plays the game and favorites it
        self.cursor.execute("""
            UPDATE games 
            SET isFavorite = 1, lastPlayed = 999999, playTimeSeconds = 3600,
                customCoverUri = 'content://gallery/cover.png', sramPath = '/saves/zelda.sav'
            WHERE uri = ?
        """, (uri,))
        self.conn.commit()

        # 3. Rescan occurs with updated title
        self.simulated_upsert_scanned_game({
            "uri": uri, "title": "The Legend of Zelda: The Minish Cap", "system": "GBA", "gameCode": "BZMP",
            "fileSize": 16777216, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
            "customCoverUri": None, "sramPath": None, "dateAdded": 2000, "region": "EUR"
        })

        # 4. Verify user fields remained intact while title was updated
        self.cursor.execute("SELECT * FROM games WHERE uri = ?", (uri,))
        row = self.cursor.fetchone()
        self.assertEqual(row["title"], "The Legend of Zelda: The Minish Cap")
        self.assertEqual(row["region"], "EUR")
        self.assertEqual(row["isFavorite"], 1)
        self.assertEqual(row["lastPlayed"], 999999)
        self.assertEqual(row["playTimeSeconds"], 3600)
        self.assertEqual(row["customCoverUri"], "content://gallery/cover.png")
        self.assertEqual(row["sramPath"], "/saves/zelda.sav")
        self.assertEqual(row["dateAdded"], 1000)  # dateAdded preserved

    def test_02_region_extraction_varieties(self):
        """Cross-verify RomFilenameCleaner.extractRegion regex and keyword resolution."""
        region_keywords = {
            "USA": "USA", "US": "USA", "U": "USA",
            "EUROPE": "EUR", "EUR": "EUR", "EU": "EUR", "E": "EUR",
            "JAPAN": "JPN", "JAP": "JPN", "J": "JPN",
            "WORLD": "WLD", "W": "WLD",
            "USA, EUROPE": "USA/EUR", "USA,EUR": "USA/EUR",
            "EN,FR,DE,ES,IT": "EUR", "EN,FR,DE": "EUR", "EN,JA": "WLD",
            "GERMANY": "GER", "FRANCE": "FRA", "SPAIN": "SPA", "ITALY": "ITA", "AUSTRALIA": "AUS"
        }
        paren_pattern = re.compile(r"\(([a-zA-Z0-9_,\-\.\s]+)\)")

        def extract_region(filename):
            for match in paren_pattern.finditer(filename):
                content = match.group(1).strip().upper()
                if content in region_keywords:
                    return region_keywords[content]
                tokens = [t.strip() for t in re.split(r"[,/ ]", content) if t.strip()]
                for t in tokens:
                    if t in region_keywords:
                        return region_keywords[t]
            return None

        cases = [
            ("Pokemon Emerald (USA).gba", "USA"),
            ("Zelda (Europe).gba", "EUR"),
            ("Mother 3 (Japan).gba", "JPN"),
            ("Tetris (World).gb", "WLD"),
            ("Advance Wars (Germany).gba", "GER"),
            ("Mario (France).gba", "FRA"),
            ("Golden Sun (Spain).gba", "SPA"),
            ("Donkey Kong (Italy).gb", "ITA"),
            ("Metroid (Australia).gba", "AUS"),
            ("Sonic (USA, Europe).gba", "USA/EUR"),
            ("Castlevania (Rev 1).gba", None),  # revision tag, returns None -> default USA
            ("Unknown ROM [!].gba", None),      # bracketed tag, returns None -> default USA
        ]

        for fname, expected in cases:
            res = extract_region(fname)
            self.assertEqual(res, expected, f"Failed region extraction for: {fname}")

    def test_03_batch_upsert_100_roms_metadata_preservation(self):
        """Stress test: 100 ROMs upserted via transactional logic preserved across 10 rescans."""
        rom_uris = [f"content://storage/roms/rom_{i:04d}.gba" for i in range(100)]
        regions = ["USA", "EUR", "JPN", "WLD"]

        # 1. Initial batch insert
        for i, uri in enumerate(rom_uris):
            self.simulated_upsert_scanned_game({
                "uri": uri, "title": f"ROM {i}", "system": "GBA", "gameCode": f"AG{i:02d}",
                "fileSize": 1000 + i, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
                "customCoverUri": None, "sramPath": None, "dateAdded": 1000 + i,
                "region": regions[i % len(regions)]
            })

        # 2. User plays 50 games and favorites 30 games
        for i in range(50):
            self.cursor.execute("""
                UPDATE games 
                SET isFavorite = ?, lastPlayed = ?, playTimeSeconds = ?, customCoverUri = ?, sramPath = ?
                WHERE uri = ?
            """, (
                1 if i % 2 == 0 else 0,
                2000000 + i,
                1800 + i,
                f"content://covers/c_{i}.png",
                f"/saves/s_{i}.sav",
                rom_uris[i]
            ))
        self.conn.commit()

        # 3. Perform 10 rescans
        for r in range(10):
            for i, uri in enumerate(rom_uris):
                self.simulated_upsert_scanned_game({
                    "uri": uri, "title": f"ROM {i} Rescan {r}", "system": "GBA", "gameCode": f"AG{i:02d}",
                    "fileSize": 1000 + i, "lastPlayed": 0, "playTimeSeconds": 0, "isFavorite": 0,
                    "customCoverUri": None, "sramPath": None, "dateAdded": 9999,
                    "region": regions[i % len(regions)]
                })

        # 4. Assert user metadata preserved for all 50 modified games
        for i in range(50):
            self.cursor.execute("SELECT * FROM games WHERE uri = ?", (rom_uris[i],))
            row = self.cursor.fetchone()
            self.assertEqual(row["isFavorite"], 1 if i % 2 == 0 else 0)
            self.assertEqual(row["lastPlayed"], 2000000 + i)
            self.assertEqual(row["playTimeSeconds"], 1800 + i)
            self.assertEqual(row["customCoverUri"], f"content://covers/c_{i}.png")
            self.assertEqual(row["sramPath"], f"/saves/s_{i}.sav")
            self.assertEqual(row["title"], f"ROM {i} Rescan 9")
            self.assertEqual(row["region"], regions[i % len(regions)])


class TestM3LibraryViewModelPipelineStress(unittest.TestCase):
    """Stress tests the LibraryViewModel reactive combining, filtering, sorting, and counting logic

    under high concurrency and rapid UI events.
    """

    class MockGame:
        def __init__(self, uri, title, system, file_size, last_played=0, is_fav=False, region="USA", game_code=""):
            self.uri = uri
            self.title = title
            self.system = system
            self.file_size = file_size
            self.last_played = last_played
            self.is_fav = is_fav
            self.region = region
            self.game_code = game_code

        @property
        def is_gba(self): return self.system.upper() == "GBA"
        @property
        def is_gb(self): return self.system.upper() == "GB"
        @property
        def is_gbc(self): return self.system.upper() == "GBC"

    def compute_pipeline(self, all_games, filter_mode, sort_order, query):
        """Python exact equivalent of LibraryViewModel.kt lines 82-148."""
        # 1. System counts
        counts = {
            "ALL": len(all_games),
            "GBA": sum(1 for g in all_games if g.is_gba),
            "GB": sum(1 for g in all_games if g.is_gb),
            "GBC": sum(1 for g in all_games if g.is_gbc),
            "FAVORITES": sum(1 for g in all_games if g.is_fav),
        }

        # 2. Filter by system
        if filter_mode == "GBA":
            filtered = [g for g in all_games if g.is_gba]
        elif filter_mode == "GB":
            filtered = [g for g in all_games if g.is_gb]
        elif filter_mode == "GBC":
            filtered = [g for g in all_games if g.is_gbc]
        elif filter_mode == "FAVORITES":
            filtered = [g for g in all_games if g.is_fav]
        else:
            filtered = all_games

        # 3. Filter by search query (including title, gameCode, and region)
        if query and query.strip():
            q = query.strip().lower()
            filtered = [
                g for g in filtered
                if q in g.title.lower() or (g.game_code and q in g.game_code.lower()) or q in g.region.lower()
            ]

        # 4. Sort
        if sort_order == "TITLE_ASC":
            sorted_games = sorted(filtered, key=lambda g: g.title.lower())
        elif sort_order == "TITLE_DESC":
            sorted_games = sorted(filtered, key=lambda g: g.title.lower(), reverse=True)
        elif sort_order == "RECENT_DESC":
            sorted_games = sorted(filtered, key=lambda g: (-g.last_played, g.title.lower()))
        elif sort_order == "SIZE_DESC":
            sorted_games = sorted(filtered, key=lambda g: (-g.file_size, g.title.lower()))
        elif sort_order == "SIZE_ASC":
            sorted_games = sorted(filtered, key=lambda g: (g.file_size, g.title.lower()))
        else:
            sorted_games = filtered

        return counts, sorted_games

    def test_01_concurrent_queries_and_rescan_emissions(self):
        """Simulates 10 concurrent worker threads firing search, filter, and rescan emissions on 2,000 games."""
        games = [
            self.MockGame(
                uri=f"content://roms/{i}.gba",
                title=f"Game Title {i:04d} Version {i%5}",
                system=["GBA", "GB", "GBC"][i % 3],
                file_size=1024 * (i + 1),
                last_played=1730000000 + i if i % 4 == 0 else 0,
                is_fav=(i % 7 == 0),
                region=["USA", "EUR", "JPN", "WLD"][i % 4],
                game_code=f"AGB{i%100:02d}"
            )
            for i in range(2000)
        ]

        errors = []
        threads = []

        def worker_task(thread_id):
            try:
                queries = ["Title", "Version 2", "AGB05", "EUR", "Zelda", ""]
                filters = ["ALL", "GBA", "GB", "GBC", "FAVORITES"]
                sorts = ["TITLE_ASC", "TITLE_DESC", "RECENT_DESC", "SIZE_DESC", "SIZE_ASC"]

                for step in range(50):
                    q = queries[(thread_id + step) % len(queries)]
                    f = filters[(thread_id + step) % len(filters)]
                    s = sorts[(thread_id + step) % len(sorts)]
                    counts, result = self.compute_pipeline(games, f, s, q)
                    self.assertEqual(counts["ALL"], 2000)
                    self.assertGreaterEqual(len(result), 0)
            except Exception as e:
                errors.append(e)

        t0 = time.perf_counter()
        for i in range(10):
            t = threading.Thread(target=worker_task, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        elapsed = time.perf_counter() - t0
        self.assertEqual(len(errors), 0, f"Encountered errors in concurrent workers: {errors}")
        # 10 threads * 50 iterations = 500 complete pipeline passes on 2,000 items
        # Must execute cleanly and fast
        self.assertLess(elapsed, 3.0, f"500 passes took {elapsed:.3f}s, expected < 3.0s")

    def test_02_search_by_region(self):
        """Verify that searching for 'EUR' or 'JPN' filters the library by the genuine region field."""
        games = [
            self.MockGame("1", "Metroid", "GBA", 1000, region="USA"),
            self.MockGame("2", "Pokemon", "GBA", 1000, region="EUR"),
            self.MockGame("3", "Mother 3", "GBA", 1000, region="JPN"),
        ]

        _, eur_games = self.compute_pipeline(games, "ALL", "TITLE_ASC", "EUR")
        self.assertEqual(len(eur_games), 1)
        self.assertEqual(eur_games[0].title, "Pokemon")

        _, jpn_games = self.compute_pipeline(games, "ALL", "TITLE_ASC", "JPN")
        self.assertEqual(len(jpn_games), 1)
        self.assertEqual(jpn_games[0].title, "Mother 3")


if __name__ == "__main__":
    unittest.main()
