"""AES-256-GCM helpers for measurement batches.

The ciphertext layout matches Java ``AES/GCM/NoPadding``: the 16-byte tag is
appended to the ciphertext. Associated data binds the batch identity.
"""

from __future__ import annotations

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def _aes(key: bytes) -> AESGCM:
    if len(key) != 32:
        raise ValueError("invalid measurement key")
    return AESGCM(key)


def encrypt(key: bytes, iv: bytes, aad: bytes, plaintext: bytes) -> bytes:
    if len(iv) != 12:
        raise ValueError("invalid iv")
    return _aes(key).encrypt(iv, plaintext, aad)


def decrypt(key: bytes, iv: bytes, aad: bytes, ciphertext: bytes) -> bytes:
    if len(iv) != 12 or len(ciphertext) < 17:
        raise ValueError("invalid ciphertext")
    try:
        return _aes(key).decrypt(iv, ciphertext, aad)
    except Exception as exc:
        raise ValueError("decrypt_failed") from exc
