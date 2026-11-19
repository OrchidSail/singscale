package runtimeinfo

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestCollectAndWriteFile(t *testing.T) {
	outPath := filepath.Join(t.TempDir(), "snapshot.runtime.json")
	if err := WriteFile(outPath); err != nil {
		t.Fatalf("WriteFile failed: %v", err)
	}
	data, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("ReadFile failed: %v", err)
	}
	var report Report
	if err := json.Unmarshal(data, &report); err != nil {
		t.Fatalf("invalid runtime report JSON: %v", err)
	}
	if len(report.Metrics) == 0 {
		t.Fatalf("expected non-empty runtime metrics")
	}
}
