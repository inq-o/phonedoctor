"""MH-1M에서 기기에서도 뽑을 수 있는 열(권한·인텐트)만 꺼내 저장하고 어휘 통계를 낸다.

data.npy는 int8, Fortran order(열 우선)라서 열이 하나씩 이어져 있다.
권한·인텐트 열은 맨 뒤에 있으므로 앞쪽 API 호출 열(약 15.5GB)은 압축만 풀어 버리고
뒤쪽만 메모리에 올린다. 압축 해제는 순차적이라 전체를 한 번 읽는 시간은 든다.

출력
  static.npz : X (행 = 샘플, 열 = 권한·인텐트, uint8 0/1), columns, sha256
  vocab_stats.csv : 열마다 정상·adware·기타 악성 보유율

사용: python ml/data_prep/mh1m_static.py ~/data/mh1m/amex-1M-inner.npz ml/data/mh1m/labels.csv.gz ml/data/mh1m
"""
import io
import sys
import zipfile
from os.path import join

import numpy as np
import pandas as pd

KEEP_PREFIXES = ("permissions::", "intents::")
CHUNK = 64 << 20


def read_tail_columns(npz_path, keep):
    """keep: 정렬된 열 번호. 반드시 맨 뒤 연속 구간이어야 한다(그래야 앞을 버리고 읽을 수 있음)."""
    z = zipfile.ZipFile(npz_path)
    with z.open("data.npy") as fh:
        version = np.lib.format.read_magic(fh)
        read_header = np.lib.format.read_array_header_1_0 if version == (1, 0) else np.lib.format.read_array_header_2_0
        shape, fortran, dtype = read_header(fh)
        n_rows, n_cols = shape
        assert fortran and dtype == np.int8, (fortran, dtype)
        assert keep[-1] == n_cols - 1 and np.all(np.diff(keep) == 1), "권한·인텐트 열이 맨 뒤 연속이 아님"
        skip = int(keep[0]) * n_rows
        while skip:
            skip -= len(fh.read(min(CHUNK, skip)))
        buf = fh.read(len(keep) * n_rows)
    assert len(buf) == len(keep) * n_rows
    return np.frombuffer(buf, dtype=np.int8).reshape((len(keep), n_rows)).T


def vocab_stats(X, columns, labels):
    groups = {
        "benign": labels["class"].eq(0).to_numpy(),
        "adware": labels["is_adware"].to_numpy(),
        "other_malware": (labels["class"].eq(1) & ~labels["is_adware"]).to_numpy(),
    }
    out = pd.DataFrame({"column": columns})
    for name, mask in groups.items():
        out[f"{name}_rate"] = X[mask].mean(axis=0)
        out[f"{name}_n"] = int(mask.sum())
    out["adware_lift"] = (out["adware_rate"] + 1e-4) / (out["benign_rate"] + 1e-4)
    return out.sort_values("adware_lift", ascending=False)


def _check():
    X = np.array([[1, 0], [1, 1], [0, 1]], dtype=np.int8)
    labels = pd.DataFrame({"class": [0, 1, 1], "is_adware": [False, True, False]})
    s = vocab_stats(X, ["a", "b"], labels).set_index("column")
    assert s.loc["a", "benign_rate"] == 1 and s.loc["b", "adware_rate"] == 1
    assert s.loc["b", "adware_lift"] > s.loc["a", "adware_lift"]


if __name__ == "__main__":
    _check()
    npz_path, labels_path, out_dir = sys.argv[1:4]
    z = zipfile.ZipFile(npz_path)
    names = np.load(io.BytesIO(z.read("column_names.npy")), allow_pickle=True)
    sha = np.load(io.BytesIO(z.read("sha256.npy")), allow_pickle=True)
    keep = np.array([i for i, c in enumerate(names) if c.startswith(KEEP_PREFIXES)])
    labels = pd.read_csv(labels_path)
    assert (labels["sha256"].to_numpy() == np.char.upper(sha.astype(str))).all(), "라벨표와 행 순서가 다름"

    X = read_tail_columns(npz_path, keep).astype(np.uint8)
    columns = names[keep]
    np.savez_compressed(join(out_dir, "static.npz"), X=X, columns=columns, sha256=sha)
    stats = vocab_stats(X, columns, labels)
    stats.to_csv(join(out_dir, "vocab_stats.csv"), index=False, float_format="%.5f")
    print(f"X {X.shape}, ones {X.mean():.4f}")
    print(stats.head(25).to_string(index=False))
