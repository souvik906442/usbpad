#!/system/bin/sh
# hid.sh on|off|status   (run as root)
# on : phone becomes a USB keyboard + mouse (one HID function -> /dev/hidg0)
# off: back to normal USB (MTP) through "svc usb setFunctions mtp"
G=${G:-/config/usb_gadget/g1}
UDCDIR=${UDCDIR:-/sys/class/udc}
F=$G/functions/hid.usb0
DESC='\005\001\011\006\241\001\205\001\005\007\031\340\051\347\025\000\045\001\165\001\225\010\201\002\225\001\165\010\201\001\225\005\165\001\005\010\031\001\051\005\221\002\225\001\165\003\221\001\225\006\165\010\025\000\045\145\005\007\031\000\051\145\201\000\300\005\001\011\002\241\001\205\002\011\001\241\000\005\011\031\001\051\003\025\000\045\001\225\003\165\001\201\002\225\001\165\005\201\001\005\001\011\060\011\061\011\070\025\201\045\177\165\010\225\003\201\006\300\300'

UDC=$(ls "$UDCDIR" 2>/dev/null | grep -v dummy | head -1)
[ -d "$G" ] || { echo "ERROR: $G not found"; exit 1; }
[ -n "$UDC" ] || { echo "ERROR: no UDC found"; exit 1; }
C=$(ls -d "$G"/configs/*/ 2>/dev/null | head -1)
C=${C%/}
[ -d "$C" ] || { echo "ERROR: no gadget config in $G"; exit 1; }

unlinkall() { for l in "$C"/*; do [ -L "$l" ] && rm "$l"; done; return 0; }

on() {
  [ "$(printf '\101')" = "A" ] || { echo "ERROR: printf octal escapes not supported"; exit 1; }
  echo "" > "$G/UDC"
  unlinkall
  [ -d "$F" ] || mkdir "$F" || { echo "ERROR: mkdir hid function failed"; exit 1; }
  echo 0 > "$F/protocol"
  echo 0 > "$F/subclass"
  echo 9 > "$F/report_length"
  printf "$DESC" > "$F/report_desc" || { echo "ERROR: report_desc write failed"; exit 1; }
  ln -s "$F" "$C/hid1" || { echo "ERROR: link failed"; exit 1; }
  echo 0x1d6b > "$G/idVendor"
  echo 0x0104 > "$G/idProduct"
  echo 0 > "$G/bDeviceClass"
  echo 0 > "$G/bDeviceSubClass"
  echo 0 > "$G/bDeviceProtocol"
  sleep 1
  echo "$UDC" > "$G/UDC" 2>/dev/null
  i=0; st=""
  while [ $i -lt 12 ]; do
    st=$(cat "$UDCDIR/$UDC/state" 2>/dev/null)
    [ "$st" = "configured" ] && break
    sleep 1; i=$((i + 1))
  done
  echo "USB state: $st"
  for i in 1 2 3 4 5; do [ -e /dev/hidg0 ] && break; sleep 1; done
  if [ -e /dev/hidg0 ]; then
    chmod 666 /dev/hidg0
    echo "HID ON: /dev/hidg0 ready (keyboard=report 1, mouse=report 2)"
    [ "$st" = "configured" ] || echo "NOTE: PC has not configured the device yet - plug the cable into the PC"
  else
    echo "ERROR: bound but /dev/hidg0 missing"; exit 1
  fi
}

off() {
  echo "" > "$G/UDC"
  unlinkall
  rmdir "$F" 2>/dev/null
  command -v svc >/dev/null 2>&1 && svc usb setFunctions mtp
  sleep 2
  [ -z "$(cat "$G/UDC" 2>/dev/null)" ] && echo "$UDC" > "$G/UDC" 2>/dev/null
  echo "HID OFF. UDC: $(cat "$G/UDC" 2>/dev/null)  state: $(cat "$UDCDIR/$UDC/state" 2>/dev/null)"
  echo "If USB does not come back: reboot the phone."
}

case "$1" in
  on) on ;;
  off) off ;;
  status)
    echo "UDC: $(cat "$G/UDC")  state: $(cat "$UDCDIR/$UDC/state" 2>/dev/null)"
    ls -l "$C" | grep -- '->'
    ls -l /dev/hidg* 2>/dev/null ;;
  *) echo "usage: sh hid.sh on|off|status" ;;
esac
