"""변형 시험: featx.py를 한 곳씩 일부러 망가뜨려 cases.json이 잡는지 확인. 사용: python3 mutate.py

"오래된 상태 정리 끔"은 결과가 같아야 맞는 변형이라 잡히지 않는 것이 정상.
"""
import os, shutil, subprocess, sys, tempfile
F = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # testapps/poc
with open(os.path.join(F, "features/featx.py"), encoding="utf-8") as f:
    src = f.read()
M = [
 ("순열 대신 조회 순서만", "perms = [list(p) for p in itertools.permutations(rel) if fixed(p)]", "perms = [list(rel)]"),
 ("똑같은 이벤트 순서 고정 뺌", "if fixed(p)]", "]"),
 ("가정 모드: 이전 상태 무시", "s = ([w[\"life\"][a]] if a in w[\"life\"] else []) + s", "pass"),
 ("가정 모드: 다른 액티비티 PAUSED 앞 규칙 뺌", "elif e[\"type\"] == PAUSED and seen_r - {a}:", "elif False:"),
 ("가정 모드: 모순 시 빈 목록", "if _lifecycle_ok(p, w)] or perms", "if _lifecycle_ok(p, w)] or perms[:1]"),
 ("가정 모드를 기본으로", "if assume == \"lifecycle\":\n        return", "if True:\n        return"),
 ("워터마크 대신 끝까지 확정", "fin = wall - SETTLE_MS", "fin = wall + 1"),
 ("커서 1ms 덜 옮김", "state[\"cursor\"] = max(cur, fin)", "state[\"cursor\"] = max(cur, fin - 1)"),
 ("커서 앞 이벤트 다시 처리", "if max(cur, skip) <= e[\"ts\"] < fin)", "if skip <= e[\"ts\"] < fin)"),
 ("버릴 범위 무시", "if max(cur, skip) <= e[\"ts\"] < fin)", "if cur <= e[\"ts\"] < fin)"),
 ("시계 후퇴 범위를 앞 조회 시각까지만", "keep = last[\"wall\"] + (el - last[\"elapsed\"])", "keep = last[\"wall\"]"),
 ("시계 변경: OS가 옮긴 기록 범위 뺌", "_uncertain(state, wall - SETTLE_MS, max(keep, moved), \"clock\")", "_uncertain(state, wall - SETTLE_MS, keep, \"clock\")"),
 ("시계 허용 오차 5초 (OS 기준 2초보다 큼)", "CLOCK_TOL_MS = 1_000 ", "CLOCK_TOL_MS = 5_000 "),
 ("버릴 범위를 합치지 않고 덮어씀", "state[\"skip_until\"] = max(state[\"skip_until\"], frm, to)", "state[\"skip_until\"] = max(frm, to)"),
 ("재부팅+시계 후퇴 검사 끔", "if base < last[\"wall\"] - CLOCK_TOL_MS:", "if False:"),
 ("재부팅+시계 후퇴를 앞 조회 시각까지만 버림", "_uncertain(state, base, None, \"boot_clock\", old_base if old_max is None else max(old_base, old_max))", "_uncertain(state, base, last[\"wall\"], \"boot_clock\")"),
 ("열린 격리를 SHUTDOWN으로 닫지 않음", "    if sd:\n", "    if False:\n"),
 ("기본에서도 SHUTDOWN으로 닫음", "if state[\"recover\"] != \"shutdown\" or q is None", "if q is None"),
 ("기본에서도 기준 시각부터 다시 읽음", "if st[\"recover\"] == \"shutdown\" and q is not None", "if q is not None"),
 ("닫을 때 커서 앞(격리 중 버린 구간)부터 새 구간", "max(sd) + 1, state[\"cursor\"])", "max(sd) + 1)"),
 ("열린 격리 중 재부팅해도 닫음", "        state[\"open_q\"][\"closable\"] = False", "        pass"),
 ("닫는 기준에 OS 기록 이동 반영 안 함", "th = q[\"after\"] + max(0, q[\"shift\"])", "th = q[\"after\"]"),
 ("옛 부팅에서 본 마지막 기록 무시 (옛 부팅 시작 이후 SHUTDOWN이면 닫음)", "max(old_base, old_max))", "old_base)"),
 ("닫을 때 앞선 버릴 범위 무시", "max(q[\"floor\"], max(sd) + 1, ", "max(max(sd) + 1, "),
 ("조회 범위에서 옛 SHUTDOWN 찾기 뺌", "        begin = min(begin, q[\"after\"] + max(0, q[\"shift\"]))", "        pass"),
 ("옛 SHUTDOWN을 격리가 열릴 때만 찾음", "and q is not None and q[\"closable\"]:", "and q is not None and q[\"closable\"] and state[\"open_q\"] is None:"),
 ("시계 변경 때 커서 그대로", "    state[\"cursor\"] = frm\n", "    pass\n"),
 ("새 STARTUP 판별 끔", "starts = sorted({e[\"ts\"] for e in events if e[\"type\"] == STARTUP and e[\"ts\"] >= last[\"wall\"]})", "starts = []"),
 ("옛 STARTUP도 새 부팅으로", "e[\"type\"] == STARTUP and e[\"ts\"] >= last[\"wall\"]", "e[\"type\"] == STARTUP"),
 ("런타임 재시작을 부팅으로", "{\"start\": s, \"kind\": \"restart\"}", "{\"start\": s, \"kind\": \"boot\"}"),
 ("시계 검사 끔", "if abs(drift) > CLOCK_TOL_MS:\n        # 어느", "if False:\n        # 어느"),
 ("null 조회를 빈 조회로", "if q[\"events\"] is None:", "if q[\"events\"] is None:\n        q = {**q, \"events\": []}\n    if False:"),
 ("조회 범위에서 옛 커서 무시", "begin = min(state[\"cursor\"], st[\"cursor\"], wall)", "begin = min(st[\"cursor\"], wall)"),
 ("조회 범위에서 새 커서 무시", "begin = min(state[\"cursor\"], st[\"cursor\"], wall)", "begin = min(state[\"cursor\"], wall)"),
 ("부팅 구간도 NOT_COVERED", "boot = seg[\"kind\"] == \"boot\" ", "boot = False "),
 ("since 무시", "    since = info.get(\"since\")", "    since = None"),
 ("꺼질 때 앞 화면 모름을 false로", "f[\"F8\"] = (None, NC)          # 꺼질 때", "f[\"F8\"] = (False, OBS)          # 꺼질 때"),
 ("3초 창 모름을 거짓으로", "return False if age >= thr else None", "return False"),
 ("저장·복구 때 세계 잃음", "state = json.loads(json.dumps(state))", "state = json.loads(json.dumps(state)); state[\"worlds\"] = [empty_world(state[\"order_assume\"])]"),
 ("시계 변경 때 대기 부팅 경계 안 지움", "state[\"pending\"] = []   # 옛 시계", "pass   # 옛 시계"),
 ("오래된 상태 정리 끔", "w[k] = {p: t for p, t in w[k].items() if now - t <= CAP_MS}", "pass"),
 ("CAP 창 대신 값만 자름", "        return (t - ts, OBS)", "        return (t - ts, OBS)\n        if ts is not None: return (CAP_MS, OBS)"),
 ("부팅 경계 적용 안 함", "            _reset(state, p[\"start\"], p[\"kind\"])\n        _process_group", "            pass\n        _process_group"),
]
caught = 0
for name, a, b in M:
    if src.count(a) != 1:
        print(f"적용 못 함 ({src.count(a)}) {name}"); continue
    d = tempfile.mkdtemp()
    os.makedirs(f"{d}/features")
    with open(f"{d}/features/featx.py", "w", encoding="utf-8") as f:
        f.write(src.replace(a, b))
    shutil.copy(os.path.join(F, "features/cases.json"), f"{d}/features/")
    shutil.copy(os.path.join(F, "rule_v0.py"), d)
    r = subprocess.run([sys.executable, f"{d}/features/featx.py"], capture_output=True, text=True, encoding="utf-8",
                       env={**os.environ, "PYTHONIOENCODING": "utf-8"})   # 한글 출력을 로케일과 무관하게
    fails = [l.split("  ")[0].replace("FAIL ", "") for l in r.stdout.splitlines() if l.startswith("FAIL")]
    hit = r.returncode != 0
    caught += hit
    print(("잡힘 " if hit else "안 잡힘 ") + name + ("  " + ",".join(fails[:6]) if fails else ("  (오류)" if hit else "")))
    shutil.rmtree(d)
print(f"{caught}/{len(M)}")
