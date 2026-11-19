//go:build darwin || linux || windows

package libbox

import (
	"math"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestWriteOOMProfilesDoNotPanic(t *testing.T) {
	sink := make([][]byte, 0, 16)
	for i := 0; i < 16; i++ {
		buf := make([]byte, 128*1024)
		buf[0] = byte(i + 1)
		sink = append(sink, buf)
	}
	runtime.KeepAlive(sink)

	tmpDir := t.TempDir()
	for _, name := range oomReportProfiles {
		outPath := filepath.Join(tmpDir, "snapshot-1-reset."+name+".pb")
		writeOOMProfile(outPath, name)
		info, err := os.Stat(outPath)
		if err != nil {
			t.Fatalf("expected OOM profile %q to be written: %v", name, err)
		}
		if info.Size() == 0 {
			t.Fatalf("expected non-empty OOM profile %q", name)
		}
	}
}

func TestComputeGoMemoryLimit(t *testing.T) {
	const mib = int64(1024 * 1024)

	if got := computeGoMemoryLimit(0, true); got != math.MaxInt64 {
		t.Fatalf("expected MaxInt64 for 0 limit, got %d", got)
	}
	if got := computeGoMemoryLimit(640*mib, true); got != 160*mib {
		t.Fatalf("expected Android 640MiB RSS ceiling -> 160MiB Go soft limit, got %d MiB", got/mib)
	}
	if got := computeGoMemoryLimit(512*mib, true); got != 160*mib {
		t.Fatalf("expected Android 512MiB RSS ceiling -> 160MiB Go soft limit, got %d MiB", got/mib)
	}
	if got := computeGoMemoryLimit(384*mib, true); got != 128*mib {
		t.Fatalf("expected Android 384MiB RSS ceiling -> 128MiB Go soft limit, got %d MiB", got/mib)
	}
	if got := computeGoMemoryLimit(100*mib, true); got != 80*mib {
		t.Fatalf("expected Android 100MiB RSS ceiling -> 80MiB Go soft limit, got %d MiB", got/mib)
	}
	if got := computeGoMemoryLimit(50*mib, false); got != 40*mib {
		t.Fatalf("expected non-Android 50MiB limit -> 40MiB Go soft limit, got %d MiB", got/mib)
	}
}
