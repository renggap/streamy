"""Reference XTEA for Streamy's Ultravox auth — mirrors UvoxXtea.encipher.

Generates golden vectors for the key-padding path (keys shorter/longer than 16
bytes) that the review flagged as untested. Run: python3 xtea_ref.py
"""
import sys


def encipher(data: bytes, key: bytes) -> str:
    MASK = 0xFFFFFFFF
    DELTA = 0x9E3779B9
    k0 = key[:16]
    if len(k0) < 16:
        k0 = k0 + b"\x00" * (16 - len(k0))
    k = [int.from_bytes(k0[i * 4 : i * 4 + 4], "big") for i in range(4)]
    if len(data) % 8 != 0:
        data = data + b"\x00" * (8 - len(data) % 8)
    out = []
    for i in range(0, len(data), 8):
        v0 = int.from_bytes(data[i : i + 4], "big")
        v1 = int.from_bytes(data[i + 4 : i + 8], "big")
        s = 0
        for _ in range(32):
            # Careful: Python's + binds tighter than ^, so group like the Kotlin port does.
            v0 = (v0 + ((((((v1 << 4) & MASK) ^ (v1 >> 5)) + v1) ^ (s + k[s & 3])) & MASK)) & MASK
            s = (s + DELTA) & MASK
            v1 = (v1 + ((((((v0 << 4) & MASK) ^ (v0 >> 5)) + v0) ^ (s + k[(s >> 11) & 3])) & MASK)) & MASK
        out.append("%08x%08x" % (v0, v1))
    return "".join(out)


def main() -> None:
    cases = [
        ("short-key-2", "test", "ab"),
        ("short-key-15", "test", "abcdefghijklmno"),
        ("exact-16", "test", "0123456789abcdef"),
        ("long-key-20", "test", "0123456789abcdefGHIJ"),
        ("empty-data-padded-key", "", "foobar"),
        ("two-blocks", "abcdefghijklmnop", "foobar"),
    ]
    for label, data, key in cases:
        print(f"{label}: {encipher(data.encode(), key.encode())}")


if __name__ == "__main__":
    main()
