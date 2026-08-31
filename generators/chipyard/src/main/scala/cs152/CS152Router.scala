// Three destinations:
//
//   counters   0x1000_0000  MMIO counter block, answered in one cycle.  These
//              are registers, not memory: there is no line to snoop, and a
//              cached read would latch one value forever.  This window is not
//              optional.
//   cached     the scratchpad, behind the L1D -- including .tohost/.fromhost,
//              which stay coherent through the snoop port (HostSnoop.scala)
//   master     everything else -> TileLink -> HTIF

package cs152

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import sodor.common.{MemPortIo, SodorCoreParams}
import sodor.common.Constants._

class CS152Router(
  scratchRange: AddressSet,
  counterRange: AddressSet
)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val corePort    = Flipped(new MemPortIo(data_width = conf.xprlen))
    val cachePort   = new MemPortIo(data_width = conf.xprlen)
    val counterPort = new MemPortIo(data_width = conf.xprlen)
    val masterPort  = new MemPortIo(data_width = conf.xprlen)
  })

  val addr      = io.corePort.req.bits.addr
  val inCounter = counterRange.contains(addr)
  val inCached  = scratchRange.contains(addr) && !inCounter
  val inMaster  = !inCounter && !inCached

  for (p <- Seq(io.cachePort, io.counterPort, io.masterPort)) {
    p.req.bits := io.corePort.req.bits
  }
  io.cachePort.req.valid   := io.corePort.req.valid && inCached
  io.counterPort.req.valid := io.corePort.req.valid && inCounter
  io.masterPort.req.valid  := io.corePort.req.valid && inMaster

  io.corePort.req.ready := Mux(inCached,  io.cachePort.req.ready,
                           Mux(inCounter, io.counterPort.req.ready,
                                          io.masterPort.req.ready))

  io.corePort.resp.valid := Mux(inCached,  io.cachePort.resp.valid,
                            Mux(inCounter, io.counterPort.resp.valid,
                                           io.masterPort.resp.valid))
  io.corePort.resp.bits := Mux(inCached,  io.cachePort.resp.bits,
                           Mux(inCounter, io.counterPort.resp.bits,
                                          io.masterPort.resp.bits))
}
