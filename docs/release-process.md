# Release process

The release and preview workflows require an explicit signing key and fail before building when
the signing configuration is incomplete. GitHub release publishing is disabled by default.

## Repository secrets

Configure these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`: the release keystore encoded as base64 without line wrapping.
- `KEYSTORE_PASS`: the release keystore password.
- `ALIAS_NAME`: the release signing key alias.
- `ALIAS_PASS`: the release signing key password.

Configure this GitHub Actions repository variable:

- `RELEASE_CERT_SHA256`: the 64-hex-character SHA-256 fingerprint of the expected release
  signing certificate. Colons and whitespace are accepted and normalized by the workflows.

The workflows decode the keystore into `RUNNER_TEMP` and pass its location through
`KEYSTORE_PATH`. The keystore is not restored inside the checkout.

Example command for encoding the keystore:

```bash
base64 -w 0 release.keystore.local
```

PowerShell equivalent:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore.local"))
```

For local signed builds, keep the key at the ignored `release.keystore.local` path or set
`KEYSTORE_PATH` to another location. The legacy ignored `release.keystore` filename remains a
fallback for existing local setups. Put the three password properties in the ignored
`local.properties` file or environment variables, then run:

```bash
./gradlew -PrequireReleaseSigning=true app:assembleOssRelease
```

## Release identity

Before dispatching `release.yml`:

1. Set `VERSION_NAME` in `nb4a.properties`.
2. Create and push a tag named either `VERSION_NAME` or `vVERSION_NAME` at the exact release
   commit.
3. Dispatch the workflow from that commit and enter the same tag.
4. Leave `publish` disabled for a build-only verification run. Enable it only when the verified
   artifacts should create a new GitHub Release.

The workflow rejects missing tags, version mismatches, commit mismatches, unsigned APKs, signer
mismatches, incomplete ABI sets, manifest identity mismatches, and existing GitHub Releases. It
publishes APK SHA-256 checksums with the release.

## Signing key hygiene

`release.keystore` was previously tracked by Git. Removing it from the current index does not
remove it from existing commits. Treat that signing material and its passwords as exposed:

1. Rotate the signing material before the next publication and update all signing secrets.
2. Update `RELEASE_CERT_SHA256` to the certificate expected for the resulting artifacts.
3. Remove the keystore blob from repository history and invalidate old clones or cached artifacts
   that contain it.

Coordinate certificate rotation with the app distribution channel before publishing because
Android updates must be signed by a certificate accepted for the installed application.

## Pinned routing assets

`buildScript/lib/assets.sh` pins the GeoIP and Geosite release versions and SHA-256 digests.
Update both values together after checking the release asset digest, then run the script locally.
The script downloads into a temporary directory, verifies both files, and only then replaces the
generated asset directory.
