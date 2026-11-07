# UsbPad HID — root দিয়ে ফোনকে USB keyboard + touchpad বানাও (Nothing Phone 1)

PC-তে কিছু install করতে হয় না। ফোন নিজেই USB HID device হয়ে যায় (configfs gadget, দুটো HID function: keyboard = hid.usb0, mouse = hid.usb1)।

## দরকার
- Root (KernelSU/Magisk), UsbPad HID app-কে root permission দিতে হবে।
- Kernel-এ `CONFIG_USB_CONFIGFS_F_HID=y` (NP1-এ আছে)।

## চালানো
1. APK install করো, app খোলো, root permission দাও।
2. **HID ON** চাপো (~১০ সেকেন্ড)। "HID2 ON", `KBD /dev/hidgX`, `MOUSE /dev/hidgY` আসবে।
3. Cable দিয়ে PC-তে লাগাও (সরাসরি port-এ, hub নয়)।
4. উপরের লাইনে `sent=… dropped=… PC-LED-reports=…` দেখাবে।
5. কাজ শেষে **HID OFF** চাপো। USB না ফিরলে `su -c "svc usb setFunctions mtp"`, না হলে reboot।

HID চলাকালীন MTP/ADB থাকে না।

## Gesture
এক আঙুল = move, tap = left click, দুই আঙুলে tap = right click, দুই আঙুল উপর/নিচ = scroll,
tap করে সাথে সাথে আবার touch করে সরালে = drag।

## বুঝবে কীভাবে PC পড়ছে
- `sent` বাড়ে আর `dropped` 0 থাকে = PC report নিচ্ছে।
- `dropped` বাড়ে = PC report পড়ছে না (ফোন hang হয় না, report ফেলে দেয়)।
- PC-র keyboard-এ Caps Lock চাপলে `PC-LED-reports` বাড়লে Windows-এর driver ফোনের সাথে কথা বলছে।

## সীমাবদ্ধতা
- শুধু US-layout ASCII অক্ষর যায়। Bengali লেখা যাবে না।
- BIOS-এ কাজ করবে না (boot-protocol keyboard নয়)।
- আগের adb + server.py পথ (Link.kt, server/) আর ব্যবহার হয় না।

## দুটো mode (app-এর উপরের "Mode" বোতামে বদলানো যায়)

- **Mode: HID (root)** - root + kernel HID gadget। PC-তে কিছু চালাতে হয় না, login screen-এও কাজ করে।
  "HID ON" চাপো, তারপর cable PC-তে লাগাও। শেষে "HID OFF"।
- **Mode: ADB** - root লাগে না। PC-তে `server/server.py` চালাও (adb reverse নিজে লাগায়),
  ফোনে USB debugging ON। Windows login/lock screen ও UAC-তে কাজ করে না।

ADB mode-এ যেতে HID চালু থাকলে আগে "HID OFF" দাও (HID চলাকালীন ফোনে adb থাকে না)।
শেষ ব্যবহৃত mode মনে থাকে।

### ADB mode: cable ছাড়া (Wireless debugging, Android 11+)
1. ফোন আর PC একই Wi-Fi-তে। ফোনে Developer options -> Wireless debugging ON।
2. "Pair device with pairing code" -> PC-তে: `adb pair IP:PAIRPORT` (code দাও)।
3. Wireless debugging-এর মূল পর্দায় দেখানো IP:PORT দিয়ে: `adb connect IP:PORT`
4. `adb devices` -> `device` দেখালে `python server\server.py` চালাও।
USB cable আর wireless একসাথে থাকলে adb "more than one device" বলে; একটা রাখো।
