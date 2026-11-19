package route

import (
	"strconv"
	"sync"
	"testing"
	"time"
)

func TestDirectFirstCacheEvictionAndConfiguration(t *testing.T) {
	if directGoodCacheTTL != 30*time.Minute {
		t.Fatalf("expected 30m good cache TTL for mobile radio saving, got %v", directGoodCacheTTL)
	}
	if directPoorCacheTTL != 5*time.Minute {
		t.Fatalf("expected 5m poor cache TTL, got %v", directPoorCacheTTL)
	}

	SingScaleConfigureDirectFirst(true, 220, 1050, true)
	now := time.Now()

	singScaleDirectFirstState.mu.Lock()
	for i := 0; i < maxDirectVerdictEntries; i++ {
		exp := now.Add(directGoodCacheTTL)
		if i%4 == 0 {
			exp = now.Add(-time.Second)
		}
		singScaleDirectFirstState.cache["host-"+strconv.Itoa(i)+":443"] = directVerdictEntry{
			useDirect: true,
			rttMs:     18,
			ruleDesc:  "direct-first(直连 18ms) => direct",
			createdAt: now.Add(time.Duration(i) * time.Millisecond),
			expiresAt: exp,
		}
	}
	evictExpiredOrOldestLocked(singScaleDirectFirstState.cache, now)
	remaining := len(singScaleDirectFirstState.cache)
	singScaleDirectFirstState.mu.Unlock()

	if remaining >= maxDirectVerdictEntries {
		t.Fatalf("expected expired entries to be evicted, got %d", remaining)
	}

	// Changing threshold automatically invalidates cached verdicts concurrently without race
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			SingScaleConfigureDirectFirst(true, int32(200+id*10), 1000, false)
		}(g)
	}
	wg.Wait()

	SingScaleConfigureDirectFirst(true, 220, 1050, true)
	singScaleDirectFirstState.mu.Lock()
	afterClear := len(singScaleDirectFirstState.cache)
	singScaleDirectFirstState.mu.Unlock()
	if afterClear != 0 {
		t.Fatalf("expected empty cache after explicit clear, got %d", afterClear)
	}
	if cap(singScaleDirectFirstState.probeSem) != maxConcurrentDirectProbes {
		t.Fatalf("expected bounded probe semaphore cap=%d, got %d", maxConcurrentDirectProbes, cap(singScaleDirectFirstState.probeSem))
	}
}
