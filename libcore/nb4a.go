package libcore

import (
	"errors"
	"fmt"
	"libcore/device"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	_ "unsafe"

	"log"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/option"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

type coreReadiness struct {
	ready chan struct{}
	once  sync.Once
	mu    sync.Mutex
	err   error
}

func newCoreReadiness() *coreReadiness {
	return &coreReadiness{ready: make(chan struct{})}
}

func (r *coreReadiness) complete(err error) {
	r.once.Do(func() {
		r.mu.Lock()
		r.err = err
		r.mu.Unlock()
		close(r.ready)
	})
}

func (r *coreReadiness) wait() error {
	<-r.ready
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.err
}

var coreInitialization struct {
	sync.RWMutex
	readiness *coreReadiness
}

func beginCoreInitialization() *coreReadiness {
	readiness := newCoreReadiness()
	coreInitialization.Lock()
	coreInitialization.readiness = readiness
	coreInitialization.Unlock()
	return readiness
}

// WaitForCore waits for asynchronous core setup, including bundled asset
// publication, and reports any initialization failure to gomobile callers.
func WaitForCore() error {
	coreInitialization.RLock()
	readiness := coreInitialization.readiness
	coreInitialization.RUnlock()
	if readiness == nil {
		return errors.New("core initialization has not started")
	}
	return readiness.wait()
}

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	neko_log.LogWriter.Truncate()
}

func ForceGc() {
	go debug.FreeOSMemory()
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 NB4AInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	readiness := beginCoreInitialization()
	asyncStarted := false
	defer func() {
		if !asyncStarted {
			readiness.complete(errors.New("core initialization stopped before asynchronous setup"))
		}
	}()
	defer device.DeferPanicToError("InitCore", func(err error) {
		log.Println(err)
		readiness.complete(err)
	})
	isBgProcess = strings.HasSuffix(process, ":bg")

	neko_common.RunMode = neko_common.RunMode_NekoBoxForAndroid
	intfNB4A = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	if err := os.MkdirAll(tmp, 0755); err != nil {
		readiness.complete(fmt.Errorf("create working directory: %w", err))
		return
	}
	if err := os.Chdir(tmp); err != nil {
		readiness.complete(fmt.Errorf("change working directory: %w", err))
		return
	}

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	neko_log.LogWriterDisable = !logEnable
	neko_log.TruncateOnStart = isBgProcess
	neko_log.SetupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"))

	// Set up some component
	asyncStarted = true
	go func() {
		var initErr error
		defer func() {
			readiness.complete(initErr)
		}()
		defer device.DeferPanicToError("InitCore-go", func(err error) {
			log.Println(err)
			initErr = errors.Join(initErr, err)
		})
		device.GoDebug(process)

		// certs
		pem, err := os.ReadFile(filepath.Join(externalAssetsPath, "ca.pem"))
		if err == nil {
			initErr = errors.Join(initErr, updateRootCACerts(pem))
		} else if !os.IsNotExist(err) {
			initErr = errors.Join(initErr, fmt.Errorf("read ca.pem: %w", err))
		}

		// bg
		if isBgProcess {
			initErr = errors.Join(initErr, extractAssets())
		}
	}()
}
