# RD Test – IDEMIA MSO 1300 E3 L1 RD Service tester

Small Android app to test Device Info and Capture with the
MSO 1300 E3 L1 RD Service (package com.idemia.l1rdservice).

## Build
1. Install Android Studio (latest) on a laptop.
2. File > Open > select this RDTest folder. Let Gradle sync
   (accept any prompt to use/download the Gradle wrapper).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK: app/build/outputs/apk/debug/app-debug.apk
5. Copy to the tablet and install (allow "Install unknown apps").
   Or connect tablet with USB debugging on and press Run.

## Use
- Environment: PP (pre-production) – must match the RD Service setting.
- Tap Device Info first -> status must be READY.
- Tap Capture -> place finger -> PASS means errCode 0 and encrypted PID data returned.
- Save log writes a .txt to Android/data/com.test.rdtest/files/

Note: this only tests the device + RD Service. Real Aadhaar authentication
needs the PID_DATA sent to UIDAI through your AUA/ASA backend.
