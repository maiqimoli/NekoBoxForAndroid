package stun

import (
	"encoding/binary"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestKeepaliveRejectsResponseWithoutMappedAddress(t *testing.T) {
	connection := newMissingMappedPacketConn()
	client := NewClientWithConnection(connection)
	client.SetServerAddr("127.0.0.1:3478")

	host, err := client.Keepalive()
	if host != nil {
		t.Fatalf("Keepalive host = %#v, want nil", host)
	}
	if err == nil || !strings.Contains(err.Error(), "no mapped address") {
		t.Fatalf("Keepalive error = %v, want missing mapped address", err)
	}
}

func TestDiscoveryRejectsResponseWithoutMappedAddress(t *testing.T) {
	connection := newMissingMappedPacketConn()
	client := NewClientWithConnection(connection)
	client.SetServerAddr("127.0.0.1:3478")

	_, host, err, _ := client.Discover()
	if host != nil {
		t.Fatalf("Discover host = %#v, want nil", host)
	}
	if err == nil || !strings.Contains(strings.ToLower(err.Error()), "no mapped address") {
		t.Fatalf("Discover error = %v, want missing mapped address", err)
	}
}

func TestBehaviorTestRejectsResponseWithoutMappedAddress(t *testing.T) {
	connection := newMissingMappedPacketConn()
	client := NewClientWithConnection(connection)
	client.SetServerAddr("127.0.0.1:3478")

	behavior, err := client.BehaviorTest()
	if behavior != nil {
		t.Fatalf("BehaviorTest result = %#v, want nil", behavior)
	}
	if err == nil || !strings.Contains(strings.ToLower(err.Error()), "no mapped address") {
		t.Fatalf("BehaviorTest error = %v, want missing mapped address", err)
	}
}

func TestNewResponseHandlesMissingLocalAddress(t *testing.T) {
	pkt := &packet{transID: make([]byte, 16)}
	pkt.attributes = []attribute{*newAttribute(attributeMappedAddress, []byte{
		0, byte(attributeFamilyIPv4), 0x0d, 0x96, 192, 0, 2, 1,
	})}
	response := newResponse(pkt, &nilLocalAddrPacketConn{})
	if response.mappedAddr == nil {
		t.Fatal("mapped address was not decoded")
	}
	if response.identical {
		t.Fatal("response with no local address was marked identical")
	}
}

type missingMappedPacketConn struct {
	mu      sync.Mutex
	request []byte
}

func newMissingMappedPacketConn() *missingMappedPacketConn {
	return &missingMappedPacketConn{}
}

func (c *missingMappedPacketConn) WriteTo(payload []byte, _ net.Addr) (int, error) {
	if len(payload) < 20 {
		return 0, errors.New("short STUN request")
	}
	c.mu.Lock()
	c.request = append(c.request[:0], payload...)
	c.mu.Unlock()
	return len(payload), nil
}

func (c *missingMappedPacketConn) ReadFrom(payload []byte) (int, net.Addr, error) {
	c.mu.Lock()
	request := append([]byte(nil), c.request...)
	c.mu.Unlock()
	if len(request) < 20 {
		return 0, nil, errors.New("no STUN request available")
	}
	response := make([]byte, 20)
	binary.BigEndian.PutUint16(response[0:2], typeBindingResponse)
	copy(response[4:20], request[4:20])
	if len(payload) < len(response) {
		return 0, nil, errors.New("short response buffer")
	}
	copy(payload, response)
	return len(response), &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 3478}, nil
}

func (c *missingMappedPacketConn) Close() error { return nil }

func (c *missingMappedPacketConn) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.ParseIP("192.0.2.10"), Port: 12345}
}

func (c *missingMappedPacketConn) SetDeadline(time.Time) error      { return nil }
func (c *missingMappedPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c *missingMappedPacketConn) SetWriteDeadline(time.Time) error { return nil }

type nilLocalAddrPacketConn struct {
	missingMappedPacketConn
}

func (*nilLocalAddrPacketConn) LocalAddr() net.Addr { return nil }
