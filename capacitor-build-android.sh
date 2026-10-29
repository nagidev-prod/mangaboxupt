#!/usr/bin/env bash
# Local Android debug build for the SD card fork.
# Same steps as the (disabled) Android job in .github/workflows/main.yml,
# plus the local capacitor-plugin-sdstorage plugin.
# Needs: Node 22, JDK 21, Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT set).
set -euo pipefail
cd "$(dirname "$0")"

# The plugin installs below are written to package.json (cap sync reads plugins from it),
# restore it afterwards so the fork stays clean.
cp package.json package.json.sdbuild-backup
trap 'mv package.json.sdbuild-backup package.json' EXIT

echo "== Web bundle -> dist/mangabox"
rm -rf dist/mangabox
mkdir -p dist/mangabox
rsync -a --exclude='.*' \
	--exclude='*.yml' \
	--exclude='package*.*' \
	--exclude='electron/' \
	--exclude='stores/' \
	--exclude='assets/' \
	--exclude='z_*' \
	--exclude='docs/' \
	--exclude='docs_old/' \
	--exclude='capacitor*' \
	--exclude='dist/' \
	--exclude='node_modules/' \
	--exclude='android/' \
	--exclude='ios/' \
	--exclude='build/' \
	./ dist/mangabox

echo "== npm install"
npm install
npm install @capacitor/status-bar@7 \
	capacitor-secure-storage-plugin@0.12.0 \
	@capacitor/camera@7 \
	@capacitor/filesystem@7 \
	@capawesome/capacitor-file-picker@7 \
	@capawesome/capacitor-android-edge-to-edge-support@7 \
	@ethion/capacitor-navigation-bar@7 \
	@capacitor/app@7 \
	@capacitor-community/volume-buttons@7 \
	./capacitor-plugin-sdstorage

if [ ! -d android ]; then
	echo "== cap add android"
	npx cap add android
	npx --yes @capacitor/assets generate --assetPath assets/icon-red --android
fi

echo "== cap sync android"
npx cap sync android
cp capacitor.AndroidManifest.xml android/app/src/main/AndroidManifest.xml

echo "== gradle assembleDebug"
(cd android && ./gradlew assembleDebug)

echo
echo "APK: $(pwd)/android/app/build/outputs/apk/debug/app-debug.apk"
