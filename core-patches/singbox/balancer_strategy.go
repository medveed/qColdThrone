package group

// StrategyLeastPing is the AutoSelectorOutboundOptions.Strategy value that ranks
// healthy members by average RTT first, the analogue of Xray's leastPing
// strategy. The default ordering (deviation first) is Xray's leastLoad
// comparator; leastLoad needs no strategy switch of its own, only the
// Balance / BalanceMode / Expected / Baselines / MaxRTT / FailTolerance options
// the selector already implements.
const StrategyLeastPing = "leastPing"
