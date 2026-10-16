//**************************************************************************
// CS152 Lab 4 -- parameters and Config fragments
//--------------------------------------------------------------------------

package cs152

import chisel3._
import chisel3.util.{isPow2, log2Ceil, log2Up, Cat}
import org.chipsalliance.cde.config.{Field, Config}
import freechips.rocketchip.subsystem.{InSubsystem, TilesLocated}
import sodor.common.SodorTileAttachParams

case class CS152CacheParams(
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 32,
  dramLat: Int = 20,
  hitLat: Int = 1,
  replacement: String = "lru"
) {
  require(isPow2(sets),      s"CS152: l1d sets must be a power of 2, got $sets")
  require(isPow2(ways),      s"CS152: l1d ways must be a power of 2, got $ways")
  require(isPow2(lineBytes), s"CS152: l1d lineBytes must be a power of 2, got $lineBytes")
  require(lineBytes >= 4,    s"CS152: l1d lineBytes must be >= 4 (one 32-bit word), got $lineBytes")
  require(ways >= 1,         s"CS152: l1d ways must be >= 1, got $ways")
  require(dramLat >= 0,      s"CS152: dramLat must be >= 0, got $dramLat")
  require(hitLat >= 1,       s"CS152: hitLat must be >= 1 (1 = same cycle), got $hitLat")
  require(Seq("lru", "rand").contains(replacement),
    s"CS152: replacement must be \"lru\" or \"rand\", got \"$replacement\"")

  def wordsPerLine: Int = lineBytes / 4
  def offsetBits:   Int = log2Ceil(lineBytes)
  def indexBits:    Int = log2Ceil(sets)
  def tagBits:      Int = 32 - offsetBits - indexBits
  def wayBits:      Int = log2Ceil(ways)
  def capacityBytes: Int = sets * ways * lineBytes

  // Hardware widths: at least one bit, so a direct-mapped or one-set cache
  // still has a (constant) field to wire.
  def idxW:  Int = log2Up(sets)
  def wayW:  Int = log2Up(ways)
  def beatW: Int = log2Up(wordsPerLine)

  def idxOf(a: UInt): UInt = if (indexBits == 0) 0.U(1.W) else a(offsetBits + indexBits - 1, offsetBits)
  def lineOf(a: UInt): UInt = Cat(a(31, offsetBits), 0.U(offsetBits.W))   // the line holding a
  /** Byte offset of word `beat` within a line. */
  def beatOff(beat: UInt): UInt =
    if (wordsPerLine == 1) 0.U(offsetBits.W) else Cat(beat(log2Ceil(wordsPerLine) - 1, 0), 0.U(2.W))

  require(tagBits > 0, s"CS152: l1d geometry leaves no tag bits (sets=$sets, lineBytes=$lineBytes)")

  def summary: String =
    f"CS152 L1D: ${capacityBytes}%d B = $sets%d sets x $ways%d ways x $lineBytes%d B/line " +
    f"(tag=$tagBits%d index=$indexBits%d offset=$offsetBits%d, repl=$replacement, " +
    f"hit_lat=$hitLat%d dram_lat=$dramLat%d)"
}

// CoherenceProtocol, L1State and BusCmd live in CoherencePolicy.scala.

case class CS152SystemParams(
  nCores: Int = 1,
  protocol: CoherenceProtocol.Value = CoherenceProtocol.MSI
) {
  // lab/runtime/crt.S gives each core a 128 KiB stack above the program, and
  // syscalls.c sizes its per-core arrays by CS152_MAX_CORES (4).
  require(nCores >= 1 && nCores <= 4, s"CS152: nCores must be 1..4, got $nCores")
}

case object CS152CacheKey extends Field[CS152CacheParams](CS152CacheParams())
case object CS152SystemKey extends Field[CS152SystemParams](CS152SystemParams())

/* Base of the counter MMIO window.  Every core sees its OWN counters here.
   Must be free in spike's memory map too (0x1000_0000 is spike's byte-only
   UART, which faults on 32-bit loads).
   CS152_CTR_BASE in lab/runtime/cs152_counters.h must match this. */
case object CS152CounterBase extends Field[BigInt](0x20000000L)

/* Path to a hex image to pre-load into the shared memory with $readmemh,
   or None for the normal fesvr/TSI load. */
case object CS152PreloadHex extends Field[Option[String]](None)

class WithScratchpadPreload(file: String = "preload.hex") extends Config((site, here, up) => {
  case CS152PreloadHex => Some(file)
})

/* L1D geometry and latencies -- the same for every core. */
class WithL1D(
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 32,
  dramLat: Int = 20,
  hitLat: Int = 1,
  replacement: String = "lru"
) extends Config((site, here, up) => {
  case CS152CacheKey =>
    CS152CacheParams(sets, ways, lineBytes, dramLat, hitLat, replacement)
})

/* Size of the memory at 0x8000_0000.  WithNSodorCores sizes the tile's
   scratchpad -- the address range the core decodes as memory -- at 4096 sets x
   64 B = 256 KiB, too small for four 128 KiB stacks; this resizes it.  Must
   match spike's -m0x80000000:0x200000 and DEPTH in scripts/cs152-preload.sh. */
class WithCS152MemoryBytes(bytes: Int = 1 << 21) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem), site).map {
    case t: SodorTileAttachParams =>
      val sp = t.tileParams.scratchpad
      require(bytes % sp.blockBytes == 0, s"CS152: memory size $bytes is not a multiple of ${sp.blockBytes} B")
      t.copy(tileParams = t.tileParams.copy(scratchpad = sp.copy(nSets = bytes / sp.blockBytes)))
    case t => t
  }
})

/* Number of Sodor cores sharing memory through the coherent bus. */
class WithNCS152Cores(n: Int) extends Config((site, here, up) => {
  case CS152SystemKey => up(CS152SystemKey, site).copy(nCores = n)
})

/* Coherence protocol for every L1D (MI, MSI, or MESI). */
class WithCoherence(protocol: CoherenceProtocol.Value) extends Config((site, here, up) => {
  case CS152SystemKey => up(CS152SystemKey, site).copy(protocol = protocol)
})
