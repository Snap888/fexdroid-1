#!/usr/bin/env python3
"""fxctl: drive fexdroid on a phone over adb for tests (NOTES N-055). Python 3 standard library.

  fxctl status | counters | focus | procs | perf | log [-n N] | report | battery | screen
  fxctl start steam|dota|steam-arm64      fxctl stop      fxctl steam-url steam://rungameid/570
  fxctl fps [--seconds 60]                fxctl screenshot out.png
  fxctl validate SCENARIO.json            fxctl run SCENARIO.json [--repeat N] [--force]

The phone: --serial or ANDROID_SERIAL, else the only device adb sees. Commands that load the
phone (start, steam-url, fps, run) refuse when it is not charging, unless --force.
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device, DeviceError  # noqa: E402
from screen import parse_raw  # noqa: E402
import scenario  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def build_parser():
    ap = argparse.ArgumentParser(prog="fxctl", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="adb serial (default: ANDROID_SERIAL)")
    ap.add_argument("--force", action="store_true", help="go on although the phone is not charging")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("status", "counters", "focus", "procs", "perf", "report", "battery", "screen", "stop"):
        sub.add_parser(name)
    p = sub.add_parser("log"); p.add_argument("-n", type=int, default=100)
    p = sub.add_parser("start"); p.add_argument("game", choices=("steam", "dota", "steam-arm64"))
    p = sub.add_parser("steam-url"); p.add_argument("url")
    p = sub.add_parser("fps"); p.add_argument("--seconds", type=int, default=60); p.add_argument("--window", type=int, default=10)
    p = sub.add_parser("screenshot"); p.add_argument("out")
    p = sub.add_parser("validate"); p.add_argument("scenario")
    p = sub.add_parser("run")
    p.add_argument("scenario")
    p.add_argument("--repeat", type=int, help="rounds per variant (overrides the file)")
    p.add_argument("--variant", action="append", help="only these variants (repeatable)")
    p.add_argument("--results", help="directory for results (default build/fxctl/<name>-<time>)")
    return ap


def require_charging(dev, force):
    b = dev.battery()
    if not b["charging"] and not force:
        raise DeviceError(f"the phone is not charging (battery {b['level']}%): tests only while it charges; --force overrides")
    return b


def main(argv=None):
    a = build_parser().parse_args(argv)
    if a.cmd == "validate":
        sc = scenario.load(a.scenario)
        print(f"{sc['name']}: ok, {len(sc['variants'])} variant(s) x {sc['repeat']} round(s), "
              f"{len(sc['run'])} steps per round")
        return 0
    dev = Device(a.serial)
    p = lambda obj: print(json.dumps(obj, indent=2, ensure_ascii=False))  # noqa: E731
    if a.cmd in ("status", "counters", "focus", "procs"):
        p(dev.control(a.cmd))
    elif a.cmd in ("perf", "report"):
        print(dev.control(a.cmd, timeout=600)["text"])
    elif a.cmd == "log":
        print("\n".join(dev.control("log", a.n)["lines"]))
    elif a.cmd == "battery":
        p(dev.battery())
    elif a.cmd == "screen":
        p(dev.screen_state())
    elif a.cmd == "stop":
        dev.control("stop")
    elif a.cmd == "start":
        dev.check_target()
        require_charging(dev, a.force)
        dev.start_session(a.game)
    elif a.cmd == "steam-url":
        require_charging(dev, a.force)
        dev.steam_url(a.url)
    elif a.cmd == "screenshot":
        with open(a.out, "wb") as f:
            f.write(parse_raw(dev.screencap_raw()).png())
        print(a.out)
    elif a.cmd == "fps":
        require_charging(dev, a.force)
        sc = scenario.validate({"name": "fps", "run": []})
        r = scenario.Runner(dev, sc, results_dir=None, force=a.force)
        r.variant = {"name": "now"}
        r.measure(a.seconds, a.window)
    elif a.cmd == "run":
        sc = scenario.load(a.scenario)
        if a.repeat:
            sc["repeat"] = a.repeat
        if a.variant:
            unknown = set(a.variant) - {v["name"] for v in sc["variants"]}
            if unknown:
                raise scenario.ScenarioError(f"no such variant: {', '.join(sorted(unknown))}")
            sc["variants"] = [v for v in sc["variants"] if v["name"] in a.variant]
        out = a.results or os.path.join(REPO, "build", "fxctl", f"{sc['name']}-{time.strftime('%Y%m%d-%H%M%S')}")
        r = scenario.Runner(dev, sc, results_dir=out, force=a.force)
        print(r.run())
        return 1 if r.failures and not r.samples else 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (DeviceError, scenario.ScenarioError, scenario.StepFailed) as e:
        print(f"fxctl: {e}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)
