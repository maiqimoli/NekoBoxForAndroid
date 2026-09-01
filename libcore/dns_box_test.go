package libcore

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
)

type successfulLookupTransport struct{}

func (successfulLookupTransport) Raw() bool {
	return false
}

func (successfulLookupTransport) NetworkHandle() int64 {
	return 0
}

func (successfulLookupTransport) Lookup(ctx *ExchangeContext, network string, domain string) error {
	ctx.Success("192.0.2.1\n2001:db8::1")
	return nil
}

func (successfulLookupTransport) Exchange(ctx *ExchangeContext, message []byte) error {
	return nil
}

func TestPlatformLocalDNSLookupSuccessCompletes(t *testing.T) {
	transport := newPlatformTransport(successfulLookupTransport{}, "local", option.LocalDNSServerOptions{})
	message := new(dns.Msg)
	message.SetQuestion("example.com.", dns.TypeA)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	response, err := transport.Exchange(ctx, message)
	if err != nil {
		t.Fatal(err)
	}
	if len(response.Answer) != 1 {
		t.Fatalf("answer count = %d, want 1", len(response.Answer))
	}
	answer, ok := response.Answer[0].(*dns.A)
	if !ok {
		t.Fatalf("answer type = %T, want *dns.A", response.Answer[0])
	}
	if got := answer.A.String(); got != "192.0.2.1" {
		t.Fatalf("answer address = %q, want %q", got, "192.0.2.1")
	}
}

func TestPlatformLocalDNSRejectsQuestionlessMessage(t *testing.T) {
	transport := newPlatformTransport(successfulLookupTransport{}, "local", option.LocalDNSServerOptions{})
	if _, err := transport.Exchange(context.Background(), new(dns.Msg)); err == nil {
		t.Fatal("expected a questionless DNS message to be rejected")
	}
}

func TestExchangeContextSuccessRejectsInvalidAddress(t *testing.T) {
	done := make(chan struct{})
	response := &ExchangeContext{
		context: context.Background(),
		done:    func() { close(done) },
	}
	response.Success("192.0.2.1\nnot-an-ip")
	<-done

	if response.error == nil || !strings.Contains(response.error.Error(), "invalid DNS address") {
		t.Fatalf("Success error = %v, want invalid address error", response.error)
	}
	if response.addresses != nil {
		t.Fatalf("Success retained partial addresses: %v", response.addresses)
	}
}
