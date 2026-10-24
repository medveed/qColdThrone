"""Patch the pinned sing-box auto-selector for the Xray-compatible offsite JSON config.

Two things the fork's group does not have on its own:

* `fallback` — Xray `routing.balancers[].fallbackTag`: the tag dialed when no member of the group is
  usable at all.
* `strategy: "leastPing"` — Xray's leastPing orders the healthy members by average RTT; the fork's
  default orders by deviation first, which is exactly Xray's leastLoad comparator. leastLoad itself
  needs no new code: `balance`/`balance_mode`/`expected`/`baselines`/`max_rtt`/`fail_tolerance`
  already reproduce it (random among the top-expected healthy members).

Sources are copied from the checksummed Go module cache; the shared cache is never mutated.
"""
import json
import os
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


def rmtree_writable(path: Path):
    if not path.exists():
        return
    # Directories can be left mode 000 by a failed removal; restore rwx top-down so the walk can descend.
    os.chmod(path, stat.S_IRWXU)
    for root, dirs, files in os.walk(path):
        for name in dirs:
            try:
                os.chmod(os.path.join(root, name), stat.S_IRWXU)
            except OSError:
                pass
        for name in files:
            try:
                os.chmod(os.path.join(root, name), stat.S_IRWXU)
            except OSError:
                pass
    shutil.rmtree(path)


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
    rmtree_writable(target)
    shutil.copytree(info["Dir"], target, copy_function=copy_writable)

    options = target / "option" / "group.go"
    expected_field = '\tExpected      int                  `json:"expected,omitempty"`\n'
    replace_once(options, expected_field, expected_field +
        "\n\t// Fallback is the tag dialed when no member of the group is usable at all\n"
        "\t// (Xray routing.balancers[].fallbackTag). Optional.\n"
        "\tFallback string `json:\"fallback,omitempty\"`\n"
        "\t// Strategy \"leastPing\" ranks healthy members by average RTT first (Xray leastPing);\n"
        "\t// the default orders by deviation first (Xray leastLoad).\n"
        "\tStrategy string `json:\"strategy,omitempty\"`\n")

    selector = target / "protocol" / "group" / "autoselector.go"
    replace_once(selector,
        "\tbalanceInterval time.Duration\n\tdialRetries     int\n",
        "\tbalanceInterval  time.Duration\n\tdialRetries      int\n"
        "\tfallbackTag      string\n\tfallbackOutbound adapter.Outbound\n"
        "\tstrategy         string\n")
    replace_once(selector,
        "\t\tdialRetries:                  options.DialRetries,\n",
        "\t\tdialRetries:                  options.DialRetries,\n"
        "\t\tfallbackTag:                  options.Fallback,\n"
        "\t\tstrategy:                     options.Strategy,\n")
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
    replace_once(selector, "\tsortNodes(ordered)\n", "\tsortNodes(ordered, s.strategy)\n")
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

    health = target / "protocol" / "group" / "autoselector_health.go"
    replace_once(health,
        "func sortNodes(nodes []*rankedNode) {",
        "func sortNodes(nodes []*rankedNode, strategy string) {")
    replace_once(health,
        "\t\tif l.stats.Deviation != r.stats.Deviation {\n"
        "\t\t\treturn l.stats.Deviation < r.stats.Deviation\n"
        "\t\t}\n"
        "\t\tif l.stats.Average != r.stats.Average {\n"
        "\t\t\treturn l.stats.Average < r.stats.Average\n"
        "\t\t}\n",
        "\t\tif strategy == StrategyLeastPing {\n"
        "\t\t\tif l.stats.Average != r.stats.Average {\n"
        "\t\t\t\treturn l.stats.Average < r.stats.Average\n"
        "\t\t\t}\n"
        "\t\t\tif l.stats.Deviation != r.stats.Deviation {\n"
        "\t\t\t\treturn l.stats.Deviation < r.stats.Deviation\n"
        "\t\t\t}\n"
        "\t\t} else {\n"
        "\t\t\tif l.stats.Deviation != r.stats.Deviation {\n"
        "\t\t\t\treturn l.stats.Deviation < r.stats.Deviation\n"
        "\t\t\t}\n"
        "\t\t\tif l.stats.Average != r.stats.Average {\n"
        "\t\t\t\treturn l.stats.Average < r.stats.Average\n"
        "\t\t\t}\n"
        "\t\t}\n")

    helper_dir = target / "protocol" / "group"
    helper_dir.chmod(helper_dir.stat().st_mode | stat.S_IWUSR)
    health_test = helper_dir / "autoselector_health_test.go"
    test_text = health_test.read_text()
    if test_text.count("sortNodes(nodes)") < 1:
        raise ValueError("Pinned sing-box health test changed; review the balancer patch")
    health_test.write_text(test_text.replace("sortNodes(nodes)", 'sortNodes(nodes, "")'), newline="\n")
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "singbox").glob("*.go"):
        copy_writable(source, helper_dir / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./.qthrone/sing-box"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
