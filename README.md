# LocalChat

Serverless chat rooms and file sharing for devices on the same local network — like LocalSend, with chat.

Works across **Windows**, **macOS**, and **Android**. Devices on the same Wi‑Fi see each other automatically. An invite code also lets someone on the internet join a room.

## Invite codes

1. Tap **+** and name the room. LocalChat shows a code such as `K7M2PQ4H`.
2. Send that code to the other person.
3. They tap **Join** (or **Join with a code**) and enter it.

The code keeps working after the app restarts. Messages in that room are saved on each device, and recent text is replayed when someone joins. Files sent with a code can be up to 8 MB.

The invite server is a small relay (`npm run relay`). The current public address is in `relay/url.txt`. That address is a tunnel from this Mac, so the Mac has to stay awake and the relay has to keep running or new joins will fail until the tunnel is started again.

## Install

Download the latest build from the [Releases](../../releases) page:

- **Windows:** `LocalChat-Setup-x.y.z.exe`. Windows SmartScreen may warn because the app is unsigned — click **More info → Run anyway**. Allow LocalChat through Windows Firewall on **Private networks** when prompted.
- **macOS:** `LocalChat-x.y.z.dmg`. The app is unsigned — right-click the app and choose **Open** the first time.
- **Android:** `LocalChat-x.y.z.apk`. Open the file on your phone and allow installing from that source if asked. Grant notifications when asked. If something fails, open **Debug log** at the bottom of the home screen and tap **Share**.

All devices must be on the same Wi‑Fi/LAN for nearby chat. Discovery uses UDP port 53318.

After this version, LocalChat checks GitHub for a newer release when it starts. Tap **Update** to download it. On Android, allow installs from LocalChat if the phone asks. On Mac, open the downloaded disk image and replace the old app.

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
