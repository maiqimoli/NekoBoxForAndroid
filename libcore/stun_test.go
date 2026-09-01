package libcore

import (
	"strings"
	"testing"
)

func TestStunTestReportsBothInvalidServerErrors(t *testing.T) {
	result := StunTest("127.0.0.1:not-a-port")
	if result.Success {
		t.Fatal("invalid STUN server unexpectedly succeeded")
	}
	if !strings.Contains(result.Text, "Discover Error:") {
		t.Fatalf("result does not include discovery error: %q", result.Text)
	}
	if !strings.Contains(result.Text, "BehaviorTest Error:") {
		t.Fatalf("result does not include behavior error: %q", result.Text)
	}
}
