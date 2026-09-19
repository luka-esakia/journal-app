#!/usr/bin/env python3
"""
Create the app signing keystore without a JDK.

`keytool` is the usual way to do this, but it needs a Java runtime — which the machine this
project was set up on did not have. Modern keytool writes PKCS12 by default anyway, so this
produces a byte-compatible equivalent using Python's `cryptography` package.

    pip install cryptography
    python3 tools/generate_keystore.py

Writes `keystore/mindjournal.jks`, which is git-ignored. Publish it to CI with:

    base64 -i keystore/mindjournal.jks | pbcopy

and paste into the `KEYSTORE_BASE64` repository secret.

This is a self-signed key for installing personal builds. A Play Store release needs a properly
protected key with a real password.
"""

from __future__ import annotations

import argparse
import datetime
import pathlib
import sys

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import NameOID
except ImportError:  # pragma: no cover - guidance path
    sys.exit("Missing dependency. Install it with:  pip install cryptography")

DEFAULT_PATH = pathlib.Path("keystore/mindjournal.jks")
VALIDITY_DAYS = 10_000


def build_keystore(alias: str, password: str) -> bytes:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    name = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, "Mind Journal"),
            x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Personal"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Mind Journal"),
            x509.NameAttribute(NameOID.LOCALITY_NAME, "Tbilisi"),
            x509.NameAttribute(NameOID.COUNTRY_NAME, "GE"),
        ]
    )

    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)  # self-signed
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=VALIDITY_DAYS))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    # The legacy PKCS12 algorithm keytool historically wrote: read by every JDK and by AGP's
    # signer without needing an explicit storeType beyond PKCS12.
    encryption = (
        serialization.PrivateFormat.PKCS12.encryption_builder()
        .key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
        .hmac_hash(hashes.SHA1())
        .build(password.encode())
    )

    return pkcs12.serialize_key_and_certificates(
        name=alias.encode(),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=encryption,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_PATH)
    parser.add_argument("--alias", default="mindjournal")
    parser.add_argument("--password", default="mindjournal")
    parser.add_argument(
        "--force",
        action="store_true",
        help="overwrite an existing keystore (this invalidates installed builds)",
    )
    args = parser.parse_args()

    if args.out.exists() and not args.force:
        return_code = 1
        print(
            f"{args.out} already exists. Replacing it changes the signing identity, so every\n"
            "installed build would have to be uninstalled before it could update again.\n"
            "Pass --force if that is really what you want.",
            file=sys.stderr,
        )
        return return_code

    args.out.parent.mkdir(parents=True, exist_ok=True)
    blob = build_keystore(args.alias, args.password)
    args.out.write_bytes(blob)

    # Read it back and report the fingerprint Android compares when installing an update.
    _, cert, _ = pkcs12.load_key_and_certificates(args.out.read_bytes(), args.password.encode())
    digest = cert.fingerprint(hashes.SHA256()).hex().upper()
    pretty = ":".join(digest[i : i + 2] for i in range(0, len(digest), 2))

    print(f"wrote {args.out} ({len(blob)} bytes)")
    print(f"alias:       {args.alias}")
    print(f"valid until: {cert.not_valid_after_utc.date()}")
    print(f"SHA-256:     {pretty}")
    print()
    print("Publish to CI:  base64 -i", args.out, "| pbcopy   → secret KEYSTORE_BASE64")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
