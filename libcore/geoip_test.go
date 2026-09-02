package libcore

import (
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/ulikunitz/xz"
)

func TestValidateGeoIP(t *testing.T) {
	fixturePath := filepath.Join("..", "app", "src", "main", "assets", "sing-box", "geoip.db.xz")
	compressed, err := os.Open(fixturePath)
	if err != nil {
		t.Fatalf("open compressed GeoIP fixture: %v", err)
	}
	defer compressed.Close()

	decompressor, err := xz.NewReader(compressed)
	if err != nil {
		t.Fatalf("create GeoIP fixture decompressor: %v", err)
	}
	fixture := filepath.Join(t.TempDir(), "geoip.db")
	output, err := os.Create(fixture)
	if err != nil {
		t.Fatalf("create decompressed GeoIP fixture: %v", err)
	}
	if _, err = io.Copy(output, decompressor); err != nil {
		output.Close()
		t.Fatalf("decompress GeoIP fixture: %v", err)
	}
	if err = output.Close(); err != nil {
		t.Fatalf("close decompressed GeoIP fixture: %v", err)
	}

	if err := ValidateGeoIP(fixture); err != nil {
		t.Fatalf("ValidateGeoIP() rejected the bundled fixture: %v", err)
	}
}

func TestValidateGeoIPRejectsCorruptFile(t *testing.T) {
	fixture := filepath.Join(t.TempDir(), "geoip.db")
	if err := os.WriteFile(fixture, []byte("not a MaxMind database"), 0o600); err != nil {
		t.Fatalf("write corrupt GeoIP fixture: %v", err)
	}

	if err := ValidateGeoIP(fixture); err == nil {
		t.Fatal("ValidateGeoIP() accepted a corrupt database")
	}
}
