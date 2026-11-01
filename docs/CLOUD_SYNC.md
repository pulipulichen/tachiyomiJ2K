# Google Drive sync beta

The sync-enabled APK is built from `feature/google-drive-sync`, separately from the regular `master` development build. The second beta displays version `1.8.1-sync02` (version code 114).

Open Settings → Google Drive sync and connect an account. Account verification displays a progress indicator. When authorization succeeds, the settings screen displays the connected account, enables its controls, and queues the first synchronization automatically. The page observes both account preferences and WorkManager so returning from Google authorization or completing background work updates the existing screen.

The sync status shows queued work (including waiting for connectivity/background execution), authorization, account verification, cloud download with file counts, local preparation, upload, merge, completion, and retry/failure. The last error remains visible separately from the last successful sync time. A waiting periodic interval is idle and does not disable the manual sync button.

Automatic two-way sync runs approximately every 15 minutes subject to Android background restrictions. Switching it off still permits a manual Sync now. While a one-time request is queued or work is running, Sync now is temporarily disabled to prevent duplicate requests; reconnect, the automatic switch, and disconnect remain available. A failed request waiting to retry can be retried immediately with Sync now.

Only account identity and sync metadata are stored in preferences; access tokens remain in memory. Workers request authorization for the already connected account. The Drive scope remains `drive.appdata`.

## Validation

Run `./gradlew :app:testStandardReleaseUnitTest assembleStandardRelease`. Unit tests cover periodic idle state, queued work, running-work precedence, file progress, retry availability, and finished/cancelled work. Both automatic ARM and manual full release builds run these tests before producing signed APKs and retain the matching R8 mapping file. See [APK release channels](RELEASES.md).

Device checks still require Google Play services and the user's account: authorize, verify that controls and account information refresh without reopening settings, watch the first sync stages, turn automatic sync off and run a manual sync, disconnect, reconnect, and verify progress against another device. No two-device runtime validation is claimed by the JVM tests.
