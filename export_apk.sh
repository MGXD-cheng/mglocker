#!/usr/bin/env bash
# ============================================================
# mglocker 构建 & 导出脚本
# 用法: bash export_apk.sh
# 产物: /sdcard/Download/mglocker<版本号>.apk
# 说明: 每次发布新版，改 app/build.gradle.kts 里的 versionName，
#       然后跑本脚本，产物自动带版本号导出到 Download 文件夹。
# ============================================================

# 加载 Android 环境（SDK 路径等）
[ -f "$HOME/.bashrc" ] && source "$HOME/.bashrc" 2>/dev/null
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

cd "$(dirname "$0")" || exit 1

# 从 build.gradle.kts 提取版本号
VERSION=$(grep 'versionName' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')
echo "[mglocker] 当前版本号: $VERSION"

# 构建 Debug APK
echo "[mglocker] 开始构建..."
./gradlew assembleDebug || { echo "[mglocker] 构建失败"; exit 1; }

APK="app/build/outputs/apk/debug/app-debug.apk"
NAME="mglocker${VERSION}.apk"

if [ ! -f "$APK" ]; then
    echo "[mglocker] 未找到 APK: $APK"
    exit 1
fi

# 导出到 Download 文件夹（兼容不同挂载路径）
copied=0
for dest in "/sdcard/Download" "/storage/emulated/0/Download"; do
    if [ -d "$dest" ]; then
        cp "$APK" "$dest/$NAME"
        echo "[mglocker] ✅ 已导出: $dest/$NAME"
        copied=1
    fi
done

if [ "$copied" -eq 0 ]; then
    echo "[mglocker] ⚠️ 未找到 Download 目录，产物仍在: $APK"
fi
