# Dependency security scan

The report was generated with Google OSV-Scanner 2.5.1:

```text
osv-scanner scan source -r . --no-call-analysis go --all-vulns
```

The vulnerable Go dependencies in `libcore/go.mod` were upgraded on 2026-08-17.
The original scan found 38 vulnerabilities across 8 packages; the follow-up scan
confirms that all 37 advisories with published fixed versions are resolved.

One advisory remains: `GO-2026-5932` in `golang.org/x/crypto` 0.55.0. OSV does
not currently publish a fixed version for it. There are no remaining critical,
high, medium, or low findings. The detailed result is in
[osv-report.md](osv-report.md).

`GO-2026-5932` applies specifically to the deprecated `x/crypto/openpgp`
packages. A production-tag dependency walk of the 585 packages in libcore does
not include any `openpgp` package, so the affected API is not present in the
current native build. The module-level scanner still reports the advisory
because package reachability analysis is disabled.

The Go call-graph pass is disabled because this repository intentionally replaces
`github.com/matsuridayo/libneko` and `github.com/sagernet/sing-box` with sibling
source trees; the scanner cannot resolve those local modules without the external
upstream workspace. Package-version findings remain enabled.
