"""SAF (Storage Access Framework) Document Cursor Simulator.
Simulates Android DocumentsContract tree traversal, MIME type resolution,
recursive scanning, and batching constraints.
"""

from dataclasses import dataclass, field
from typing import Dict, Iterator, List, Optional, Set, Tuple


@dataclass
class MockDocumentEntry:
    document_id: str
    display_name: str
    mime_type: str
    size: int
    last_modified: int
    is_directory: bool = False
    children: List["MockDocumentEntry"] = field(default_factory=list)
    raw_data: Optional[bytes] = None


class MockSafTree:
    """Simulates an Android Storage Access Framework directory tree."""

    COLUMN_DOCUMENT_ID = "document_id"
    COLUMN_DISPLAY_NAME = "_display_name"
    COLUMN_MIME_TYPE = "mime_type"
    COLUMN_SIZE = "_size"
    COLUMN_LAST_MODIFIED = "last_modified"

    MIME_DIR = "vnd.android.document/directory"
    MIME_OCTET = "application/octet-stream"

    ROM_EXTENSIONS = {".gba", ".gb", ".gbc"}

    def __init__(self, root_doc_id: str = "primary:RetroROMs"):
        self.root_doc_id = root_doc_id
        self.root = MockDocumentEntry(
            document_id=root_doc_id,
            display_name="RetroROMs",
            mime_type=self.MIME_DIR,
            size=0,
            last_modified=1700000000,
            is_directory=True
        )
        self._doc_map: Dict[str, MockDocumentEntry] = {root_doc_id: self.root}

    def add_directory(self, parent_id: str, dir_name: str) -> MockDocumentEntry:
        parent = self._doc_map.get(parent_id)
        if not parent or not parent.is_directory:
            raise ValueError(f"Parent {parent_id} not found or not a directory")

        new_doc_id = f"{parent_id}/{dir_name}"
        dir_entry = MockDocumentEntry(
            document_id=new_doc_id,
            display_name=dir_name,
            mime_type=self.MIME_DIR,
            size=0,
            last_modified=1700000000,
            is_directory=True
        )
        parent.children.append(dir_entry)
        self._doc_map[new_doc_id] = dir_entry
        return dir_entry

    def add_file(
        self,
        parent_id: str,
        file_name: str,
        size: int,
        data: Optional[bytes] = None,
        mime_type: Optional[str] = None
    ) -> MockDocumentEntry:
        parent = self._doc_map.get(parent_id)
        if not parent or not parent.is_directory:
            raise ValueError(f"Parent {parent_id} not found or not a directory")

        new_doc_id = f"{parent_id}/{file_name}"
        if not mime_type:
            mime_type = self.MIME_OCTET

        file_entry = MockDocumentEntry(
            document_id=new_doc_id,
            display_name=file_name,
            mime_type=mime_type,
            size=size,
            last_modified=1700000000,
            is_directory=False,
            raw_data=data
        )
        parent.children.append(file_entry)
        self._doc_map[new_doc_id] = file_entry
        return file_entry

    def query_child_documents(self, parent_doc_id: str) -> List[Dict[str, any]]:
        """Simulates ContentResolver.query on buildChildDocumentsUriUsingTree."""
        parent = self._doc_map.get(parent_doc_id)
        if not parent or not parent.is_directory:
            return []

        rows = []
        for child in parent.children:
            rows.append({
                self.COLUMN_DOCUMENT_ID: child.document_id,
                self.COLUMN_DISPLAY_NAME: child.display_name,
                self.COLUMN_MIME_TYPE: child.mime_type,
                self.COLUMN_SIZE: child.size,
                self.COLUMN_LAST_MODIFIED: child.last_modified,
            })
        return rows

    def fast_recursive_scan(self, root_id: Optional[str] = None) -> List[Dict[str, any]]:
        """High-speed recursive traversal with ROM extension matching and .nomedia respect."""
        target_root = root_id or self.root_doc_id
        discovered_roms = []
        stack = [target_root]

        while stack:
            current_id = stack.pop()
            children = self.query_child_documents(current_id)

            # Check for .nomedia directory exclusion
            has_nomedia = any(c[self.COLUMN_DISPLAY_NAME].lower() == ".nomedia" for c in children)
            if has_nomedia:
                continue

            for c in children:
                mime = c[self.COLUMN_MIME_TYPE]
                name = c[self.COLUMN_DISPLAY_NAME]
                doc_id = c[self.COLUMN_DOCUMENT_ID]

                if mime == self.MIME_DIR:
                    # Avoid traversing hidden directories
                    if not name.startswith("."):
                        stack.append(doc_id)
                else:
                    lower = name.lower()
                    if any(lower.endswith(ext) for ext in self.ROM_EXTENSIONS):
                        discovered_roms.append(c)

        return discovered_roms
