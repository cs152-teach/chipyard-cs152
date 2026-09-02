package cs152

import chisel3._
import chisel3.util.{isPow2, log2Ceil}
import org.chipsalliance.cde.config.{Field, Config}

case class CS152CacheParams(
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 32,
  dramLat: Int = 20,
  hitLat: Int = 1,
  // Not yet implemented -- present so the config surface matches the lab spec.
  writeBack: Boolean = true,
  writeAllocate: Boolean = true,
  replacement: String = "lru"
) {
  require(isPow2(sets),      s"CS152: l1d sets must be a power of 2, got $sets")
  require(isPow2(ways),      s"CS152: l1d ways must be a power of 2, got $ways")
  require(isPow2(lineBytes), s"CS152: l1d lineBytes must be a power of 2, got $lineBytes")
  require(lineBytes >= 4,    s"CS152: l1d lineBytes must be >= 4 (one 32-bit word), got $lineBytes")
  require(ways >= 1,         s"CS152: l1d ways must be >= 1, got $ways")
  require(dramLat >= 0,      s"CS152: dramLat must be >= 0, got $dramLat")
  require(hitLat >= 1,       s"CS152: hitLat must be >= 1 (1 = same cycle), got $hitLat")
  require(writeBack,      "CS152: write-through is not implemented yet; use writeBack = true")
  require(writeAllocate,  "CS152: no-write-allocate is not implemented yet; use writeAllocate = true")
  require(Seq("lru", "rand").contains(replacement),
    s"CS152: replacement must be \"lru\" or \"rand\", got \"$replacement\"")

  def wordsPerLine: Int = lineBytes / 4
  def offsetBits:   Int = log2Ceil(lineBytes)
  def indexBits:    Int = log2Ceil(sets)
  def tagBits:      Int = 32 - offsetBits - indexBits
  def wayBits:      Int = log2Ceil(ways)
  def capacityBytes: Int = sets * ways * lineBytes

  require(tagBits > 0, s"CS152: l1d geometry leaves no tag bits (sets=$sets, lineBytes=$lineBytes)")

  def summary: String =
    f"CS152 L1D: ${capacityBytes}%d B = $sets%d sets x $ways%d ways x $lineBytes%d B/line " +
    f"(tag=$tagBits%d index=$indexBits%d offset=$offsetBits%d, repl=$replacement, " +
    f"hit_lat=$hitLat%d dram_lat=$dramLat%d)"
}

/* Per-line coherence state. */
object L1State {
  val width = 2
  def I = 0.U(width.W)   // invalid
  def S = 1.U(width.W)   // valid, read-only, possibly shared
  def M = 2.U(width.W)   // valid, read/write, exclusive

  def isValid(s: UInt):  Bool = s =/= I
  def canRead(s: UInt):  Bool = s =/= I
  def canWrite(s: UInt): Bool = s === M
}

case object CS152CacheKey extends Field[CS152CacheParams](CS152CacheParams())

/* Base of the counter MMIO window.
   NOT 0x1000_0000: spike parks a byte-only ns16550 UART there, so a 32-bit
   counter read under spike raises a load access fault and the program traps.
   0x2000_0000 is free both here and in spike, which keeps spike usable as a
   functional golden reference:
     spike --isa=rv32i_zicsr -m0x20000000:0x1000,0x80000000:0x40000 prog.riscv
   CS152_CTR_BASE in lab/runtime/cs152_counters.h must match this. */
case object CS152CounterBase extends Field[BigInt](0x20000000L)

/* The fragment students edit.
   writeBack and writeAllocate are deliberately NOT exposed here: only one of
   the four combinations is implemented, so offering them would only let a
   student pick a combination that fails during elaboration.  They remain in
   CS152CacheParams for whenever the other policies get written. */
class WithL1D(
  sets: Int = 64,
  ways: Int = 2,
  lineBytes: Int = 32,
  dramLat: Int = 20,
  hitLat: Int = 1,
  replacement: String = "lru"
) extends Config((site, here, up) => {
  case CS152CacheKey =>
    CS152CacheParams(sets, ways, lineBytes, dramLat, hitLat,
                     writeBack = true, writeAllocate = true,
                     replacement = replacement)
})
