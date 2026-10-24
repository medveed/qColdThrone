import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path


def patch(core: Path):
    module = "github.com/xchacha20-poly1305/sing-trusttunnel"
    info = json.loads(subprocess.check_output(["go", "mod", "download", "-json", module + "@v0.3.2"], cwd=core))
    lock = json.loads((Path(__file__).resolve().parents[1] / "core-dependencies.json").read_text())[module]
    if info.get("Sum") != lock["sum"] or info.get("GoModSum") != lock["mod_sum"]:
        raise ValueError("TrustTunnel source checksum mismatch")
    target = core / ".qthrone" / "trusttunnel"
    target.mkdir(parents=True, exist_ok=True)
    for source in Path(info["Dir"]).glob("*"):
        if source.is_file():
            destination = target / source.name
            if destination.exists():
                destination.chmod(stat.S_IREAD | stat.S_IWRITE)
            shutil.copyfile(source, destination)
    client = target / "client.go"
    content = client.read_text()
    before = "\treturn client, nil\n}"
    if content.count(before) != 1:
        raise ValueError("TrustTunnel constructor changed; review fallback hook")
    client.write_text(content.replace(before, "\tif options.QUIC {\n\t\tif err = client.enableHTTPSFallback(options); err != nil { return nil, err }\n\t}\n" + before), newline="\n")
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "trusttunnel").glob("*.go"):
        shutil.copyfile(source, target / source.name)
    subprocess.run(["go", "mod", "edit", "-replace=" + module + "=./.qthrone/trusttunnel"], cwd=core, check=True)
    for source in (Path(__file__).resolve().parents[1] / "core-patches" / "mobile").glob("*.go"):
        shutil.copyfile(source, core / "mobile" / source.name)
    patch_cold_request_hedge(core)


# The desktop core's hedged cold request (qThroneDesktop core/internal/probe/urltest.go, 1.8.1).
COLD_REQUEST_BEFORE = """func urlTestRequests(ctx context.Context, client *http.Client, url string, timeout, firstTimeout time.Duration, twice bool) (time.Duration, error) {
	duration, err := urlTest(ctx, client, url, firstTimeout)
	if err == nil && twice {
		return urlTest(ctx, client, url, timeout)
	}
	return duration, err
}
"""
COLD_REQUEST_AFTER = """const ColdRequestHedgeDelay = time.Second

func urlTestRequests(ctx context.Context, client *http.Client, url string, timeout, firstTimeout time.Duration, twice bool) (time.Duration, error) {
	if !twice {
		return urlTest(ctx, client, url, firstTimeout)
	}
	if _, err := hedgedURLTest(ctx, client, url, firstTimeout, ColdRequestHedgeDelay); err != nil {
		return 0, err
	}
	return urlTest(ctx, client, url, timeout)
}

func hedgedURLTest(ctx context.Context, client *http.Client, url string, timeout, delay time.Duration) (time.Duration, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type outcome struct {
		duration time.Duration
		err      error
	}
	results := make(chan outcome, 2)
	attempt := func() {
		duration, err := urlTest(ctx, client, url, timeout)
		results <- outcome{duration, err}
	}
	go attempt()
	hedge := time.NewTimer(delay)
	defer hedge.Stop()
	pending := 1
	for {
		select {
		case <-hedge.C:
			pending++
			go attempt()
		case res := <-results:
			pending--
			if res.err == nil || pending == 0 {
				return res.duration, res.err
			}
		}
	}
}
"""


def patch_cold_request_hedge(core: Path):
    path = core / "internal" / "probe" / "urltest.go"
    content = path.read_text()
    if content.count(COLD_REQUEST_BEFORE) != 1:
        raise ValueError("URL test cold request changed; review the hedge patch")
    path.write_text(content.replace(COLD_REQUEST_BEFORE, COLD_REQUEST_AFTER), newline=chr(10))


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
