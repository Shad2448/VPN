# OpenShield VPN — Cloud APK Build Guide (No Android Studio Required)

You can compile this project into an installable `app-debug.apk` directly in your mobile or desktop browser using GitHub's free cloud build servers:

1. **Create a free GitHub repository**: Go to [github.com/new](https://github.com/new), name your repository `openshield-vpn`, and tap **Create repository**.
2. **Upload these extracted project files**: Unzip `OpenShield_VPN_Cloud_APK_Builder.zip`, tap **uploading an existing file** on your new GitHub repository page, drag or select all the extracted files/folders (including `.github/workflows/build-apk.yml`, `app/`, `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`), and tap **Commit changes**.
3. **Download your APK**: Open the **Actions** tab in your GitHub repository, click the **Build Android APK** workflow run, and once it finishes (~2 minutes), scroll down to **Artifacts** and tap **OpenShield-VPN-Debug-APK** to download your installable `.apk` file.
