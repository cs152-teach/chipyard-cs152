package chipyard

import org.chipsalliance.cde.config.Config

/** One Sodor 5-stage core with the CS152 L1D: 4 KiB, 2-way, 32 B lines. */
class CS152SodorConfig extends Config(
  new cs152.WithScratchpadPreload ++
  new cs152.WithL1D(sets = 64, ways = 2, lineBytes = 32) ++
  new cs152.WithCS152MemoryBytes(1 << 21) ++
  new sodor.common.WithNSodorCores(1, internalTile = cs152.CS152Stage5Factory) ++
  new testchipip.soc.WithNoScratchpads ++
  new testchipip.serdes.WithSerialTLWidth(32) ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new freechips.rocketchip.subsystem.WithNBanks(0) ++
  new chipyard.config.AbstractConfig)

// Lab 4: two cores, private L1Ds kept coherent by a snooping bus.
// MESI is also supported, but not used in the lab.
class CS152Lab4Config(protocol: cs152.CoherenceProtocol.Value) extends Config(
  new cs152.WithCoherence(protocol) ++
  new cs152.WithNCS152Cores(2) ++
  new CS152SodorConfig)

class CS152Lab4MIConfig   extends CS152Lab4Config(cs152.CoherenceProtocol.MI)
class CS152Lab4MSIConfig  extends CS152Lab4Config(cs152.CoherenceProtocol.MSI)
class CS152Lab4MESIConfig extends CS152Lab4Config(cs152.CoherenceProtocol.MESI)

// Add your own configs below.
