"""일치 테스트(T024)의 기대값. PC 추출기(extract.py, androguard)로 APK마다 라벨과 특징 벡터를 JSON 한 줄씩 쓴다.

기기 추출기(core:classifier)의 시험 ParityTest가 같은 APK를 읽어 벡터가 비트 단위로 같은지 본다(classifier.md §5).
라벨은 기기에선 PackageManager가 주므로, 시험은 이 파일의 라벨을 입력으로 쓰고 라벨을 뺀 나머지 계산을 대조한다.

  레포 안 작은 APK(우리가 쓴 매니페스트, parity_fixture/build.sh·craft.py):
    python ml/extract/parity_expected.py core/classifier/src/test/resources/parity/*.apk > core/classifier/src/test/resources/parity/expected.jsonl
    python ml/extract/parity_expected.py core/classifier/src/test/resources/parity/crafted/*.apk > core/classifier/src/test/resources/parity/crafted/expected.jsonl
  PC가 거절한 APK는 {"reject": 예외 이름}으로 적고, 기기도 거절해야 통과
  받은 APK(레포 밖, ml/data). 시험은 이 파일이 있을 때만 돈다:
    python ml/extract/parity_expected.py ml/data/apk/*.apk > ml/data/parity_expected.jsonl
"""
import hashlib
import json
import sys
from pathlib import Path

from loguru import logger

import extract

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent.parent / "core/classifier/src/test/resources/parity"


def rows(paths):
    logger.remove()
    s = extract.load_schema()
    for p in sorted(paths):
        data = Path(p).read_bytes()
        try:
            raw = extract.parse(data)
        except Exception as e:  # extract.main은 깨진 APK를 건너뛴다: 기기도 거절해야 같다
            yield {"file": Path(p).name, "sha256": hashlib.sha256(data).hexdigest(), "reject": type(e).__name__}
            continue
        yield {
            "file": Path(p).name,
            "sha256": hashlib.sha256(data).hexdigest(),
            "package": raw["package"],
            "label": raw["label"],
            "schema_version": s["version"],
            "columns": [[c, v] for c, v in extract.vectorize(raw, s)],
        }


def dump(paths):
    return "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows(paths))


def _check():
    """레포에 넣은 기대값이 지금 androguard로 다시 뽑은 것과 같은지 (기대값을 손으로 고치지 않았는지).
    aapt2로 만든 것(parity/)과 손으로 만든 것(parity/crafted/, craft.py) 둘 다"""
    for d in (FIXTURES, FIXTURES / "crafted"):
        apks = sorted(d.glob("*.apk"))
        assert apks, f"{d}에 APK 없음"
        want = (d / "expected.jsonl").read_text(encoding="utf-8")
        got = dump(apks)
        assert got == want, f"{d}/expected.jsonl이 PC 추출기 결과와 다름: 사용법대로 다시 만들 것"


if __name__ == "__main__":
    if len(sys.argv) > 1:
        sys.stdout.write(dump(sys.argv[1:]))
    else:
        _check()
        print("자체 검사 통과")
