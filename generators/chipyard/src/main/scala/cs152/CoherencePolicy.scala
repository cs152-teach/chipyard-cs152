//**************************************************************************
// CS152 Lab 4 -- coherence protocol decisions
//--------------------------------------------------------------------------
//
// The bus (CoherentBus.scala) and the caches (L1DCache.scala) move lines and
// track who holds them; every decision that differs between MI, MSI and MESI
// is made here.  There are three:
//
//   missCmd     a miss: what to ask the bus for
//   grantState  the requester's state once its request completes
//   snoopNext   another cache's copy, after it snoops a request
//
// The resulting protocols, for one line, from one cache's point of view:
//
//   this cache           bus     this cache ends in      other caches' copies
//   -------------------  ------  ----------------------  ---------------------------
//   load miss    MI      GetM    M (clean)               -> I
//                MSI     GetS    S                       M -> S, S stays S
//                MESI    GetS    E if no other copy,     M/E -> S, S stays S
//                                S otherwise
//   store miss           GetM    M, then dirty           -> I
//   store hit in S       Upg     M, then dirty           S -> I     (no data moves)
//   store hit in E       --      M, then dirty           (none exist: silent upgrade)
//
// A copy that is DIRTY when snooped is written back first: the bus streams it
// to memory and the requester in the same beats (sInt), and it takes its
// snoopNext state only on the last beat.  A clean copy takes it at once.
//
// Upg needs no protocol decision of its own: a cache issues it whenever a
// store hits a line it holds without write permission, i.e. in S.

package cs152

import chisel3._

/* The snooping protocol every L1D in the system runs.
     MI    one valid state.  Any miss, load or store, takes the line exclusively,
           so two cores that only READ a line still steal it from each other.
     MSI   adds Shared: any number of readers, and a store to a Shared line
           broadcasts an upgrade that invalidates the other copies.
     MESI  adds Exclusive: a load miss that no other cache holds is installed
           Exclusive, and a later store to it upgrades silently, with no bus
           transaction.  Supported, but not used in the lab. */
object CoherenceProtocol extends Enumeration {
  val MI, MSI, MESI = Value
}

/* Per-line coherence state.  `dirty` is kept separately: a line in M has write
   permission but may still be clean (it was filled for a store that has not
   happened yet, or under MI it was filled for a load).  S and E are always
   clean. */
object L1State {
  val width = 2
  def I = 0.U(width.W)   // invalid
  def S = 1.U(width.W)   // valid, read-only, possibly shared
  def M = 2.U(width.W)   // valid, read/write, the only copy
  def E = 3.U(width.W)   // valid, read-only for now, the only copy (MESI only)

  def isValid(s: UInt):  Bool = s =/= I
  def canRead(s: UInt):  Bool = s =/= I
  /* A store may proceed without the bus.  E counts: the upgrade to M is silent. */
  def canWrite(s: UInt): Bool = s === M || s === E
}

object BusCmd {
  val width = 2
  def GetS = 0.U(width.W)   // load miss                       (MSI, MESI)
  def GetM = 1.U(width.W)   // store miss; ANY miss under MI
  def Upg  = 2.U(width.W)   // store to a line held in S: invalidate the other copies, no data
  def PutM = 3.U(width.W)   // write a dirty line back (the flush/clean walk); no snoop

  /* A request that brings a line in: what the `misses` counter counts. */
  def isMiss(cmd: UInt): Bool = cmd === GetS || cmd === GetM
}

class CoherencePolicy(val protocol: CoherenceProtocol.Value) {
  import CoherenceProtocol._

  /** A miss: what to ask the bus for.  MI has no read-only state, so a load
    * takes the line exclusively, exactly like a store. */
  def missCmd(isWrite: Bool): UInt = protocol match {
    case MI       => BusCmd.GetM
    case MSI|MESI => Mux(isWrite, BusCmd.GetM, BusCmd.GetS)
  }

  /** The requester's state once `cmd` completes.  `shared`: the snoop found
    * another valid copy of the line. */
  def grantState(cmd: UInt, shared: Bool): UInt = {
    val afterGetS = protocol match {
      case MI   => L1State.S    // unreachable: MI issues no GetS
      case MSI  => L1State.S
      case MESI => Mux(shared, L1State.S, L1State.E)
    }
    Mux(cmd === BusCmd.GetS, afterGetS, L1State.M)
  }

  /** Another cache's copy, after it snoops `cmd`: a GetS leaves it readable;
    * a GetM or Upg invalidates it.  The same for every protocol: MI never
    * issues GetS, so under MI a snooped copy always goes to I. */
  def snoopNext(cmd: UInt): UInt = Mux(cmd === BusCmd.GetS, L1State.S, L1State.I)
}
