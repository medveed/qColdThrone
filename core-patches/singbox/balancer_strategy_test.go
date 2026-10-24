package group

import (
	"testing"
	"time"
)

func TestSortNodesLeastPingOrdersByAverageFirst(t *testing.T) {
	fast := &rankedNode{
		tag:   "fast",
		tier:  tierHealthy,
		stats: memberStats{All: 5, Average: 100 * time.Millisecond, Deviation: 80 * time.Millisecond},
	}
	stable := &rankedNode{
		tag:   "stable",
		tier:  tierHealthy,
		stats: memberStats{All: 5, Average: 200 * time.Millisecond, Deviation: 10 * time.Millisecond},
	}
	nodes := []*rankedNode{fast, stable}

	// leastPing: the lower average wins even with the worse deviation.
	sortNodes(nodes, StrategyLeastPing)
	if nodes[0].tag != "fast" {
		t.Fatalf("leastPing first = %q, want fast", nodes[0].tag)
	}

	// default (leastLoad): the lower deviation wins.
	sortNodes(nodes, "")
	if nodes[0].tag != "stable" {
		t.Fatalf("leastLoad first = %q, want stable", nodes[0].tag)
	}
}
