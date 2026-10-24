# AllDoc Document Vault: Cryptographic Security Model

## 1. Overview
AllDoc provides an on-device document vault encryption system allowing users to protect any document with a user-supplied passphrase. The architecture uses authenticated encryption (AEAD) to prevent plaintext disclosure and detect ciphertext tampering.

## 2. Cryptographic Primitives
- **Symmetric Cipher**: AES-256 in Galois/Counter Mode (`AES/GCM/NoPadding`).
- **Authentication Tag**: 128-bit GMAC (Galois Message Authentication Code) appended to the ciphertext.
- **Key Derivation Function (KDF)**: `PBKDF2WithHmacSHA256`.
- **Iteration Count**: **600,000 iterations** (exceeding OWASP Password Storage Guidelines).
- **Salt Generation**: 128-bit (16-byte) CSPRNG (`java.security.SecureRandom`).
- **IV / Nonce**: 96-bit (12-byte) CSPRNG per encryption event.

## 3. Container File Format (`.vault`)
```
+-----------------------------------+-------------------+--------------------+------------------------------------------+
| Magic Header (16 bytes)           | Salt (16 bytes)   | IV (12 bytes)      | Ciphertext + GCM Tag (16 bytes)          |
| "ALLDOC_VAULT_V1\n"               | Random CSPRNG     | Random CSPRNG      | AES-256-GCM encrypted payload            |
+-----------------------------------+-------------------+--------------------+------------------------------------------+
```

## 4. Threat Model & Mitigations
1. **Offline Brute-Force Attacks**:
   - Mitigated by 600,000 iterations of PBKDF2-HMAC-SHA256, imposing prohibitive computational cost on GPU/ASIC cracking rigs.
2. **Rainbow Table / Precomputation Attacks**:
   - Mitigated by 128-bit unique cryptographically random salt per file.
3. **Nonce-Reuse Attacks (AES-GCM Fatal Vulnerability)**:
   - Mitigated by generating a fresh 96-bit random IV for every file encryption.
4. **Ciphertext Tampering / Bit-Flipping**:
   - Mitigated by AES-GCM's 128-bit authentication tag. Tampering causes `javax.crypto.AEADBadTagException` before any plaintext is released.
5. **Memory Residue**:
   - Passwords are handled as `CharArray` rather than immutable `String` objects where possible, allowing memory clearing.
