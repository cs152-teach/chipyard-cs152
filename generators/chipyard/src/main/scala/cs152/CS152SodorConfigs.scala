package chipyard

import org.chipsalliance.cde.config.Config

/** 4 KiB, 2-way, 32 B lines. */
class CS152Lab2Config extends Config(
  new cs152.WithScratchpadPreload ++
  new cs152.WithL1D(sets = 64, ways = 2, lineBytes = 32) ++
  new sodor.common.WithNSodorCores(1, internalTile = cs152.CS152Stage5Factory) ++
  new testchipip.soc.WithNoScratchpads ++
  new testchipip.serdes.WithSerialTLWidth(32) ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++
  new freechips.rocketchip.subsystem.WithNBanks(0) ++
  new chipyard.config.AbstractConfig)

// The directed sweep: 4 KiB held constant, associativity varied.
class CS152Lab2Config1Way extends Config(
  new cs152.WithL1D(sets = 128, ways = 1, lineBytes = 32) ++ new CS152Lab2Config)
class CS152Lab2Config2Way extends Config(
  new cs152.WithL1D(sets = 64,  ways = 2, lineBytes = 32) ++ new CS152Lab2Config)
class CS152Lab2Config4Way extends Config(
  new cs152.WithL1D(sets = 32,  ways = 4, lineBytes = 32) ++ new CS152Lab2Config)
class CS152Lab2Config8Way extends Config(
  new cs152.WithL1D(sets = 16,  ways = 8, lineBytes = 32) ++ new CS152Lab2Config)


// Open-ended 2 -- hardware prefetcher
class CS152Lab2PrefetchConfig extends Config(
  new cs152.WithL1D(sets = 64, ways = 2, lineBytes = 32, prefetch = true) ++
  new CS152Lab2Config)

// TODO: Add your own configs here :)
