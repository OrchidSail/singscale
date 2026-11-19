//go:build (darwin || linux || windows) && !go1.27

package oomprofile

import "math"

// Matches Go <= 1.26's internal/profilerecord.MemProfileRecord layout expected by
// runtime.pprof_memProfileInternal.
type memProfileRecord struct {
	AllocBytes, FreeBytes     int64
	AllocObjects, FreeObjects int64
	Stack                     []uintptr
}

func (r *memProfileRecord) InUseBytes() int64 {
	return r.AllocBytes - r.FreeBytes
}

func (r *memProfileRecord) InUseObjects() int64 {
	return r.AllocObjects - r.FreeObjects
}

func (r *memProfileRecord) sampleValues(rate int64) (allocObjects, allocBytes, inuseObjects, inuseBytes, blockSize int64) {
	allocObjects, allocBytes = scaleHeapSample(r.AllocObjects, r.AllocBytes, rate)
	inuseObjects, inuseBytes = scaleHeapSample(r.InUseObjects(), r.InUseBytes(), rate)
	if r.AllocObjects > 0 {
		blockSize = r.AllocBytes / r.AllocObjects
	}
	return
}

func scaleHeapSample(count int64, size int64, rate int64) (int64, int64) {
	if count == 0 || size == 0 {
		return 0, 0
	}
	if rate <= 1 {
		return count, size
	}

	avgSize := float64(size) / float64(count)
	scale := 1 / (1 - math.Exp(-avgSize/float64(rate)))
	return int64(float64(count) * scale), int64(float64(size) * scale)
}
