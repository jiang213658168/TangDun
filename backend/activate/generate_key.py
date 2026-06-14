"""
糖盾激活码生成器 v2 (交互式)
AES-128-CBC 加密 → 解密成功即验证通过
"""
import json, base64, os, time
from datetime import datetime
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

AES_KEY = b'TangDun@2026!Key'

def aes_encrypt(data: bytes) -> bytes:
    iv = os.urandom(16)
    cipher = AES.new(AES_KEY, AES.MODE_CBC, iv)
    return iv + cipher.encrypt(pad(data, AES.block_size))

def gen(typ, days, chat, photo, predict, report, export, window=None):
    now = int(time.time())
    if typ == "admin":
        flags = {"chat": -1, "photo": -1, "predict": -1, "report": -1, "export": -1}
        vf = now; vu = now + 365*24*3600; exp = vu
        tp = "admin"
    else:
        flags = {"chat": chat, "photo": photo, "predict": predict, "report": report, "export": export}
        if window:
            vf = int(datetime.strptime(window[0], "%Y-%m-%d %H:%M").timestamp())
            vu = int(datetime.strptime(window[1], "%Y-%m-%d %H:%M").timestamp())
            exp = vu
        else:
            vf = now; vu = now + days*24*3600; exp = vu
        tp = "user"
    payload = {"v": 2, "t": tp, "iat": now, "vf": vf, "vu": vu, "exp": exp, "flg": flags}
    enc = aes_encrypt(json.dumps(payload, separators=(",", ":")).encode())
    b64 = base64.urlsafe_b64encode(enc).decode().rstrip("=")
    return "TD2." + ".".join([b64[i:i+4] for i in range(0, len(b64), 4)])

def menu():
    print("\n" + "=" * 50)
    print("    糖盾 TangDun 激活码生成器 v2")
    print("=" * 50)
    print("  1. 生成管理员激活码 (永久+无限)")
    print("  2. 生成普通用户激活码 (限时+限量)")
    print("  3. 批量生成普通用户激活码")
    print("  0. 退出")
    print("=" * 50)

def get_int(prompt, default):
    s = input(prompt).strip()
    return int(s) if s else default

def main():
    while True:
        menu()
        ch = input("请选择 [0-3]: ").strip()
        if ch == "0": print("再见!"); break

        if ch == "1":
            code = gen("admin", 0, -1, -1, -1, -1, -1)
            print(f"\n  管理员激活码:\n  {code}\n")
            input("按回车继续...")

        elif ch == "2":
            print("\n--- 普通用户激活码 ---")
            d = get_int("  有效天数 (默认30): ", 30)
            c = get_int("  每日AI对话次数 (-1无限, 默认10): ", 10)
            p = get_int("  每日拍照识别次数 (-1无限, 默认5): ", 5)
            pr = get_int("  预测功能 (-1无限/0禁用/默认无限): ", -1)
            r = get_int("  报告功能 (默认无限): ", -1)
            e = get_int("  导出功能 (默认无限): ", -1)
            w = input("  激活窗口 (如: 2026-06-13 10:00,2026-06-13 12:00 或直接回车不限): ").strip()
            window = w.split(",") if w else None
            code = gen("user", d, c, p, pr, r, e, window)
            print(f"\n  激活码:\n  {code}\n")
            if window: print(f"  激活窗口: {window[0]} ~ {window[1]}")
            else: print(f"  有效期: {d}天")
            print(f"  限制: 对话{c if c>=0 else '无限'}次/天 拍照{p if p>=0 else '无限'}次/天")
            input("\n按回车继续...")

        elif ch == "3":
            n = get_int("  生成数量 (默认5): ", 5)
            d = get_int("  有效天数 (默认30): ", 30)
            c = get_int("  每日AI对话次数 (默认10): ", 10)
            p = get_int("  每日拍照识别次数 (默认5): ", 5)
            print(f"\n  生成{n}个激活码:\n")
            for i in range(n):
                code = gen("user", d, c, p, -1, -1, -1)
                print(f"  [{i+1}] {code}")
            print()
            input("按回车继续...")
        else:
            print("无效选择，请重试")

if __name__ == "__main__":
    main()
