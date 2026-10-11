"""Means and spreads for A/B runs. NOTES N-046: one Dota replay run differs from the next by
about 10%, so a single run proves nothing and a difference below the noise is reported as such."""
import math


def summary(values):
    n = len(values)
    if n == 0:
        return {"n": 0}
    mean = sum(values) / n
    sd = math.sqrt(sum((v - mean) ** 2 for v in values) / (n - 1)) if n > 1 else 0.0
    return {"n": n, "mean": mean, "sd": sd, "min": min(values), "max": max(values)}


def welch_t(a, b):
    """t of the difference of two means with unequal spreads; None when it cannot be told."""
    sa, sb = summary(a), summary(b)
    if sa["n"] < 2 or sb["n"] < 2:
        return None
    se = math.sqrt(sa["sd"] ** 2 / sa["n"] + sb["sd"] ** 2 / sb["n"])
    if se == 0:  # identical runs within each side
        return 0.0 if sa["mean"] == sb["mean"] else math.copysign(math.inf, sb["mean"] - sa["mean"])
    return (sb["mean"] - sa["mean"]) / se


def compare(base, other, noise_pct=10.0):
    """Verdict on other vs base: the difference must beat both the run-to-run noise and t >= 2."""
    sa, sb = summary(base), summary(other)
    if sa["n"] == 0 or sb["n"] == 0 or sa["mean"] == 0:
        return {"diff_pct": None, "t": None, "verdict": "not enough runs"}
    diff = (sb["mean"] - sa["mean"]) / sa["mean"] * 100
    t = welch_t(base, other)
    if sa["n"] < 2 or sb["n"] < 2:
        verdict = "one run each: no verdict"
    elif abs(diff) < noise_pct or t is None or abs(t) < 2:
        verdict = "within noise"
    else:
        verdict = "faster" if diff > 0 else "slower"
    return {"diff_pct": diff, "t": t, "verdict": verdict}


def schedule(variants, repeat, order="abba"):
    """Run order. abba alternates the direction every round so slow drift (heat, battery)
    falls on both variants alike; interleaved is ABAB; blocks is AAABBB."""
    names = list(variants)
    out = []
    if order == "blocks":
        for v in names:
            out += [(r, v) for r in range(repeat)]
        return out
    for r in range(repeat):
        seq = names if (order == "interleaved" or r % 2 == 0) else list(reversed(names))
        out += [(r, v) for v in seq]
    return out
