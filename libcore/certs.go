package libcore

import (
	"crypto/x509"
	"errors"
	"log"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

func updateRootCACerts(pem []byte) error {
	x509.SystemCertPool()
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		return errors.New("failed to append certificates from PEM")
	}
	systemRoots = roots
	log.Println("external ca.pem was loaded")
	return nil
}

//go:linkname initSystemRoots crypto/x509.initSystemRoots
func initSystemRoots()
