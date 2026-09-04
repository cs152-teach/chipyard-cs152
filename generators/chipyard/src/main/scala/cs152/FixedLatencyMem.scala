package cs152

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.PlusArg
import sodor.common.{MemPortIo, SodorCoreParams}

/** One word of DRAM traffic.  `burst` marks a continuation of an already-open
  * burst, which costs one cycle instead of the full dram_lat. */
class DramReq extends Bundle {
  val addr  = UInt(32.W)
  val data  = UInt(32.W)
  val fcn   = UInt(1.W)   // function code: M_XRD / M_XWR
  val typ   = UInt(3.W)   // always MT_W: fills and writebacks move whole words
  val burst = Bool()
}

/** From the client's point of view. */
class DramPortIo extends Bundle {
  val req  = Decoupled(new DramReq)
  val resp = Flipped(Valid(UInt(32.W)))
}

class FixedLatencyMem(cfg: CS152CacheParams)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val cache = Flipped(new DramPortIo)
    val mem   = new MemPortIo(data_width = 32)   // to scratchpad D-port
  })

  // Default comes from the Scala config, so the plain make flow needs no flags.
  val dramLat = cfg.dramLat.U(16.W)

  val sIdle :: sWait :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val count = Reg(UInt(16.W))
  val held  = Reg(new DramReq)

  val idle = state === sIdle
  io.cache.req.ready := idle

  val inValid = io.cache.req.valid
  val inReq   = io.cache.req.bits

  // A burst continuation is delivered the same cycle it is asked for, so the
  // cache's beat counter paces the burst at exactly one word per cycle.
  val beatLat = Mux(inReq.burst, 0.U, dramLat)

  val accept  = inValid && idle
  val zeroLat = accept && (beatLat === 0.U)

  when (accept && !zeroLat) {
    held  := inReq
    count := beatLat - 1.U
    state := sWait
  }
  when (state === sWait) {
    when (count === 0.U) { state := sIdle }
      .otherwise         { count := count - 1.U }
  }

  // The cycle we actually touch the storage array.
  val doAccess = zeroLat || (state === sWait && count === 0.U)
  val accReq   = Mux(zeroLat, inReq, held)

  io.mem.req.valid     := doAccess
  io.mem.req.bits.addr := accReq.addr
  io.mem.req.bits.data := accReq.data
  io.mem.req.bits.fcn  := accReq.fcn
  io.mem.req.bits.typ  := accReq.typ

  // The scratchpad is combinational: data is available the cycle we ask.
  io.cache.resp.valid := doAccess
  io.cache.resp.bits  := io.mem.resp.bits.data
}
