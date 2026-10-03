package main

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"strings"
	"sync"
	"time"
)

type tokenResponse struct {
	AccessToken string `json:"access_token"`
	UserID      string `json:"user_id"`
	Role        string `json:"role"`
}

type showResponse struct {
	ID     string `json:"id"`
	Counts struct {
		Available  int  `json:"available"`
		Held       int  `json:"held"`
		Confirmed  int  `json:"confirmed"`
		TotalSeats int  `json:"total_seats"`
		Reconciled bool `json:"reconciled"`
	} `json:"counts"`
}

type reservationResponse struct {
	ReservationID string `json:"reservation_id"`
}

// failure collects everything that went wrong, so one run reports every problem rather
// than stopping at the first.
type failure struct {
	problems []string
}

func (f *failure) add(format string, args ...any) {
	f.problems = append(f.problems, fmt.Sprintf(format, args...))
}

func (f *failure) err() error {
	if len(f.problems) == 0 {
		return nil
	}
	return fmt.Errorf("%d check(s) failed:\n  - %s",
		len(f.problems), strings.Join(f.problems, "\n  - "))
}

func (r *runner) run() error {
	fmt.Printf("seat-reservation burst\n")
	fmt.Printf("target      %s\n", r.cfg.baseURL)
	fmt.Printf("buyers      %d simultaneous\n", r.cfg.buyers)
	fmt.Printf("seats       %d\n\n", r.cfg.seats)

	fails := &failure{}

	if err := r.warmUp(); err != nil {
		return err
	}
	adminToken, err := r.mintAdmin()
	if err != nil {
		return err
	}
	tokens, err := r.mintBuyers()
	if err != nil {
		return err
	}
	showID, labels, err := r.createShow(adminToken)
	if err != nil {
		return err
	}

	r.hotSeatStorm(showID, labels[0], tokens, fails)
	r.stampede(showID, labels, tokens, fails)
	r.idempotentRetries(showID, idemSeat, tokens[0], fails)
	r.reconcile(showID, fails)

	fmt.Println()
	if err := fails.err(); err != nil {
		fmt.Println("RESULT: FAILED")
		return err
	}
	fmt.Println("RESULT: PASSED — no seat sold twice, no 5xx, retries created nothing extra,")
	fmt.Println("        and the reconciliation invariant holds.")
	return nil
}

// --- phase 0: warm up -------------------------------------------------------------------

// warmUp waits for the service to answer before anything is measured.
//
// A free-tier instance sleeps when idle and takes a minute or more to wake, with the
// database waking alongside it. Measuring into that would make a healthy service look
// broken, so the wake-up is paid for here and excluded from every figure below.
func (r *runner) warmUp() error {
	fmt.Print("warming up ")
	deadline := time.Now().Add(r.cfg.warmupWait)
	began := time.Now()
	for attempt := 0; time.Now().Before(deadline); attempt++ {
		resp := r.do("GET", "/actuator/health/readiness", "", nil, "")
		if resp.err == nil && resp.status == 200 {
			fmt.Printf("ready after %v\n\n", time.Since(began).Round(time.Millisecond))
			return nil
		}
		fmt.Print(".")
		time.Sleep(3 * time.Second)
	}
	fmt.Println()
	return fmt.Errorf("service was not ready within %v — is it deployed and is the database reachable?",
		r.cfg.warmupWait)
}

// --- setup ------------------------------------------------------------------------------

func (r *runner) mintAdmin() (string, error) {
	resp := r.doWithBackoff("POST", "/auth/token", "", map[string]any{
		"handle":       fmt.Sprintf("burst-admin-%d", time.Now().UnixNano()),
		"admin_secret": r.cfg.adminSecret,
	}, "")
	if resp.err != nil {
		return "", fmt.Errorf("minting an admin token: %w", resp.err)
	}
	if resp.status != 200 {
		return "", fmt.Errorf("minting an admin token: HTTP %d %s (is -admin-secret correct?)",
			resp.status, strings.TrimSpace(string(resp.body)))
	}
	var parsed tokenResponse
	if err := json.Unmarshal(resp.body, &parsed); err != nil {
		return "", err
	}
	if parsed.Role != "ADMIN" {
		return "", fmt.Errorf("token came back with role %q, expected ADMIN", parsed.Role)
	}
	return parsed.AccessToken, nil
}

// mintBuyers creates every buyer's token up front.
//
// Deliberately before the clock starts: a burst that includes token minting would be
// measuring sign-in, and the thing under test is reserving.
func (r *runner) mintBuyers() ([]string, error) {
	fmt.Printf("minting %d buyer tokens... ", r.cfg.buyers)
	began := time.Now()

	tokens := make([]string, r.cfg.buyers)
	errs := make([]error, r.cfg.buyers)
	run := fmt.Sprintf("%d", time.Now().UnixNano())

	// Bounded concurrency: this is setup, and hammering the token endpoint on a small
	// instance would just make it slower.
	const parallel = 12
	sem := make(chan struct{}, parallel)
	wg := &sync.WaitGroup{}
	for i := 0; i < r.cfg.buyers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			resp := r.doWithBackoff("POST", "/auth/token", "", map[string]any{
				"handle": fmt.Sprintf("buyer-%s-%d", run, i),
			}, "")
			if resp.err != nil {
				errs[i] = resp.err
				return
			}
			if resp.status != 200 {
				errs[i] = fmt.Errorf("HTTP %d", resp.status)
				return
			}
			var parsed tokenResponse
			if err := json.Unmarshal(resp.body, &parsed); err != nil {
				errs[i] = err
				return
			}
			tokens[i] = parsed.AccessToken
		}(i)
	}
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			return nil, fmt.Errorf("minting token %d: %w", i, err)
		}
	}
	fmt.Printf("done in %v\n", time.Since(began).Round(time.Millisecond))
	return tokens, nil
}

// idemSeat is set aside for the idempotency phase and deliberately kept out of the
// stampede's pool. Without it the stampede can take the seat phase 3 needs, and the run
// reports "idempotency is broken" when the only thing that happened is that somebody
// bought the seat first — a false failure that sends you hunting a bug that is not there.
const idemSeat = "IDEM1"

func (r *runner) createShow(adminToken string) (string, []string, error) {
	labels := make([]string, r.cfg.seats)
	for i := range labels {
		labels[i] = fmt.Sprintf("%c%d", 'A'+rune(i/100), i%100+1)
	}
	seats := append(append([]string{}, labels...), idemSeat)

	name := fmt.Sprintf("burst-%d", time.Now().Unix())
	fmt.Printf("creating show %q with %d seats... ", name, len(seats))
	began := time.Now()

	resp := r.doWithBackoff("POST", "/shows", adminToken, map[string]any{
		"name":        name,
		"seats":       seats,
		"price_paise": 25000,
	}, "")
	if resp.err != nil {
		return "", nil, fmt.Errorf("creating the show: %w", resp.err)
	}
	if resp.status != 201 {
		return "", nil, fmt.Errorf("creating the show: HTTP %d %s",
			resp.status, strings.TrimSpace(string(resp.body)))
	}
	var parsed showResponse
	if err := json.Unmarshal(resp.body, &parsed); err != nil {
		return "", nil, err
	}
	fmt.Printf("done in %v\n\n", time.Since(began).Round(time.Millisecond))
	return parsed.ID, labels, nil
}

// --- phase 1: the hot seat --------------------------------------------------------------

// hotSeatStorm sends every buyer at one seat at the same instant.
//
// This is the assignment's central scenario and the sharpest test in the file: exactly one
// buyer may be told they got it, and everybody else must be declined cleanly rather than
// shown an error.
func (r *runner) hotSeatStorm(showID, seat string, tokens []string, fails *failure) {
	fmt.Printf("── phase 1: hot-seat storm — %d buyers, one seat (%s)\n", len(tokens), seat)

	results := fire(len(tokens), func(i int) response {
		return r.do("POST", "/shows/"+showID+"/reserve", tokens[i], map[string]any{
			"seats": []string{seat},
		}, fmt.Sprintf("storm-%s-%d", seat, i))
	})
	results.print("   ")

	winners := results.count(201)
	switch {
	case winners == 1:
		fmt.Printf("   ✓ exactly one buyer won %s\n", seat)
	case winners == 0:
		fails.add("hot-seat storm: nobody won %s — expected exactly one winner", seat)
	default:
		fails.add("hot-seat storm: %d buyers were told they got %s — THE SEAT WAS SOLD %d TIMES",
			winners, seat, winners)
	}
	if n := results.serverErrors(); n > 0 {
		fails.add("hot-seat storm: %d server errors — a lost race must be a decline, not a 5xx", n)
	} else {
		fmt.Printf("   ✓ zero 5xx\n")
	}
	fmt.Println()
}

// --- phase 2: the stampede --------------------------------------------------------------

// stampede spreads buyers across the hall, which is what a real on-sale looks like: most
// people get something, and the good seats are contested.
func (r *runner) stampede(showID string, labels []string, tokens []string, fails *failure) {
	fmt.Printf("── phase 2: general stampede — %d buyers across %d seats\n", len(tokens), len(labels))

	source := rand.New(rand.NewSource(time.Now().UnixNano()))
	picks := make([]string, len(tokens))
	for i := range picks {
		picks[i] = labels[source.Intn(len(labels))]
	}

	results := fire(len(tokens), func(i int) response {
		return r.do("POST", "/shows/"+showID+"/reserve", tokens[i], map[string]any{
			"seats": []string{picks[i]},
		}, fmt.Sprintf("stampede-%d-%d", time.Now().UnixNano(), i))
	})
	results.print("   ")

	if n := results.serverErrors(); n > 0 {
		fails.add("stampede: %d server errors", n)
	} else {
		fmt.Printf("   ✓ zero 5xx\n")
	}
	if n := results.transportErrors(); n > 0 {
		fmt.Printf("   ! %d requests never reached the service (client or network limit, not a service fault)\n", n)
	}
	fmt.Println()
}

// --- phase 3: idempotent retries --------------------------------------------------------

// idempotentRetries fires one buyer's identical request many times at once, which is what a
// phone on a flaky connection does. Exactly one booking may result.
func (r *runner) idempotentRetries(showID, seat, token string, fails *failure) {
	fmt.Printf("── phase 3: idempotent retries — %d concurrent copies of one request\n", r.cfg.retries)

	key := fmt.Sprintf("retry-%d", time.Now().UnixNano())
	ids := make([]string, r.cfg.retries)

	results := fire(r.cfg.retries, func(i int) response {
		resp := r.do("POST", "/shows/"+showID+"/reserve", token, map[string]any{
			"seats": []string{seat},
		}, key)
		if resp.err == nil && (resp.status == 200 || resp.status == 201) {
			var parsed reservationResponse
			if json.Unmarshal(resp.body, &parsed) == nil {
				ids[i] = parsed.ReservationID
			}
		}
		return resp
	})
	results.print("   ")

	distinct := map[string]bool{}
	for _, id := range ids {
		if id != "" {
			distinct[id] = true
		}
	}

	if created := results.count(201); created > 1 {
		fails.add("idempotency: %d requests created a booking — the same key must book once", created)
	}
	switch len(distinct) {
	case 1:
		fmt.Printf("   ✓ every caller was given the same reservation\n")
	case 0:
		if results.count(409) == r.cfg.retries {
			fails.add("idempotency: every retry was declined — seat %s was not free, so this "+
				"phase proved nothing (the reserved seat should be untouchable)", seat)
		} else {
			fails.add("idempotency: no caller received a reservation")
		}
	default:
		fails.add("idempotency: %d different reservations came back for one key", len(distinct))
	}
	if n := results.serverErrors(); n > 0 {
		fails.add("idempotency: %d server errors", n)
	}
	fmt.Println()
}

// --- phase 4: reconciliation ------------------------------------------------------------

// reconcile checks the invariant the whole design exists to protect, after everything above
// has finished moving.
func (r *runner) reconcile(showID string, fails *failure) {
	fmt.Println("── phase 4: reconciliation")

	resp := r.do("GET", "/shows/"+showID+"?seats=false", "", nil, "")
	if resp.err != nil || resp.status != 200 {
		fails.add("reconciliation: could not read the show back (HTTP %d)", resp.status)
		return
	}
	var show showResponse
	if err := json.Unmarshal(resp.body, &show); err != nil {
		fails.add("reconciliation: unreadable response: %v", err)
		return
	}

	c := show.Counts
	sum := c.Available + c.Held + c.Confirmed
	fmt.Printf("   available %d + held %d + confirmed %d = %d, total_seats %d\n",
		c.Available, c.Held, c.Confirmed, sum, c.TotalSeats)

	if sum != c.TotalSeats {
		fails.add("reconciliation: %d + %d + %d = %d but the show has %d seats — SEATS LOST OR DUPLICATED",
			c.Available, c.Held, c.Confirmed, sum, c.TotalSeats)
	} else {
		fmt.Printf("   ✓ invariant holds\n")
	}
	if !c.Reconciled {
		fails.add("reconciliation: the service reports reconciled=false")
	}

	// The metrics endpoint is checked too, because "metrics must reconcile with the API" is
	// a separate claim from "the API is self-consistent", and only comparing them tests it.
	//
	// The gauge is read from the database on a refresh interval rather than accumulated in
	// memory, so immediately after a burst it can legitimately lag by one interval. The
	// property being tested is convergence, not simultaneity: demanding they match in the
	// same instant would fail runs where nothing is wrong.
	r.checkMetrics(showID, c.Available, fails)
}

func (r *runner) checkMetrics(showID string, apiAvailable int, fails *failure) {
	const settleFor = 15 * time.Second

	deadline := time.Now().Add(settleFor)
	var lastGauge int
	var sawGauge bool

	for {
		metrics := r.do("GET", "/actuator/prometheus", "", nil, "")
		if metrics.err != nil || metrics.status != 200 {
			fmt.Printf("   ! metrics endpoint unavailable (HTTP %d) — skipping that check\n", metrics.status)
			return
		}
		text := string(metrics.body)

		for _, alarm := range []string{"seat_backstop_fired_total", "app_unexpected_errors_total",
			"reconciliation_mismatch_total"} {
			if value, ok := counterFor(text, alarm); ok && value > 0 {
				fails.add("metrics: %s is %d, it must be zero", alarm, value)
			}
		}

		gauge, ok := gaugeFor(text, "seats_available", showID)
		if !ok {
			// Not published yet rather than never: the gauge is refreshed on an interval, so
			// a show created moments ago legitimately has not appeared. Keep waiting.
			if time.Now().After(deadline) {
				fmt.Printf("   ! seats_available was never published for this show — skipping\n")
				return
			}
			time.Sleep(time.Second)
			continue
		}
		lastGauge, sawGauge = gauge, true
		if gauge == apiAvailable {
			fmt.Printf("   ✓ seats_available gauge agrees with the API (%d)\n", gauge)
			fmt.Printf("   ✓ backstop, unexpected-error and mismatch counters all zero\n")
			return
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(time.Second)
	}

	if sawGauge {
		fails.add("metrics: seats_available settled at %d but the API says %d — the gauge should "+
			"converge within a refresh interval, so a persistent gap means they are reading "+
			"different things", lastGauge, apiAvailable)
	}
}

func gaugeFor(metrics, name, showID string) (int, bool) {
	for _, line := range strings.Split(metrics, "\n") {
		if strings.HasPrefix(line, name+"{") && strings.Contains(line, showID) {
			fields := strings.Fields(line)
			var value float64
			if _, err := fmt.Sscanf(fields[len(fields)-1], "%g", &value); err == nil {
				return int(value), true
			}
		}
	}
	return 0, false
}

func counterFor(metrics, name string) (int, bool) {
	for _, line := range strings.Split(metrics, "\n") {
		if strings.HasPrefix(line, name+"{") || strings.HasPrefix(line, name+" ") {
			fields := strings.Fields(line)
			var value float64
			if _, err := fmt.Sscanf(fields[len(fields)-1], "%g", &value); err == nil {
				return int(value), true
			}
		}
	}
	return 0, false
}
