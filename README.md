# LocalChat

Serverless chat rooms and file sharing for devices on the same local network — like LocalSend, with chat.

## Install

Download the latest installer from the [Releases](../../releases) page:

- **Windows:** `LocalChat-Setup-x.y.z.exe`. Windows SmartScreen may warn because the app is unsigned — click **More info → Run anyway**. Allow LocalChat through Windows Firewall on **Private networks** when prompted.
- **macOS:** `LocalChat-x.y.z.dmg`. The app is unsigned — right-click the app and choose **Open** the first time.

All devices must be on the same Wi-Fi/LAN. Discovery uses UDP port 53318.

## Develop

```bash
npm install
npm start
npm run start:second   # second instance with its own profile, for testing on one machine
```

## Release

Push a version tag and GitHub Actions builds the installers and attaches them to a release:

```bash
npm version patch
git push --follow-tags
```
