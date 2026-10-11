"""Screenshots without extra Python packages: `screencap` raw output (RGBA after a small header),
pixel checks for the conditions, and a PNG writer so a person can look at what the test saw."""
import struct
import zlib


class Image:
    def __init__(self, width, height, rgba):
        self.width, self.height, self.rgba = width, height, rgba

    def pixel(self, x, y):
        if not (0 <= x < self.width and 0 <= y < self.height):
            raise ValueError(f"pixel {x},{y} outside the {self.width}x{self.height} screen")
        i = (y * self.width + x) * 4
        return tuple(self.rgba[i:i + 3])

    def brightest_x(self, y, x0, x1):
        """x of the brightest pixel on row y between x0 and x1 (a slider's handle)."""
        return max(range(x0, x1 + 1), key=lambda x: sum(self.pixel(x, y)))

    def png(self):
        rows = b"".join(b"\0" + self.rgba[r * self.width * 4:(r + 1) * self.width * 4] for r in range(self.height))
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 3)) + chunk(b"IEND", b""))


def parse_raw(data):
    """`screencap` without -p: width, height, format (1 = RGBA_8888), on Android 9+ a colour
    space too, then the pixels. The header size follows from the length."""
    if len(data) < 12:
        raise ValueError("screencap returned nothing")
    w, h, fmt = struct.unpack_from("<III", data, 0)
    if fmt != 1:
        raise ValueError(f"screencap pixel format {fmt}, expected 1 (RGBA_8888)")
    header = len(data) - w * h * 4
    if header not in (12, 16):
        raise ValueError(f"screencap: {len(data)} bytes do not fit {w}x{h} RGBA")
    return Image(w, h, data[header:])


def matches_color(rgb, spec):
    """spec: {"rgb": [r, g, b], "tol": 40} or {"dominant": "green"|"red"|"blue", "min": 80, "margin": 30}."""
    if "rgb" in spec:
        tol = spec.get("tol", 40)
        return all(abs(a - b) <= tol for a, b in zip(rgb, spec["rgb"]))
    if "dominant" in spec:
        idx = {"red": 0, "green": 1, "blue": 2}[spec["dominant"]]
        main = rgb[idx]
        others = [c for i, c in enumerate(rgb) if i != idx]
        return main > spec.get("min", 80) and all(main > o + spec.get("margin", 30) for o in others)
    raise ValueError(f"colour check needs rgb or dominant: {spec}")
