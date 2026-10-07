# AppForge 4.3

- AppForge is landscape-only; the rest of BountyPilot remains portrait.
- AppForge no longer launches Termux automatically when BUILD is pressed.
- Generated projects are stored under the app's private `appforge_projects` directory.
- BUILD PROJECT validates the generated project and creates build instructions instead of redirecting the user.
- EXPORT PROJECT TO DOWNLOADS creates a ZIP in Android Downloads and offers the standard Android share sheet.
- COPY PROJECT PATH copies the local project path for use with Code on the Go or another build environment.
- BUILD SETUP explains the current toolchain boundary.
- Android APK compilation still requires an Android/Gradle build engine such as Code on the Go; Android cannot bundle every compiler/runtime into this APK.
