#!/usr/bin/env python3
"""Pack an unpacked extension directory into a signed CRX3.

Chromium's own packer lives behind `chrome --pack-extension`, which needs a
desktop Chrome binary. Cobalt's build produces an Android APK and nothing that
can run on the build host, so the format is written directly here instead of
building a second browser just to sign a zip.

CRX3 layout:

    "Cr24"                      magic
    uint32le 3                  version
    uint32le header_size
    CrxFileHeader               protobuf
    zip                         the extension itself

The signature covers a domain-separated preamble rather than the archive alone,
which is what stops a signature being lifted from one CRX onto another:

    "CRX3 SignedData\x00" + uint32le(len(signed_header_data))
                           + signed_header_data + zip_bytes

The extension ID is derived from the public key, so the key must be kept and
reused: regenerating it changes the ID, and the ID is what policy pins uBO by.
The key is a build secret and never ships inside the APK.

Protobuf is hand-encoded. The two messages have three fields between them, so
the alternative was a build-time dependency on protoc for 30 lines of wire
format.
"""
import argparse
import hashlib
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        out.append(b | (0x80 if value else 0))
        if not value:
            return bytes(out)


def field(number: int, payload: bytes) -> bytes:
    """Length-delimited protobuf field (wire type 2)."""
    return varint((number << 3) | 2) + varint(len(payload)) + payload


def extension_id(public_key_der: bytes) -> str:
    """Chromium's ID: first 16 bytes of the key digest, hex mapped onto a-p."""
    digest = hashlib.sha256(public_key_der).digest()[:16]
    return "".join(chr(ord("a") + (b >> 4)) + chr(ord("a") + (b & 0xF)) for b in digest)


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, **kw)


def ensure_key(key_path: Path) -> None:
    if key_path.exists():
        return
    key_path.parent.mkdir(parents=True, exist_ok=True)
    print("generating a new signing key at " + str(key_path))
    print("KEEP IT: the extension ID is derived from it, and policy pins that ID.")
    run(["openssl", "genrsa", "-out", str(key_path), "2048"])
    key_path.chmod(0o600)


def zip_dir(src: Path, dest: Path) -> None:
    # Sorted, with fixed timestamps, so the same input produces the same bytes
    # and the packed CRX can be diffed between builds.
    files = sorted(p for p in src.rglob("*") if p.is_file())
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            info = zipfile.ZipInfo(str(f.relative_to(src)).replace("\\", "/"))
            info.date_time = (1980, 1, 1, 0, 0, 0)
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, f.read_bytes())
    print("zipped " + str(len(files)) + " files")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source", help="unpacked extension directory")
    ap.add_argument("--out", required=True, help="output .crx path")
    ap.add_argument("--key", required=True, help="RSA private key (created if absent)")
    args = ap.parse_args()

    src = Path(args.source)
    if not (src / "manifest.json").is_file():
        print("no manifest.json in " + str(src), file=sys.stderr)
        return 1

    key_path = Path(args.key)
    ensure_key(key_path)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    zip_path = out.with_suffix(".zip")
    zip_dir(src, zip_path)
    zip_bytes = zip_path.read_bytes()

    pub_der = run(["openssl", "rsa", "-in", str(key_path), "-pubout", "-outform", "DER"]).stdout
    ext_id = extension_id(pub_der)

    signed_header_data = field(1, bytes.fromhex(
        "".join("%02x" % ((ord(ext_id[i]) - ord("a")) << 4 | (ord(ext_id[i + 1]) - ord("a")))
                for i in range(0, 32, 2))))

    to_sign = (b"CRX3 SignedData\x00"
               + struct.pack("<I", len(signed_header_data))
               + signed_header_data
               + zip_bytes)

    sign_input = out.with_suffix(".tosign")
    sign_input.write_bytes(to_sign)
    signature = run(["openssl", "dgst", "-sha256", "-sign", str(key_path),
                     str(sign_input)]).stdout
    sign_input.unlink()

    proof = field(1, pub_der) + field(2, signature)
    header = field(2, proof) + field(10000, signed_header_data)

    with out.open("wb") as f:
        f.write(b"Cr24")
        f.write(struct.pack("<I", 3))
        f.write(struct.pack("<I", len(header)))
        f.write(header)
        f.write(zip_bytes)
    zip_path.unlink()

    print("id      " + ext_id)
    print("crx     " + str(out) + " (" + str(out.stat().st_size) + " bytes)")

    # Hand-encoded protobuf and a hand-built preamble are exactly the kind of
    # thing that produces a file which looks right and is rejected on device
    # hours later. Verify by re-parsing what was just written.
    if not verify(out):
        return 1
    return 0


def verify(path: Path) -> bool:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    data = path.read_bytes()
    if data[:4] != b"Cr24":
        print("bad magic", file=sys.stderr)
        return False
    version, header_len = struct.unpack("<II", data[4:12])
    if version != 3:
        print("unexpected CRX version " + str(version), file=sys.stderr)
        return False
    header, zip_bytes = data[12:12 + header_len], data[12 + header_len:]

    def parse(buf):
        out, i = {}, 0
        while i < len(buf):
            key = shift = 0
            while True:
                b = buf[i]; i += 1
                key |= (b & 0x7F) << shift
                if not b & 0x80:
                    break
                shift += 7
            length = shift = 0
            while True:
                b = buf[i]; i += 1
                length |= (b & 0x7F) << shift
                if not b & 0x80:
                    break
                shift += 7
            out.setdefault(key >> 3, []).append(buf[i:i + length]); i += length
        return out

    fields = parse(header)
    proof = parse(fields[2][0])
    pub, sig = proof[1][0], proof[2][0]
    shd = fields[10000][0]

    if parse(shd)[1][0] != hashlib.sha256(pub).digest()[:16]:
        print("crx_id in signed header does not match the key", file=sys.stderr)
        return False

    signed = (b"CRX3 SignedData\x00" + struct.pack("<I", len(shd)) + shd + zip_bytes)
    try:
        serialization.load_der_public_key(pub).verify(
            sig, signed, padding.PKCS1v15(), hashes.SHA256())
    except Exception as e:
        print("signature does not verify: " + str(e), file=sys.stderr)
        return False

    print("verify  signature OK, id matches key")
    return True


if __name__ == "__main__":
    raise SystemExit(main())
