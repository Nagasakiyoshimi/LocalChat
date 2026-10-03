# LocalChat

Serverless chat rooms and file sharing for devices on the same local network — like LocalSend, with chat.

Works across **Windows**, **macOS**, and **Android** on the same Wi‑Fi.

## Install

Download the latest build from the [Releases](../../releases) page:

- **Windows:** `LocalChat-Setup-x.y.z.exe`. Windows SmartScreen may warn because the app is unsigned — click **More info → Run anyway**. Allow LocalChat through Windows Firewall on **Private networks** when prompted.
- **macOS:** `LocalChat-x.y.z.dmg`. The app is unsigned — right-click the app and choose **Open** the first time.
- **Android:** `LocalChat-v*.apk`. Open the file on your phone and allow installing from that source if asked. Grant notifications and local network / Wi‑Fi related prompts so devices can find each other.

All devices must be on the same Wi‑Fi/LAN. Discovery uses UDP port 53318.

## Develop

### Desktop

```bash
npm install
npm start
npm run start:second   # second instance with its own profile, for testing on one machine
```

### Android

Open the `android/` folder in Android Studio, or:

```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Release

Push a version tag and GitHub Actions builds Windows, macOS, and Android artifacts:

```bash
npm version patch
git push --follow-tags
```
