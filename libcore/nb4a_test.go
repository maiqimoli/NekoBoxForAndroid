package libcore

import (
	"errors"
	"strings"
	"testing"
	"time"
)

func TestCoreReadinessWaitsForCompletion(t *testing.T) {
	readiness := newCoreReadiness()
	result := make(chan error, 1)
	go func() {
		result <- readiness.wait()
	}()

	select {
	case err := <-result:
		t.Fatalf("wait returned before completion: %v", err)
	case <-time.After(50 * time.Millisecond):
	}

	sentinel := errors.New("initialization failed")
	readiness.complete(sentinel)
	select {
	case err := <-result:
		if !errors.Is(err, sentinel) {
			t.Fatalf("wait error = %v, want %v", err, sentinel)
		}
	case <-time.After(time.Second):
		t.Fatal("wait did not return after completion")
	}
}

func TestCoreReadinessCompletesOnlyOnce(t *testing.T) {
	readiness := newCoreReadiness()
	first := errors.New("first")
	second := errors.New("second")
	readiness.complete(first)
	readiness.complete(second)

	if err := readiness.wait(); !errors.Is(err, first) {
		t.Fatalf("wait error = %v, want first completion", err)
	}
}

func TestNewSingBoxInstanceWaitsForCoreReadiness(t *testing.T) {
	readiness := newCoreReadiness()
	coreInitialization.Lock()
	previous := coreInitialization.readiness
	coreInitialization.readiness = readiness
	coreInitialization.Unlock()
	t.Cleanup(func() {
		coreInitialization.Lock()
		coreInitialization.readiness = previous
		coreInitialization.Unlock()
	})

	result := make(chan error, 1)
	go func() {
		_, err := NewSingBoxInstance("{}", nil)
		result <- err
	}()

	select {
	case err := <-result:
		t.Fatalf("constructor returned before core initialization: %v", err)
	case <-time.After(50 * time.Millisecond):
	}

	sentinel := errors.New("asset publication failed")
	readiness.complete(sentinel)
	select {
	case err := <-result:
		if !errors.Is(err, sentinel) {
			t.Fatalf("constructor error = %v, want %v", err, sentinel)
		}
		if !strings.Contains(err.Error(), "wait for core initialization") {
			t.Fatalf("constructor error lacks initialization context: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("constructor remained blocked after core initialization completed")
	}
}
