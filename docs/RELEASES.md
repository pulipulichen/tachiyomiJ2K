# APK release channels

Pushing code to `master` automatically builds and signs only ARM64 (`arm64-v8a`) and ARMv7 (`armeabi-v7a`). Download these APKs from the rolling [ARM development release](https://github.com/pulipulichen/tachiyomiJ2K/releases/tag/dev-arm-latest).

The Google Drive sync beta branch (`feature/google-drive-sync`) has its own rolling [ARM sync beta release](https://github.com/pulipulichen/tachiyomiJ2K/releases/tag/sync-arm-latest). This keeps the sync beta separate from the regular master build.

For a complete build, open Actions → **APKs - automatic ARM, manual full** → **Run workflow**, select the source branch, and enter a new version tag. A manual run creates a separate versioned prerelease with ARM64, ARMv7, x86, x86_64 and a universal APK. All APKs use the existing release signing certificate.

Automatic builds pass `-Ptachiyomi.build.abis=arm64-v8a,armeabi-v7a -Ptachiyomi.build.universal=false` to Gradle. These settings restrict both native ABI filters and APK splits; x86 and universal packages are excluded during the build. The workflow validates the generated variants before signing. Without these options, Gradle retains the complete four-ABI plus universal build.

Both modes run available release unit tests and include `SHA256SUMS` and the exact build's R8 `mapping.txt` in GitHub Releases. APKs are not uploaded to Actions artifact storage. Automatic runs for the same branch cancel an older automatic run; a manual full build has a separate concurrency group. The standalone cloud-sync compile check is manual to avoid repeating the automatic ARM build's compile/test work.
