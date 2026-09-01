package stun

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestPacketRoundTrip(t *testing.T) {
	pkt, err := newPacket()
	if err != nil {
		t.Fatal(err)
	}
	pkt.types = typeBindingRequest
	pkt.addAttribute(*newSoftwareAttribute("nb4a-client"))
	wirePacket := pkt.bytes()

	parsed, err := newPacketFromBytes(wirePacket)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.types != typeBindingRequest {
		t.Fatalf("packet type = %#x", parsed.types)
	}
	if len(parsed.attributes) != 1 || parsed.attributes[0].types != attributeSoftware {
		t.Fatalf("attributes = %#v", parsed.attributes)
	}
	if got, want := parsed.length, uint16(len(wirePacket)-20); got != want {
		t.Fatalf("parsed packet length = %d, want %d", got, want)
	}
	if got, want := parsed.attributes[0].length, uint16(len("nb4a-client")); got != want {
		t.Fatalf("parsed attribute length = %d, want %d", got, want)
	}
	if reencoded := parsed.bytes(); !bytes.Equal(reencoded, wirePacket) {
		t.Fatalf("packet changed after decode/encode:\n got %x\nwant %x", reencoded, wirePacket)
	}
}

func TestPacketRejectsMalformedLengths(t *testing.T) {
	tests := map[string][]byte{
		"short header": make([]byte, 19),
		"message length mismatch": func() []byte {
			data := make([]byte, 20)
			binary.BigEndian.PutUint16(data[2:4], 4)
			return data
		}(),
		"truncated attribute": func() []byte {
			data := make([]byte, 24)
			binary.BigEndian.PutUint16(data[2:4], 4)
			binary.BigEndian.PutUint16(data[20:22], attributeSoftware)
			binary.BigEndian.PutUint16(data[22:24], 8)
			return data
		}(),
	}

	for name, data := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := newPacketFromBytes(data); err == nil {
				t.Fatal("expected malformed packet to be rejected")
			}
		})
	}
}

func TestPacketRejectsMalformedAddressAttributes(t *testing.T) {
	tests := map[string][]byte{
		"empty address":        addressPacket(attributeMappedAddress, nil),
		"missing family":       addressPacket(attributeMappedAddress, []byte{0}),
		"short IPv4 address":   addressPacket(attributeMappedAddress, []byte{0, attributeFamilyIPv4, 0, 80}),
		"long IPv6 address":    addressPacket(attributeXorMappedAddress, append([]byte{0, attributeFamilyIPV6, 0, 80}, make([]byte, 20)...)),
		"unknown address type": addressPacket(attributeOtherAddress, []byte{0, 3, 0, 80, 127, 0, 0, 1}),
	}

	for name, data := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := newPacketFromBytes(data); err == nil {
				t.Fatal("expected malformed address attribute to be rejected")
			}
		})
	}
}

func TestAddressDecodersAreDefensive(t *testing.T) {
	invalid := &attribute{types: attributeXorMappedAddress, value: make([]byte, 24)}
	if address := invalid.xorAddr(make([]byte, 16)); address != nil {
		t.Fatalf("unexpected XOR address: %#v", address)
	}
	if address := invalid.rawAddr(); address != nil {
		t.Fatalf("unexpected raw address: %#v", address)
	}
}

func addressPacket(types uint16, value []byte) []byte {
	paddedLength := int(align(uint16(len(value))))
	data := make([]byte, 20+4+paddedLength)
	binary.BigEndian.PutUint16(data[2:4], uint16(4+paddedLength))
	binary.BigEndian.PutUint16(data[20:22], types)
	binary.BigEndian.PutUint16(data[22:24], uint16(len(value)))
	copy(data[24:], value)
	return data
}
