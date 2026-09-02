package libcore

import (
	"sync"
	"testing"
	"time"
)

type selectorCallbackCall struct {
	instanceToken int64
	selectorTag   string
	tag           string
}

type selectorCallbackRecorder struct {
	sync.Mutex
	calls []selectorCallbackCall
}

func (*selectorCallbackRecorder) UseOfficialAssets() bool { return false }

func (*selectorCallbackRecorder) PublishBundledAsset(_, _, _ string) error { return nil }

func (r *selectorCallbackRecorder) Selector_OnProxySelected(
	instanceToken int64,
	selectorTag string,
	tag string,
) {
	r.Lock()
	r.calls = append(r.calls, selectorCallbackCall{instanceToken, selectorTag, tag})
	r.Unlock()
}

func (r *selectorCallbackRecorder) snapshot() []selectorCallbackCall {
	r.Lock()
	defer r.Unlock()
	return append([]selectorCallbackCall(nil), r.calls...)
}

func TestBoxCloseWaitsForActiveOperations(t *testing.T) {
	instance := &BoxInstance{}
	if !instance.beginOperation() {
		t.Fatal("initial operation was rejected")
	}

	closeResult := make(chan error, 1)
	go func() {
		closeResult <- instance.Close()
	}()
	waitForBoxState(t, instance, boxStateClosing)

	if instance.beginOperation() {
		instance.operations.Done()
		t.Fatal("operation was accepted after closing began")
	}
	select {
	case err := <-closeResult:
		t.Fatalf("Close returned while an operation was active: %v", err)
	case <-time.After(50 * time.Millisecond):
	}

	instance.operations.Done()
	select {
	case err := <-closeResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not return after the active operation completed")
	}
	waitForBoxState(t, instance, boxStateClosed)
}

func TestConcurrentBoxCloseSharesCompletion(t *testing.T) {
	instance := &BoxInstance{}
	if !instance.beginOperation() {
		t.Fatal("initial operation was rejected")
	}

	results := make(chan error, 2)
	go func() { results <- instance.Close() }()
	waitForBoxState(t, instance, boxStateClosing)
	go func() { results <- instance.Close() }()

	select {
	case err := <-results:
		t.Fatalf("concurrent Close returned before operation completion: %v", err)
	case <-time.After(50 * time.Millisecond):
	}
	instance.operations.Done()

	for range 2 {
		select {
		case err := <-results:
			if err != nil {
				t.Fatalf("Close error = %v, want nil", err)
			}
		case <-time.After(time.Second):
			t.Fatal("concurrent Close did not observe completion")
		}
	}
	if err := instance.Close(); err != nil {
		t.Fatalf("Close after completion returned %v", err)
	}
}

func TestNilBoxClose(t *testing.T) {
	var instance *BoxInstance
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestBoxInstanceTokensAreUniqueAndNonZero(t *testing.T) {
	first := nextBoxInstanceToken()
	second := nextBoxInstanceToken()
	if first == 0 || second == 0 {
		t.Fatalf("instance tokens must be non-zero: %d, %d", first, second)
	}
	if first == second {
		t.Fatalf("instance tokens must be unique: %d", first)
	}
}

func TestSelectorCallbackOnlyForCurrentMainInstance(t *testing.T) {
	recorder := new(selectorCallbackRecorder)
	stale := &BoxInstance{instanceToken: nextBoxInstanceToken()}
	current := &BoxInstance{instanceToken: nextBoxInstanceToken()}

	mainInstance.Lock()
	previousMain := mainInstance.instance
	previousGeneration := mainInstance.generation
	mainInstance.instance = current
	mainInstance.generation++
	mainInstance.Unlock()
	previousCallback := intfNB4A
	intfNB4A = recorder
	t.Cleanup(func() {
		intfNB4A = previousCallback
		mainInstance.Lock()
		mainInstance.instance = previousMain
		mainInstance.generation = previousGeneration
		mainInstance.Unlock()
	})

	stale.onSelectorSelected("proxy", "stale-tag")
	current.onSelectorSelected("proxy", "current-tag")

	calls := recorder.snapshot()
	if len(calls) != 1 {
		t.Fatalf("callback count = %d, want 1: %#v", len(calls), calls)
	}
	want := selectorCallbackCall{current.instanceToken, "proxy", "current-tag"}
	if calls[0] != want {
		t.Fatalf("callback = %#v, want %#v", calls[0], want)
	}
}

func waitForBoxState(t *testing.T, instance *BoxInstance, want boxInstanceState) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		instance.access.Lock()
		state := instance.state
		instance.access.Unlock()
		if state == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	instance.access.Lock()
	state := instance.state
	instance.access.Unlock()
	t.Fatalf("box state = %v, want %v", state, want)
}
