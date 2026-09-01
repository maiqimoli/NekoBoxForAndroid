package libcore

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestHttpClientHasTotalTimeout(t *testing.T) {
	client := NewHttpClient().(*httpClient)
	defer client.Close()
	if client.h1h2Client.Timeout != httpRequestTimeout {
		t.Fatalf("unexpected timeout: %v", client.h1h2Client.Timeout)
	}
	if client.h1h2Client.CheckRedirect == nil {
		t.Fatal("redirect policy is missing")
	}
}

func TestHTTPRedirectPolicy(t *testing.T) {
	tests := []struct {
		name    string
		target  *http.Request
		via     []*http.Request
		allowed bool
	}{
		{
			name:    "HTTPS to HTTPS",
			target:  redirectRequest("https://next.example/path"),
			via:     []*http.Request{redirectRequest("https://start.example/path")},
			allowed: true,
		},
		{
			name:    "HTTP loopback to loopback",
			target:  redirectRequest("http://127.0.0.2/path"),
			via:     []*http.Request{redirectRequest("http://localhost/path")},
			allowed: true,
		},
		{
			name:    "IPv6 loopback",
			target:  redirectRequest("http://[::1]/path"),
			allowed: true,
		},
		{
			name:   "loopback prefix domain",
			target: redirectRequest("http://127.0.0.1.example.com/path"),
		},
		{
			name:   "invalid IPv4 octet",
			target: redirectRequest("http://127.0.0.256/path"),
		},
		{
			name:   "HTTPS downgrade to loopback",
			target: redirectRequest("http://127.0.0.1/path"),
			via:    []*http.Request{redirectRequest("https://start.example/path")},
		},
		{
			name:   "opaque URL",
			target: &http.Request{URL: &url.URL{Scheme: "http", Opaque: "//127.0.0.1/path"}},
		},
		{
			name:   "empty host",
			target: redirectRequest("http:/path"),
		},
		{
			name:   "invalid scheme",
			target: redirectRequest("ftp://127.0.0.1/path"),
		},
		{
			name:   "nil request",
			target: nil,
		},
		{
			name:   "nil URL",
			target: &http.Request{},
		},
		{
			name:   "nil redirect history",
			target: redirectRequest("https://next.example/path"),
			via:    []*http.Request{nil},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := checkHTTPRedirect(test.target, test.via)
			if test.allowed {
				if err != nil {
					t.Fatalf("redirect rejected: %v", err)
				}
				return
			}
			if !errors.Is(err, errHTTPRedirectRejected) {
				t.Fatalf("redirect error = %v, want %v", err, errHTTPRedirectRejected)
			}
		})
	}
}

func TestHTTPRedirectPolicyPreservesTenHopLimit(t *testing.T) {
	via := make([]*http.Request, 10)
	for index := range via {
		via[index] = redirectRequest("https://redirect.example/path")
	}
	err := checkHTTPRedirect(redirectRequest("https://target.example/path"), via)
	if !errors.Is(err, errHTTPRedirectRejected) || !strings.Contains(err.Error(), "10 redirects") {
		t.Fatalf("redirect error = %v, want ten-hop rejection", err)
	}
}

func TestHTTPRedirectPolicyBlocksTLSDowngradeBeforeTargetRequest(t *testing.T) {
	var targetHits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		targetHits.Add(1)
		response.WriteHeader(http.StatusOK)
	}))
	defer target.Close()

	redirect := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		http.Redirect(response, request, target.URL, http.StatusFound)
	}))
	defer redirect.Close()

	client := NewHttpClient().(*httpClient)
	defer client.Close()
	request := client.NewRequest().(*httpRequest)
	request.AllowInsecure()
	if err := request.SetURL(redirect.URL); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if response != nil {
		t.Fatalf("unexpected response: %#v", response)
	}
	if !errors.Is(err, errHTTPRedirectRejected) {
		t.Fatalf("Execute error = %v, want %v", err, errHTTPRedirectRejected)
	}
	if hits := targetHits.Load(); hits != 0 {
		t.Fatalf("HTTP redirect target received %d requests", hits)
	}
}

func redirectRequest(rawURL string) *http.Request {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		panic(err)
	}
	return &http.Request{URL: parsed}
}

func TestSetURLStripsUserInfo(t *testing.T) {
	request := NewHttpClient().(*httpClient).NewRequest().(*httpRequest)
	if err := request.SetURL("https://alice:secret@example.com/sub"); err != nil {
		t.Fatal(err)
	}
	if request.request.URL.User != nil {
		t.Fatal("userinfo remains in request URL")
	}
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("alice:secret"))
	if got := request.request.Header.Get("Authorization"); got != want {
		t.Fatalf("unexpected Authorization header: %q", got)
	}
}

func TestReadAllLimited(t *testing.T) {
	if _, err := readAllLimited(bytes.NewReader([]byte("12345")), 4); err == nil {
		t.Fatal("expected oversized input error")
	}
	content, err := readAllLimited(bytes.NewReader([]byte("1234")), 4)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "1234" {
		t.Fatalf("unexpected content: %q", content)
	}
}

func TestWriteToPreservesExistingFileOnOversizedResponse(t *testing.T) {
	directory := t.TempDir()
	target := filepath.Join(directory, "asset.bin")
	if err := os.WriteFile(target, []byte("existing"), 0o600); err != nil {
		t.Fatal(err)
	}
	response := newHTTPResponse(&http.Response{
		Body:          io.NopCloser(strings.NewReader("12345")),
		ContentLength: -1,
	}, 4)
	if err := response.WriteTo(target); err == nil {
		t.Fatal("expected oversized response error")
	}
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "existing" {
		t.Fatalf("existing target changed: %q", content)
	}
}

func TestWriteToAllowsExplicitStreamingResponse(t *testing.T) {
	target := filepath.Join(t.TempDir(), "asset.bin")
	response := newHTTPResponse(&http.Response{
		Body:          io.NopCloser(strings.NewReader("download")),
		ContentLength: int64(len("download")),
	}, 0)
	if err := response.WriteTo(target); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "download" {
		t.Fatalf("unexpected target content: %q", content)
	}
}

func TestWriteToReplacesExistingFile(t *testing.T) {
	directory := t.TempDir()
	target := filepath.Join(directory, "asset.bin")
	if err := os.WriteFile(target, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	response := newHTTPResponse(&http.Response{
		Body:          io.NopCloser(strings.NewReader("new")),
		ContentLength: int64(len("new")),
	}, maxHTTPResponseBytes)
	if err := response.WriteTo(target); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "new" {
		t.Fatalf("unexpected target content: %q", content)
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != filepath.Base(target) {
		t.Fatalf("temporary publication files remain: %v", entries)
	}
}

func TestCloneRequestUsesIndependentBodies(t *testing.T) {
	request := NewHttpClient().(*httpClient).NewRequest().(*httpRequest)
	request.SetContentString("payload")

	first, err := request.cloneRequest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer first.Body.Close()
	second, err := request.cloneRequest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer second.Body.Close()

	firstContent, err := io.ReadAll(first.Body)
	if err != nil {
		t.Fatal(err)
	}
	secondContent, err := io.ReadAll(second.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(firstContent) != "payload" || string(secondContent) != "payload" {
		t.Fatalf("cloned bodies = %q and %q", firstContent, secondContent)
	}
}

func TestExecuteReturnsRoundTripperPanic(t *testing.T) {
	client := NewHttpClient().(*httpClient)
	client.h1h2Client.Transport = panicRoundTripper{}
	request := client.NewRequest().(*httpRequest)
	if err := request.SetURL("https://example.invalid/"); err != nil {
		t.Fatal(err)
	}

	response, err := request.Execute()
	if response != nil {
		t.Fatalf("unexpected response: %#v", response)
	}
	if err == nil || !strings.Contains(err.Error(), "http execute panic: round trip panic") {
		t.Fatalf("Execute error = %v, want recovered round trip panic", err)
	}
}

type panicRoundTripper struct{}

func (panicRoundTripper) RoundTrip(*http.Request) (*http.Response, error) {
	panic("round trip panic")
}
