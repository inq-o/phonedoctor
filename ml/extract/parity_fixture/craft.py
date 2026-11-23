"""일치 테스트(T024)용 손으로 만든 매니페스트 APK. aapt2가 만들지 않는 모양(패커·깨진 파일 흉내)을 PC·기기 양쪽에 같이 넣는다.

aapt2로 만든 parity_fixture/a·b는 정상 모양만 다룬다. 여기서는 바이너리 XML을 직접 써서
네임스페이스 붙은 태그, 이름에 박힌 접두어, NUL·BOM이 든 값, 비거나 빠진 이름, 여러 uses-sdk, 다른 값 종류,
시스템 속성 ID 빈 칸, 네임스페이스 조각 크기 조작, 압축 방식 조작, 아주 깊은 중첩, 같은 큰 문자열을 수만 번 가리키는 문자열 표를 만든다.

사용: python ml/extract/parity_fixture/craft.py core/classifier/src/test/resources/parity/crafted
그다음 parity_expected.py로 기대값을 다시 만든다 (PC가 거절한 APK는 reject로 적힘)
"""
import os
import struct
import sys
import zipfile

A = "http://schemas.android.com/apk/res/android"
T_NULL, T_REF, T_STR, T_FLOAT, T_INT, T_BOOL, T_COLOR = 0, 1, 3, 4, 0x10, 0x12, 0x1C
IDS = {"name": 0x01010003, "minSdkVersion": 0x0101020C, "targetSdkVersion": 0x01010270, "enabled": 0x0101000E}


def build(root, utf8=False, nsdecl=(("android", A),), dup_offsets=0, big=None, end_ns_size=None):
    """root: (ns, 이름, 속성들, 자식들). 속성: (ns, 이름, 리소스 ID 또는 None, 종류, 값)"""
    strings, resids = [], []

    def sidx(s):
        if s not in strings:
            strings.append(s)
        return strings.index(s)

    def walk(e):
        # 아주 깊은 중첩도 다루게 재귀 없이 (문서 순서)
        stack = [e]
        while stack:
            x = stack.pop()
            yield x
            stack.extend(reversed(x[3]))

    attr_keys = []
    for e in walk(root):
        for a in e[2]:
            if a[2] is not None and (a[1], a[2]) not in attr_keys:
                attr_keys.append((a[1], a[2]))
    # 리소스 ID가 있는 속성 이름은 문자열 표 앞쪽 (리소스 표와 같은 순서)
    for n, rid in attr_keys:
        strings.append(n)
        resids.append(rid)
    name_to_idx = {k: i for i, k in enumerate(attr_keys)}
    for p, u in nsdecl:
        sidx(p)
        sidx(u)
    chunks = []

    def chunk(t, body, size=None):
        hdr = struct.pack('<HHI', t, 0x10, size if size is not None else 16 + len(body)) + struct.pack('<II', 1, 0xFFFFFFFF)
        chunks.append(hdr + body)

    def ns_i(u):
        return 0xFFFFFFFF if u is None else sidx(u)

    for p, u in nsdecl:
        chunk(0x0100, struct.pack('<II', sidx(p), sidx(u)))

    def start(e):
        ns, name, attrs, _ = e
        body = struct.pack('<II', ns_i(ns), sidx(name)) + struct.pack('<HHHHHH', 20, 20, len(attrs), 0, 0, 0)
        for (ans, an, rid, t, v) in attrs:
            ni = name_to_idx[(an, rid)] if rid is not None else sidx(an)
            if t == T_STR:
                raw = data = sidx(v)
            else:
                raw, data = 0xFFFFFFFF, v & 0xFFFFFFFF
            body += struct.pack('<III', ns_i(ans), ni, raw) + struct.pack('<HBBI', 8, 0, t, data)
        chunk(0x0102, body)

    def end(e):
        chunk(0x0103, struct.pack('<II', ns_i(e[0]), sidx(e[1])))

    # 재귀 없이 시작·끝 조각을 차례로 (아주 깊은 중첩)
    stack = [(root, False)]
    while stack:
        e, closing = stack.pop()
        if closing:
            end(e)
            continue
        start(e)
        stack.append((e, True))
        stack.extend((k, False) for k in reversed(e[3]))
    for i, (p, u) in enumerate(reversed(nsdecl)):
        last = i == len(nsdecl) - 1
        chunk(0x0101, struct.pack('<II', sidx(p), sidx(u)), size=end_ns_size if last else None)

    data = b''
    offs = []
    for s in strings:
        offs.append(len(data))
        if utf8:
            b = s.encode('utf-8')
            u16 = len(s.encode('utf-16-le')) // 2

            def l8(n):
                return bytes([n]) if n < 0x80 else bytes([0x80 | (n >> 8), n & 0xFF])
            data += l8(u16) + l8(len(b)) + b + b'\x00'
        else:
            b = s.encode('utf-16-le')
            n = len(b) // 2
            data += (struct.pack('<H', n) if n < 0x8000 else struct.pack('<HH', 0x8000 | (n >> 16), n & 0xFFFF)) + b + b'\x00\x00'
    if big is not None:
        bigoff = len(data)
        b = big.encode('utf-8')
        data += bytes([0x80 | (len(big) >> 8), len(big) & 0xFF, 0x80 | (len(b) >> 8), len(b) & 0xFF]) + b + b'\x00'
        offs += [bigoff] * dup_offsets
    while len(data) % 4:
        data += b'\x00'
    count = len(offs)
    sp = struct.pack('<HHIIIIII', 1, 0x1C, 28 + 4 * count + len(data), count, 0, 0x100 if utf8 else 0, 28 + 4 * count, 0)
    sp += b''.join(struct.pack('<I', o) for o in offs) + data
    rm = struct.pack('<HHI', 0x0180, 8, 8 + 4 * len(resids)) + b''.join(struct.pack('<I', r) for r in resids)
    body = sp + rm + b''.join(chunks)
    return struct.pack('<HHI', 3, 8, 8 + len(body)) + body


def at(name, v, t=T_STR, ns=A, rid="auto"):
    return (ns, name, IDS.get(name) if rid == "auto" else rid, t, v)


def el(name, attrs=(), kids=(), ns=None):
    return (ns, name, list(attrs), list(kids))


def manifest(app_kids, top_extra=(), sdk=None, pkg="com.t.x"):
    sdk = sdk if sdk is not None else [el("uses-sdk", [at("minSdkVersion", 21, T_INT), at("targetSdkVersion", 34, T_INT)])]
    return el("manifest", [at("package", pkg, ns=None, rid=None)],
              list(sdk) + [el("uses-permission", [at("name", "android.permission.INTERNET")])] + list(top_extra)
              + [el("application", [], app_kids)])


def launcher(name="com.t.x.Main", tag_ns=None, action_ns=None):
    return el("activity", [at("name", name)], [
        el("intent-filter", [], [el("action", [at("name", "android.intent.action.MAIN")], ns=action_ns),
                                 el("category", [at("name", "android.intent.category.LAUNCHER")])])], ns=tag_ns)


def receiver(action="android.intent.action.BOOT_COMPLETED", action_ns=None, name="com.t.x.R", tag_ns=None):
    return el("receiver", [at("name", name)], [el("intent-filter", [], [el("action", [at("name", action)], ns=action_ns)])], ns=tag_ns)


def deep(n):
    e = el("x")
    root = e
    for _ in range(n):
        child = el("x")
        e[3].append(child)
        e = child
    return root


VARIANTS = {
    "base": lambda: build(manifest([launcher(), receiver()])),
    "base_utf8": lambda: build(manifest([launcher(), receiver()]), utf8=True),
    "ns_action": lambda: build(manifest([launcher(), receiver(action_ns=A)])),
    "ns_activity_launcher": lambda: build(manifest([launcher(tag_ns=A), receiver()])),
    "other_ns_activity": lambda: build(manifest([launcher(), el("activity", [at("name", "com.t.x.Other")], ns="http://x"), receiver()])),
    "embedded_prefix_tag": lambda: build(manifest([launcher(), receiver()], top_extra=[el("android:uses-permission", [at("name", "android.permission.CAMERA")])])),
    "target_typenull": lambda: build(manifest([launcher(), receiver()], sdk=[el("uses-sdk", [at("minSdkVersion", 21, T_INT), at("targetSdkVersion", 1, T_NULL)])])),
    "target_color": lambda: build(manifest([launcher(), receiver()], sdk=[el("uses-sdk", [at("minSdkVersion", 21, T_INT), at("targetSdkVersion", 30, T_COLOR)])])),
    "target_float": lambda: build(manifest([launcher(), receiver()], sdk=[el("uses-sdk", [at("minSdkVersion", 21, T_INT), at("targetSdkVersion", 0x41F00000, T_FLOAT)])])),
    "target_ws": lambda: build(manifest([launcher(), receiver()], sdk=[el("uses-sdk", [at("minSdkVersion", 21, T_INT), at("targetSdkVersion", " 3_0")])])),
    "min_codename": lambda: build(manifest([launcher(), receiver()], sdk=[el("uses-sdk", [at("minSdkVersion", "P"), at("targetSdkVersion", 34, T_INT)])])),
    "perm_nul": lambda: build(manifest([launcher(), receiver()], top_extra=[el("uses-permission", [at("name", "android.permission.CAMERA\x00x")])])),
    "main_nul": lambda: build(manifest([el("activity", [at("name", "com.t.x.Main")], [el("intent-filter", [], [
        el("action", [at("name", "android.intent.action.MAIN\x00")]), el("category", [at("name", "android.intent.category.LAUNCHER")])])]), receiver()])),
    "bom_component": lambda: build(manifest([launcher(), receiver(), el("activity", [at("name", "\ufeffcom.google.android.gms.ads.AdActivity")])])),
    "bom_component_utf8": lambda: build(manifest([launcher(), receiver(), el("activity", [at("name", "\ufeffcom.google.android.gms.ads.AdActivity")])]), utf8=True),
    "plain_empty_name": lambda: build(manifest([launcher(), receiver(), el("activity", [at("name", "", ns=None, rid=None)])])),
    "empty_android_name_launcher": lambda: build(manifest([launcher(name=""), receiver()])),
    "two_uses_sdk": lambda: build(manifest([launcher(), receiver(), el("uses-sdk", [at("targetSdkVersion", 1, T_INT)])])),
    "gap_resid": lambda: build(manifest([launcher(), receiver(), el("activity", [at("name", "com.t.x.Gap", rid=0x01010267)])])),
    "disabled_alias_only": lambda: build(manifest([el("activity", [at("name", ".Hidden")]), el("activity-alias", [at("name", ".L"), at("enabled", 0, T_BOOL)], [
        el("intent-filter", [], [el("action", [at("name", "android.intent.action.MAIN")]), el("category", [at("name", "android.intent.category.LAUNCHER")])])])])),
    "nameless_action": lambda: build(manifest([launcher(), el("receiver", [at("name", "com.t.x.R")], [el("intent-filter", [], [el("action")])])])),
    "end_ns_size_32": lambda: build(manifest([launcher(), receiver()]), end_ns_size=32),
    "deep_nesting": lambda: build(manifest([launcher(), receiver(), deep(20000)])),
    "dup_string_offsets": lambda: build(manifest([launcher(), receiver()]), dup_offsets=50000, big="x" * 30000),
    "no_package": lambda: build(el("manifest", [], [el("application", [], [launcher(), receiver()])])),
    # 빈 이름은 패키지명을 붙이지 않는다: 붙이면 "com.applovin.demo."이 되어 광고 SDK 접두어에 걸림
    "empty_name_ad_package": lambda: build(manifest([launcher(), receiver(), el("activity", [at("name", "", ns=None, rid=None)])], pkg="com.applovin.demo")),
}


def apk(path, manifest, method=None):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml', manifest)
    if method is not None:
        # 압축 방식 값만 바꾼다 (저장한 그대로의 바이트): 로컬 헤더 8바이트째, 중앙 디렉터리 10바이트째
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as z:
            z.writestr('AndroidManifest.xml', manifest)
        b = bytearray(open(path, 'rb').read())
        b[8:10] = struct.pack('<H', method)
        cd = b.rfind(b'PK\x01\x02')
        b[cd + 10:cd + 12] = struct.pack('<H', method)
        open(path, 'wb').write(bytes(b))


def main(out):
    os.makedirs(out, exist_ok=True)
    for name, make in VARIANTS.items():
        apk(os.path.join(out, name + ".apk"), make())
    apk(os.path.join(out, "zip_method_99.apk"), VARIANTS["base"](), method=99)
    print(f"{len(VARIANTS) + 1}개 -> {out}")


if __name__ == "__main__":
    main(sys.argv[1])
