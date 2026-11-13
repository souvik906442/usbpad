package com.example.usbpad;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs as root:  CLASSPATH=<apk> app_process /system/bin com.example.usbpad.HidHelper /dev/hidgK /dev/hidgM
 * stdin : text commands (M dx dy | W d | D/U/C L|R|M | T text | K tap|down|up name | R)
 * stdout: "S <sent> <dropped> <host-led-reports>" every second, "E <msg>" on fatal error
 * Writes are NON-blocking: if the PC is not reading, reports are dropped instead of hanging.
 * Two HID functions (no report ids): keyboard 8 bytes (mods,0,k1..k6), mouse 4 bytes (buttons,x,y,wheel)
 */
public class HidHelper {
    private static final FileDescriptor[] fds = new FileDescriptor[2]; // 0 = keyboard, 1 = mouse
    private static final boolean[] stalled = new boolean[2];
    private static int mods = 0;
    private static int buttons = 0;
    private static final int[] keys = new int[6];
    private static volatile long sent = 0;
    private static volatile long dropped = 0;
    private static volatile long leds = 0;

    public static void main(String[] args) throws Exception {
        String kdev = args.length > 0 ? args[0] : "/dev/hidg0";
        String mdev = args.length > 1 ? args[1] : "/dev/hidg1";
        try {
            fds[0] = Os.open(kdev, OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
            fds[1] = Os.open(mdev, OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            System.out.println("E cannot open " + kdev + " / " + mdev + ": " + e.getMessage());
            System.out.flush();
            System.exit(1);
        }

        Thread status = new Thread(() -> {
            while (true) {
                System.out.println("S " + sent + " " + dropped + " " + leds);
                System.out.flush();
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        });
        status.setDaemon(true);
        status.start();

        // Output reports from the PC (keyboard LEDs). Proves the PC driver talks to us.
        Thread rd = new Thread(() -> {
            byte[] buf = new byte[16];
            while (true) {
                try {
                    int n = Os.read(fds[0], buf, 0, buf.length);
                    if (n > 0) leds++;
                } catch (ErrnoException e) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                } catch (InterruptedIOException e) {
                    return;
                }
            }
        });
        rd.setDaemon(true);
        rd.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                handle(line);
            } catch (Exception e) {
                // ignore bad lines
            }
        }
        releaseAll();
        System.exit(0);
    }

    // ---------- low level ----------
    private static void write(int dev, byte[] r, boolean essential) {
        int tries = essential ? (stalled[dev] ? 20 : 300) : (stalled[dev] ? 1 : 20);
        for (int i = 0; i < tries; i++) {
            try {
                Os.write(fds[dev], r, 0, r.length);
                sent++;
                stalled[dev] = false;
                return;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EAGAIN) {
                    dropped++;
                    return;
                }
                try { Thread.sleep(1); } catch (InterruptedException ie) { return; }
            } catch (InterruptedIOException e) {
                return;
            }
        }
        dropped++;
        stalled[dev] = true;
    }

    private static void sendKbd(boolean essential) {
        byte[] r = new byte[8];
        r[0] = (byte) mods;
        r[1] = 0;
        for (int i = 0; i < 6; i++) r[2 + i] = (byte) keys[i];
        write(0, r, essential);
    }

    private static void sendMouse(int dx, int dy, int wheel, boolean essential) {
        byte[] r = {(byte) buttons, (byte) dx, (byte) dy, (byte) wheel};
        write(1, r, essential);
    }

    private static void addKey(int u) {
        for (int i = 0; i < 6; i++) if (keys[i] == u) return;
        for (int i = 0; i < 6; i++) {
            if (keys[i] == 0) { keys[i] = u; return; }
        }
    }

    private static void removeKey(int u) {
        for (int i = 0; i < 6; i++) if (keys[i] == u) keys[i] = 0;
    }

    // ---------- commands ----------
    private static void handle(String line) {
        int sp = line.indexOf(' ');
        String cmd = sp < 0 ? line : line.substring(0, sp);
        String arg = sp < 0 ? "" : line.substring(sp + 1);
        switch (cmd) {
            case "M": {
                String[] p = arg.trim().split("\\s+");
                move(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                break;
            }
            case "W": wheel(Integer.parseInt(arg.trim())); break;
            case "D": setButton(arg.trim(), true); break;
            case "U": setButton(arg.trim(), false); break;
            case "C": setButton(arg.trim(), true); setButton(arg.trim(), false); break;
            case "T": typeText(arg); break;
            case "X":
                for (String h : arg.trim().split("\\s+")) altHex(h);
                break;
            case "K": {
                int s2 = arg.indexOf(' ');
                keyAction(arg.substring(0, s2), arg.substring(s2 + 1).trim());
                break;
            }
            case "R": releaseAll(); break;
            default: break;
        }
    }

    private static void releaseAll() {
        mods = 0;
        buttons = 0;
        for (int i = 0; i < 6; i++) keys[i] = 0;
        sendKbd(true);
        sendMouse(0, 0, 0, true);
    }

    private static void move(int dx, int dy) {
        while (dx != 0 || dy != 0) {
            int sx = Math.max(-127, Math.min(127, dx));
            int sy = Math.max(-127, Math.min(127, dy));
            sendMouse(sx, sy, 0, false);
            dx -= sx;
            dy -= sy;
        }
    }

    private static void wheel(int delta) {
        if (delta == 0) return;
        int steps = delta / 120;
        if (steps == 0) steps = delta > 0 ? 1 : -1;
        while (steps != 0) {
            int s = Math.max(-127, Math.min(127, steps));
            sendMouse(0, 0, s, false);
            steps -= s;
        }
    }

    private static void setButton(String name, boolean down) {
        int bit = name.equals("L") ? 1 : name.equals("R") ? 2 : name.equals("M") ? 4 : 0;
        if (bit == 0) return;
        if (down) buttons |= bit; else buttons &= ~bit;
        sendMouse(0, 0, 0, !down);
    }

    private static final String PLAIN = "-=[]\\;'`,./";
    private static final String SHIFT = "_+{}|:\"~<>?";
    private static final int[] SYM = {45, 46, 47, 48, 49, 51, 52, 53, 54, 55, 56};

    /** usage id, +0x100 if shift is needed; -1 if unsupported (HID can only send US-layout keys). */
    private static int usageFor(int cp) {
        if (cp >= 'a' && cp <= 'z') return cp - 'a' + 4;
        if (cp >= 'A' && cp <= 'Z') return (cp - 'A' + 4) | 0x100;
        if (cp >= '1' && cp <= '9') return cp - '1' + 30;
        if (cp == '0') return 39;
        if (cp == ' ') return 44;
        if (cp == '\n') return 40;
        if (cp == '\t') return 43;
        int i = "!@#$%^&*()".indexOf(cp);
        if (i >= 0) return (30 + i) | 0x100;
        i = PLAIN.indexOf(cp);
        if (i >= 0) return SYM[i];
        i = SHIFT.indexOf(cp);
        if (i >= 0) return SYM[i] | 0x100;
        return -1;
    }

    private static void tap(int usage, boolean shift) {
        int saved = mods;
        if (shift) mods |= 2;
        addKey(usage);
        sendKbd(false);
        removeKey(usage);
        mods = saved;
        sendKbd(true);
    }

    private static void typeText(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int u = usageFor(cp);
            if (u < 0) continue;
            tap(u & 0xFF, (u & 0x100) != 0);
        }
    }

    private static int hexUsage(char c) {
        if (c >= '1' && c <= '9') return 89 + (c - '1'); // keypad 1..9 (NumLock must be ON)
        if (c == '0') return 98;                           // keypad 0
        if (c >= 'a' && c <= 'f') return 4 + (c - 'a');
        return 0;
    }

    /** One Unicode char via Windows hex Alt code (needs EnableHexNumpad=1 on the PC). */
    private static void altHex(String hex) {
        if (hex.isEmpty()) return;
        int saved = mods;
        mods = saved | 4; sendKbd(false);
        addKey(0x57); sendKbd(false); removeKey(0x57); sendKbd(false);
        for (char ch : hex.toLowerCase().toCharArray()) {
            int u = hexUsage(ch);
            if (u == 0) continue;
            addKey(u); sendKbd(false); removeKey(u); sendKbd(false);
        }
        mods = saved; sendKbd(true);
    }

    private static int modBit(String n) {
        switch (n) {
            case "ctrl": return 1;
            case "shift": return 2;
            case "alt": return 4;
            case "win": return 8;
            default: return 0;
        }
    }

    private static int namedKey(String n) {
        switch (n) {
            case "enter": return 40;
            case "esc": return 41;
            case "backspace": return 42;
            case "tab": return 43;
            case "space": return 44;
            case "delete": return 76;
            case "home": return 74;
            case "end": return 77;
            case "pgup": return 75;
            case "pgdn": return 78;
            case "right": return 79;
            case "left": return 80;
            case "down": return 81;
            case "up": return 82;
            default:
                if (n.length() >= 2 && n.charAt(0) == 'f') {
                    try {
                        int f = Integer.parseInt(n.substring(1));
                        if (f >= 1 && f <= 12) return 57 + f;
                    } catch (NumberFormatException e) { }
                }
                return 0;
        }
    }

    private static void keyAction(String action, String name) {
        int mb = modBit(name);
        int usage = mb != 0 ? 0 : namedKey(name);
        if (mb == 0 && usage == 0) return;
        switch (action) {
            case "tap":
                if (mb != 0) {
                    mods |= mb; sendKbd(false);
                    mods &= ~mb; sendKbd(true);
                } else {
                    addKey(usage); sendKbd(false);
                    removeKey(usage); sendKbd(true);
                }
                break;
            case "down":
                if (mb != 0) mods |= mb; else addKey(usage);
                sendKbd(false);
                break;
            case "up":
                if (mb != 0) mods &= ~mb; else removeKey(usage);
                sendKbd(true);
                break;
            default:
                break;
        }
    }
}
