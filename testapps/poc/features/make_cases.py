"""cases.json 생성기. 기대값은 추출기 실행 전에 손으로 계산해 여기 적음. 사용: python3 make_cases.py cases.json"""
import json, sys
O, N, NC, LF, T = "OBSERVED", "NONE_IN_WINDOW", "NOT_COVERED", "LOOKUP_FAILED", "TIED"
def F(**kw):   # 일부 특징만 확인
    return dict(kw)
def full(F0, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10):
    return {"F0": F0, "F1": F1, "F2": F2, "F3": F3, "F4": F4, "F5": F5, "F6": F6, "F7": F7, "F8": F8, "F9": F9, "F10": F10}
none, nc, lf, tied = [None, N], [None, NC], [None, LF], [None, T]
o = lambda v: [v, O]
CAP = 600000
AD = lambda u=[]: {"outcome": "AD", "failed": [], "unknown": u}
NORMAL = lambda f, u=[]: {"outcome": "NORMAL", "failed": f, "unknown": u}
HOLD = lambda u: {"outcome": "HOLD", "failed": [], "unknown": u}
init = {"wall": -600000, "elapsed": 0, "boot_count": 1}
cases = []
def add(id, title, expect, **kw):
    cases.append({"id": id, "title": title, **kw, "expect": expect})

add("1", "R2: 같은 앱 안 전환 (PAUSED 300ms 뒤 RESUMED)",
    [{"target": [5300, "app.a", ".Detail"], "features": full(o(CAP), none, o(True), o(4300), o(300), o(False), none, none, nc, o(False), o(False)),
      "rule_v0": ["R2"], "rule_v0t": NORMAL(["R2"])}],
    events=[[1000, "RESUMED", "app.a", ".Main"], [5000, "PAUSED", "app.a", ".Main"], [5300, "RESUMED", "app.a", ".Detail"]])
add("1b", "R2 통과: PAUSED가 3초 전",
    [{"target": [5300, "app.a", ".Detail"], "features": F(F3=o(4300), F4=o(3000)), "rule_v0": [], "rule_v0t": AD()}],
    events=[[1000, "RESUMED", "app.a", ".Main"], [2300, "PAUSED", "app.a", ".Main"], [5300, "RESUMED", "app.a", ".Detail"]])
e1c = [[1000, "RESUMED", "app.a", ".Main"], [5000, "PAUSED", "app.a", ".Main"], [5000, "RESUMED", "app.a", ".Detail"]]
add("1c", "같은 ms, 다른 액티비티의 PAUSED·RESUMED (실측 68건 형태): 순서를 모르면 F4 TIED, R2 모름",
    [{"target": [5000, "app.a", ".Detail"], "features": F(F2=o(True), F3=o(4000), F4=tied, F5=o(False)), "rule_v0": ["R2"], "rule_v0t": HOLD(["R2"])}],
    events=e1c)
add("1c-L", "[생명주기 가정] 1c: 다른 액티비티의 PAUSED가 앞",
    [{"target": [5000, "app.a", ".Detail"], "features": F(F2=o(True), F3=o(4000), F4=o(0), F5=o(False)), "rule_v0": ["R2"], "rule_v0t": NORMAL(["R2"])}],
    order_assume="lifecycle", events=e1c)
e1d = [[1000, "RESUMED", "app.a", ".Main"], [10000, "SCREEN_OFF", "android", ""], [29990, "SCREEN_ON", "android", ""],
       [30000, "RESUMED", "ad.x", ".AdActivity"], [30000, "PAUSED", "ad.x", ".AdActivity"], [30000, "RESUMED", "ad.x", ".AdActivity"]]
add("1d", "같은 ms 같은 액티비티 R-P-R (실측 2건, 화면 켜질 때): 똑같은 RESUMED 둘은 순서 고정, PAUSED 위치는 모름",
    [{"target": [30000, "ad.x", ".AdActivity"], "each": [
        {"features": full(o(CAP), none, o(False), o(29000), tied, o(True), o(10), none, o(False), o(False), o(False)), "rule_v0": [], "rule_v0t": AD()},
        {"features": full(o(CAP), none, o(True), o(0), tied, o(False), o(10), none, o(False), o(False), o(False)), "rule_v0": ["R2"], "rule_v0t": HOLD(["R2"])}]}],
    events=e1d)
add("1d-L", "[생명주기 가정] 1d: RESUMED·PAUSED가 번갈아 와야 하므로 R-P-R만 가능, 첫 번째만 광고",
    [{"target": [30000, "ad.x", ".AdActivity"], "each": [
        {"features": full(o(CAP), none, o(False), o(29000), none, o(True), o(10), none, o(False), o(False), o(False)), "rule_v0": [], "rule_v0t": AD()},
        {"features": full(o(CAP), none, o(True), o(0), o(0), o(False), o(10), none, o(False), o(False), o(False)), "rule_v0": ["R2"], "rule_v0t": NORMAL(["R2"])}]}],
    order_assume="lifecycle", events=e1d)
add("1e", "같은 ms 생명주기 이벤트 두 쌍 (R-P, R-P): 가능한 순서 모두 → TIED·HOLD",
    [{"target": [5000, "ad.x", ".T"], "features": F(F2=tied, F3=tied, F4=tied, F5=o(False)), "rule_v0": [], "rule_v0t": HOLD(["R2"])},
     {"target": [5000, "ad.x", ".Ad"], "features": F(F2=tied, F3=tied, F4=tied, F5=o(False)), "rule_v0": ["R2"], "rule_v0t": HOLD(["R2"])}],
    events=[[1000, "RESUMED", "app.a", ".Main"], [5000, "RESUMED", "ad.x", ".T"], [5000, "PAUSED", "ad.x", ".T"],
            [5000, "RESUMED", "ad.x", ".Ad"], [5000, "PAUSED", "ad.x", ".Ad"]])
add("1f", "[생명주기 가정] 가정과 모순되는 묶음 (RESUMED 상태에서 R-P-R): 가정을 버리고 모든 순서",
    [{"target": [5000, "ad.x", ".Ad"], "each": [
        {"features": F(F2=o(True), F3=o(3000), F4=tied, F5=o(False)), "rule_v0t": HOLD(["R2"])},
        {"features": F(F2=o(True), F3=o(0), F4=tied, F5=o(False)), "rule_v0t": HOLD(["R2"])}]}],
    order_assume="lifecycle",
    events=[[1000, "RESUMED", "app.a", ".Main"], [2000, "RESUMED", "ad.x", ".Ad"],
            [5000, "RESUMED", "ad.x", ".Ad"], [5000, "PAUSED", "ad.x", ".Ad"], [5000, "RESUMED", "ad.x", ".Ad"]])
e15 = [[1000, "RESUMED", "app.a", ".Feed"], [2000, "PAUSED", "app.a", ".Feed"]]
t15 = [[10000, "RESUMED", "app.a", ".Feed"], [10000, "PAUSED", "app.a", ".Feed"]]
x15 = {"target": [10000, "app.a", ".Feed"], "features": F(F2=o(True), F3=o(9000), F4=tied, F5=o(False)), "rule_v0t": HOLD(["R2"])}
add("15", "검토 반례: 같은 ms R·P를 조회 순서 R-P로 받음 → 순서를 모르므로 HOLD", [x15], events=e15 + t15)
add("15b", "검토 반례: 같은 묶음을 P-R로 받음 → 15와 같은 결과", [x15], events=e15 + t15[::-1])
add("15c", "[생명주기 가정] 15: 앞 상태가 PAUSED라 R-P만 가능 → 조회 순서와 무관하게 광고",
    [{"target": [10000, "app.a", ".Feed"], "features": F(F3=o(9000), F4=o(8000)), "rule_v0t": AD()}],
    order_assume="lifecycle", events=e15 + t15[::-1])
c2 = [[1000, "RESUMED", "app.a", ".Feed"], [8000, "PAUSED", "app.a", ".Feed"], [8000, "SCREEN_OFF", "android", ""],
      [20000, "SCREEN_ON", "android", ""]]
add("2", "R4: 화면 켜고 꺼지기 전 화면으로 복귀",
    [{"target": [20500, "app.a", ".Feed"], "features": full(o(CAP), none, o(True), o(19500), o(12500), o(True), o(500), none, o(True), o(False), o(False)),
      "rule_v0": ["R4"], "rule_v0t": NORMAL(["R4"])}],
    events=c2 + [[20500, "RESUMED", "app.a", ".Feed"]])
add("2b", "R4 통과: 화면 켜진 뒤 앱이 다른 화면을 띄움",
    [{"target": [20500, "app.a", ".AdActivity"], "features": F(F8=o(False), F9=o(False)), "rule_v0": [], "rule_v0t": AD()}],
    events=c2 + [[20500, "RESUMED", "app.a", ".AdActivity"]])
c3 = [[1000, "RESUMED", "app.a", ".Main"], [1500, "USER_INTERACTION", "app.a", ""], [60000, "PAUSED", "app.a", ".Main"],
      [60010, "RESUMED", "ad.x", ".AdActivity"]]
e3 = {"target": [60010, "ad.x", ".AdActivity"], "features": full(o(CAP), none, o(False), o(59010), none, o(False), none, none, nc, o(False), o(False)),
      "rule_v0t": AD()}
add("3", "다른 앱 위 광고 (F8은 화면 꺼짐을 못 봤으므로 NOT_COVERED)", [{**e3, "rule_v0": []}], events=c3)
add("4", "배치 경계 + 이미 확정한 구간 재조회 (중복 없음)",
    [e3, {"target": [1000, "app.a", ".Main"], "rows": 1}],
    init=init, counts={"pre_cursor_ignored": 1},
    queries=[{"wall_to": 30000, "elapsed": 630000, "boot_count": 1, "events": c3[:2]},
             {"wall_to": 70000, "elapsed": 670000, "boot_count": 1, "events": [c3[1]] + c3[2:]}])
add("4c", "한계: 확정 뒤 늦게 기록된 같은 ms 이벤트는 반영 못 함 (기록 지연 > SETTLE_MS)",
    [{**e3, "features": F(F1=none)}], init=init, counts={"pre_cursor_ignored": 1},
    queries=[{"wall_to": 66000, "elapsed": 666000, "boot_count": 1, "events": c3},
             {"wall_to": 90000, "elapsed": 690000, "boot_count": 1, "events": [[60010, "USER_INTERACTION", "ad.x", ""]]}])
c5 = [[1000, "RESUMED", "app.a", ".Main"], [5000, "PAUSED", "app.a", ".Main"]]
e5 = {"target": [5000, "app.b", ".Other"], "features": full(o(CAP), tied, o(False), o(4000), none, o(False), none, none, nc, o(False), o(False)),
      "rule_v0t": HOLD(["R3"])}
add("5", "같은 ms: USER_INTERACTION이 대상 뒤 순서", [{**e5, "rule_v0": []}],
    events=c5 + [[5000, "RESUMED", "app.b", ".Other"], [5000, "USER_INTERACTION", "app.b", ""]])
add("5b", "같은 ms: USER_INTERACTION이 대상 앞 순서 (rule_v0만 달라짐)", [{**e5, "rule_v0": ["R3"]}],
    events=c5 + [[5000, "USER_INTERACTION", "app.b", ""], [5000, "RESUMED", "app.b", ".Other"]])
add("6", "스냅샷에 패키지 없음",
    [{"target": [9000, "ad.y", ".Ad"], "features": full(o(CAP), none, o(False), o(8000), none, o(False), none, none, nc, lf, lf),
      "meta_validity": "absent", "rule_v0": [], "rule_v0t": AD()}],
    events=[[1000, "RESUMED", "app.a", ".Main"], [9000, "RESUMED", "ad.y", ".Ad"]])
add("6b", "설치는 확인, 런처 없음 (LOOKUP_FAILED 아님)",
    [{"target": [9000, "ad.z", ".Ad"], "features": F(F9=o(False), F10=o(False)), "meta_validity": "unverified", "rule_v0": [], "rule_v0t": AD()}],
    launchers={"ad.z": {"resolved": [], "system": False}},
    events=[[1000, "RESUMED", "app.a", ".Main"], [9000, "RESUMED", "ad.z", ".Ad"]])
add("6c", "이벤트 뒤 업데이트된 앱: 판정 시점 스냅샷을 소급하지 않음 (rule_v0만 R5)",
    [{"target": [9000, "ad.w", ".Main"], "features": F(F9=lf, F10=lf), "meta_validity": "updated_after_event", "rule_v0": ["R5"], "rule_v0t": AD()}],
    launchers={"ad.w": {"resolved": [".Main"], "system": False, "since": 9500}},
    events=[[1000, "RESUMED", "app.a", ".Main"], [9000, "RESUMED", "ad.w", ".Main"]])
e7 = {"target": [52000, "ad.x", ".AdActivity"], "features": full(o(2000), none, none, none, none, none, none, none, none, o(False), o(False)),
      "rule_v0t": AD()}
add("7", "재부팅 (DEVICE_STARTUP 있음): 부팅 전 상태 무효, 부팅 뒤 '없음'은 확정", [e7], init=init, segments=[1, 2],
    counts={"segments_boot": 1},
    queries=[{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"], [2000, "SHUTDOWN", "android", ""]]},
             {"wall_to": 60000, "elapsed": 10000, "boot_count": 2, "events": [[50000, "STARTUP", "android", ""], [52000, "RESUMED", "ad.x", ".AdActivity"]]}])
q7 = [{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"]]},
      {"wall_to": 60000, "elapsed": 10000, "boot_count": 2, "events": [[52000, "RESUMED", "ad.x", ".AdActivity"]]}]
add("7b", "재부팅 (STARTUP 없음, API 28): BOOT_COUNT 변화로 감지, 부팅 시각 = wall - elapsed", [e7], init=init, segments=[1, 2], queries=q7)
add("7c", "재부팅 (STARTUP·BOOT_COUNT 둘 다 없음): elapsed 감소로 감지", [e7], segments=[1, 2],
    init={**init, "boot_count": None}, queries=[{**q, "boot_count": None} for q in q7])
add("7d", "부팅 직후 첫 조회가 부팅 시각보다 워터마크가 앞 → 다음 조회에서 구간 시작", [e7], init=init, segments=[1, 2],
    queries=[q7[0], {"wall_to": 51000, "elapsed": 1000, "boot_count": 2, "events": []},
             {"wall_to": 60000, "elapsed": 10000, "boot_count": 2, "events": [[52000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("7e", "부팅 경계를 기다리는 중 시계 후퇴: 옛 부팅 경계로 되돌아가지 않음, 옛 시계 기록이 있을 수 있는 110000까지 버림",
    [{"target": [70000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [120000, "ad.x", ".AdActivity"], "features": F(F0=o(10000)), "rule_v0t": AD()}],
    init=init, segments=[1, 2], counts={"segments_clock": 1, "segments_boot": 0, "uncertain_discarded": 1},
    queries=[q7[0], {"wall_to": 51000, "elapsed": 1000, "boot_count": 2, "events": []},
             {"wall_to": 65000, "elapsed": 60000, "boot_count": 2, "events": []},
             {"wall_to": 90000, "elapsed": 85000, "boot_count": 2, "events": [[70000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 140000, "elapsed": 135000, "boot_count": 2, "events": [[120000, "RESUMED", "ad.x", ".AdActivity"]]}])
q8 = {"wall_to": 100000, "elapsed": 700000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"], [50000, "USER_INTERACTION", "app.a", ""]]}
add("8", "시계 후퇴 70초: 커서를 새 시계로 재설정, 옛 시계 기록이 있을 수 있는 [85000, 160000)은 버림, 그 뒤 새 구간",
    [{"target": [100000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [170000, "ad.x", ".AdActivity"], "features": full(o(10000), nc, nc, nc, nc, nc, nc, nc, nc, o(False), o(False)), "rule_v0t": AD()}],
    init=init, segments=[1, 2], counts={"segments_clock": 1, "uncertain_discarded": 1},
    queries=[q8, {"wall_to": 90000, "elapsed": 760000, "boot_count": 1, "events": [], "range": [85000, 90000]},
             {"wall_to": 120000, "elapsed": 790000, "boot_count": 1, "events": [[100000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 200000, "elapsed": 870000, "boot_count": 1, "events": [[170000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("8b", "시계 전진: 그 조회분은 어느 시계 기준인지 몰라 버림",
    [{"target": [120000, "ad.x", ".AdActivity"], "rows": 0}, {"target": [410000, "ad.x", ".AdActivity"], "features": F(F0=o(15000)), "rule_v0t": AD()}],
    init=init, counts={"segments_clock": 1, "clock_discarded": 1},
    queries=[q8, {"wall_to": 400000, "elapsed": 760000, "boot_count": 1, "events": [[120000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 430000, "elapsed": 790000, "boot_count": 1, "events": [[410000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("8c", "검토 반례: 시계 후퇴가 두 번 겹침 → 앞서 버리기로 한 [85000, 160000)을 줄이지 않음 (합치면 [75000, 160000))",
    [{"target": [120000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [170000, "ad.x", ".AdActivity"], "features": full(o(10000), nc, nc, nc, nc, nc, nc, nc, nc, o(False), o(False)), "rule_v0t": AD()}],
    init=init, segments=[1, 3], counts={"segments_clock": 2, "uncertain_discarded": 1},
    queries=[q8, {"wall_to": 90000, "elapsed": 760000, "boot_count": 1, "events": []},
             {"wall_to": 80000, "elapsed": 770000, "boot_count": 1, "events": [], "range": [75000, 80000]},
             {"wall_to": 140000, "elapsed": 830000, "boot_count": 1, "events": [[120000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 200000, "elapsed": 890000, "boot_count": 1, "events": [[170000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("8d", "시계 3초 전진, OS가 기록도 옮김 (AOSP는 2초 넘게 어긋나면 옮김, 옮긴 양은 앱이 본 차이와 OS 기준 2배까지 다를 수 있음): "
          "이미 처리한 94600이 101600으로 옮겨져 다시 보여도 두 번 처리하지 않음",
    [{"target": [94600, "ad.x", ".AdActivity"], "rows": 1}, {"target": [101600, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [120000, "ad.x", ".AdActivity"], "features": F(F0=o(18000)), "rule_v0t": AD()}],
    init=init, segments=[1, 1, 2], counts={"segments_clock": 1, "clock_discarded": 1, "uncertain_discarded": 1},
    queries=[{"wall_to": 100000, "elapsed": 700000, "boot_count": 1,
              "events": [[1000, "RESUMED", "app.a", ".Main"], [94600, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 104000, "elapsed": 701000, "boot_count": 1, "events": [[101600, "RESUMED", "ad.x", ".AdActivity"]],
              "range": [95000, 104000]},
             {"wall_to": 130000, "elapsed": 727000, "boot_count": 1,
              "events": [[101600, "RESUMED", "ad.x", ".AdActivity"], [120000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("9", "같은 튜플 두 번: 키로 거르지 않고 두 행 (같은 액티비티라 조회 순서 유지), 둘 다 광고",
    [{"target": [2000, "ad.x", ".AdActivity"], "rows": 2, "features": F(F5=o(False), F4=none), "rule_v0": [], "rule_v0t": AD()}],
    events=[[1000, "RESUMED", "app.a", ".Main"], [2000, "RESUMED", "ad.x", ".AdActivity"], [2000, "RESUMED", "ad.x", ".AdActivity"]])
add("10", "같은 ms의 화면 꺼짐·직전 RESUMED (검토 반례): rule_v0는 R2 통과, rule_v0t는 HOLD",
    [{"target": [1300, "app.a", ".Detail"], "features": full(o(CAP), none, o(True), o(300), o(100), tied, none, none, tied, o(False), o(False)),
      "rule_v0": [], "rule_v0t": HOLD(["R2"])}],
    events=[[1000, "SCREEN_OFF", "android", ""], [1000, "RESUMED", "app.a", ".Feed"], [1200, "PAUSED", "app.a", ".Feed"],
            [1300, "RESUMED", "app.a", ".Detail"]])
add("11", "수집 시작 직후 (워밍업): 3초 창을 못 채워 R3·R4 모름",
    [{"target": [2500, "ad.x", ".AdActivity"], "features": full(o(2500), nc, o(False), o(1500), nc, o(False), nc, nc, nc, o(False), o(False)),
      "rule_v0": [], "rule_v0t": HOLD(["R3", "R4"])}],
    segment={"start": 0, "kind": "install"},
    events=[[1000, "RESUMED", "app.a", ".Main"], [2500, "RESUMED", "ad.x", ".AdActivity"]])
add("12", "CAP 경계: 600001ms 전 상호작용은 없음, 직전 RESUMED 경과는 상한값",
    [{"target": [601001, "app.a", ".X"], "features": full(o(CAP), none, o(True), o(CAP), o(1), o(False), none, none, nc, o(False), o(False)),
      "rule_v0": ["R2"], "rule_v0t": NORMAL(["R2"])}],
    events=[[0, "RESUMED", "app.a", ".Main"], [1000, "USER_INTERACTION", "app.a", ""], [601000, "PAUSED", "app.a", ".Main"],
            [601001, "RESUMED", "app.a", ".X"]])
add("13", "직전 50개 제한 (rule_v0만 R3 놓침)",
    [{"target": [1100, "ad.x", ".AdActivity"], "features": F(F1=o(100)), "rule_v0": [], "rule_v0t": NORMAL(["R3"])}],
    events=[[0, "RESUMED", "app.a", ".Main"], [1000, "USER_INTERACTION", "ad.x", ""]]
           + [[1001 + k, "PAUSED", "noise.n", ".N"] for k in range(51)] + [[1100, "RESUMED", "ad.x", ".AdActivity"]])
quiet = [{"wall_to": 10000 + 900000 * k, "elapsed": 610000 + 900000 * k, "boot_count": 1, "events": []} for k in range(1, 5)]
add("14", "이벤트 없이 1시간 조회만 계속: 공백 아님, 직전 RESUMED 유지",
    [{"target": [3610500, "app.a", ".Detail"], "features": F(F2=o(True), F3=o(CAP), F4=o(500)), "rule_v0t": NORMAL(["R2"])}],
    init=init, segments=[1, 1],
    queries=[{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"]]}] + quiet
            + [{"wall_to": 3700000, "elapsed": 4300000, "boot_count": 1,
                "events": [[3610000, "PAUSED", "app.a", ".Main"], [3610500, "RESUMED", "app.a", ".Detail"]]}])
add("14b", "4일 동안 조회 못 함: 공백 구간, 직전 RESUMED 무효라 R2 모름",
    [{"target": [345000500, "app.a", ".Detail"], "features": F(F1=none, F2=nc, F4=o(500)), "rule_v0t": HOLD(["R2"])}],
    init=init, segments=[1, 2], counts={"segments_gap": 1},
    queries=[{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"]]},
             {"wall_to": 345610000, "elapsed": 346210000, "boot_count": 1,
              "events": [[345000000, "PAUSED", "app.a", ".Main"], [345000500, "RESUMED", "app.a", ".Detail"]]}])

q16 = {"wall_to": 100000, "elapsed": 700000, "boot_count": 1, "events": [[90000, "RESUMED", "app.a", ".Main"]]}
r16 = {"wall_to": 60000, "elapsed": 10000, "boot_count": 2, "events": [[52000, "RESUMED", "ad.x", ".AdActivity"]]}
add("16", "검토 반례: 재부팅하면서 시계도 뒤로 감 → 옛 부팅이 앞 조회(100000) 뒤 꺼질 때까지 남긴 기록의 끝을 모름 → "
          "옛 부팅의 SHUTDOWN이 보일 때까지 행을 만들지 않음 (120000 옛 부팅 기록도 버림). 옛 부팅에서 본 기록이 없어 옛 부팅 시작부터 SHUTDOWN을 찾음",
    [{"target": [52000, "ad.x", ".AdActivity"], "rows": 0}, {"target": [70000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [120000, "ad.x", ".OldBoot"], "rows": 0}],
    init=init, segments=[], counts={"segments_boot_clock": 0, "uncertain_discarded": 3},
    queries=[{"wall_to": 100000, "elapsed": 700000, "boot_count": 1, "events": []}, {**r16, "range": [-600000, 60000]},
             {"wall_to": 80000, "elapsed": 30000, "boot_count": 2, "events": [[70000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 130000, "elapsed": 80000, "boot_count": 2, "events": [[120000, "RESUMED", "ad.x", ".OldBoot"]]}])
add("16b", "16에서 옛 부팅의 SHUTDOWN(105000)이 보임 → 옛 부팅 기록은 거기까지, 그 뒤부터 새 구간 (끝을 아는 격리)",
    [{"target": [90000, "app.a", ".Main"], "rows": 1}, {"target": [52000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [110000, "ad.x", ".AdActivity"], "features": full(o(4999), nc, nc, nc, nc, nc, nc, nc, nc, o(False), o(False)), "rule_v0t": AD()}],
    init=init, segments=[1, 2], counts={"segments_boot_clock": 1, "uncertain_discarded": 3},
    queries=[q16, {**r16, "range": [50000, 60000]},
             {"wall_to": 80000, "elapsed": 30000, "boot_count": 2, "events": [[70000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 130000, "elapsed": 80000, "boot_count": 2,
              "events": [[105000, "SHUTDOWN", "android", ""], [110000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("16c", "옛 부팅의 SHUTDOWN(45000)이 새 부팅 시작(50000)보다 앞: 커서 앞이라 옛 부팅의 마지막 기록(40000)부터 읽어 찾고, 새 부팅 시작부터 바로 판정",
    [{"target": [52000, "ad.x", ".AdActivity"], "features": full(o(2000), nc, nc, nc, nc, nc, nc, nc, nc, o(False), o(False)),
      "rule_v0t": HOLD(["R3", "R4"])}],
    init=init, segments=[1, 2], counts={"segments_boot_clock": 1, "uncertain_discarded": 0, "pre_cursor_ignored": 1},
    queries=[{**q16, "events": [[40000, "RESUMED", "app.a", ".Main"]]},
             {**r16, "range": [40000, 60000], "events": [[45000, "SHUTDOWN", "android", ""], [50000, "STARTUP", "android", ""],
                                                         [52000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("16d", "한계: 열린 격리 중 다시 재부팅 → 어느 SHUTDOWN이 그 옛 부팅의 끝인지 가를 수 없어 계속 버림",
    [{"target": [110000, "ad.x", ".AdActivity"], "rows": 0}],
    init=init, segments=[1], counts={"segments_boot_clock": 0, "uncertain_discarded": 2},
    queries=[q16, {**r16, "events": []}, {"wall_to": 90000, "elapsed": 5000, "boot_count": 3, "events": []},
             {"wall_to": 130000, "elapsed": 45000, "boot_count": 3,
              "events": [[105000, "SHUTDOWN", "android", ""], [110000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("16e", "열린 격리 중 시계 1000초 전진, OS가 기록을 옮김: 더 앞 부팅의 SHUTDOWN(990000 → 1990000)이 보여도 닫지 않고, "
           "옛 부팅의 SHUTDOWN(1960000 → 2960000)에서 닫음",
    [{"target": [1992000, "ad.x", ".AdActivity"], "rows": 0},
     {"target": [2970000, "ad.x", ".AdActivity"], "features": F(F0=o(9999)), "rule_v0t": AD()}],
    segment={"start": 1000000, "kind": "install"}, init={"wall": 1000000, "elapsed": 0, "boot_count": 1},
    segments=[1, 2], counts={"segments_boot_clock": 1, "uncertain_discarded": 3},
    queries=[{"wall_to": 2000000, "elapsed": 1000000, "boot_count": 1, "events": [[1950000, "RESUMED", "app.a", ".Main"]]},
             {"wall_to": 100000, "elapsed": 10000, "boot_count": 2, "events": [], "range": [90000, 100000]},
             {"wall_to": 1120000, "elapsed": 30000, "boot_count": 2, "events": []},
             {"wall_to": 2000000, "elapsed": 910000, "boot_count": 2,
              "events": [[1990000, "SHUTDOWN", "android", ""], [1992000, "RESUMED", "ad.x", ".AdActivity"]]},
             {"wall_to": 3000000, "elapsed": 1910000, "boot_count": 2,
              "events": [[2960000, "SHUTDOWN", "android", ""], [2970000, "RESUMED", "ad.x", ".AdActivity"]]}])
q17 = {"wall_to": 60000, "elapsed": 10000, "boot_count": None, "range": [0, 60000],
       "events": [[50000, "STARTUP", "android", ""], [52000, "RESUMED", "ad.x", ".AdActivity"]]}
i17 = {"wall": 10000, "elapsed": 1000, "boot_count": None}
add("17", "검토 반례: BOOT_COUNT 없음, 앞 조회의 elapsed가 작아 elapsed가 줄지 않은 재부팅 → STARTUP으로 부팅 구간 (시계 변경으로 버리지 않음)",
    [e7], segment={"start": 0, "kind": "install"}, init=i17, segments=[2], counts={"segments_boot": 1, "clock_discarded": 0},
    queries=[q17])
add("17b", "이미 처리한 STARTUP을 다시 받아도 새 부팅으로 보지 않음",
    [{"target": [52000, "ad.x", ".AdActivity"], "rows": 1},
     {"target": [80000, "ad.x", ".AdActivity"], "features": F(F0=o(30000), F2=o(True), F3=o(28000)), "rule_v0t": AD()}],
    segment={"start": 0, "kind": "install"}, init=i17, segments=[2, 2], counts={"segments_boot": 1, "pre_cursor_ignored": 2},
    queries=[q17, {"wall_to": 90000, "elapsed": 40000, "boot_count": None,
                   "events": q17["events"] + [[80000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("17c", "elapsed가 이어지는 STARTUP (런타임 재시작): 새 구간이지만 '없음'을 확정하지 않음",
    [{"target": [52000, "ad.x", ".AdActivity"], "features": full(o(2000), nc, nc, nc, nc, nc, nc, nc, nc, o(False), o(False)),
      "rule_v0t": HOLD(["R3", "R4"])}],
    init=init, segments=[1, 2], counts={"segments_restart": 1, "segments_boot": 0},
    queries=[{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"]]},
             {"wall_to": 60000, "elapsed": 660000, "boot_count": 1, "events": [[50000, "STARTUP", "android", ""], [52000, "RESUMED", "ad.x", ".AdActivity"]]}])
add("18", "조회가 null (사용자 잠금 해제 전): 커서를 옮기지 않아 다음 조회에서 그 사이 이벤트를 받음",
    [{"target": [30500, "app.a", ".Detail"], "features": F(F2=o(True), F3=o(29500), F4=o(500)), "rule_v0t": NORMAL(["R2"])}],
    init=init, segments=[1, 1], counts={"query_null": 1, "pre_cursor_ignored": 0},
    queries=[{"wall_to": 10000, "elapsed": 610000, "boot_count": 1, "events": [[1000, "RESUMED", "app.a", ".Main"]]},
             {"wall_to": 40000, "elapsed": 640000, "boot_count": 1, "events": None},
             {"wall_to": 70000, "elapsed": 670000, "boot_count": 1,
              "events": [[30000, "PAUSED", "app.a", ".Main"], [30500, "RESUMED", "app.a", ".Detail"]]}])

spec = {"schema": "fx0-draft",
        "note": "기대값은 추출기 실행 전에 손으로 계산. 시각 ms. segment 기본값 {start:-600000, kind:install}(CAP 이상 관측). events 사례는 검사기가 한 번 조회·모든 묶음 경계 두 조회 분할·조회마다 저장 실패 재실행·같은 ms 순서 바꾸기(뒤집기, 돌리기)를 함께 확인. queries 사례는 적힌 조회 그대로에 저장 실패 재실행·같은 ms 순서 바꾸기를 확인하고, range가 있으면 query_range 결과도 확인. order_assume이 있는 사례([생명주기 가정])는 분석용 모드",
        "launchers": {"app.a": {"resolved": [".Main"], "system": False}, "app.b": {"resolved": [".Main"], "system": False},
                      "ad.x": {"resolved": [".Splash"], "system": False}},
        "cases": cases}
json.dump(spec, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(cases), "cases")
