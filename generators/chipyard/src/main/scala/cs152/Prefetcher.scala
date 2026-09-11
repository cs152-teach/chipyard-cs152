//**************************************************************************
// CS152 Lab 2 -- hardware prefetcher
//--------------------------------------------------------------------------
// One module: ModelPrefetcher, a BlackBox over the DPI harness that calls the
// C++ once per cycle.
//
// The performance baseline is that same config run with +prefetch=0, which
// gates the request inside the tile.  So a baseline run and a prefetch run are
// the same binary, the same hardware and the same harness, differing in nothing
// but whether the request is honoured -- and a student's .cc cannot influence
// the baseline whatever it returns.

package cs152

import chisel3._
import chisel3.util._
import chisel3.experimental.IntParam
import chisel3.util.HasBlackBoxResource

class ModelPrefetcher(cfg: CS152CacheParams) extends BlackBox(Map(
      "LINE_BYTES" -> IntParam(cfg.lineBytes),
      "SETS"       -> IntParam(cfg.sets),
      "WAYS"       -> IntParam(cfg.ways)
    )) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val accValid = Input(Bool())
    val accAddr  = Input(UInt(32.W))
    val accWrite = Input(Bool())
    val accMiss  = Input(Bool())

    val busy     = Input(Bool())
    val dropped  = Input(Bool())

    // Valid, not Decoupled: the harness registers its outputs, so a `ready`
    // could not be sampled correctly.  Use busy/dropped instead.
    val req      = Valid(UInt(32.W))
  })

  addResource("/vsrc/prefetcher_harness.v")
}
