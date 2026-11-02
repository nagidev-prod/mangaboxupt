#!/usr/bin/env bash
# Local Android debug build for the SD card fork.
# Same steps as the (disabled) Android job in .github/workflows/main.yml,
# plus the local capacitor-plugin-sdstorage plugin.
# Needs: Node 22, JDK 21, Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT set).
#
# Optional environment:
#   MB_APP_ID / MB_APP_NAME           install next to the official app (e.g. com.zpaolo11x.mangabox.sd)
#   MB_VERSION_NAME / MB_VERSION_CODE  version shown by Android; the code must grow for updates
set -euo pipefail
cd "$(dirname "$0")"

# The plugin installs below are written to package.json (cap sync reads plugins from it),
# and the app id override edits capacitor.config.json: restore both afterwards.
cp package.json package.json.sdbuild-backup
cp capacitor.config.json capacitor.config.json.sdbuild-backup
trap 'mv package.json.sdbuild-backup package.json; mv capacitor.config.json.sdbuild-backup capacitor.config.json' EXIT

if [ -n "${MB_APP_ID:-}" ] || [ -n "${MB_APP_NAME:-}" ]; then
	node -e '
		const fs = require("fs");
		const c = JSON.parse(fs.readFileSync("capacitor.config.json", "utf8"));
		if (process.env.MB_APP_ID) c.appId = process.env.MB_APP_ID;
		if (process.env.MB_APP_NAME) c.appName = process.env.MB_APP_NAME;
		fs.writeFileSync("capacitor.config.json", JSON.stringify(c, null, "\t"));
	'
	if [ -d android ] && ! grep -q "applicationId \"${MB_APP_ID:-}\"" android/app/build.gradle; then
		echo "android/ was created with another app id, delete it and run again" >&2
		exit 1
	fi
fi

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
npm install @capacitor/status-bar@8 \
	capacitor-secure-storage-plugin@0.12.0 \
	@capacitor/camera@8 \
	@capacitor/filesystem@8 \
	@capawesome/capacitor-file-picker@8 \
	@capawesome/capacitor-android-edge-to-edge-support@8 \
	@capacitor/app@8 \
	@capacitor-community/volume-buttons@8 \
	./capacitor-plugin-sdstorage

if [ ! -d android ]; then
	echo "== cap add android"
	npx cap add android
	npx --yes @capacitor/assets generate --assetPath assets/icon-red --android
fi

echo "== cap sync android"
npx cap sync android
cp capacitor.AndroidManifest.xml android/app/src/main/AndroidManifest.xml

if [ -n "${MB_VERSION_NAME:-}" ]; then
	sed -i "s/versionName \".*\"/versionName \"$MB_VERSION_NAME\"/" android/app/build.gradle
fi
if [ -n "${MB_VERSION_CODE:-}" ]; then
	sed -i "s/versionCode [0-9]\+/versionCode $MB_VERSION_CODE/" android/app/build.gradle
fi

echo "== gradle assembleDebug"
(cd android && ./gradlew assembleDebug)

echo
echo "APK: $(pwd)/android/app/build/outputs/apk/debug/app-debug.apk"
