package libcore

import (
	"errors"
	"fmt"
	"net"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

// ValidateGeoIP verifies that path contains a readable sing-box GeoIP database.
// It is exported for gomobile callers that need to validate a downloaded database
// before replacing the active one.
func ValidateGeoIP(path string) (resultErr error) {
	reader, err := maxminddb.Open(path)
	if err != nil {
		return fmt.Errorf("open GeoIP database: %w", err)
	}
	defer func() {
		if err := reader.Close(); err != nil {
			resultErr = errors.Join(resultErr, fmt.Errorf("close GeoIP database: %w", err))
		}
	}()

	if reader.Metadata.DatabaseType != "sing-geoip" {
		return fmt.Errorf(
			"incorrect GeoIP database type: expected sing-geoip, got %q",
			reader.Metadata.DatabaseType,
		)
	}

	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	hasRecord := false
	for networks.Next() {
		var countryCode string
		if _, err := networks.Network(&countryCode); err != nil {
			return fmt.Errorf("read GeoIP record: %w", err)
		}
		if strings.TrimSpace(countryCode) != "" {
			hasRecord = true
		}
	}
	if err := networks.Err(); err != nil {
		return fmt.Errorf("iterate GeoIP records: %w", err)
	}
	if !hasRecord {
		return errors.New("GeoIP database contains no non-empty records")
	}

	return nil
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}
	if err := networks.Err(); err != nil {
		return nil, fmt.Errorf("failed to iterate networks: %w", err)
	}

	ipNets := countryMap[strings.ToLower(countryCode)]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		if err := WaitForCore(); err != nil {
			return nil, fmt.Errorf("wait for core initialization: %w", err)
		}
		g := new(geoip)
		if err := g.Open(filepath.Join(externalAssetsPath, "geoip.db")); err != nil {
			return nil, err
		}
		rules, rulesErr := g.Rules(name)
		closeErr := g.geoipReader.Close()
		return rules, errors.Join(rulesErr, closeErr)
	}
}
