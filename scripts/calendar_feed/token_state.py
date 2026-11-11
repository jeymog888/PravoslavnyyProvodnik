"""Authenticated encryption for a rotating API JWT, never a plaintext token file.

The initial high-entropy JWT remains a protected workflow secret and is used as
master key material. Its API expiration does not expire the encryption key. This
format is not a password vault and explicitly rejects non-JWT master strings.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import tempfile

from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

if __package__:
    from .errors import FeedError
else:
    from errors import FeedError

MAX_ENVELOPE_BYTES = 32_000
PURPOSE = b"feoria-calendar/azbyka-api-v2/jwt-state/v1"
ALGORITHM = "fernet-hkdf-sha256"
BASE64 = re.compile(r"[A-Za-z0-9_-]+={0,2}")


def decode64(value: str) -> bytes:
    if not isinstance(value, str) or not BASE64.fullmatch(value):
        raise FeedError("invalid_token_state_encoding")
    try:
        return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, TypeError):
        raise FeedError("invalid_token_state_encoding") from None


def jwt(value: str) -> str:
    if not isinstance(value, str) or not 100 <= len(value) <= 20_000:
        raise FeedError("invalid_state_jwt")
    parts = value.split(".")
    if len(parts) != 3:
        raise FeedError("invalid_state_jwt")
    try:
        header = json.loads(decode64(parts[0]))
        claims = json.loads(decode64(parts[1]))
        signature = decode64(parts[2])
        if not isinstance(header, dict) or header.get("alg") not in {"RS256", "RS384", "RS512", "HS256", "HS384", "HS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512", "EdDSA"} or not isinstance(claims, dict) or len(signature) < 32:
            raise ValueError()
    except (ValueError, TypeError, UnicodeDecodeError):
        raise FeedError("invalid_state_jwt") from None
    # The API verifies the actual signature. Syntax checks here prevent use of an
    # ordinary weak password as master material, not assert authenticity or validity.
    return value


def cipher(master: str, salt: bytes) -> Fernet:
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=PURPOSE).derive(jwt(master).encode("ascii"))
    return Fernet(base64.urlsafe_b64encode(key))


def validate_envelope(value) -> None:
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "algorithm", "salt", "ciphertext"} or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1 or value["algorithm"] != ALGORITHM:
        raise FeedError("invalid_token_state")
    if not isinstance(value["salt"], str) or len(value["salt"]) != 44 or len(decode64(value["salt"])) != 32:
        raise FeedError("invalid_token_state_salt")
    ciphertext = value["ciphertext"]
    if not isinstance(ciphertext, str) or not 100 <= len(ciphertext) <= 30_000:
        raise FeedError("invalid_token_state_size")
    data = decode64(ciphertext)
    # Fernet format version, timestamp, IV, ciphertext blocks, and HMAC.
    if len(data) < 73 or data[0] != 0x80 or (len(data) - 57) % 16 != 0:
        raise FeedError("invalid_token_state_format")
    if len(json.dumps(value).encode("ascii")) > MAX_ENVELOPE_BYTES:
        raise FeedError("invalid_token_state_size")


def seal(token: str, master: str) -> dict:
    jwt(token)
    salt = os.urandom(32)
    plain = json.dumps({"purpose": PURPOSE.decode("ascii"), "token": token}, separators=(",", ":")).encode("ascii")
    value = {"schemaVersion": 1, "algorithm": ALGORITHM,
             "salt": base64.urlsafe_b64encode(salt).decode("ascii"),
             "ciphertext": cipher(master, salt).encrypt(plain).decode("ascii")}
    validate_envelope(value)
    return value


def unseal(value: dict, master: str) -> str:
    validate_envelope(value)
    try:
        plain = cipher(master, decode64(value["salt"])).decrypt(value["ciphertext"].encode("ascii"))
        content = json.loads(plain)
        if not isinstance(content, dict) or set(content) != {"purpose", "token"} or content["purpose"] != PURPOSE.decode("ascii"):
            raise ValueError()
        return jwt(content["token"])
    except (InvalidToken, ValueError, UnicodeDecodeError, TypeError):
        raise FeedError("token_state_authentication_failed") from None


def load(path: Path, master: str) -> str:
    if path.is_symlink():
        raise FeedError("token_state_symlink_rejected")
    if not path.exists():
        return jwt(master)
    try:
        if not 0 < path.stat().st_size <= MAX_ENVELOPE_BYTES:
            raise FeedError("invalid_token_state_size")
        value = json.loads(path.read_text(encoding="ascii"))
    except (ValueError, UnicodeError, OSError):
        raise FeedError("invalid_token_state_file") from None
    return unseal(value, master)


def save(path: Path, token: str, master: str) -> None:
    value = seal(token, master)
    if path.is_symlink() or path.parent.is_symlink():
        raise FeedError("token_state_symlink_rejected")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".token-state-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
