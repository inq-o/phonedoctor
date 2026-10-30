"""증상 감지 특징 추출기 fx0 초안 (PC 기준 구현). 가정·미확인 사항은 같은 폴더 README.md

기기 추출기(Kotlin)는 이 파일과 같은 입력에서 같은 행(값·상태·판정)을 내야 한다.

- 입력: 조회 1회분 = {wall_to, elapsed, boot_count, events}. events는 OS가 query_range()의 [begin, wall_to)에서
  돌려준 순서 그대로. 사용자 잠금 해제 전처럼 조회가 null이면 events=None
- 커서는 워터마크: wall_to - SETTLE_MS 앞의 이벤트만 확정 처리하고, 나머지는 다음 조회에서 다시 받음.
  확정된 시각 묶음은 다시 처리하지 않으므로 키 집합 중복 제거가 없음 (같은 튜플 두 개는 두 행)
- 같은 ms 묶음의 순서는 모름 → 가능한 순서를 모두 계산 ("세계" 여러 개). 특징 값이 세계마다 다르면 TIED,
  규칙 결과가 다르면 HOLD. order_assume="lifecycle"이면 생명주기 가정으로 순서를 줄임 (분석용, 확정 판정에 안 씀)
- 관측 구간(segment): 재부팅·시계 변경·긴 공백이면 상태를 비우고 새 구간. 어느 시계·부팅의 기록인지 가를 수 없는
  시각 범위는 행을 만들지 않고 개수만 셈 (uncertain_discarded). 버릴 범위는 합치기만 하고 줄이지 않음.
  재부팅하면서 시계가 뒤로 가면 버릴 범위의 끝을 모름 → 옛 부팅의 SHUTDOWN이 보일 때까지 행을 만들지 않음
- 시계 변경 때 OS가 저장된 기록의 시각을 옮기는 경우(AOSP 15 UsageStatsDatabase.onTimeChanged, 2초 넘게 어긋날 때)와
  옮기지 않는 경우를 모두 가정하고, 어느 쪽이든 섞일 수 있는 범위를 버림
- 판정: rule_v0t = 규칙 v0를 특징으로 다시 쓴 시간 기준 판. 3값 논리(참·거짓·모름), 모름이 남으면 HOLD

사용:
  python3 featx.py                      검증 사례(cases.json) 전부 확인
  python3 featx.py real <launchers.csv> <events.csv>...   실측 자료에서 rule_v0와 rule_v0t 차이 집계
"""
import copy
import csv
import itertools
import json
import os
import sys

SCHEMA = "fx0-draft"
CAP_MS = 600_000            # 경과 시간 특징의 관측 창이자 값 상한 (동결 전 결정)
SETTLE_MS = 5_000           # 워터마크 여유: 기록 지연이 이보다 짧다고 가정 [실기기 미확인]
CLOCK_TOL_MS = 1_000        # 벽시계 경과와 elapsed 경과의 허용 차이. OS가 기록을 옮기는 기준(OS_SHIFT_MS)보다 작게
OS_SHIFT_MS = 2_000         # AOSP 15 UsageStatsService.TIME_CHANGE_THRESHOLD_MILLIS [갤럭시 미확인]
RETENTION_SAFE_MS = 3 * 86_400_000  # 이보다 오래 조회를 못 하면 공백 구간 [실기기 미확인: 보존 기간]
MAX_TIE = 6                 # 같은 ms 묶음에서 순서를 모르는 이벤트 수 상한 (6! = 720가지)
MAX_WORLDS = 64
HANDOFF_MS, LOOKBACK_MS = 2000, 3000

RESUMED, PAUSED, USER_INTERACTION = 1, 2, 7
SCREEN_ON, SCREEN_OFF, KEYGUARD_SHOWN, KEYGUARD_HIDDEN = 15, 16, 17, 18
SHUTDOWN, STARTUP = 26, 27
TYPE = {"RESUMED": 1, "PAUSED": 2, "USER_INTERACTION": 7, "SCREEN_ON": 15, "SCREEN_OFF": 16,
        "KEYGUARD_SHOWN": 17, "KEYGUARD_HIDDEN": 18, "SHUTDOWN": 26, "STARTUP": 27}
ORDERED = {RESUMED, PAUSED, USER_INTERACTION, SCREEN_ON, SCREEN_OFF, KEYGUARD_HIDDEN}   # 특징에 영향을 주는 종류

OBS, NONE, NC, LF, TIED = "OBSERVED", "NONE_IN_WINDOW", "NOT_COVERED", "LOOKUP_FAILED", "TIED"
FEATURES = ["F0", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10"]
RULES = ["R1", "R2", "R3", "R4", "R5"]


def empty_world(assume=None):
    w = {"prev": None, "off_after_prev": False, "paused": {}, "ui": {}, "on": None, "unlock": None, "off": None}
    if assume == "lifecycle":
        w["life"] = {}   # 액티비티별 마지막 RESUMED/PAUSED (가정 모드에서만 씀)
    return w


def init_state(start, kind="install", wall=None, elapsed=None, boot_count=None, order_assume=None):
    """수집 시작. wall/elapsed는 시작 시점 기준값 (없으면 start, 0)"""
    return {"schema": SCHEMA, "order_assume": order_assume, "cursor": start, "skip_until": start,
            "seg": {"id": 1, "start": start, "kind": kind}, "worlds": [empty_world(order_assume)], "pending": [],
            "uncertain": [], "open_q": None, "boot_max_ts": None,
            "last": {"wall": start if wall is None else wall, "elapsed": 0 if elapsed is None else elapsed,
                     "boot_count": boot_count},
            "next_query": 1, "next_ingest": 1, "counts": {}}


def _bump(state, k, n=1):
    state["counts"][k] = state["counts"].get(k, 0) + n


def _reset(state, start, kind):
    state["seg"] = {"id": state["seg"]["id"] + 1, "start": start, "kind": kind}
    state["worlds"] = [empty_world(state["order_assume"])]
    _bump(state, "segments_" + kind)


def _uncertain(state, frm, to, kind, after=None):
    """시계 기준이 바뀜. frm부터 다시 읽되 [frm, to)는 옛 시계 기록과 섞일 수 있어 쓰지 않고, 새 구간은 그 끝에서 시작.
    앞서 버리기로 한 범위가 남아 있으면 합침 (줄이면 옛 기록이 다시 판정됨).
    to=None이면 끝을 모름(열린 격리): 시각이 after 이상인 SHUTDOWN이 보일 때까지 행을 만들지 않음 (_close_open)"""
    state["cursor"] = frm
    state["pending"] = []   # 옛 시계 기준 부팅 경계는 버림
    state["uncertain"] = (state["uncertain"] + [[frm, to, kind]])[-20:]
    q = state["open_q"]
    if q is not None:   # 이미 열린 격리: 닫을 때 지킬 하한만 올림. 구간은 닫을 때 새로 엶
        q["floor"] = max(q["floor"], frm if to is None else to)
        q["closable"] = q["closable"] and to is not None
        return
    if to is None:
        state["open_q"] = {"after": after, "shift": 0, "floor": max(state["skip_until"], frm), "closable": True, "kind": kind}
        state["skip_until"] = None
        return
    state["skip_until"] = max(state["skip_until"], frm, to)
    _reset(state, state["skip_until"], kind)


def _close_open(state, events):
    """열린 격리를 옛 부팅의 SHUTDOWN으로 닫음. AOSP는 부팅 때 옛 부팅의 마지막 저장 시각으로 SHUTDOWN을 기록하므로
    옛 부팅 기록은 그 시각까지만 있음 [갤럭시 미확인]. 그보다 앞 부팅의 SHUTDOWN과 섞이지 않게 after 이상만 봄.
    OS가 기록을 옮겼다면 after도 같이 옮겨졌으므로 앞으로 옮긴 만큼(shift > 0) 올려서 봄"""
    q = state["open_q"]
    if q is None or not q["closable"]:
        return
    th = q["after"] + max(0, q["shift"])
    sd = [e["ts"] for e in events if e["type"] == SHUTDOWN and e["ts"] >= th]
    if sd:
        state["open_q"] = None
        state["skip_until"] = max(q["floor"], max(sd) + 1)
        _reset(state, state["skip_until"], q["kind"])


def _rebase(state, wall, el, bc, events):
    """조회 시각(wall·elapsed·BOOT_COUNT)과 새 STARTUP으로 재부팅·시계 변경을 판별. 이번 조회분을 버려야 하면 False"""
    last = state["last"]
    state["last"] = {"wall": wall, "elapsed": el, "boot_count": bc}
    base = wall - el   # 지금 부팅의 시작 시각 (지금 시계 기준)
    drift = (wall - last["wall"]) - (el - last["elapsed"])
    starts = sorted({e["ts"] for e in events if e["type"] == STARTUP and e["ts"] >= last["wall"]})   # 앞 조회 뒤 기록
    rebooted = el < last["elapsed"] or (bc is not None and last["boot_count"] is not None and bc != last["boot_count"])
    if state["open_q"] is not None and (rebooted or starts):
        # 열린 격리 중 재부팅·런타임 재시작: 새 SHUTDOWN이 생겨 어느 것이 그 옛 부팅의 끝인지 가를 수 없음
        state["open_q"]["closable"] = False
    if not rebooted and starts and abs(drift) <= CLOCK_TOL_MS:
        # elapsed가 이어지는 STARTUP: 런타임 재시작. 기기가 꺼져 있던 게 아니라 "없음"을 확정하지 않음
        state["pending"] += [{"start": s, "kind": "restart"} for s in starts]
        return True
    if rebooted or any(s >= base - CLOCK_TOL_MS for s in starts):   # 뒤쪽은 앞 조회의 elapsed가 더 작았던 재부팅
        old_max, state["boot_max_ts"] = state["boot_max_ts"], None
        if base < last["wall"] - CLOCK_TOL_MS:
            # 앞 조회보다 먼저 부팅했을 수 없음 → 재부팅하면서 시계도 뒤로 감. 옛 부팅은 앞 조회 뒤 꺼질 때까지 기록을 남겼을 수
            # 있고 그 끝은 지금 시각만으로 알 수 없음 → 열린 격리. 옛 부팅의 SHUTDOWN은 옛 부팅에서 본 마지막 기록 이후
            old_base = last["wall"] - last["elapsed"]
            _uncertain(state, base, None, "boot_clock", old_base if old_max is None else max(old_base, old_max))
            return True
        bounds = {s for s in starts if s >= base - CLOCK_TOL_MS} or {base}
        state["pending"] += [{"start": s, "kind": "boot"} for s in sorted(bounds | set(starts))]
        return True
    if abs(drift) > CLOCK_TOL_MS:
        # 어느 이벤트가 바뀌기 전 시계인지 모름 → 이번 조회분은 버림. 버릴 범위의 끝은 두 경우 중 늦은 쪽:
        # 기록을 옮기지 않으면 옛 시계 기록이 last.wall + elapsed 경과까지, 옮기면 이미 처리한 기록(커서 앞)이 앞으로 옮겨져
        # 다시 보일 수 있는 끝(OS가 옮긴 양은 앱이 본 차이와 OS 기준 2배까지 다를 수 있음)
        _bump(state, "clock_discarded", len(events))
        if state["open_q"] is not None:
            state["open_q"]["shift"] += drift
        keep = last["wall"] + (el - last["elapsed"])
        moved = state["cursor"] + drift + 2 * OS_SHIFT_MS if drift > 0 else keep
        _uncertain(state, wall - SETTLE_MS, max(keep, moved), "clock")
        return False
    return True


def query_range(state, wall, elapsed, boot_count):
    """수집기가 queryEvents에 넘길 [begin, end). 시계가 뒤로 가면 옛 커서가 wall보다 클 수 있어 기준을 다시 계산.
    STARTUP으로만 알 수 있는 재부팅을 놓치지 않게 옛 커서와 새 커서 중 앞쪽부터 읽음"""
    st = copy.deepcopy(state)
    _rebase(st, wall, elapsed, boot_count, [])
    begin = min(state["cursor"], st["cursor"], wall)
    if st["open_q"] is not None and state["open_q"] is None:   # 이번에 열리는 격리: 닫을 SHUTDOWN이 커서 앞에 있을 수 있음
        begin = min(begin, st["open_q"]["after"])
    return begin, wall


def _apply(w, e):
    t, typ, pkg, cls = e["ts"], e["type"], e["pkg"], e["cls"]
    if typ == RESUMED:
        w["prev"], w["off_after_prev"] = [pkg, cls, t], False
    elif typ == PAUSED:
        w["paused"][pkg] = t
    elif typ == USER_INTERACTION:
        w["ui"][pkg] = t
    elif typ == SCREEN_ON:
        w["on"] = t
    elif typ == KEYGUARD_HIDDEN:
        w["unlock"] = t
    elif typ == SCREEN_OFF:
        w["off"] = {"ts": t, "before": w["prev"][:2] if w["prev"] else None}
        w["off_after_prev"] = True
    if "life" in w and typ in (RESUMED, PAUSED):
        w["life"][pkg + "/" + cls] = typ


def _prune(w, now):
    """CAP보다 오래된 창 기반 상태는 지움. 특징 계산도 같은 창을 쓰므로 정리 시점과 무관하게 값이 같음"""
    for k in ("paused", "ui"):
        w[k] = {p: t for p, t in w[k].items() if now - t <= CAP_MS}
    for k in ("on", "unlock"):
        if w[k] is not None and now - w[k] > CAP_MS:
            w[k] = None


def _meta(snap, p, t):
    """판정 시점 스냅샷의 패키지 정보. 이벤트 뒤 설치·업데이트됐으면 그 이벤트에는 쓰지 않음"""
    info = snap.get(p)
    if info is None:
        return None, "absent"
    since = info.get("since")
    if since is None:
        return info, "unverified"
    return (None, "updated_after_event") if since > t else (info, "verified")


def features(w, seg, t, p, c, snap):
    age = t - seg["start"]
    boot = seg["kind"] == "boot"   # 부팅 구간: 시작 전엔 기기가 꺼져 있었으므로 "없음"이 확정
    none_state = (None, NONE) if boot else (None, NC)

    def win(ts):
        if ts is not None and t - ts <= CAP_MS:
            return (t - ts, OBS)
        return (None, NONE) if boot or age >= CAP_MS else (None, NC)

    prev = w["prev"]
    f = {"F0": (min(age, CAP_MS), OBS), "F1": win(w["ui"].get(p))}
    f["F2"] = (prev[0] == p, OBS) if prev else none_state
    f["F3"] = (min(t - prev[2], CAP_MS), OBS) if prev else none_state
    f["F4"] = win(w["paused"].get(p))
    f["F5"] = (w["off_after_prev"], OBS) if prev else none_state
    f["F6"] = win(w["on"])
    f["F7"] = win(w["unlock"])
    off = w["off"]
    if off is None:
        f["F8"] = none_state
    elif off["before"] is None:
        f["F8"] = (None, NC)          # 꺼질 때 앞 화면을 몰랐음
    else:
        f["F8"] = (off["before"] == [p, c], OBS)
    info, _ = _meta(snap, p, t)
    f["F9"] = (c in info["resolved"], OBS) if info else (None, LF)
    f["F10"] = (info["system"], OBS) if info else (None, LF)
    return f


def _le(f, thr, age):
    v, s = f
    if s == OBS:
        return v <= thr
    if s == NONE:
        return False
    if s == NC:
        return False if age >= thr else None   # 구간이 thr보다 길면 thr 안에는 확실히 없음
    return None


def _b(f):
    v, s = f
    return v if s == OBS else (False if s == NONE else None)


def _and(*xs):
    return False if False in xs else (True if all(x is True for x in xs) else None)


def _or(*xs):
    return True if True in xs else (False if all(x is False for x in xs) else None)


def _not(x):
    return None if x is None else not x


def rule_v0t(f, age, system_filter=True):
    """조건별 '실패' 여부 (True=실패, False=통과, None=모름). R1·R5의 LOOKUP_FAILED는 통과 (아이콘 숨긴 앱을 놓치지 않게, rule_v0와 같음)"""
    r = {"R1": (f["F10"][0] if f["F10"][1] == OBS else False) if system_filter else False}
    r["R2"] = _and(_b(f["F2"]), _not(_b(f["F5"])), _le(f["F4"], HANDOFF_MS, age))
    r["R3"] = _le(f["F1"], LOOKBACK_MS, age)
    r["R4"] = _and(_or(_le(f["F6"], LOOKBACK_MS, age), _le(f["F7"], LOOKBACK_MS, age)), _b(f["F8"]))
    r["R5"] = f["F9"][0] if f["F9"][1] == OBS else False
    return r


def _lifecycle_ok(order, w):
    """[가정] 액티비티마다 RESUMED·PAUSED가 번갈아 옴(이전 상태는 이 세계에서 관측한 값), 다른 액티비티끼리는 PAUSED가 앞.
    같은 클래스 여러 인스턴스·다중 창에서는 깨질 수 있음 [미확인]"""
    seq = {}
    for e in order:
        if e["type"] in (RESUMED, PAUSED):
            seq.setdefault(e["pkg"] + "/" + e["cls"], []).append(e["type"])
    for a, s in seq.items():
        s = ([w["life"][a]] if a in w["life"] else []) + s
        if any(x == y for x, y in zip(s, s[1:])):
            return False
    seen_r = set()
    for e in order:
        a = e["pkg"] + "/" + e["cls"]
        if e["type"] == RESUMED:
            seen_r.add(a)
        elif e["type"] == PAUSED and seen_r - {a}:
            return False
    return True


def _orders(group, w, assume):
    """묶음 안에서 가능한 처리 순서. 조회 순서는 근거로 쓰지 않음.
    똑같은 이벤트(시각·종류·패키지·클래스)끼리는 바꿔도 행 묶음이 같으므로 조회 순서로 고정 (가정 아님).
    assume="lifecycle"이면 _lifecycle_ok를 만족하는 순서만, 만족하는 순서가 없으면 가정을 버리고 전부"""
    rel = [e for e in group if e["type"] in ORDERED]
    key = lambda e: (e["type"], e["pkg"], e["cls"])
    same = [(a, b) for i, a in enumerate(rel) for b in rel[i + 1:] if key(a) == key(b)]

    def fixed(order):
        pos = {id(e): i for i, e in enumerate(order)}
        return all(pos[id(a)] < pos[id(b)] for a, b in same)

    perms = [list(p) for p in itertools.permutations(rel) if fixed(p)]
    if assume == "lifecycle":
        return [p for p in perms if _lifecycle_ok(p, w)] or perms
    return perms


def _process_group(state, group, snap, query_id, rows, system_filter):
    seg, assume = state["seg"], state["order_assume"]
    targets = [e for e in group if e["type"] == RESUMED]
    if sum(e["type"] in ORDERED for e in group) > MAX_TIE:   # 순서를 다 셀 수 없음 → 판정 보류하고 새 구간
        for e in targets:
            rows.append(_row(state, e, query_id, {k: (None, TIED) for k in FEATURES},
                             [{r: None for r in RULES}], _meta(snap, e["pkg"], e["ts"])[1]))
        _reset(state, group[0]["ts"], "tie_overflow")
        return
    per = {id(e): [] for e in targets}
    worlds, seen = [], set()
    for w0 in state["worlds"]:
        for order in _orders(group, w0, assume):
            w = copy.deepcopy(w0)
            for e in order:
                if e["type"] == RESUMED:
                    f = features(w, seg, e["ts"], e["pkg"], e["cls"], snap)
                    per[id(e)].append((f, rule_v0t(f, e["ts"] - seg["start"], system_filter)))
                _apply(w, e)
            _prune(w, group[0]["ts"])
            k = json.dumps(w, sort_keys=True)
            if k not in seen:
                seen.add(k)
                worlds.append(w)
    for e in targets:
        fs = [x[0] for x in per[id(e)]]
        merged = {k: (fs[0][k] if all(f[k] == fs[0][k] for f in fs) else (None, TIED)) for k in FEATURES}
        rows.append(_row(state, e, query_id, merged, [x[1] for x in per[id(e)]], _meta(snap, e["pkg"], e["ts"])[1]))
    state["worlds"] = sorted(worlds, key=lambda w: json.dumps(w, sort_keys=True))
    if len(worlds) > MAX_WORLDS:
        _reset(state, group[0]["ts"] + 1, "world_overflow")


def _row(state, e, query_id, f, rule_worlds, meta_validity):
    failed = [r for r in RULES if all(rw[r] is True for rw in rule_worlds)]
    passed = [r for r in RULES if all(rw[r] is False for rw in rule_worlds)]
    unknown = [r for r in RULES if r not in failed and r not in passed]
    outs = set()
    for rw in rule_worlds:
        outs.add("NORMAL" if any(v is True for v in rw.values()) else ("HOLD" if any(v is None for v in rw.values()) else "AD"))
    row = {"segment_id": state["seg"]["id"], "ingest_id": state["next_ingest"], "query_id": query_id,
           "source_seq": e["seq"], "ts": e["ts"], "pkg": e["pkg"], "cls": e["cls"],
           "features": {k: list(v) for k, v in f.items()}, "meta_validity": meta_validity,
           "rule_v0t": {"outcome": outs.pop() if len(outs) == 1 else "HOLD", "failed": failed, "unknown": unknown},
           "order_basis": "lifecycle_assumed" if state["order_assume"] else "none", "schema": SCHEMA}
    state["next_ingest"] += 1
    return row


def process_query(state, q, snap, system_filter=True):
    """조회 1회 처리. state를 바꾸고 이번에 확정한 행을 돌려줌. 실패로 저장 못 하면 호출 측이 이전 state로 다시 부르면 됨"""
    query_id = state["next_query"]
    state["next_query"] += 1
    if q["events"] is None:   # Android R+는 사용자 잠금 해제 전 null. 빈 조회로 보지 않고 시각 기준·커서를 그대로 둠
        _bump(state, "query_null")
        return []
    wall = q["wall_to"]
    events = [dict(ts=int(e[0]), type=e[1], pkg=e[2], cls=e[3] if len(e) > 3 else "", seq=i)
              for i, e in enumerate(q["events"])]
    rows = []
    ok = _rebase(state, wall, q["elapsed"], q.get("boot_count"), events)
    ts = [e["ts"] for e in events] + ([] if state["boot_max_ts"] is None else [state["boot_max_ts"]])
    if ts:   # 이 부팅에서 본 가장 늦은 기록. 재부팅하면서 시계가 뒤로 가면 옛 부팅의 SHUTDOWN을 가르는 기준
        state["boot_max_ts"] = max(ts)
    if not ok:
        return rows
    _close_open(state, events)
    if wall - state["cursor"] > RETENTION_SAFE_MS:
        start = wall - RETENTION_SAFE_MS
        _bump(state, "gap_discarded", sum(state["cursor"] <= e["ts"] < start for e in events))
        state["cursor"] = start
        if state["open_q"] is not None:
            state["open_q"]["floor"] = max(state["open_q"]["floor"], start)
        else:
            state["skip_until"] = max(state["skip_until"], start)
            _reset(state, state["skip_until"], "gap")
    fin = wall - SETTLE_MS
    cur = state["cursor"]
    skip = fin if state["skip_until"] is None else state["skip_until"]   # 열린 격리면 이번 조회분 전부
    _bump(state, "pre_cursor_ignored", sum(e["ts"] < cur for e in events))
    _bump(state, "uncertain_discarded", sum(cur <= e["ts"] < min(skip, fin) for e in events))
    todo = sorted((e for e in events if max(cur, skip) <= e["ts"] < fin), key=lambda e: e["ts"])
    for _, g in itertools.groupby(todo, key=lambda e: e["ts"]):
        g = list(g)
        while state["pending"] and state["pending"][0]["start"] <= g[0]["ts"]:
            p = state["pending"].pop(0)
            _reset(state, p["start"], p["kind"])
        _process_group(state, g, snap, query_id, rows, system_filter)
    while state["pending"] and state["pending"][0]["start"] < fin:
        p = state["pending"].pop(0)
        _reset(state, p["start"], p["kind"])
    state["cursor"] = max(cur, fin)
    return rows


# ---------- 검증 ----------

HERE = os.path.dirname(os.path.abspath(__file__))


def _rule_v0():
    sys.path.insert(0, os.path.dirname(HERE))   # testapps/poc/rule_v0.py
    import rule_v0
    return rule_v0


def snap_from(d):
    return {p: {"resolved": set(v.get("resolved", [])), "system": v.get("system", False), "since": v.get("since")}
            for p, v in d.items()}


def _ev(e):
    return [e[0], TYPE[e[1]] if isinstance(e[1], str) else e[1], e[2], e[3] if len(e) > 3 else ""]


def run(queries, snap, state, roundtrip=True):
    rows = []
    for q in queries:
        rows += process_query(state, q, snap)
        if roundtrip:   # 배치 사이 저장·복구 흉내 (Room 트랜잭션 대신 JSON)
            state = json.loads(json.dumps(state))
    return rows, state


def _comparable(rows):
    """배치 분할과 무관해야 하는 부분만 (조회 번호·순번·행 번호 제외). 같은 ms 안 행 순서는 조회 순서를 따르므로 정렬해서 비교"""
    return sorted([(r["segment_id"], r["ts"], r["pkg"], r["cls"], json.dumps(r["features"], sort_keys=True),
             json.dumps(r["rule_v0t"], sort_keys=True), r["meta_validity"]) for r in rows])


def _single(events, start, elapsed0=1_000_000):
    """한 번에 다 읽는 조회 (부팅·시계 변화 없음)"""
    end = max(e[0] for e in events) + SETTLE_MS + 1
    return [{"wall_to": end, "elapsed": elapsed0 + end - start, "boot_count": 1, "events": events}]


def _splits(events, start, elapsed0=1_000_000):
    """두 조회로 나눔. 1차 워터마크 b = 각 묶음 시각과 그 1ms 뒤. 2차 조회는 처음부터 전부 다시 돌려줌
    (이미 확정한 구간 재조회 — 커서 앞 이벤트를 다시 처리하면 결과가 달라져 잡힘)"""
    ts = sorted({e[0] for e in events})
    end = ts[-1] + SETTLE_MS + 1
    for b in sorted({x for t in ts for x in (t, t + 1)} - {ts[0]}):
        w1 = b + SETTLE_MS
        q1 = {"wall_to": w1, "elapsed": elapsed0 + w1 - start, "boot_count": 1, "events": [e for e in events if e[0] < w1]}
        q2 = {"wall_to": end, "elapsed": elapsed0 + end - start, "boot_count": 1, "events": events}
        yield b, [q1, q2]


def _shuffles(events):
    """같은 ms 묶음 안의 조회 순서를 바꾼 판 두 가지 (뒤집기, 한 칸 돌리기). 예외 없음"""
    groups = [list(g) for _, g in itertools.groupby(events, key=lambda e: e[0])]
    out = []
    for f in (lambda g: g[::-1], lambda g: g[1:] + g[:1]):
        sh = [e for g in groups for e in f(g)]
        if sh != events:
            out.append(sh)
    return out


def _replays(qs, snap, st0):
    """조회마다 저장 실패 → 같은 상태로 다시 실행해도 결과가 같은지"""
    for i in range(len(qs)):
        rows, st = run(qs[:i], snap, copy.deepcopy(st0))
        saved = json.dumps(st)
        process_query(json.loads(saved), qs[i], snap)   # 버려지는 실행
        r2, _ = run(qs[i:], snap, json.loads(saved))
        yield i, rows + r2


def check_cases(path=os.path.join(HERE, "cases.json"), verbose=True):
    spec = json.load(open(path, encoding="utf-8"))
    rv0 = _rule_v0()
    base_launchers = spec["launchers"]
    fails = 0
    for case in spec["cases"]:
        snap = snap_from({**base_launchers, **case.get("launchers", {})})
        seg = case.get("segment", {"start": -CAP_MS, "kind": "install"})
        assume = case.get("order_assume")
        msgs = []
        if "queries" in case:
            qs = [{**q, "events": None if q["events"] is None else [_ev(e) for e in q["events"]]} for q in case["queries"]]
            st0 = init_state(seg["start"], seg["kind"], order_assume=assume, **case.get("init", {}))
            rows, st = run(qs, snap, copy.deepcopy(st0))
            ref = _comparable(rows)
            s = copy.deepcopy(st0)
            for i, q in enumerate(case["queries"]):   # 수집기가 요청할 조회 범위
                got = list(query_range(s, q["wall_to"], q["elapsed"], q.get("boot_count")))
                if "range" in q and got != q["range"]:
                    msgs.append(f"조회 {i + 1} 범위 = {got} (기대 {q['range']})")
                process_query(s, qs[i], snap)
            for i, r2 in _replays(qs, snap, st0):
                if _comparable(r2) != ref:
                    msgs.append(f"조회 {i + 1} 재실행 결과가 다름")
            for k in range(2):
                sq = [q if q["events"] is None else {**q, "events": (_shuffles(q["events"]) + [q["events"]] * 2)[k]} for q in qs]
                if sq != qs and _comparable(run(sq, snap, copy.deepcopy(st0))[0]) != ref:
                    msgs.append("같은 ms 묶음의 조회 순서를 바꾸면 결과가 다름")
            flat = [e for q in qs for e in (q["events"] or [])]
        else:
            flat = [_ev(e) for e in case["events"]]
            qs = _single(flat, seg["start"])
            mk = lambda: init_state(seg["start"], seg["kind"], wall=seg["start"], elapsed=1_000_000, boot_count=1,
                                    order_assume=assume)
            rows, st = run(qs, snap, mk())
            ref = _comparable(rows)
            for b, qs2 in _splits(flat, seg["start"]):
                if _comparable(run(qs2, snap, mk())[0]) != ref:
                    msgs.append(f"배치 분할 {b}에서 결과가 다름")
                for i, r2 in _replays(qs2, snap, mk()):
                    if _comparable(r2) != ref:
                        msgs.append(f"분할 {b} 조회 {i + 1} 재실행 결과가 다름")
            for sh in _shuffles(flat):
                if _comparable(run(_single(sh, seg["start"]), snap, mk())[0]) != ref:
                    msgs.append("같은 ms 묶음의 조회 순서를 바꾸면 결과가 다름")
            rv_events = [{"timeStamp": str(e[0]), "type": str(e[1]), "package": e[2], "class": e[3]} for e in flat]
            fl = rv0.flags(rv_events, {p: {"resolved": v["resolved"], "system": v["system"]} for p, v in snap.items()})
        for ex in case["expect"]:
            t, p, c = ex["target"]
            hit = [r for r in rows if (r["ts"], r["pkg"], r["cls"]) == (t, p, c)]
            each = ex.get("each") or [ex] * ex.get("rows", 1)   # each: 같은 키 행마다 조회 순서대로 기대값
            if len(hit) != len(each):
                msgs.append(f"{ex['target']} 행 {len(hit)}개 (기대 {len(each)})")
                continue
            idx = [i for i, e in enumerate(flat) if e[1] == RESUMED and (e[0], e[2], e[3]) == (t, p, c)]
            for n, (r, x) in enumerate(zip(hit, each)):
                tag = f"{ex['target']}" + (f"#{n}" if len(each) > 1 else "")
                for k, v in x.get("features", {}).items():
                    if r["features"][k] != v:
                        msgs.append(f"{tag} {k} = {r['features'][k]} (기대 {v})")
                for k in ("outcome", "failed", "unknown"):
                    if k in x.get("rule_v0t", {}) and r["rule_v0t"][k] != x["rule_v0t"][k]:
                        msgs.append(f"{tag} rule_v0t.{k} = {r['rule_v0t'][k]} (기대 {x['rule_v0t'][k]})")
                if "meta_validity" in x and r["meta_validity"] != x["meta_validity"]:
                    msgs.append(f"{tag} meta_validity = {r['meta_validity']} (기대 {x['meta_validity']})")
                if "rule_v0" in x and "queries" not in case and fl[idx[n]] != x["rule_v0"]:
                    msgs.append(f"{tag} rule_v0 = {fl[idx[n]]} (기대 {x['rule_v0']})")
        for k, v in case.get("counts", {}).items():
            if st["counts"].get(k, 0) != v:
                msgs.append(f"counts.{k} = {st['counts'].get(k, 0)} (기대 {v})")
        if "segments" in case:
            got = [(r["segment_id"]) for r in rows]
            if got != case["segments"]:
                msgs.append(f"segment_id 열 = {got} (기대 {case['segments']})")
        fails += bool(msgs)
        if verbose:
            print(("FAIL " if msgs else "ok   ") + case["id"] + "  " + case["title"])
            for m in msgs:
                print("       " + m)
    print(f"{len(spec['cases']) - fails}/{len(spec['cases'])} 사례 통과")
    return fails


def real(launchers_csv, paths):
    """실측 이벤트 CSV(DumpReceiver 형식, 조회 1회분)에서 rule_v0와 rule_v0t 비교. 런처 목록은 실험 뒤 스냅샷이라
    meta_validity=unverified. 같은 ms 순서 가정 없음(기본)과 생명주기 가정(분석용) 두 판을 함께 셈"""
    rv0 = _rule_v0()
    L = rv0.load_launchers(launchers_csv)
    snap = {p: {"resolved": v["resolved"], "system": v["system"], "since": None} for p, v in L.items()}
    for assume in (None, "lifecycle"):
        tot, diff, outs, tied, inv_bad = 0, {}, {}, {}, 0
        for path in paths:
            raw = list(csv.DictReader(open(path, encoding="utf-8")))
            if not raw:
                continue
            flat = [[int(r["timeStamp"]), int(r["type"]), r["package"], r["class"]] for r in raw]
            start = flat[0][0]
            mk = lambda: init_state(start, "install", wall=start, elapsed=1_000_000, boot_count=1, order_assume=assume)
            rows, _ = run(_single(flat, start), snap, mk())
            fl = rv0.flags([{"timeStamp": str(e[0]), "type": str(e[1]), "package": e[2], "class": e[3]} for e in flat], L)
            idx = [i for i, e in enumerate(flat) if e[1] == RESUMED]
            assert len(idx) == len(rows), (path, len(idx), len(rows))
            rows_by_seq = {r["source_seq"]: r for r in rows}
            for i in idx:
                r = rows_by_seq[i]
                a = "AD" if not fl[i] else "NORMAL"
                b = r["rule_v0t"]["outcome"]
                tot += 1
                outs[b] = outs.get(b, 0) + 1
                for k, (v, s) in r["features"].items():
                    if s == TIED:
                        tied[k] = tied.get(k, 0) + 1
                if a != b or (a == "NORMAL" and sorted(fl[i]) != r["rule_v0t"]["failed"]):
                    key = f"{a}[{','.join(fl[i])}] -> {b}[{','.join(r['rule_v0t']['failed'])}|?{','.join(r['rule_v0t']['unknown'])}]"
                    diff.setdefault(key, []).append(f"{os.path.relpath(path)}:{r['ts']} {r['pkg'].split('.')[-1]}/{r['cls'].split('.')[-1]}")
            # 15분 간격 배치로 나눠도 같은지
            ref = _comparable(rows)
            end = flat[-1][0] + SETTLE_MS + 1
            qs, b = [], start + 900_000
            while b - SETTLE_MS < end:
                qs.append({"wall_to": b, "elapsed": 1_000_000 + b - start, "boot_count": 1, "events": [e for e in flat if e[0] < b]})
                b += 900_000
            qs.append({"wall_to": b, "elapsed": 1_000_000 + b - start, "boot_count": 1, "events": flat})
            inv_bad += _comparable(run(qs, snap, mk())[0]) != ref
        print(f"== 같은 ms 순서: {'생명주기 가정 (분석용)' if assume else '가정 없음 (기본)'}")
        print(f"RESUMED {tot}개, rule_v0t 판정: {outs}")
        print(f"TIED 특징: {tied or '없음'}")
        print(f"15분 배치 분할 불일치 파일: {inv_bad}")
        print(f"rule_v0와 다른 판정: {sum(len(v) for v in diff.values())}건")
        for k, v in sorted(diff.items(), key=lambda kv: -len(kv[1])):
            print(f"  {len(v):>3}  {k}   예: {'; '.join(v[:2])}")


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "real":
        real(sys.argv[2], sys.argv[3:])
    else:
        sys.exit(1 if check_cases() else 0)
