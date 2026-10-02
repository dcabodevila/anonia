"""Small, read-only BIFF8 reader for INE's public surname workbook.

Not a general Excel implementation: accepts regular OLE Workbook streams and
BIFF8 string/numeric cells only. Rejects encryption and unsupported layouts.
No formulas, macros, external links or workbook code are evaluated.
"""
import struct


def u32(data, offset=0):
    return struct.unpack_from("<I", data, offset)[0]


def workbook_stream(data):
    if data[:8] != bytes.fromhex("d0cf11e0a1b11ae1"):
        raise ValueError("Expected an OLE XLS workbook")
    sector_size = 1 << struct.unpack_from("<H", data, 30)[0]
    if sector_size not in (512, 4096):
        raise ValueError("Unsupported OLE sector size")

    def sector(index):
        start = (index + 1) * sector_size
        result = data[start:start + sector_size]
        if len(result) != sector_size:
            raise ValueError("OLE sector outside file")
        return result

    fat_sectors = [n for n in struct.unpack_from("<109I", data, 76) if n < 0xFFFFFFFA]
    next_difat = u32(data, 68)
    seen = set()
    for _ in range(u32(data, 72)):
        if next_difat in seen:
            raise ValueError("Cyclic OLE DIFAT")
        seen.add(next_difat)
        values = struct.unpack("<" + "I" * (sector_size // 4), sector(next_difat))
        fat_sectors.extend(n for n in values[:-1] if n < 0xFFFFFFFA)
        next_difat = values[-1]
    if len(fat_sectors) != u32(data, 44):
        raise ValueError("OLE FAT length mismatch")
    fat = []
    for index in fat_sectors:
        fat.extend(struct.unpack("<" + "I" * (sector_size // 4), sector(index)))

    def chain(index):
        pieces = []
        visited = set()
        while index != 0xFFFFFFFE:
            if index >= len(fat) or index in visited:
                raise ValueError("Invalid OLE chain")
            visited.add(index)
            pieces.append(sector(index))
            index = fat[index]
        return b"".join(pieces)

    directory = chain(u32(data, 48))
    for offset in range(0, len(directory), 128):
        entry = directory[offset:offset + 128]
        length = struct.unpack_from("<H", entry, 64)[0]
        name = entry[:max(0, length - 2)].decode("utf-16le")
        if name in ("Workbook", "Book") and entry[66] == 2:
            size = struct.unpack_from("<Q", entry, 120)[0]
            if size < u32(data, 56):
                raise ValueError("Mini-stream workbooks are not supported")
            return chain(u32(entry, 116))[:size]
    raise ValueError("No Workbook stream")


def records(data):
    offset = 0
    while offset + 4 <= len(data):
        kind, length = struct.unpack_from("<HH", data, offset)
        if offset + 4 + length > len(data):
            raise ValueError("Truncated BIFF record")
        yield offset, kind, data[offset + 4:offset + 4 + length]
        offset += 4 + length


class Segments:
    """SST cursor; character continuations have an extra encoding byte."""
    def __init__(self, pieces):
        self.pieces = pieces
        self.index = 0
        self.offset = 0

    def remaining(self):
        return len(self.pieces[self.index]) - self.offset

    def advance(self):
        self.index += 1
        self.offset = 0
        if self.index >= len(self.pieces):
            raise ValueError("Truncated SST")

    def read(self, size):
        result = bytearray()
        while size:
            if not self.remaining():
                self.advance()
            take = min(size, self.remaining())
            result.extend(self.pieces[self.index][self.offset:self.offset + take])
            self.offset += take
            size -= take
        return bytes(result)

    def strings(self):
        _, count = struct.unpack("<II", self.read(8))
        result = []
        for _ in range(count):
            length = struct.unpack("<H", self.read(2))[0]
            flags = self.read(1)[0]
            runs = struct.unpack("<H", self.read(2))[0] if flags & 8 else 0
            extension = u32(self.read(4)) if flags & 4 else 0
            wide = bool(flags & 1)
            chunks = []
            while length:
                if not self.remaining():
                    self.advance()
                    wide = bool(self.read(1)[0] & 1)
                width = 2 if wide else 1
                take = min(length, self.remaining() // width)
                if not take:
                    raise ValueError("Split UTF-16 character in SST")
                chunks.append(self.read(take * width).decode("utf-16le" if wide else "latin-1"))
                length -= take
            result.append("".join(chunks))
            self.read(4 * runs + extension)
        return result


def rk_number(raw):
    if raw & 2:
        value = struct.unpack("<i", struct.pack("<I", raw))[0] >> 2
    else:
        value = struct.unpack("<d", struct.pack("<II", 0, raw & 0xFFFFFFFC))[0]
    return value / 100 if raw & 1 else value


def sheets(data):
    """Return (sheet name, sparse row dictionaries) for BIFF8 worksheets."""
    stream = workbook_stream(data)
    entries = list(records(stream))
    boundaries = []
    strings = None
    for i, (_, kind, payload) in enumerate(entries):
        if kind == 0x002F:
            raise ValueError("Encrypted XLS is not supported")
        if kind == 0x0085 and payload[5] == 0:
            length, wide = payload[6], payload[7] & 1
            name = payload[8:8 + length * (2 if wide else 1)].decode("utf-16le" if wide else "latin-1")
            boundaries.append((u32(payload), name))
        elif kind == 0x00FC:
            pieces = [payload]
            j = i + 1
            while j < len(entries) and entries[j][1] == 0x003C:
                pieces.append(entries[j][2])
                j += 1
            strings = Segments(pieces).strings()
    if strings is None or not boundaries:
        raise ValueError("Expected BIFF8 worksheets with shared strings")
    for start, name in boundaries:
        rows = {}
        for _, kind, payload in records(stream[start:]):
            if kind == 0x000A:
                break
            if kind not in (0x00FD, 0x0203, 0x027E, 0x00BD, 0x0006):
                continue
            row, column = struct.unpack_from("<HH", payload)
            cells = rows.setdefault(row, {})
            if kind == 0x00FD:
                cells[column] = strings[u32(payload, 6)]
            elif kind == 0x0203:
                cells[column] = struct.unpack_from("<d", payload, 6)[0]
            elif kind == 0x027E:
                cells[column] = rk_number(u32(payload, 6))
            elif kind == 0x0006:
                # Cached result only. Suppressed frequencies are strings ("..").
                # Never evaluate the formula or use hidden helper columns.
                cached = payload[6:14]
                cells[column] = None if cached[6:] == b"\xff\xff" else struct.unpack("<d", cached)[0]
            else:
                last = struct.unpack_from("<H", payload, len(payload) - 2)[0]
                if len(payload) != 6 + 6 * (last - column + 1):
                    raise ValueError("Invalid MULRK record")
                for col in range(column, last + 1):
                    cells[col] = rk_number(u32(payload, 6 + 6 * (col - column)))
        yield name, rows
