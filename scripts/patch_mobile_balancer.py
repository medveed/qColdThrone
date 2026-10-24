"""Patch the pinned sing-box auto-selector with least-load balancing and an external fallback.

The offsite JSON config maps Xray `routing.balancers[]` onto the group: `strategy: "leastLoad"`
spreads dials by the connections a member already carries, and `fallback` is the tag dialed when no
member of the group is usable at all (Xray `fallbackTag`). Sources are copied from the checksummed
Go module cache; the shared cache itself is never mutated.
"""
import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path

MODULE = "github.com/sagernet/sing-box"
SOURCE = "github.com/throneproj/sing-box"
VERSION = "v1.11.16-0.20261003011458-ebe0747b4182"
SUM = "h1:UoiYa7jmDKPZ5Skbl7MFpG3xjqr+E1BzlQufUxpcb90="
MOD_SUM = "h1:YTImygsnr/4DfL6ORmQVyFsfMFjwTA6oJAFU+1uJNVY="


def replace_once(path: Path, before: str, after: str):
    text = path.read_text()
    if text.count(before) != 1:
        raise ValueError(f"Pinned sing-box source changed; review {path.name}")
    path.write_text(text.replace(before, after), newline="\n")


def copy_writable(source, destination):
    path = Path(destination)
    if path.exists():
        path.chmod(stat.S_IREAD | stat.S_IWRITE)
    return shutil.copyfile(source, destination)


def patch(core: Path):
    current = json.loads(subprocess.check_output(["go", "mod", "edit", "-json"], cwd=core))
    replacement = next((item["New"] for item in current.get("Replace", [])
                        if item["Old"]["Path"] == MODULE), None)
    if replacement not in ({"Path": SOURCE, "Version": VERSION}, {"Path": "./.qthrone/sing-box"}):
        raise ValueError("Core sing-box replacement changed; review the pinned balancer patch")
    info = json.loads(subprocess.check_output(
        ["go", "mod", "download", "-json", SOURCE + "@" + VERSION], cwd=core))
    if info.get("Sum") != SUM or info.get("GoModSum") != MOD_SUM:
        raise ValueError("Pinned sing-box source checksum mismatch")
    target = core / ".qthrone" / "sing-box"
    shutil.copytree(info["Dir"], target, dirs_exist_ok=True, copy_function=copy_writable)

    options = target / "option" / "group.go"
    expected_field = '\tExpected      int                  `json:"expected,omitempty"`\n'
    replace_once(options, expected_field, expected_field +
        "\n\t// Fallback is the tag dialed when no member of the group is usable at all\n"
        "\t// (Xray routing.balancers[].fallbackTag). Optional.\n"
        "\tFallback string `json:\"fallback,omitempty\"`\n"
        "\t// Strategy selects among the healthy set: \"\" is the ranked pick, \"leastLoad\"\n"
        "\t// picks the member carrying the fewest connections (rank order on ties).\n"
        "\tStrategy string `json:\"strategy,omitempty\"`\n")

    selector = target / "protocol" / "group" / "autoselector.go"
    replace_once(selector,
        "\tbalanceInterval time.Duration\n\tdialRetries     int\n",
        "\tbalanceInterval  time.Duration\n\tdialRetries      int\n"
        "\tfallbackTag      string\n\tfallbackOutbound adapter.Outbound\n"
        "\tstrategy         string\n\tactiveConns      map[string]int\n")
    replace_once(selector,
        "\t\tdialRetries:                  options.DialRetries,\n",
        "\t\tdialRetries:                  options.DialRetries,\n"
        "\t\tfallbackTag:                  options.Fallback,\n"
        "\t\tstrategy:                     options.Strategy,\n"
        "\t\tactiveConns:                  make(map[string]int),\n")
    replace_once(selector,
        "\ts.access.Lock()\n\t// The caller's ordering is the ranking prior:",
        "\tif s.fallbackTag != \"\" {\n"
        "\t\tfallback, loaded := s.outbound.Outbound(s.fallbackTag)\n"
        "\t\tif !loaded {\n"
        "\t\t\treturn E.New(\"fallback outbound not found: \", s.fallbackTag)\n"
        "\t\t}\n"
        "\t\ts.fallbackOutbound = fallback\n"
        "\t}\n\n"
        "\ts.access.Lock()\n\t// The caller's ordering is the ranking prior:")
    replace_once(selector,
        "\tbalancing := s.balance && s.pinnedTag == \"\"\n",
        "\tbalancing := s.balance && s.pinnedTag == \"\"\n"
        "\tif s.strategy == StrategyLeastLoad && s.pinnedTag == \"\" && len(s.qualified) > 1 {\n"
        "\t\tif detour := s.pickLeastLoadLocked(network, tried, now); detour != nil {\n"
        "\t\t\treturn detour\n"
        "\t\t}\n"
        "\t}\n")
    replace_once(selector,
        "\t// Everything is cooling down. Better to retry a penalised member than to\n"
        "\t// hand the client a failure.\n"
        "\tfor _, node := range s.ranked {\n"
        "\t\tif detour := s.usableLocked(node.tag, network, tried, now, true); detour != nil {\n"
        "\t\t\treturn detour\n"
        "\t\t}\n"
        "\t}\n\treturn nil\n}",
        "\t// Everything is cooling down. Better to retry a penalised member than to\n"
        "\t// hand the client a failure.\n"
        "\tfor _, node := range s.ranked {\n"
        "\t\tif detour := s.usableLocked(node.tag, network, tried, now, true); detour != nil {\n"
        "\t\t\treturn detour\n"
        "\t\t}\n"
        "\t}\n"
        "\t// No member is usable at all: the Xray fallbackTag.\n"
        "\tif s.fallbackOutbound != nil && !tried[s.fallbackOutbound.Tag()] {\n"
        "\t\treturn s.fallbackOutbound\n"
        "\t}\n\treturn nil\n}")
    replace_once(selector,
        "\t\tconn, err := detour.DialContext(ctx, network, destination)\n"
        "\t\tif err == nil {\n"
        "\t\t\ts.noteDialResult(detour, N.NetworkName(network), nil)\n"
        "\t\t\treturn s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil\n"
        "\t\t}",
        "\t\tconn, err := detour.DialContext(ctx, network, destination)\n"
        "\t\tif err == nil {\n"
        "\t\t\ts.noteDialResult(detour, N.NetworkName(network), nil)\n"
        "\t\t\tconn = s.trackConn(detour.Tag(), conn)\n"
        "\t\t\treturn s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil\n"
        "\t\t}")
    replace_once(selector,
        "\t\tconn, err := detour.ListenPacket(ctx, destination)\n"
        "\t\tif err == nil {\n"
        "\t\t\ts.noteDialResult(detour, N.NetworkUDP, nil)\n"
        "\t\t\treturn s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil\n"
        "\t\t}",
        "\t\tconn, err := detour.ListenPacket(ctx, destination)\n"
        "\t\tif err == nil {\n"
        "\t\t\ts.noteDialResult(detour, N.NetworkUDP, nil)\n"
        "\t\t\tconn = s.trackPacketConn(detour.Tag(), conn)\n"
        "\t\t\treturn s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil\n"
        "\t\t}")
    replace_once(selector,
        "\t// While balancing, every qualified member carries traffic, not only the current pick.\n"
        "\tif s.balance && s.pinnedTag == \"\" {",
        "\t// While balancing (or least-load balancing), every qualified member carries traffic.\n"
        "\tif (s.balance || s.strategy == StrategyLeastLoad) && s.pinnedTag == \"\" {")

    helper_dir = target / "protocol" / "group"
    helper_dir.chmod(helper_dir.stat().st_mode | stat.S_IWUSR)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "singbox").glob("*.go"):
        copy_writable(source, helper_dir / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./.qthrone/sing-box"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
