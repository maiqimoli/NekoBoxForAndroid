# MQ build notes

Date: 2026-05-02

## What changed

- Added SOCKS5 plain credential parsing for `host:port:username:password`.
- Added SOCKS5 plain credential parsing for bracketed IPv6 hosts:
  `[2001:db8::1]:1080:username:password`.
- Added auto-fill on the SOCKS settings page when that plain format is pasted into the server field.
- Added plain SOCKS5 parsing to clipboard/subscription import.
- Added chain profile hop details on profile cards.
- Chain hop testing now tests prefix chains:
  - hop 1: transit node only
  - hop 2: transit node -> landing node
- Chain hop tests use isolated temporary profiles, so group-level front/landing proxies are not mixed
  into the single-node or prefix-chain diagnostics.
- Chain hop tests clone profile beans before building temporary configs, avoiding transient runtime
  fields such as `finalAddress`/`finalPort` leaking back into the profile list.
- Chain hop result text keeps a trailing "testing" marker while later hops are still running.
- Chain hop testing logic was moved out of `ConfigurationFragment` into `ChainHopTester`.
- Added app language selection.
- Added optional automatic system time zone switching based on the effective exit node.
- Added automatic time zone region hints for Thailand, Malaysia, Vietnam, Philippines, and Netherlands.

## Version

- `nb4a.properties` `VERSION_NAME` is `1.4.2mq`.
- Debug APK package: `moe.nb4a.debug`.
- Release/original package: `moe.nb4a`.

## Verified

- `:app:compileOssDebugKotlin` passed.
- `:app:assembleOssDebug` passed.
- `:app:assembleOssRelease` passed, but generated unsigned release APKs because signing credentials were not available.
- Targeted unit test passed:
  `:app:testOssDebugUnitTest --tests io.nekohasekai.sagernet.fmt.socks.SOCKSFmtTest`.
- Installed debug APK on device `1a11b808`.
- Direct SOCKS5 connectivity to the Indonesian landing proxy worked.
- The tested Indonesian exit IP was `36.81.77.156`, country `ID`, city `Padang`.
- `:app:assembleOssDebug` still passes after the SOCKS IPv6 parser and auto-region matcher refinements.
- Installed the current arm64 debug APK on unlocked wireless device
  `adb-718b4b09-ooMRqv._adb-tls-connect._tcp`.
- Verified the existing 2-hop chain card expands with hop details and no text overlap on a 1080x2340
  device. A 3-5 hop chain was not present on-device, and no temporary profile data was created.

## Auto region behavior

- Group-level landing proxy is treated as the effective final exit for automatic time zone matching.
- Automatic time zone switching checks `SET_TIME_ZONE` before calling Android `AlarmManager`.
  Ordinary APK installs usually do not have this signature permission, so the feature will skip cleanly.
- Indonesia/Indonesian nodes are mapped to `Asia/Jakarta`.
- Chinese one-character region hints are intentionally not used, because node names often contain words
  such as `中转` that would otherwise be misclassified.

## Chain behavior

For a chain configured as:

```text
Japan transit -> Indonesia SOCKS landing
```

the UI stores the chain in visible order, while `ConfigBuilder` reverses it internally for sing-box so traffic is dialed as:

```text
local -> Japan transit -> Indonesia landing
```

The same applies to:

```text
US transit -> Indonesia SOCKS landing
```

Device database inspection showed:

- Japan -> Indonesia chain uses Japanese transit id `17` and Indonesian landing id `220`.
- US -> Indonesia chain uses US transit id `19` and the same Indonesian landing id `220`.

So when Japan -> Indonesia times out but US -> Indonesia works, the app logic and chain order are not the cause. The likely cause is one of:

- Japanese transit cannot reliably reach `global.rotgb.711proxy.com:10000`.
- The proxy provider assigns different Indonesian residential exits based on the transit source.
- A sticky session is bound differently depending on whether the source is Japan or US.

Recommended check: change only the SOCKS username session value and retest Japan -> Indonesia.

## Data migration note

Android app data is isolated by package name and signing certificate.

- Existing original package: `moe.nb4a`, signature observed on device: `fce245f0`.
- MQ debug package: `moe.nb4a.debug`, signature observed on device: `34fb139b`.
- The device has no `su` root access.
- `run-as moe.nb4a` is not allowed because the original package is not debuggable.

Because of that, the original account/subscription database cannot be copied directly into the MQ debug package with adb.

Two valid migration paths:

1. Build a signed MQ release APK with the same signing key as the installed original `moe.nb4a`, then install it with `adb install -r`. Android will preserve the original data.
2. In the original app, use `Tools -> Backup -> Share/Export`, then import that JSON in the MQ build through `Tools -> Backup -> Import from file`.

The first path requires these signing values:

```properties
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

They can be provided via `local.properties` or environment variables before building `:app:assembleOssRelease`.
