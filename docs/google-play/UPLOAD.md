# Google Play upload preparation

## Current state

The owner confirmed that PortTracker will use Navisense’s existing developer account, also used for Heizungstrainer. The browser session checked on 14 October 2026 opens registration for a different Google account; switch to the existing publisher account. No account registration, payment, terms acceptance, app creation or upload has been performed by this work.

The project now has two distribution flavors:

- `play`: ARM32/ARM64 only, target API 36; APK self-update API disabled, update buttons replaced with a Google Play message; package-install permission and diagnostic launch components removed.
- `sideload`: retains the GitHub updater for direct installations.

Both currently use `com.porttracker.ais`, versionCode **31**, versionName **3.1.0**. Confirm the package before the first upload: Play package names cannot subsequently be changed. GitHub v30 uses a debug signing certificate, so production Play builds cannot update those existing installations in place. Preserve/export settings before a deliberate migration, or choose a separate store application ID before uploading.

## Account and signing

1. Open https://play.google.com/console/ with the Google account that has access to Navisense, and select the existing developer account. Do not register a second account.
2. Confirm whether an existing Play app/package or upload key exists under another Google account before creating a new key.
3. Configure an upload key outside Git. Copy `signing.properties.example` to `signing.properties`, fill values locally, and restrict its permissions (`chmod 600 signing.properties`). Do not send key passwords in chat or commit the keystore. Back up the key securely.
4. Enroll in Play App Signing when setting up the first release. The upload key authenticates uploads; Google manages the distributed app-signing key according to the selected setup.

Without signing.properties, release artifacts intentionally remain **unsigned and are not upload-ready**. There is no fallback to the debug certificate.

## Build and validate

```bash
./gradlew lintPlayDebug testPlayDebugUnitTest assemblePlayDebug bundlePlayRelease
# Upload candidate, only once production upload signing is configured:
# app/build/outputs/bundle/playRelease/app-play-release.aab

# Direct-download APK, independent of Play updates:
./gradlew assembleSideloadRelease
```

Toolchain: AGP 8.13.2, Gradle 8.14, compile/target SDK 36, NDK 28.2.13676358. Extracted native libraries are explicitly preserved because FRP executes from the native library directory. Check all bundled libraries for 16 KB alignment, and test a generated bundle APK on a 16 KB device/emulator before public release.

The Android 15+ dataSync timeout now stops the service; boot skips web-only startup when USB permission is unavailable. A foreground-start refusal stops cleanly rather than claiming success. These safeguards do not resolve the broader lifecycle issues listed in the release audit. USB reception must still be tested with hardware.

## First upload: internal testing

After creating the app in Play Console:

1. Confirm app name, default language, app type, and free/paid choice. Suggested name: **PortTracker AIS**. Do not finalize pricing without the owner's choice.
2. Open **Test and release → Testing → Internal testing**, create a release, configure Play App Signing, and upload the signed `.aab`.
3. Wait for Google's processing and review all compatibility/policy messages. Add release notes and a tester list, then review the intended rollout audience before starting the test.
4. Install from the Play testing link; this verifies Play-generated split APKs and signing, which an adb debug install does not.
5. Test no-SDR startup, USB permission and reception, repeated reconnect/restart, background operation, location granted/denied, network outages, MQTT reconnect, and tunnel authentication.

If using a personal developer account created after 13 November 2023, Google requires a closed test with at least 12 testers opted in continuously for 14 days before applying for production access. Organization accounts have a different verification path; check the Console's actual requirements.

## Information still required from the owner

- Access to the existing Navisense developer account (confirmed by the owner).
- Existing app/package/key status and final package/signing migration decision.
- Public support email and app-specific privacy policy URL.
- Free/paid choice, target countries, audience and content-rating answers.
- Accurate data-retention/deletion and collection/sharing declarations for the backend.
- Account-deletion URL/path if account creation is available through the app's sign-in flow.
- Reviewer access instructions and SDR hardware/testing explanation.

Do not claim "no data collected" without reviewing account identifiers, device/station identity, GPS and MQTT destinations. The store listing must not promise functional network receiver modes until those paths are implemented and tested.

## Official references

- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Target API requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [New personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [User Data and privacy policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

This is an internal-test preparation workflow, not confirmation that the app is ready for production review.

## API publishing after initial Console setup

Use the existing Navisense account (`carsten@navisense.de`). Google’s Edits API requires an existing app with an initial Console upload; legal consents remain in Console. After that, enable the Google Play Android Developer API in the selected Cloud project and use a service account invited to this app under Play Console → Users and permissions. Grant only app visibility and testing-release permissions for the internal-test workflow; production or billing access is unnecessary.

Keep the credential JSON outside the repository. An upload keystore is **not** a publishing API credential. No publishing API credential was found in the Heizungstrainer project inspection; its local upload keystore exists but has not been repurposed for PortTracker.

The automated sequence is: `edits.insert` → `edits.bundles.upload` → `edits.tracks.update` (`internal`, release status `draft`) → `edits.validate` → `edits.commit`. A draft does not roll out to testers. Avoid simultaneous Console edits during an API edit. Use the returned bundle version code, and never implicitly select production.

- [API access setup](https://developers.google.com/android-publisher/getting_started)
- [Edits and first-upload restriction](https://developers.google.com/android-publisher/edits)
- [Bundle upload](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload)

The prebuilt x86_64 tunnel executable has 4 KB ELF segment alignment. The initial Play flavor therefore supports ARM devices only; the sideload flavor retains all four ABIs. ARM native libraries have at least 16 KB LOAD alignment. Runtime testing on a 16 KB Android device is still required.

## Local verification (14 October 2026)

- Play release AAB, Play debug APK, Play unit tests and Play lint completed successfully; 3 tests passed, lint 0 errors / 54 warnings. Sideload debug also built successfully before the final layout adjustment.
- Installed Play debug 3.1.0 (31) over the existing debug build on the USB Samsung Android 16 phone. Confirmed unobscured WebView header, running service and HTTP 200 health endpoint.
- Play update-check endpoint returns 404; config reports sideload updates unavailable. Play merged manifest omits package-install permission; sideload retains it.
- Final Play bundle contains ARM32 and ARM64 native libraries only. It remains unsigned pending selection/configuration of the PortTracker upload key.
- No SDR reception, 16 KB runtime, foreground timeout duration, Play signing/delivery or API authentication test has been completed. No app has been uploaded or published.
