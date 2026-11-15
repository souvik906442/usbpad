"""UsbPad tray app (Windows): runs the LAN server hidden, no console window."""
import os
import sys
import threading
import winreg

import pystray
from PIL import Image, ImageDraw

sys.argv = [sys.argv[0], "lan"]
sys.path.insert(0, getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))))
import server  # noqa: E402

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP = "UsbPadServer"


def autostart_on():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
        return True
    except OSError:
        return False


def toggle_autostart(icon, item):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if autostart_on():
            winreg.DeleteValue(k, APP)
        else:
            exe = sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__)
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, '"%s"' % exe)


def make_icon():
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((6, 6, 58, 58), 12, fill=(46, 125, 50, 255))
    d.rectangle((18, 20, 46, 44), outline="white", width=4)
    return im


def main():
    pin = server.load_pin()
    ips = server.local_ips()
    threading.Thread(target=server.serve, args=(True,), daemon=True).start()
    menu = pystray.Menu(
        pystray.MenuItem("UsbPad server running (port %d)" % server.PORT, None, enabled=False),
        pystray.MenuItem("IP: " + (" / ".join(ips) or "?"), None, enabled=False),
        pystray.MenuItem("PIN: " + pin, None, enabled=False),
        pystray.MenuItem("Start with Windows", toggle_autostart, checked=lambda i: autostart_on()),
        pystray.MenuItem("Quit", lambda icon, item: icon.stop()),
    )
    pystray.Icon("UsbPad", make_icon(), "UsbPad: " + (ips[0] if ips else "no network") + "  PIN " + pin, menu).run()


if __name__ == "__main__":
    main()
