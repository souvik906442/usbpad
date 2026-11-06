"""UsbPad Windows server: phone -> (adb reverse) -> 127.0.0.1:5555 -> SendInput.
Run:  python server.py     (run as Administrator to also control elevated windows)
Needs: Python 3, adb (platform-tools) in PATH, USB debugging ON on the phone.
"""
import ctypes
import socket
import struct
import subprocess
import sys
import threading
import time
from ctypes import wintypes

PORT = 5555

if sys.platform != "win32":
    sys.exit("This server only runs on Windows.")

user32 = ctypes.windll.user32
user32.VkKeyScanW.restype = ctypes.c_short
user32.VkKeyScanW.argtypes = (wintypes.WCHAR,)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_WHEEL = 0x0800
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0001, 0x0002, 0x0004

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                ("wParamH", wintypes.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


def _send(inp):
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def mouse(flags, data=0, dx=0, dy=0):
    i = INPUT(type=INPUT_MOUSE)
    i.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    _send(i)


VK = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "home": 0x24, "end": 0x23, "pgup": 0x21, "pgdn": 0x22, "delete": 0x2E,
    "shift": 0x10, "ctrl": 0x11, "alt": 0x12, "win": 0x5B,
}
for n in range(1, 13):
    VK["f%d" % n] = 0x6F + n
EXT = {0x25, 0x26, 0x27, 0x28, 0x24, 0x23, 0x21, 0x22, 0x2E, 0x5B}
MODS = {"shift", "ctrl", "alt", "win"}

held_mods = set()
held_buttons = set()
BTN = {
    "L": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "R": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "M": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}


def key_vk(vk, up=False):
    flags = (KEYEVENTF_EXTENDEDKEY if vk in EXT else 0) | (KEYEVENTF_KEYUP if up else 0)
    i = INPUT(type=INPUT_KEYBOARD)
    i.ki = KEYBDINPUT(vk, user32.MapVirtualKeyW(vk, 0), flags, 0, 0)
    _send(i)


def key_unicode(ch):
    data = ch.encode("utf-16-le")
    for (unit,) in struct.iter_unpack("<H", data):
        for up in (False, True):
            i = INPUT(type=INPUT_KEYBOARD)
            i.ki = KEYBDINPUT(0, unit, KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0), 0, 0)
            _send(i)


def key_action(action, name):
    vk = VK.get(name)
    if vk is None:
        return
    if action == "tap":
        key_vk(vk)
        key_vk(vk, True)
    elif action == "down":
        key_vk(vk)
        if name in MODS:
            held_mods.add(name)
    elif action == "up":
        key_vk(vk, True)
        held_mods.discard(name)


def type_text(text):
    for ch in text:
        if ch == "\n":
            key_action("tap", "enter")
            continue
        if held_mods & {"ctrl", "alt", "win"}:
            r = user32.VkKeyScanW(ch)
            if r != -1 and (r & 0xFF) != 0xFF:
                vk = r & 0xFF
                need_shift = bool(r & 0x100) and "shift" not in held_mods
                if need_shift:
                    key_vk(VK["shift"])
                key_vk(vk)
                key_vk(vk, True)
                if need_shift:
                    key_vk(VK["shift"], True)
                continue
        key_unicode(ch)


def release_all():
    for m in list(held_mods):
        key_vk(VK[m], True)
    held_mods.clear()
    for b in list(held_buttons):
        mouse(BTN[b][1])
    held_buttons.clear()


def handle(line):
    cmd, _, arg = line.partition(" ")
    if cmd == "M":
        dx, dy = arg.split()
        mouse(MOUSEEVENTF_MOVE, dx=int(dx), dy=int(dy))
    elif cmd == "W":
        mouse(MOUSEEVENTF_WHEEL, data=int(arg))
    elif cmd == "D" and arg in BTN:
        mouse(BTN[arg][0])
        held_buttons.add(arg)
    elif cmd == "U" and arg in BTN:
        mouse(BTN[arg][1])
        held_buttons.discard(arg)
    elif cmd == "C" and arg in BTN:
        mouse(BTN[arg][0])
        mouse(BTN[arg][1])
    elif cmd == "T":
        type_text(arg)
    elif cmd == "K":
        action, _, name = arg.partition(" ")
        key_action(action, name)
    elif cmd == "R":
        release_all()


def pinger(conn, stop):
    while not stop.is_set():
        try:
            conn.sendall(b"P\n")
        except OSError:
            return
        stop.wait(1)


def client(conn):
    stop = threading.Event()
    print("phone connected")
    try:
        conn.sendall(b"HELLO\n")
        threading.Thread(target=pinger, args=(conn, stop), daemon=True).start()
        with conn.makefile("r", encoding="utf-8", errors="replace", newline="\n") as f:
            for raw in f:
                try:
                    handle(raw.rstrip("\r\n"))
                except Exception as e:  # keep going on bad lines
                    print("bad line:", repr(raw), e)
    except OSError:
        pass
    finally:
        stop.set()
        release_all()
        conn.close()
        print("phone disconnected")


def adb_loop():
    flags = 0x08000000  # CREATE_NO_WINDOW
    warned = False
    while True:
        try:
            out = subprocess.run(["adb", "reverse", "--list"], capture_output=True,
                                 text=True, timeout=8, creationflags=flags).stdout
            if "tcp:%d" % PORT not in out:
                subprocess.run(["adb", "reverse", "tcp:%d" % PORT, "tcp:%d" % PORT],
                               capture_output=True, timeout=8, creationflags=flags)
        except FileNotFoundError:
            if not warned:
                print("adb not found in PATH. Install platform-tools or run adb reverse manually.")
                warned = True
        except Exception:
            pass
        time.sleep(3)


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))  # localhost only
    srv.listen(2)
    threading.Thread(target=adb_loop, daemon=True).start()
    print("UsbPad server on 127.0.0.1:%d - plug in phone (USB debugging ON), open the app." % PORT)
    while True:
        conn, _ = srv.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=client, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
