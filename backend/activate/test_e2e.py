"""
端到端测试：生成码 → 模拟Android解码 → 验证签名
"""
import json, base64, hashlib, os, time
from datetime import datetime
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

AES_KEY = b'TangDun@2026!Key'

def sign(data: str) -> str:
    return hashlib.sha256((data + AES_KEY.hex()).encode()).hexdigest()[:12]

def encrypt(data: bytes) -> bytes:
    iv = os.urandom(16)
    cipher = AES.new(AES_KEY, AES.MODE_CBC, iv)
    return iv + cipher.encrypt(pad(data, AES.block_size))

def decrypt(data: bytes) -> bytes:
    iv = data[:16]
    ciphertext = data[16:]
    cipher = AES.new(AES_KEY, AES.MODE_CBC, iv)
    return unpad(cipher.decrypt(ciphertext), AES.block_size)

# ===== Generate code (exact same as generate_key.py) =====
def generate(admin=True):
    now = int(time.time())
    flags = {"chat": -1, "photo": -1, "predict": -1, "report": -1, "export": -1}
    valid_from = now
    valid_until = now + 365*24*3600
    expiry = valid_until

    payload = {"v": 2, "t": "admin", "iat": now, "vf": valid_from, "vu": valid_until, "exp": expiry, "flg": flags}
    payload["sig"] = sign(json.dumps(payload, sort_keys=True, separators=(",", ":")))

    print("=== Python: generate ===")
    print(f"Payload (no sig): {json.dumps({k:v for k,v in payload.items() if k!='sig'}, sort_keys=True, separators=(',',':'))}")
    print(f"Signature: {payload['sig']}")
    print(f"Full JSON: {json.dumps(payload, sort_keys=True, separators=(',',':'))}")

    encrypted = encrypt(json.dumps(payload, separators=(",", ":")).encode())
    encoded = base64.urlsafe_b64encode(encrypted).decode().rstrip("=")
    code = "TD2." + ".".join([encoded[i:i+4] for i in range(0, len(encoded), 4)])
    return code

# ===== Verify (simulate Android) =====
def verify(code: str):
    print("\n=== Simulate Android: verify ===")

    # Step 1: Parse code
    b64 = code.replace("TD2.", "").replace(".", "").replace("-", "+").replace("_", "/")
    m = len(b64) % 4
    if m: b64 += "=" * (4 - m)
    decoded = base64.b64decode(b64)
    print(f"Base64 decoded: {len(decoded)} bytes")

    # Step 2: AES decrypt
    plain = decrypt(decoded).decode()
    print(f"Decrypted: {plain}")

    # Step 3: Parse JSON
    py_json = json.loads(plain)
    sig = py_json.pop("sig")
    print(f"Sig from code: {sig}")

    # Step 4: Sort keys + compact JSON (match Python)
    compact = json.dumps(py_json, sort_keys=True, separators=(",", ":"))
    print(f"Compact (no sig): {compact}")

    # Step 5: Compute signature
    expected = sign(compact)
    print(f"Expected sig: {expected}")

    # Step 6: Compare
    if sig == expected:
        print("\n✅ SIGNATURE MATCH! 激活码验证通过")
        return True
    else:
        print(f"\n❌ MISMATCH: code_sig={sig} vs expected={expected}")
        return False

if __name__ == "__main__":
    code = generate(admin=True)
    print(f"\nCode: {code}")
    verify(code)
