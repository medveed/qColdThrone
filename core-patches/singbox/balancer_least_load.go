package group

import (
	"net"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

// StrategyLeastLoad is the AutoSelectorOutboundOptions.Strategy value that
// spreads dials over the qualified members by the load they already carry,
// the analogue of Xray routing.balancers[].strategy leastLoad. The load is the
// number of connections a member currently carries, with the regular rank order
// as the tie-break: it needs no extra probes and is exact about what it counts.
const StrategyLeastLoad = "leastLoad"

// pickLeastLoadLocked returns the usable qualified member carrying the fewest
// connections, in rank order on ties. The caller holds s.access.
func (s *AutoSelector) pickLeastLoadLocked(network string, tried map[string]bool, now time.Time) adapter.Outbound {
	var best adapter.Outbound
	bestLoad := 0
	for _, tag := range s.qualified {
		detour := s.usableLocked(tag, network, tried, now, false)
		if detour == nil {
			continue
		}
		load := s.activeConns[tag]
		if best == nil || load < bestLoad {
			best, bestLoad = detour, load
		}
	}
	return best
}

// trackConn counts a member's live connection while the leastLoad strategy is on. Every other strategy gets the
// bare connection, so the wrapper never changes regular behavior.
func (s *AutoSelector) trackConn(tag string, conn net.Conn) net.Conn {
	if s.strategy != StrategyLeastLoad {
		return conn
	}
	s.acquireLoad(tag)
	return &leastLoadConn{Conn: conn, selector: s, tag: tag}
}

func (s *AutoSelector) trackPacketConn(tag string, conn net.PacketConn) net.PacketConn {
	if s.strategy != StrategyLeastLoad {
		return conn
	}
	s.acquireLoad(tag)
	return &leastLoadPacketConn{PacketConn: conn, selector: s, tag: tag}
}

func (s *AutoSelector) acquireLoad(tag string) {
	s.access.Lock()
	s.activeConns[tag]++
	s.access.Unlock()
}

func (s *AutoSelector) releaseLoad(tag string) {
	s.access.Lock()
	if s.activeConns[tag] > 0 {
		s.activeConns[tag]--
	}
	s.access.Unlock()
}

type leastLoadConn struct {
	net.Conn
	selector *AutoSelector
	tag      string
	closed   bool
}

func (c *leastLoadConn) Close() error {
	if !c.closed {
		c.closed = true
		c.selector.releaseLoad(c.tag)
	}
	return c.Conn.Close()
}

type leastLoadPacketConn struct {
	net.PacketConn
	selector *AutoSelector
	tag      string
	closed   bool
}

func (c *leastLoadPacketConn) Close() error {
	if !c.closed {
		c.closed = true
		c.selector.releaseLoad(c.tag)
	}
	return c.PacketConn.Close()
}
