package libcore

import (
	"encoding/hex"
	"testing"
)

func TestHashHelpers(t *testing.T) {
	data := []byte("nekobox")

	if got := hex.EncodeToString(Sha1(data)); got != "5a00b19e39f33f49f10ec095c8aafd5eb6c33b0f" {
		t.Fatalf("Sha1() = %s", got)
	}
	if got := Sha256Hex(data); got != "d6ce7bc97919220a2b200aaf65411f8a450ff6c4111b2d67e2f88b5f6752901b" {
		t.Fatalf("Sha256Hex() = %s", got)
	}
}
