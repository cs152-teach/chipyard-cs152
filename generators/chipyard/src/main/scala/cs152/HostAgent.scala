//**************************************************************************
// CS152 Lab 4 -- the host (fesvr) as one more client of the coherent bus
//--------------------------------------------------------------------------
//
// The host reaches memory -- tohost, fromhost, syscall buffers -- through the
// tile's debug port.  Each access takes a bus tenure, like a cache miss that
// installs nothing:
//
//   host read   asks for what a load miss would (GetS; GetM under MI).  A
//               dirty owner writes the line back to memory (sInt).
//   host write  GetM.  Every copy is invalidated; a dirty owner writes back
//               first.
//
// The bus skips sFill, and in sDone the agent reads or writes the word in
// memory.  By then no cache can write the line without the bus, which this
// tenure still holds, so the access is atomic.
//
// Handshake.  SodorScratchpadAdapter presents a request for exactly one
// cycle, one cycle after the rocket-chip ScratchpadSlavePort in front of it
// saw req.ready, and returns the response one cycle later; the port waits as
// long as needed.  While waiting, the port may also issue speculative reads
// (its `ready_likely` path), which the adapter passes through.  So a request
// is accepted only if req.ready was high the previous cycle.  req.ready is
// low while a request is pending and for one cycle after the response, so the
// port cannot commit to a new request before it has taken in the response.

package cs152

import chisel3._
import chisel3.util._
import sodor.common.{MemPortIo, MemReq, SodorCoreParams}
import sodor.common.Constants._

class HostAgent(cfg: CS152CacheParams, policy: CoherencePolicy)
    (implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val host = Flipped(new MemPortIo(data_width = 32))  // from SodorScratchpadAdapter
    val bus  = new L1BusIO(cfg)
    val mem  = new MemPortIo(data_width = 32)           // the shared memory's debug port
  })

  val pending = RegInit(false.B)
  val held    = Reg(new MemReq(32))

  val answer   = pending && io.bus.done
  val answered = RegNext(answer, false.B)

  io.host.req.ready := !pending && !answered
  when (io.host.req.valid && RegNext(io.host.req.ready, false.B) && !pending) {
    pending := true.B
    held    := io.host.req.bits
  }
  when (answer) { pending := false.B }

  // ---- the bus: a requester with no lines ----
  io.bus.req.valid  := pending
  io.bus.req.cmd    := policy.missCmd(held.fcn === M_XWR)
  io.bus.req.addr   := cfg.lineOf(held.addr)
  io.bus.req.way    := 0.U
  io.bus.req.wb     := false.B
  io.bus.req.wbAddr := 0.U
  io.bus.req.fill   := false.B
  io.bus.rdData     := 0.U
  io.bus.snHit      := false.B
  io.bus.snOwner    := false.B
  io.bus.snWay      := 0.U

  // ---- the memory access itself, in sDone ----
  io.mem.req.valid := answer
  io.mem.req.bits  := held

  io.host.resp.valid     := answer
  io.host.resp.bits.data := io.mem.resp.bits.data
}
