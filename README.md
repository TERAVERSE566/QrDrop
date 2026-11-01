# QR Drop (v1)

Android app: pick a big file, show a QR, receiver scans it and downloads straight from your phone.

## Build the APK (Android Studio)
1. Install Android Studio (Hedgehog or newer; it bundles JDK 17).
2. File > Open > select this QRDrop folder. Let Gradle sync finish (needs internet once).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Click "locate" in the popup: app/build/outputs/apk/debug/app-debug.apk
5. Copy the APK to your phone and install (allow "install unknown apps").

## Use it
- Sender: Choose file > Start sharing > show the QR.
- Receiver on same Wi-Fi: scan with the app (Scan a QR) or any camera app, a browser opens, tap Download.
- No Wi-Fi? Sender turns on hotspot (button opens settings), receiver joins it, sender taps Refresh address.

## Build the APK on GitHub (no Android Studio)
1. Create a new repo on GitHub and upload everything in this folder, including the hidden .github folder.
2. Open the repo's Actions tab. The "Build APK" workflow runs on every push (or press Run workflow).
3. When it turns green (about 3-5 minutes), open the run and download the QRDrop-apk artifact (a zip containing app-debug.apk).
