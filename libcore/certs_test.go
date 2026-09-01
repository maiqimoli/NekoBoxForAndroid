package libcore

import "testing"

func TestUpdateRootCACertsRejectsInvalidPEM(t *testing.T) {
	if err := updateRootCACerts([]byte("not a PEM certificate")); err == nil {
		t.Fatal("expected invalid PEM to be rejected")
	}
}
