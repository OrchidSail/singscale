package oomkiller

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBadCleanupAndRecorderSnapshot(t *testing.T) {
	badCleanup()

	baseDir := t.TempDir()
	called := false
	recorder := NewRecorder(RecorderOptions{
		BasePath: baseDir,
		SnapshotCallback: func(directory string, prefix string) {
			called = true
			_ = os.WriteFile(filepath.Join(directory, prefix+".marker"), []byte("ok"), 0o600)
		},
	})
	recorder.Start()
	defer recorder.Close()

	if err := recorder.snapshot(SnapshotReasonReset, memorySample{usage: 64 * 1024 * 1024}, false, false); err != nil {
		t.Fatalf("snapshot failed: %v", err)
	}
	if !called {
		t.Fatal("expected SnapshotCallback to be invoked")
	}
}
