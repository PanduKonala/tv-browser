#!/bin/sh
# Build and install on one or more TVs:  ./install.sh 192.168.88.8 192.168.88.6
# Refresh the filter lists first with:   python3 tools/build_filters.py
set -e
cd "$(dirname "$0")"
TV=../cleanyourtv/tools
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
for host in "$@"; do
  echo "== $host"
  # b64push: plain adb push times out on these TVs over Wi-Fi
  TV_HOST=$host "$TV/venv/bin/python" "$TV/b64push.py" "$APK" /data/local/tmp/clear.apk | tail -c 120
  printf 'pm install -r /data/local/tmp/clear.apk\nrm /data/local/tmp/clear.apk\n' |
    TV_HOST=$host "$TV/venv/bin/python" "$TV/tv.py" --batch
done
