#!/system/bin/sh
# hid2.sh on|off|status|test   (run as root)
# Standard layout used by most Linux HID gadgets (works with Windows):
#   hid.usb0 = boot keyboard, 8-byte reports, no report id
#   hid.usb1 = boot mouse,    4-byte reports (buttons,x,y,wheel), no report id
G=${G:-/config/usb_gadget/g1}
UDCDIR=${UDCDIR:-/sys/class/udc}
K=$G/functions/hid.usb0
M=$G/functions/hid.usb1
KDESC='\005\001\011\006\241\001\005\007\031\340\051\347\025\000\045\001\165\001\225\010\201\002\225\001\165\010\201\003\225\005\165\001\005\010\031\001\051\005\221\002\225\001\165\003\221\003\225\006\165\010\025\000\045\145\005\007\031\000\051\145\201\000\300'
MDESC='\005\001\011\002\241\001\011\001\241\000\005\011\031\001\051\003\025\000\045\001\225\003\165\001\201\002\225\001\165\005\201\003\005\001\011\060\011\061\011\070\025\201\045\177\165\010\225\003\201\006\300\300'

die() { echo "ERROR: $*"; exit 1; }
UDC=$(ls "$UDCDIR" 2>/dev/null | grep -v dummy | head -1)
[ -d "$G" ] || die "$G not found"
[ -n "$UDC" ] || die "no UDC"
C=$(ls -d "$G"/configs/*/ 2>/dev/null | head -1)
C=${C%/}
[ -d "$C" ] || die "no gadget config"

unlinkall() { for l in "$C"/*; do [ -L "$l" ] && rm "$l"; done; return 0; }
nodes() {
  kn=$(cat "$K/dev" 2>/dev/null); mn=$(cat "$M/dev" 2>/dev/null)
  KBD=/dev/hidg${kn#*:}
  MOUSE=/dev/hidg${mn#*:}
}

on() {
  [ "$(printf '\101')" = "A" ] || die "printf octal escapes not supported"
  echo "" > "$G/UDC"
  unlinkall
  rmdir "$K" "$M" 2>/dev/null
  [ -d "$K" ] || mkdir "$K" || die "mkdir keyboard function failed"
  [ -d "$M" ] || mkdir "$M" || die "mkdir mouse function failed (endpoints?)"
  echo 1 > "$K/protocol"; echo 1 > "$K/subclass"; echo 8 > "$K/report_length"
  printf "$KDESC" > "$K/report_desc" || die "keyboard report_desc failed"
  echo 2 > "$M/protocol"; echo 1 > "$M/subclass"; echo 4 > "$M/report_length"
  printf "$MDESC" > "$M/report_desc" || die "mouse report_desc failed"
  ln -s "$K" "$C/hid1" || die "link keyboard failed"
  ln -s "$M" "$C/hid2" || die "link mouse failed"
  echo 0x1d6b > "$G/idVendor"
  echo 0x0108 > "$G/idProduct"      # new product id: Windows forgets old cached state
  echo 0 > "$G/bDeviceClass"; echo 0 > "$G/bDeviceSubClass"; echo 0 > "$G/bDeviceProtocol"
  echo 0x80 > "$C/bmAttributes" 2>/dev/null   # bus powered, no remote wakeup -> no selective suspend
  sleep 1
  echo "$UDC" > "$G/UDC" 2>/dev/null
  i=0; st=""
  while [ $i -lt 12 ]; do
    st=$(cat "$UDCDIR/$UDC/state" 2>/dev/null)
    [ "$st" = "configured" ] && break
    sleep 1; i=$((i + 1))
  done
  echo "USB state: $st"
  nodes
  for i in 1 2 3 4 5; do [ -e "$KBD" ] && [ -e "$MOUSE" ] && break; sleep 1; done
  [ -e "$KBD" ] && [ -e "$MOUSE" ] || die "bind failed or nodes missing (KBD=$KBD MOUSE=$MOUSE)"
  chmod 666 "$KBD" "$MOUSE"
  echo "KBD $KBD"
  echo "MOUSE $MOUSE"
  echo "HID2 ON"
}

off() {
  echo "" > "$G/UDC"
  unlinkall
  rmdir "$K" "$M" 2>/dev/null
  command -v svc >/dev/null 2>&1 && svc usb setFunctions mtp
  sleep 2
  [ -z "$(cat "$G/UDC" 2>/dev/null)" ] && echo "$UDC" > "$G/UDC" 2>/dev/null
  echo "HID2 OFF. UDC: $(cat "$G/UDC" 2>/dev/null)  state: $(cat "$UDCDIR/$UDC/state" 2>/dev/null)"
  echo "If USB does not come back: reboot the phone."
}

t() { # description, node, bytes
  timeout 2 sh -c "printf '$3' > $2"
  echo "$1 exit=$?"
}

test() {
  nodes
  echo "Open Notepad on the PC and keep it focused."
  t "mouse move 1" "$MOUSE" '\000\012\012\000'
  t "mouse move 2" "$MOUSE" '\000\012\012\000'
  t "mouse move 3" "$MOUSE" '\000\012\012\000'
  t "key 'a' down" "$KBD" '\000\000\004\000\000\000\000\000'
  t "key 'a' up  " "$KBD" '\000\000\000\000\000\000\000\000'
  echo "exit=0 means written, exit=124 means PC did not read."
}

case "$1" in
  on) on ;;
  off) off ;;
  test) test ;;
  status)
    echo "UDC: $(cat "$G/UDC")  state: $(cat "$UDCDIR/$UDC/state" 2>/dev/null)"
    ls -l "$C" | grep -- '->'
    nodes; echo "KBD $KBD  MOUSE $MOUSE"
    echo "bmAttributes: $(cat "$C/bmAttributes" 2>/dev/null)  MaxPower: $(cat "$C/MaxPower" 2>/dev/null)" ;;
  *) echo "usage: sh hid2.sh on|off|status|test" ;;
esac
