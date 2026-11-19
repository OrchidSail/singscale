//go:build darwin || linux || windows

package oomprofile

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"testing"
)

func TestWriteFileAllProfiles(t *testing.T) {
	// Ensure sampled heap allocations are recorded by runtime.MemProfile.
	sink := make([][]byte, 0, 24)
	for i := 0; i < 24; i++ {
		buf := make([]byte, 128*1024)
		buf[0] = byte(i + 1)
		sink = append(sink, buf)
	}
	runtime.KeepAlive(sink)

	// Spawn a goroutine with pprof labels to verify labelMap ABI compatibility as well.
	ready := make(chan struct{})
	done := make(chan struct{})
	go func() {
		pprof.Do(context.Background(), pprof.Labels("singscale_test", "oomprofile"), func(context.Context) {
			close(ready)
			<-done
		})
	}()
	<-ready
	defer close(done)

	tmpDir := t.TempDir()
	profiles := []string{"allocs", "heap", "block", "goroutine", "mutex", "threadcreate"}
	for _, name := range profiles {
		outPath := filepath.Join(tmpDir, "snapshot."+name+".pb")
		if err := WriteFile(outPath, name); err != nil {
			t.Fatalf("WriteFile(%q) failed: %v", name, err)
		}
		data, err := os.ReadFile(outPath)
		if err != nil {
			t.Fatalf("ReadFile(%q) failed: %v", name, err)
		}
		if len(data) == 0 {
			t.Fatalf("expected non-empty profile %q, got 0 bytes", name)
		}
		switch name {
		case "allocs", "heap":
			if !bytes.Contains(data, []byte("alloc_space")) || !bytes.Contains(data, []byte("inuse_space")) {
				t.Fatalf("profile %q missing expected heap sample types", name)
			}
			if !bytes.Contains(data, []byte("bytes")) {
				t.Fatalf("profile %q missing expected bytes label", name)
			}
		case "goroutine":
			if !bytes.Contains(data, []byte("singscale_test")) || !bytes.Contains(data, []byte("oomprofile")) {
				t.Fatalf("goroutine profile missing expected pprof label strings")
			}
		}
	}
}

func TestMemProfileRecordSampleValues(t *testing.T) {
	// Verify scaling does not produce zero or negative values on realistic samples.
	var records []memProfileRecord
	n, _ := runtimeMemProfileInternal(nil, true)
	for {
		records = make([]memProfileRecord, n+50)
		var ok bool
		n, ok = runtimeMemProfileInternal(records, true)
		if ok {
			records = records[:n]
			break
		}
	}
	if len(records) == 0 {
		t.Fatal("expected at least one memProfileRecord from runtime")
	}
	for i, r := range records {
		for _, pc := range r.Stack {
			if pc != 0 && pc < 0x1000 {
				t.Fatalf("record[%d] contains suspicious non-address PC %#x (possible ABI mismatch)", i, pc)
			}
		}
		ao, ab, io, ib, bs := r.sampleValues(int64(runtime.MemProfileRate))
		if ao < 0 || ab < 0 || io < 0 || ib < 0 || bs < 0 {
			t.Fatalf("record[%d] produced negative sample values: %d %d %d %d %d", i, ao, ab, io, ib, bs)
		}
	}
}
