package dev.kigya.translatepdf

/** Avoids duplicating large model weights on low-RAM devices; this does not gate model loading. */
internal object WeightRepackingPolicy {
  fun shouldAllow(weightFileBytes: Long, totalPhysicalMemoryBytes: Long): Boolean =
    weightFileBytes > 0 && totalPhysicalMemoryBytes > 0 && weightFileBytes <= totalPhysicalMemoryBytes / 2
}
