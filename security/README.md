# Dependency security scan

The report was generated with Google OSV-Scanner 2.5.1:

```text
osv-scanner scan source -r . --no-call-analysis go --all-vulns
```

The scan found 38 known Go vulnerabilities across 8 packages in `libcore/go.mod`:
8 critical, 3 high, 8 medium, and 19 with unknown severity. 37 have a published
fixed version. The detailed result is in [osv-report.md](osv-report.md).

The Go call-graph pass is disabled because this repository intentionally replaces
`github.com/matsuridayo/libneko` and `github.com/sagernet/sing-box` with sibling
source trees; the scanner cannot resolve those local modules without the external
upstream workspace. The package-version findings remain enabled and should be
reviewed before a release build.
