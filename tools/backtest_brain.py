#!/usr/bin/env python3
"""Walk-forward backtest of a LastLauncher brain export (Settings > Suggestions
> Export). Replays every launch chronologically, ranking with only earlier rows
via the exact same math as PredictionEngine (notification bonus and boosts are
not replayable and are excluded). Companion to predict/Backtester.kt: this is
the analyst's version, with baselines, ablations, paired significance tests and
data-health tables.

Usage:  python tools/backtest_brain.py path/to/lastlauncher-brain.json
"""
import argparse
import datetime
import json
import math
import statistics
from collections import Counter, defaultdict

DAY = 86_400_000.0
HIST = 60 * DAY
DECAY = 20.0
W_HOUR, W_DAY, W_TRANS, W_TRIG, W_MISS = 1.2, 0.3, 2.5, 3.5, 1.5
MIN_CONFIDENCE = 1.0
CRUSH_LO, CRUSH_HI = 15_000, 45 * 60_000
JUST_USED_FACTOR = 0.05
WARMUP_FRACTION = 0.2


def weekend(dow):
    return dow in (1, 7)  # Calendar.SUNDAY / SATURDAY


def hourdiff(a, b):
    d = abs(a - b)
    return min(d, 24 - d)


def load(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    launches = sorted(d["launches"], key=lambda r: r["ts"])
    misses = sorted(d.get("misses", []), key=lambda r: r["ts"])
    return d, launches, misses


def rank_of(scores, target, favorites):
    """Engine ranking: confident scores, then favorites fillers, then the rest.
    (defaultPicks can't be replayed - favorites dominate the filler zone.)"""
    ranked = sorted((p for p, s in scores.items() if s >= MIN_CONFIDENCE),
                    key=lambda p: -scores[p])
    seen = set(ranked)
    for p in favorites:
        if p not in seen:
            ranked.append(p)
            seen.add(p)
    for p in sorted(scores, key=lambda p: -scores[p]):
        if p not in seen:
            ranked.append(p)
            seen.add(p)
    try:
        return ranked.index(target) + 1
    except ValueError:
        return None


def evaluate(launches, misses, favorites, warmup,
             wh=W_HOUR, wd=W_DAY, wt=W_TRANS, wg=W_TRIG, crush=JUST_USED_FACTOR):
    """One walk-forward pass; returns (hit@1, hit@3, hit@12, MRR, per-row ranks)."""
    n = len(launches)
    h1 = h3 = h12 = 0
    mrr = 0.0
    ranks = []
    for i in range(warmup, n):
        t = launches[i]
        now, hn, ctx = t["ts"], t["hour"], t["ctx"]
        wkn = weekend(t["dow"])
        prev = launches[i - 1]["pkg"]
        gap = now - launches[i - 1]["ts"]
        scores = defaultdict(float)
        for r in launches[:i]:
            age = now - r["ts"]
            if age > HIST:
                continue
            w = math.exp(-(age / DAY) / DECAY)
            s = w
            if hourdiff(r["hour"], hn) <= 1:
                s += wh * w
            if weekend(r["dow"]) == wkn:
                s += wd * w
            if r["prev"] == prev:
                s += wt * w
            if ctx is not None and r["ctx"] == ctx:
                s += wg * w
            scores[r["pkg"]] += s
        for r in misses:
            age = now - r["ts"]
            if age < 0 or age > HIST:
                continue
            w = math.exp(-(age / DAY) / DECAY)
            s = w
            if hourdiff(r["hour"], hn) <= 1:
                s += wh * w
            if weekend(r["dow"]) == wkn:
                s += wd * w
            if ctx is not None and r["ctx"] == ctx:
                s += wg * w
            scores[r["pkg"]] -= W_MISS * s
        if CRUSH_LO <= gap <= CRUSH_HI and prev in scores:
            scores[prev] *= crush
        rk = rank_of(scores, t["pkg"], favorites)
        ranks.append(rk)
        if rk:
            if rk <= 1:
                h1 += 1
            if rk <= 3:
                h3 += 1
            if rk <= 12:
                h12 += 1
            mrr += 1.0 / rk
    m = n - warmup
    return h1 / m, h3 / m, h12 / m, mrr / m, ranks


def baseline(launches, warmup, kind):
    """LRU (rank by last use) or MFU (rank by 60-day count)."""
    h1 = h3 = h12 = 0
    mrr = 0.0
    n = len(launches)
    for i in range(warmup, n):
        now = launches[i]["ts"]
        if kind == "lru":
            key = {}
            for r in launches[:i]:
                if now - r["ts"] <= HIST:
                    key[r["pkg"]] = r["ts"]
        else:
            key = Counter(r["pkg"] for r in launches[:i] if now - r["ts"] <= HIST)
        ranked = sorted(key, key=lambda p: -key[p])
        try:
            rk = ranked.index(launches[i]["pkg"]) + 1
        except ValueError:
            rk = None
        if rk:
            if rk <= 1:
                h1 += 1
            if rk <= 3:
                h3 += 1
            if rk <= 12:
                h12 += 1
            mrr += 1.0 / rk
    m = n - warmup
    return h1 / m, h3 / m, h12 / m, mrr / m


def paired(base_ranks, ranks, k=3):
    """Paired top-k win/loss with a normal-approx z (|z|>1.96 ~ p<0.05)."""
    win = sum(1 for a, b in zip(base_ranks, ranks)
              if (b and b <= k) and not (a and a <= k))
    loss = sum(1 for a, b in zip(base_ranks, ranks)
               if (a and a <= k) and not (b and b <= k))
    z = (win - loss) / math.sqrt(win + loss) if win + loss else 0.0
    return win, loss, z


def fmt(name, h1, h3, h12, mrr, extra=""):
    print(f"  {name:34s} hit@1={h1:.3f} hit@3={h3:.3f} hit@12={h12:.3f} MRR={mrr:.3f}{extra}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("export", help="path to lastlauncher-brain.json")
    args = ap.parse_args()
    d, launches, misses = load(args.export)
    favorites = d.get("favorites", [])
    n = len(launches)
    warmup = int(n * WARMUP_FRACTION)

    def dt(ts):
        return datetime.datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M")

    span_days = (launches[-1]["ts"] - launches[0]["ts"]) / DAY
    print(f"export {args.export}")
    print(f"  app {d.get('app_version')}  exported {dt(d['exported_at'])}")
    print(f"  {n} launches / {len(misses)} misses over {span_days:.1f} days, "
          f"{len(set(r['pkg'] for r in launches))} apps, warmup {warmup}")
    top3 = Counter(r["pkg"] for r in launches).most_common(3)
    print(f"  top-3 apps hold {sum(c for _, c in top3) / n * 100:.1f}% of launches "
          f"({', '.join(p.rsplit('.', 1)[-1] for p, _ in top3)})")
    gaps = [(launches[i]["ts"] - launches[i - 1]["ts"]) / 60000 for i in range(1, n)]
    print(f"  median inter-launch gap {statistics.median(gaps):.1f} min; "
          f"{sum(1 for g in gaps if g <= 5) / len(gaps) * 100:.0f}% within 5 min")

    print("\nSIGNAL HEALTH (first/last seen per trigger - watch for dead or too-new signals)")
    seen = defaultdict(list)
    for r in launches:
        if r["ctx"]:
            seen[r["ctx"]].append(r["ts"])
    if not seen:
        print("  no context-stamped rows at all")
    for c, ts in sorted(seen.items(), key=lambda kv: -len(kv[1])):
        print(f"  {c:20s} n={len(ts):4d}  first {dt(min(ts))}  last {dt(max(ts))}")
    covered = sum(len(v) for v in seen.values())
    print(f"  ctx coverage: {covered}/{n} rows ({covered / n * 100:.1f}%)")

    print("\nWALK-FORWARD (notification bonus & boosts excluded - not replayable)")
    e1, e3, e12, emrr, base_ranks = evaluate(launches, misses, favorites, warmup)
    fmt("engine (current weights)", e1, e3, e12, emrr)
    fmt("MFU baseline", *baseline(launches, warmup, "mfu"))
    fmt("LRU baseline", *baseline(launches, warmup, "lru"))

    print("\nABLATIONS (one term zeroed at a time)")
    for label, kw in (("no hour", {"wh": 0.0}), ("no daytype", {"wd": 0.0}),
                      ("no transition", {"wt": 0.0}), ("no trigger", {"wg": 0.0})):
        h1, h3, h12, mrr, ranks = evaluate(launches, misses, favorites, warmup, **kw)
        w, l, z = paired(base_ranks, ranks)
        fmt(label, h1, h3, h12, mrr, f"  [top-3 win={w} loss={l} z={z:+.2f}]")

    print("\nJUST-USED CRUSH (the factor sweep; 1.0 = crush removed)")
    for cf in (0.05, 0.4, 1.0):
        h1, h3, h12, mrr, ranks = evaluate(launches, misses, favorites, warmup, crush=cf)
        w, l, z = paired(base_ranks, ranks)
        fmt(f"crush factor {cf}", h1, h3, h12, mrr, f"  [win={w} loss={l} z={z:+.2f}]")
    inwin = sum(1 for i in range(warmup, n)
                if launches[i]["pkg"] == launches[i - 1]["pkg"]
                and CRUSH_LO <= launches[i]["ts"] - launches[i - 1]["ts"] <= CRUSH_HI)
    print(f"  crushed-app relaunches inside the window: {inwin} "
          f"({inwin / (n - warmup) * 100:.1f}% of evaluated rows)")

    print("\nREBOUND RATES (same-app relaunch in 5s-45min; roadmap 1.6 needs >0.3, n>=10)")
    reb_n, reb_hit = Counter(), Counter()
    for i in range(1, n):
        reb_n[launches[i - 1]["pkg"]] += 1
        gap = launches[i]["ts"] - launches[i - 1]["ts"]
        if launches[i]["pkg"] == launches[i - 1]["pkg"] and 5_000 <= gap <= CRUSH_HI:
            reb_hit[launches[i - 1]["pkg"]] += 1
    rows = [(reb_hit[p] / reb_n[p], p) for p in reb_n if reb_n[p] >= 10]
    for rate, p in sorted(rows, reverse=True)[:8]:
        print(f"  {p:44s} n={reb_n[p]:4d} rebound={rate:.2f}")

    print("\nTRIGGER LIFT (P(app|ctx) / P(app); pooling shows as mixed lifts under one token)")
    base_c = Counter(r["pkg"] for r in launches)
    for ctx, cnt in Counter(r["ctx"] for r in launches if r["ctx"]).most_common():
        apps = Counter(r["pkg"] for r in launches if r["ctx"] == ctx)
        parts = []
        for p, c in apps.most_common(4):
            lift = (c / cnt) / (base_c[p] / n)
            parts.append(f"{p.rsplit('.', 1)[-1]} x{lift:.1f}({c})")
        print(f"  {ctx:20s} {', '.join(parts)}")

    print("\nLEARNING CURVE (hit@3 per quartile of the evaluated range)")
    m = n - warmup
    q = m // 4
    for k in range(4):
        seg = base_ranks[k * q:(k + 1) * q]
        print(f"  Q{k + 1}: {sum(1 for r in seg if r and r <= 3) / len(seg):.3f}")

    print("\nCaveats: single user; notification/boost terms excluded; differences "
          f"under ~{2 / math.sqrt(m) * 100:.1f}pp are noise at n={m} - trust the "
          "paired win/loss z, not raw deltas. Signals younger than the export "
          "window under-measure (check SIGNAL HEALTH first/last columns).")


if __name__ == "__main__":
    main()
