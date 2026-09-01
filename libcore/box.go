package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance struct {
	sync.Mutex
	instance   *BoxInstance
	generation uint64
}

var boxInstanceTokenCounter atomic.Int64

func nextBoxInstanceToken() int64 {
	for {
		if token := boxInstanceTokenCounter.Add(1); token != 0 {
			return token
		}
	}
}

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		conntrack.Close()
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type boxInstanceState uint8

const (
	boxStateCreated boxInstanceState = iota
	boxStateStarted
	boxStateClosing
	boxStateClosed
)

type boxLifecycle interface {
	Start() error
	Close() error
}

type BoxInstance struct {
	access        sync.Mutex
	operations    sync.WaitGroup
	closeDone     chan struct{}
	closeErr      error
	instanceToken int64

	*box.Box
	lifecycle boxLifecycle
	cancel    context.CancelFunc
	state     boxInstanceState

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })
	if err := WaitForCore(); err != nil {
		return nil, fmt.Errorf("wait for core initialization: %w", err)
	}

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	success := false
	defer func() {
		if success {
			return
		}
		if b != nil {
			_ = b.Close()
			b = nil
		} else {
			cancel()
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[platform.Interface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:           instance,
		lifecycle:     instance,
		cancel:        cancel,
		closeDone:     make(chan struct{}),
		instanceToken: nextBoxInstanceToken(),
		pauseManager:  service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
			selector.SetSelectCallback(b.onSelectorSelected)
		}
	}

	success = true
	return b, nil
}

// InstanceToken identifies the native instance that emitted an asynchronous
// selector callback. It is immutable and non-zero for every constructed box.
func (b *BoxInstance) InstanceToken() int64 {
	if b == nil {
		return 0
	}
	return b.instanceToken
}

func (b *BoxInstance) onSelectorSelected(selectorTag, tag string) {
	if b == nil || b.instanceToken == 0 {
		return
	}

	// Serialize the reset with SetAsMain and Close. Once the lock is released a
	// replacement may proceed, but the instance token lets the platform discard
	// the now-stale notification without affecting the new service generation.
	mainInstance.Lock()
	if mainInstance.instance != b {
		mainInstance.Unlock()
		return
	}
	conntrack.Close()
	callback := intfNB4A
	token := b.instanceToken
	mainInstance.Unlock()

	if callback != nil {
		callback.Selector_OnProxySelected(token, selectorTag, tag)
	}
}

func (b *BoxInstance) Start() (err error) {
	if b == nil {
		return errors.New("box is nil")
	}

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })
	b.access.Lock()
	if b.state != boxStateCreated {
		b.access.Unlock()
		return errors.New("already started or closed")
	}
	lifecycle := b.lifecycle
	if lifecycle == nil && b.Box != nil {
		lifecycle = b.Box
	}
	if lifecycle == nil {
		b.access.Unlock()
		return errors.New("box is nil")
	}
	// A start attempt is intentionally one-shot even when Start returns an
	// error. sing-box may have allocated partial resources by then, so the
	// instance remains closable and Close owns their cleanup.
	b.state = boxStateStarted
	b.operations.Add(1)
	b.access.Unlock()
	defer b.operations.Done()

	return lifecycle.Start()
}

func (b *BoxInstance) Close() (err error) {
	if b == nil {
		return nil
	}

	b.access.Lock()
	switch b.state {
	case boxStateClosing:
		done := b.closeDone
		b.access.Unlock()
		<-done
		b.access.Lock()
		err = b.closeErr
		b.access.Unlock()
		return err
	case boxStateClosed:
		err = b.closeErr
		b.access.Unlock()
		return err
	}
	if b.closeDone == nil {
		b.closeDone = make(chan struct{})
	}
	b.state = boxStateClosing

	var protectGeneration uint64
	mainInstance.Lock()
	if mainInstance.instance == b {
		mainInstance.instance = nil
		mainInstance.generation++
		protectGeneration = mainInstance.generation
	}
	mainInstance.Unlock()
	b.access.Unlock()

	defer func() {
		b.access.Lock()
		b.closeErr = err
		b.state = boxStateClosed
		close(b.closeDone)
		b.access.Unlock()
	}()
	defer device.DeferPanicToError("box.Close", func(err_ error) { err = errors.Join(err, err_) })

	if b.cancel != nil {
		b.cancel()
	}
	if protectGeneration != 0 {
		syncProtectServer(protectGeneration, false)
	}
	b.operations.Wait()

	lifecycle := b.lifecycle
	if lifecycle == nil && b.Box != nil {
		lifecycle = b.Box
	}
	if lifecycle != nil {
		err = lifecycle.Close()
	}
	return err
}

func (b *BoxInstance) Sleep() {
	if !b.beginOperation() {
		return
	}
	defer b.operations.Done()
	b.access.Lock()
	pauseManager := b.pauseManager
	b.access.Unlock()
	if pauseManager != nil {
		pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if !b.beginOperation() {
		return
	}
	defer b.operations.Done()
	b.access.Lock()
	pauseManager := b.pauseManager
	b.access.Unlock()
	if pauseManager != nil {
		pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	if b == nil {
		return
	}
	b.access.Lock()
	if b.state == boxStateClosing || b.state == boxStateClosed {
		b.access.Unlock()
		return
	}
	mainInstance.Lock()
	if mainInstance.instance == b {
		mainInstance.Unlock()
		b.access.Unlock()
		return
	}
	mainInstance.instance = b
	mainInstance.generation++
	generation := mainInstance.generation
	mainInstance.Unlock()
	b.access.Unlock()

	syncProtectServer(generation, true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	if !b.beginOperation() {
		return
	}
	defer b.operations.Done()
	b.access.Lock()
	if b.v2api != nil {
		b.access.Unlock()
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	v2api := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.v2api = v2api
	instance := b.Box
	b.access.Unlock()
	instance.Router().AppendTracker(v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if !b.beginOperation() {
		return 0
	}
	defer b.operations.Done()
	b.access.Lock()
	v2api := b.v2api
	b.access.Unlock()
	if v2api == nil {
		return 0
	}
	return v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if !b.beginOperation() {
		return false
	}
	defer b.operations.Done()
	b.access.Lock()
	selector := b.selector
	b.access.Unlock()
	if selector != nil {
		return selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		if !i.beginOperation() {
			return 0, errors.New("box is closing or closed")
		}
		defer i.operations.Done()
		instance, v2api := i.snapshot()
		if instance == nil {
			return 0, errors.New("box is nil")
		}
		if v2api != nil {
			connectionTracker = v2api.StatsService()
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(instance, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	main := acquireMainInstance()
	if main == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	defer main.operations.Done()
	// test mainInstance
	instance, v2api := main.snapshot()
	if v2api != nil {
		connectionTracker = v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(instance, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}

func (b *BoxInstance) beginOperation() bool {
	if b == nil {
		return false
	}
	b.access.Lock()
	defer b.access.Unlock()
	if b.state == boxStateClosing || b.state == boxStateClosed {
		return false
	}
	b.operations.Add(1)
	return true
}

func (b *BoxInstance) snapshot() (*box.Box, *boxapi.SbV2rayServer) {
	b.access.Lock()
	defer b.access.Unlock()
	return b.Box, b.v2api
}

// acquireMainInstance takes an operation reference only after dropping the
// global main lock. The generation recheck prevents using a replaced main
// instance without introducing a global-lock -> instance-lock inversion.
func acquireMainInstance() *BoxInstance {
	for {
		mainInstance.Lock()
		instance := mainInstance.instance
		generation := mainInstance.generation
		mainInstance.Unlock()
		if instance == nil {
			return nil
		}
		if !instance.beginOperation() {
			mainInstance.Lock()
			unchanged := mainInstance.instance == instance && mainInstance.generation == generation
			mainInstance.Unlock()
			if unchanged {
				return nil
			}
			continue
		}
		mainInstance.Lock()
		current := mainInstance.instance == instance && mainInstance.generation == generation
		mainInstance.Unlock()
		if current {
			return instance
		}
		instance.operations.Done()
	}
}

var protectServer struct {
	sync.Mutex
	generation uint64
	closer     io.Closer
}

// syncProtectServer performs potentially blocking server construction and
// Closer.Close calls outside global locks. A generation check prevents an old
// SetAsMain/Close operation from publishing stale state.
func syncProtectServer(generation uint64, start bool) {
	protectServer.Lock()
	if generation <= protectServer.generation {
		protectServer.Unlock()
		return
	}
	protectServer.generation = generation
	previous := protectServer.closer
	protectServer.closer = nil
	protectServer.Unlock()

	if previous != nil {
		if err := previous.Close(); err != nil {
			log.Println("close protect server:", err)
		}
	}
	if !start {
		return
	}

	next := protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
		intfBox.AutoDetectInterfaceControl(int32(fd))
	})
	protectServer.Lock()
	if protectServer.generation == generation {
		protectServer.closer = next
		next = nil
	}
	protectServer.Unlock()
	if next != nil {
		if err := next.Close(); err != nil {
			log.Println("close stale protect server:", err)
		}
	}
}
