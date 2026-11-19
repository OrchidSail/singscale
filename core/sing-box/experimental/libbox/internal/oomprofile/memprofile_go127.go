//go:build (darwin || linux || windows) && go1.27

package oomprofile

import "math"

// Matches Go 1.27's internal/profilerecord.MemProfileRecord layout expected by
// runtime.pprof_memProfileInternal.
type memProfileRecord struct {
	ObjectSize                int64
	AllocObjects, FreeObjects int64
	Stack                     []uintptr
}

func (r *memProfileRecord) InUseObjects() int64 {
	return r.AllocObjects - r.FreeObjects
}

func (r *memProfileRecord) sampleValues(rate int64) (allocObjects, allocBytes, inuseObjects, inuseBytes, blockSize int64) {
	allocObjects, allocBytes = scaleHeapSample(r.AllocObjects, r.ObjectSize, rate)
	inuseObjects, inuseBytes = scaleHeapSample(r.InUseObjects(), r.ObjectSize, rate)
	blockSize = r.ObjectSize
	return
}

func scaleHeapSample(count int64, avgSize int64, rate int64) (int64, int64) {
	if count == 0 || avgSize == 0 {
		return 0, 0
	}
	if rate <= 1 {
		return count, count * avgSize
	}

	scale := 1 / (1 - math.Exp(-float64(avgSize)/float64(rate)))
	return int64(float64(count) * scale), int64(float64(count*avgSize) * scale)
}
