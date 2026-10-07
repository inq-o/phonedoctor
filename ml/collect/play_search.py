"""Play 공개 검색 결과에서 수집 후보 패키지명을 뽑는다 (T025).

로그인 없이 검색 페이지 HTML만 읽는다. group은 검색 의도일 뿐 라벨이 아니다.
라벨은 APK를 받은 뒤 직접 판정한다.

사용: python ml/collect/play_search.py ml/collect/queries.csv ml/collect/packages.csv
그다음 APK 다운로드는 ml/collect/README.md 참고.
"""
import csv
import re
import sys
import time
import urllib.parse
import urllib.request

URL = "https://play.google.com/store/search?q={}&c=apps&hl=ko&gl=KR"
ID = re.compile(r"details\?id=([A-Za-z0-9_.]+)")


def search(query):
    req = urllib.request.Request(URL.format(urllib.parse.quote(query)), headers={"User-Agent": "Mozilla/5.0"})
    html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8")
    return list(dict.fromkeys(ID.findall(html)))  # 검색 순위 유지, 중복 제거


def main(queries_path, out_path):
    seen = {}
    with open(queries_path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            for rank, pkg in enumerate(search(row["query"]), 1):
                seen.setdefault(pkg, (row["group"], row["query"], rank))
            time.sleep(2)
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["package", "group", "query", "rank"])
        for pkg, v in seen.items():
            w.writerow([pkg, *v])
    print(f"{len(seen)} packages -> {out_path}")


def _check():
    html = '<a href="/store/apps/details?id=com.a.b">x</a><a href="/store/apps/details?id=com.c_d.e2&hl=ko">'
    assert ID.findall(html) == ["com.a.b", "com.c_d.e2"]


if __name__ == "__main__":
    _check()
    main(*sys.argv[1:3])
