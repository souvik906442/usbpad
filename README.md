# UsbPad — ফোনকে USB cable দিয়ে Windows PC-র touchpad + keyboard বানাও (root লাগে না)

## PC-তে (একবার)
1. Python 3 install করো।
2. Android platform-tools (adb) install করে PATH-এ রাখো।
3. ফোনে Developer options → USB debugging ON।
4. `python server\server.py` চালাও (Admin হিসেবে চালালে elevated window-এও কাজ করবে)।
   এটা নিজেই `adb reverse tcp:5555 tcp:5555` লাগিয়ে নেয়, cable খুলে আবার লাগালেও।

## ফোনে
- APK বানাও: repo GitHub-এ push করো → Actions → "Build APK" → artifact `usbpad-debug-apk`
  (অথবা Android Studio-তে `android/` folder খুলে Run)।
- `adb install app-debug.apk`, তারপর UsbPad খোলো। উপরে "● Connected" দেখালে ready।

## Gesture
| কাজ | কীভাবে |
|---|---|
| Mouse move | এক আঙুল সরাও |
| Left click | এক আঙুলে tap |
| Right click | দুই আঙুলে tap |
| Scroll | দুই আঙুল উপর/নিচ |
| Drag | tap করে সাথে সাথে আবার touch করে সরাও |
| Ctrl/Alt/Shift/Win | button চাপলে latch হয়, আবার চাপলে ছাড়ে (Ctrl + Keyboard-এ `c` = copy) |

## Protocol (server.py)
`M dx dy`, `W delta`, `D|U|C L|R|M`, `T text`, `K tap|down|up name`, `R`

## সীমাবদ্ধতা
- Windows lock/login screen বা UAC prompt-এ কাজ করবে না (এজন্যই পরে root HID gadget)।
- Sensitivity/scroll speed বদলাতে `TouchpadView.kt`-এর `sensitivity` আর `scrollStep`।
- Scroll উল্টো লাগলে `W ${steps * 120}`-এর চিহ্ন উল্টে দাও।
