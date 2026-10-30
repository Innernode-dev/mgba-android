"""ELF64 Binary and Dynamic Symbol Analyzer.
Opaque-box verification for arm64-v8a shared libraries (.so) and native symbols.
"""

import os
import struct
from typing import Dict, List, Optional, Set, Tuple


class Elf64Analyzer:
    """Opaque-box ELF64 binary inspector for arm64-v8a Android libraries."""

    EM_AARCH64 = 0xB7  # 183
    ELFCLASS64 = 2
    ELFDATA2LSB = 1    # Little endian

    def __init__(self, file_path_or_bytes):
        if isinstance(file_path_or_bytes, (str, os.PathLike)):
            with open(file_path_or_bytes, "rb") as f:
                self.data = f.read()
        else:
            self.data = bytes(file_path_or_bytes)
        self.is_valid = False
        self.machine = 0
        self.dynamic_symbols: Set[str] = set()
        self.sections: Dict[str, Tuple[int, int]] = {}
        if len(self.data) >= 64:
            self._parse_header()

    def _parse_header(self):
        # 16-byte e_ident
        e_ident = self.data[0:16]
        if e_ident[0:4] != b"\x7fELF":
            return
        if e_ident[4] != self.ELFCLASS64:
            return
        if e_ident[5] != self.ELFDATA2LSB:
            return

        # Parse ELF64 header fields
        # e_type(H), e_machine(H), e_version(I), e_entry(Q), e_phoff(Q), e_shoff(Q), e_flags(I),
        # e_ehsize(H), e_phentsize(H), e_phnum(H), e_shentsize(H), e_shnum(H), e_shstrndx(H)
        try:
            (
                e_type, e_machine, e_version, e_entry, e_phoff, e_shoff,
                e_flags, e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx
            ) = struct.unpack_from("<HHIQQQIHHHHHH", self.data, 16)
        except struct.error:
            return

        self.machine = e_machine
        self.is_valid = (e_machine == self.EM_AARCH64)
        if not self.is_valid or e_shoff == 0 or e_shnum == 0:
            return

        # Parse Section Headers
        # Shdr: sh_name(I), sh_type(I), sh_flags(Q), sh_addr(Q), sh_offset(Q), sh_size(Q),
        # sh_link(I), sh_info(I), sh_addralign(Q), sh_entsize(Q)
        sh_fmt = "<IIQQQQIIQQ"
        sh_size = struct.calcsize(sh_fmt)
        if e_shentsize < sh_size or e_shoff + e_shnum * e_shentsize > len(self.data):
            return

        # First pass: read string table for section names
        if e_shstrndx >= e_shnum:
            return
        strtab_sh_offset = e_shoff + e_shstrndx * e_shentsize
        _, _, _, _, strtab_offset, strtab_size, _, _, _, _ = struct.unpack_from(sh_fmt, self.data, strtab_sh_offset)
        sec_strtab = self.data[strtab_offset:strtab_offset + strtab_size]

        dynsym_offset = 0
        dynsym_size = 0
        dynsym_entsize = 24  # default Elf64_Sym size
        dynstr_offset = 0
        dynstr_size = 0

        for i in range(e_shnum):
            offset = e_shoff + i * e_shentsize
            (
                sh_name, sh_type, sh_flags, sh_addr, sec_offset, sec_len,
                sh_link, sh_info, sh_addralign, sh_entsize
            ) = struct.unpack_from(sh_fmt, self.data, offset)

            # Extract section name from sec_strtab
            name_end = sec_strtab.find(b"\x00", sh_name)
            sec_name = sec_strtab[sh_name:name_end].decode("ascii", "replace") if name_end != -1 else ""
            self.sections[sec_name] = (sec_offset, sec_len)

            # SHT_DYNSYM = 11, SHT_STRTAB = 3
            if sh_type == 11 or sec_name == ".dynsym":
                dynsym_offset = sec_offset
                dynsym_size = sec_len
                if sh_entsize > 0:
                    dynsym_entsize = sh_entsize
            elif sec_name == ".dynstr":
                dynstr_offset = sec_offset
                dynstr_size = sec_len

        # Extract dynamic symbols if present
        if dynsym_offset > 0 and dynstr_offset > 0:
            dynstr_data = self.data[dynstr_offset:dynstr_offset + dynstr_size]
            sym_fmt = "<IBBHQQ"  # st_name(I), st_info(B), st_other(B), st_shndx(H), st_value(Q), st_size(Q)
            sym_size = struct.calcsize(sym_fmt)
            num_syms = dynsym_size // sym_size
            for i in range(num_syms):
                sym_pos = dynsym_offset + i * sym_size
                if sym_pos + sym_size > len(self.data):
                    break
                st_name, st_info, st_other, st_shndx, st_value, st_len = struct.unpack_from(sym_fmt, self.data, sym_pos)
                if st_name < len(dynstr_data):
                    end_idx = dynstr_data.find(b"\x00", st_name)
                    if end_idx != -1:
                        sym_str = dynstr_data[st_name:end_idx].decode("ascii", "replace")
                        if sym_str:
                            self.dynamic_symbols.add(sym_str)

    def has_symbol(self, symbol_name: str) -> bool:
        """Check if dynamic symbol is exported."""
        return symbol_name in self.dynamic_symbols

    def has_symbols(self, symbols: List[str]) -> Dict[str, bool]:
        """Check a list of symbols and return status dictionary."""
        return {s: (s in self.dynamic_symbols) for s in symbols}


def create_mock_arm64_so(symbols: List[str]) -> bytes:
    """Generate a valid minimal ELF64 AArch64 shared library with given exported dynamic symbols."""
    # Build string table
    dynstr = bytearray(b"\x00")
    sym_entries = []

    # Symbol 0: dummy undefined symbol
    sym_entries.append((0, 0, 0, 0, 0, 0))

    for sym in symbols:
        name_offset = len(dynstr)
        dynstr.extend(sym.encode("ascii") + b"\x00")
        # STB_GLOBAL=1, STT_FUNC=2 -> st_info = (1 << 4) | 2 = 0x12
        sym_entries.append((name_offset, 0x12, 0, 1, 0x1000, 32))

    shstrtab = bytearray(b"\x00.dynsym\x00.dynstr\x00.shstrtab\x00.text\x00")
    sec_names = {
        "": 0,
        ".dynsym": 1,
        ".dynstr": 9,
        ".shstrtab": 17,
        ".text": 27,
    }

    # Layout offsets
    ehdr_size = 64
    text_code = b"\x1f\x20\x03\xd5\xc0\x03\x5f\xd6"  # nop; ret
    text_offset = 0x1000
    dynstr_offset = text_offset + len(text_code)
    dynsym_offset = dynstr_offset + len(dynstr)
    dynsym_entsize = 24
    dynsym_size = len(sym_entries) * dynsym_entsize
    shstrtab_offset = dynsym_offset + dynsym_size

    # Section headers: 0 (null), 1 (.dynsym), 2 (.dynstr), 3 (.shstrtab), 4 (.text)
    sh_entsize = 64
    sh_num = 5
    sh_offset = (shstrtab_offset + len(shstrtab) + 15) & ~15

    total_size = sh_offset + sh_num * sh_entsize
    out = bytearray(total_size)

    # 1. ELF Header
    # e_ident: \x7fELF, ELFCLASS64(2), ELFDATA2LSB(1), EV_CURRENT(1), OSABI_NONE(0), PAD(7)
    e_ident = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8
    out[0:16] = e_ident
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        out, 16,
        3,          # e_type: ET_DYN (shared object)
        0xB7,       # e_machine: EM_AARCH64
        1,          # e_version: EV_CURRENT
        0x1000,     # e_entry
        0,          # e_phoff
        sh_offset,  # e_shoff
        0,          # e_flags
        64,         # e_ehsize
        56,         # e_phentsize
        0,          # e_phnum
        sh_entsize, # e_shentsize
        sh_num,     # e_shnum
        3           # e_shstrndx (.shstrtab is section 3)
    )

    # 2. Section bodies
    out[text_offset:text_offset + len(text_code)] = text_code
    out[dynstr_offset:dynstr_offset + len(dynstr)] = dynstr

    for i, entry in enumerate(sym_entries):
        pos = dynsym_offset + i * dynsym_entsize
        struct.pack_into("<IBBHQQ", out, pos, entry[0], entry[1], entry[2], entry[3], entry[4], entry[5])

    out[shstrtab_offset:shstrtab_offset + len(shstrtab)] = shstrtab

    # 3. Section Headers
    # Null section 0
    # Section 1: .dynsym (SHT_DYNSYM=11, SHF_ALLOC=2)
    struct.pack_into(
        "<IIQQQQIIQQ", out, sh_offset + 1 * sh_entsize,
        sec_names[".dynsym"], 11, 2, 0x2000, dynsym_offset, dynsym_size, 2, 1, 8, dynsym_entsize
    )
    # Section 2: .dynstr (SHT_STRTAB=3, SHF_ALLOC=2)
    struct.pack_into(
        "<IIQQQQIIQQ", out, sh_offset + 2 * sh_entsize,
        sec_names[".dynstr"], 3, 2, 0x3000, dynstr_offset, len(dynstr), 0, 0, 1, 0
    )
    # Section 3: .shstrtab (SHT_STRTAB=3, flags=0)
    struct.pack_into(
        "<IIQQQQIIQQ", out, sh_offset + 3 * sh_entsize,
        sec_names[".shstrtab"], 3, 0, 0, shstrtab_offset, len(shstrtab), 0, 0, 1, 0
    )
    # Section 4: .text (SHT_PROGBITS=1, SHF_ALLOC|SHF_EXECINSTR=6)
    struct.pack_into(
        "<IIQQQQIIQQ", out, sh_offset + 4 * sh_entsize,
        sec_names[".text"], 1, 6, 0x1000, text_offset, len(text_code), 0, 0, 4, 0
    )

    return bytes(out)
