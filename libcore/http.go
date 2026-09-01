package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

const (
	httpRequestTimeout        = 60 * time.Second
	largeHTTPResponseTimeout  = 15 * time.Minute
	maxHTTPResponseBytes      = int64(64 << 20)
	maxLargeHTTPResponseBytes = int64(512 << 20)
)

var (
	errHTTPResponseTooLarge = errors.New("HTTP response exceeds configured limit")
	errHTTPRedirectRejected = errors.New("HTTP redirect rejected")
)

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	AllowLargeResponse()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client = newPolicyHTTPClient(&client.h1h2Transport, httpRequestTimeout)
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	client.h1h2Transport.DialContext = (&net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
	}).DialContext
	client.h1h2Transport.TLSHandshakeTimeout = 10 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 20 * time.Second
	return client
}

func newPolicyHTTPClient(transport http.RoundTripper, timeout time.Duration) http.Client {
	return http.Client{
		Transport:     transport,
		Timeout:       timeout,
		CheckRedirect: checkHTTPRedirect,
	}
}

func checkHTTPRedirect(request *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return fmt.Errorf("%w: stopped after 10 redirects", errHTTPRedirectRejected)
	}

	scheme, host, err := checkedHTTPRedirectURL(request)
	if err != nil {
		return err
	}

	usedHTTPS := false
	for _, previous := range via {
		previousScheme, _, err := checkedHTTPRedirectURL(previous)
		if err != nil {
			return err
		}
		usedHTTPS = usedHTTPS || previousScheme == "https"
	}

	if scheme == "https" {
		return nil
	}
	if usedHTTPS {
		return fmt.Errorf("%w: HTTPS downgrade to HTTP", errHTTPRedirectRejected)
	}
	if !isAllowedRedirectHTTPHost(host) {
		return fmt.Errorf("%w: HTTP redirect host %q is not loopback", errHTTPRedirectRejected, host)
	}
	return nil
}

func checkedHTTPRedirectURL(request *http.Request) (scheme string, host string, err error) {
	if request == nil || request.URL == nil {
		return "", "", fmt.Errorf("%w: missing URL", errHTTPRedirectRejected)
	}
	if request.URL.Opaque != "" {
		return "", "", fmt.Errorf("%w: opaque URL", errHTTPRedirectRejected)
	}
	scheme = strings.ToLower(request.URL.Scheme)
	if scheme != "http" && scheme != "https" {
		return "", "", fmt.Errorf("%w: unsupported scheme %q", errHTTPRedirectRejected, request.URL.Scheme)
	}
	host = strings.ToLower(request.URL.Hostname())
	if host == "" {
		return "", "", fmt.Errorf("%w: missing host", errHTTPRedirectRejected)
	}
	return scheme, host, nil
}

func isAllowedRedirectHTTPHost(host string) bool {
	if host == "localhost" || host == "::1" {
		return true
	}
	parts := strings.Split(host, ".")
	if len(parts) != 4 {
		return false
	}
	for index, part := range parts {
		if part == "" {
			return false
		}
		value := 0
		for _, digit := range []byte(part) {
			if digit < '0' || digit > '9' {
				return false
			}
			value = value*10 + int(digit-'0')
			if value > 255 {
				return false
			}
		}
		if index == 0 && value != 127 {
			return false
		}
	}
	return true
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			if err != nil {
				_ = socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request            http.Request
	allowLargeResponse bool
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) AllowLargeResponse() {
	r.allowLargeResponse = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if (r.request.URL.Scheme != "http" && r.request.URL.Scheme != "https") || r.request.URL.Hostname() == "" {
		return errors.New("URL must use HTTP or HTTPS and include a host")
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
		r.request.URL.User = nil
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	payload := append([]byte(nil), content...)
	r.request.GetBody = func() (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(payload)), nil
	}
	r.request.Body, _ = r.request.GetBody()
	r.request.ContentLength = int64(len(payload))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (response HTTPResponse, err error) {
	defer device.DeferPanicToError("http execute", func(panicErr error) {
		log.Println(panicErr)
		response = nil
		err = errors.Join(err, panicErr)
	})
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	request, err := r.cloneRequest(context.Background())
	if err != nil {
		return nil, err
	}
	requestClient := r.h1h2Client
	requestClient.Timeout = r.totalTimeout()
	standardResponse, err := requestClient.Do(request)
	if err != nil {
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := newHTTPResponse(standardResponse, r.responseLimit())
	if err := httpResp.validateSize(); err != nil {
		_ = standardResponse.Body.Close()
		return nil, err
	}
	if standardResponse.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

func (r *httpRequest) cloneRequest(ctx context.Context) (*http.Request, error) {
	request := r.request.Clone(ctx)
	if r.request.Body == nil {
		return request, nil
	}
	if r.request.GetBody == nil {
		return nil, errors.New("request body is not replayable")
	}
	body, err := r.request.GetBody()
	if err != nil {
		return nil, fmt.Errorf("clone request body: %w", err)
	}
	request.Body = body
	return request, nil
}

func (r *httpRequest) responseLimit() int64 {
	if r.allowLargeResponse {
		return maxLargeHTTPResponseBytes
	}
	return maxHTTPResponseBytes
}

func (r *httpRequest) totalTimeout() time.Duration {
	if r.allowLargeResponse {
		return largeHTTPResponseTimeout
	}
	return httpRequestTimeout
}

type requestFunc func(context.Context) (response *http.Response, err error)

type requestCandidate struct {
	name           string
	request        requestFunc
	context        context.Context
	cancel         context.CancelFunc
	closeTransport func()
	releaseOnce    sync.Once
}

func (c *requestCandidate) release() {
	c.releaseOnce.Do(func() {
		c.cancel()
		c.closeTransport()
	})
}

type requestResult struct {
	index    int
	response *http.Response
	err      error
}

type cleanupReadCloser struct {
	io.ReadCloser
	cleanup func()
}

func (c *cleanupReadCloser) Close() error {
	err := c.ReadCloser.Close()
	c.cleanup()
	return err
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	clientTimeout := r.totalTimeout()

	echTransport := &http.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
			connection, err := dialer.DialContext(ctx, network, addr)
			if err != nil {
				return connection, err
			}
			domain := addr
			if host, _, _ := net.SplitHostPort(addr); host != "" {
				domain = host
			}
			echTLS := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
			return echTLS.Client(ctx, connection)
		},
		DisableKeepAlives:     true,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
	}
	echClient := newPolicyHTTPClient(echTransport, clientTimeout)
	candidates := []requestCandidate{{
		name: "http(s)",
		request: func(ctx context.Context) (*http.Response, error) {
			request, err := r.cloneRequest(ctx)
			if err != nil {
				return nil, err
			}
			return echClient.Do(request)
		},
		closeTransport: echTransport.CloseIdleConnections,
	}}

	if r.request.URL.Scheme != "http" {
		h3Transport := &http3.Transport{
			TLSClientConfig: r.tls.Clone(),
			QUICConfig: &quic.Config{
				MaxIdleTimeout: time.Second,
			},
		}
		h3Client := newPolicyHTTPClient(h3Transport, clientTimeout)
		candidates = append(candidates, requestCandidate{
			name: "h3",
			request: func(ctx context.Context) (*http.Response, error) {
				request, err := r.cloneRequest(ctx)
				if err != nil {
					return nil, err
				}
				return h3Client.Do(request)
			},
			closeTransport: func() { _ = h3Transport.Close() },
		})
	}

	results := make(chan requestResult, len(candidates))
	for i := range candidates {
		candidates[i].context, candidates[i].cancel = context.WithCancel(context.Background())
		go func(index int) {
			defer device.DeferPanicToError("http", func(err error) {
				results <- requestResult{index: index, err: err}
			})
			response, err := candidates[index].request(candidates[index].context)
			results <- requestResult{index: index, response: response, err: err}
		}(i)
	}

	drainResults := func(count int) {
		go func() {
			for range count {
				result := <-results
				if result.response != nil && result.response.Body != nil {
					_ = result.response.Body.Close()
				}
				candidates[result.index].release()
			}
		}()
	}
	releaseAll := func(except int) {
		for i := range candidates {
			if i != except {
				candidates[i].release()
			}
		}
	}

	timer := time.NewTimer(clientTimeout)
	defer timer.Stop()
	remaining := len(candidates)
	var finalErr error
	for remaining > 0 {
		select {
		case result := <-results:
			remaining--
			candidate := &candidates[result.index]
			if result.response == nil || result.err != nil {
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", candidate.name, result.err))
				if result.response != nil && result.response.Body != nil {
					_ = result.response.Body.Close()
				}
				candidate.release()
				continue
			}
			if result.response.StatusCode != http.StatusOK {
				errorResponse := newHTTPResponse(result.response, maxHTTPResponseBytes)
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %s", candidate.name, errorResponse.errorString()))
				candidate.release()
				continue
			}
			response := newHTTPResponse(result.response, r.responseLimit())
			if err := response.validateSize(); err != nil {
				_ = result.response.Body.Close()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", candidate.name, err))
				candidate.release()
				continue
			}

			releaseAll(result.index)
			if remaining > 0 {
				drainResults(remaining)
			}
			result.response.Body = &cleanupReadCloser{
				ReadCloser: result.response.Body,
				cleanup:    candidate.release,
			}
			return response, nil
		case <-timer.C:
			releaseAll(-1)
			if remaining > 0 {
				drainResults(remaining)
			}
			return nil, errors.Join(finalErr, context.DeadlineExceeded)
		}
	}
	return nil, finalErr
}

type httpResponse struct {
	*http.Response
	maxBytes int64

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func newHTTPResponse(response *http.Response, maxBytes int64) *httpResponse {
	return &httpResponse{Response: response, maxBytes: maxBytes}
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		if err := h.validateSize(); err != nil {
			h.contentError = err
			return
		}
		if h.maxBytes > 0 {
			h.content, h.contentError = readAllLimited(h.Body, h.maxBytes)
		} else {
			h.content, h.contentError = io.ReadAll(h.Body)
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	if err := h.validateSize(); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := file.Name()
	committed := false
	defer func() {
		_ = file.Close()
		if !committed {
			_ = os.Remove(temporaryPath)
		}
	}()

	var written int64
	var copyErr error
	if h.maxBytes > 0 {
		written, copyErr = io.Copy(file, io.LimitReader(h.Body, h.maxBytes+1))
	} else {
		written, copyErr = io.Copy(file, h.Body)
	}
	closeErr := file.Close()
	if copyErr == nil && h.maxBytes > 0 && written > h.maxBytes {
		copyErr = httpResponseTooLargeError(h.maxBytes)
	}
	if err := errors.Join(copyErr, closeErr); err != nil {
		return err
	}
	publication, err := publishStagedPath(temporaryPath, path)
	if err != nil {
		return err
	}
	committed = true
	publication.commit()
	return nil
}

func (h *httpResponse) validateSize() error {
	if h.maxBytes > 0 && h.ContentLength > h.maxBytes {
		return httpResponseTooLargeError(h.maxBytes)
	}
	return nil
}

func httpResponseTooLargeError(maxBytes int64) error {
	return fmt.Errorf("%w (%d bytes)", errHTTPResponseTooLarge, maxBytes)
}

func readAllLimited(reader io.Reader, maxBytes int64) ([]byte, error) {
	content, err := io.ReadAll(io.LimitReader(reader, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(content)) > maxBytes {
		return nil, fmt.Errorf("input exceeds %d bytes", maxBytes)
	}
	return content, nil
}
