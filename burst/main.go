// Command burst fires an on-sale stampede at a running seat-reservation service and
// reports whether it behaved correctly.
//
// This is a correctness harness, not a benchmark. It answers four questions:
//
//	1. When many buyers fight over one seat, does exactly one win?
//	2. Did any request come back 5xx? (a lost race must be a decline, not a failure)
//	3. Do concurrent retries of one idempotency key produce exactly one booking?
//	4. Does available + held + confirmed still equal the seat count afterwards?
//
// It exits non-zero if any of those fail, so it can be run as a check rather than read
// as a report. Timings are printed as context and are deliberately not a verdict: on a
// small free-tier instance they say more about the host than about the design.
//
// Usage:
//
//	go run ./burst -url https://example.onrender.com -admin-secret SECRET
//	go run ./burst -url http://localhost:8080 -admin-secret local-admin-secret -n 500
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

type config struct {
	baseURL     string
	adminSecret string
	buyers      int
	retries     int
	seats       int
	timeout     time.Duration
	warmupWait  time.Duration
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.baseURL, "url", "http://localhost:8080", "base URL of the service")
	flag.StringVar(&cfg.adminSecret, "admin-secret", "local-admin-secret", "admin secret for creating the show")
	flag.IntVar(&cfg.buyers, "n", 300, "buyers firing simultaneously")
	flag.IntVar(&cfg.retries, "retries", 50, "concurrent retries of one idempotency key")
	flag.IntVar(&cfg.seats, "seats", 200, "seats in the show created for the run")
	flag.DurationVar(&cfg.timeout, "timeout", 60*time.Second, "per-request timeout")
	flag.DurationVar(&cfg.warmupWait, "warmup", 5*time.Minute, "how long to wait for a cold instance to wake")
	flag.Parse()

	cfg.baseURL = strings.TrimRight(cfg.baseURL, "/")

	runner := newRunner(cfg)
	if err := runner.run(); err != nil {
		fmt.Fprintf(os.Stderr, "\nburst failed: %v\n", err)
		os.Exit(1)
	}
}

// --- http -------------------------------------------------------------------------------

type runner struct {
	cfg    config
	client *http.Client
}

func newRunner(cfg config) *runner {
	// Connection reuse is not an optimisation here, it is a correctness requirement for the
	// measurement. A reservation costs about 3 ms server-side; a fresh TLS handshake from
	// another continent costs several hundred. Without keep-alive this program would be
	// timing handshakes and reporting them as service latency.
	transport := &http.Transport{
		MaxIdleConns:        4096,
		MaxIdleConnsPerHost: 4096,
		MaxConnsPerHost:     0,
		IdleConnTimeout:     90 * time.Second,
		ForceAttemptHTTP2:   true,
	}
	return &runner{
		cfg:    cfg,
		client: &http.Client{Transport: transport, Timeout: cfg.timeout},
	}
}

type response struct {
	status int
	body   []byte
	err    error
}

// doWithBackoff retries a request while the service is shedding load.
//
// Only for setup — minting tokens and creating the show. During the measured phases a 429
// is a real outcome and must be recorded, not retried away. Here it is noise: we are trying
// to reach the starting line, not measure anything.
func (r *runner) doWithBackoff(method, path, token string, body any, idemKey string) response {
	const attempts = 6
	wait := 250 * time.Millisecond
	var last response
	for attempt := 0; attempt < attempts; attempt++ {
		last = r.do(method, path, token, body, idemKey)
		if last.err == nil && last.status != 429 && last.status != 503 {
			return last
		}
		time.Sleep(wait)
		wait *= 2
	}
	return last
}

func (r *runner) do(method, path, token string, body any, idemKey string) response {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return response{err: err}
		}
		reader = bytes.NewReader(encoded)
	}

	req, err := http.NewRequest(method, r.cfg.baseURL+path, reader)
	if err != nil {
		return response{err: err}
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if idemKey != "" {
		req.Header.Set("Idempotency-Key", idemKey)
	}

	resp, err := r.client.Do(req)
	if err != nil {
		return response{err: err}
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(resp.Body)
	return response{status: resp.StatusCode, body: payload}
}

// --- outcome tallying -------------------------------------------------------------------

// outcomes records what came back, bucketed by status and by the service's own reason code.
// Bucketing by reason is the difference between "lots of 409s" and knowing whether buyers
// lost seat races, hit their limit, or were retrying.
type outcomes struct {
	mu       sync.Mutex
	statuses map[int]int
	reasons  map[string]int
	errors   map[string]int
	latency  []time.Duration
}

func newOutcomes() *outcomes {
	return &outcomes{
		statuses: map[int]int{},
		reasons:  map[string]int{},
		errors:   map[string]int{},
	}
}

func (o *outcomes) record(resp response, took time.Duration) {
	o.mu.Lock()
	defer o.mu.Unlock()

	if resp.err != nil {
		// A transport failure is not an HTTP outcome. Counted separately so it can never be
		// mistaken for the service answering cleanly.
		o.errors[classifyTransportError(resp.err)]++
		return
	}
	o.statuses[resp.status]++
	o.latency = append(o.latency, took)
	if reason := reasonOf(resp.body); reason != "" {
		o.reasons[reason]++
	}
}

func (o *outcomes) count(status int) int { return o.statuses[status] }

func (o *outcomes) serverErrors() int {
	total := 0
	for status, n := range o.statuses {
		if status >= 500 {
			total += n
		}
	}
	return total
}

func (o *outcomes) transportErrors() int {
	total := 0
	for _, n := range o.errors {
		total += n
	}
	return total
}

func (o *outcomes) percentiles() (p50, p95, max time.Duration) {
	if len(o.latency) == 0 {
		return 0, 0, 0
	}
	sorted := append([]time.Duration(nil), o.latency...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	at := func(q float64) time.Duration {
		idx := int(float64(len(sorted)-1) * q)
		return sorted[idx]
	}
	return at(0.50), at(0.95), sorted[len(sorted)-1]
}

func (o *outcomes) print(indent string) {
	keys := make([]int, 0, len(o.statuses))
	for status := range o.statuses {
		keys = append(keys, status)
	}
	sort.Ints(keys)
	for _, status := range keys {
		fmt.Printf("%sHTTP %d  ×%d\n", indent, status, o.statuses[status])
	}

	if len(o.reasons) > 0 {
		reasons := make([]string, 0, len(o.reasons))
		for reason := range o.reasons {
			reasons = append(reasons, reason)
		}
		sort.Strings(reasons)
		fmt.Printf("%sdeclined by reason:\n", indent)
		for _, reason := range reasons {
			fmt.Printf("%s  %-24s ×%d\n", indent, reason, o.reasons[reason])
		}
	}

	if len(o.errors) > 0 {
		fmt.Printf("%stransport failures (never reached the service):\n", indent)
		for kind, n := range o.errors {
			fmt.Printf("%s  %-24s ×%d\n", indent, kind, n)
		}
	}

	if p50, p95, max := o.percentiles(); p50 > 0 {
		fmt.Printf("%slatency  p50 %v   p95 %v   max %v\n", indent,
			p50.Round(time.Millisecond), p95.Round(time.Millisecond), max.Round(time.Millisecond))
	}
}

func reasonOf(body []byte) string {
	var parsed struct {
		Code string `json:"code"`
	}
	if json.Unmarshal(body, &parsed) == nil {
		return parsed.Code
	}
	return ""
}

func classifyTransportError(err error) string {
	text := err.Error()
	switch {
	case strings.Contains(text, "timeout") || strings.Contains(text, "deadline exceeded"):
		return "timeout"
	case strings.Contains(text, "connection reset"):
		return "connection reset"
	case strings.Contains(text, "connection refused"):
		return "connection refused"
	case strings.Contains(text, "EOF"):
		return "unexpected EOF"
	default:
		return "other"
	}
}

// --- the simultaneous release -----------------------------------------------------------

// fire runs n requests as close to simultaneously as one machine manages.
//
// Every worker is parked on a start channel and released at once. Simply submitting tasks
// to a pool is not enough: the first would finish before the last had started, and a race
// nobody runs is a race nobody tested.
func fire(n int, work func(i int) response) *outcomes {
	results := newOutcomes()
	start := make(chan struct{})
	ready := &sync.WaitGroup{}
	done := &sync.WaitGroup{}

	ready.Add(n)
	done.Add(n)
	for i := 0; i < n; i++ {
		go func(i int) {
			defer done.Done()
			ready.Done()
			<-start
			began := time.Now()
			results.record(work(i), time.Since(began))
		}(i)
	}

	ready.Wait()
	close(start)
	done.Wait()
	return results
}
