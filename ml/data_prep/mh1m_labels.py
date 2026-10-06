"""MH-1M 메타데이터에서 샘플별 라벨표를 뽑는다.

Figshare 전처리본(amex-1M-inner.npz)의 metadata에 VirusTotal 추천 위협 라벨
(VT_SCANNERS_SUGGESTED_THREAT_LABEL, 예: "adware.dowgin/kuguo")이 들어 있어서
Dataverse 라벨 원본(12.3GB)을 받지 않아도 adware 계열을 가를 수 있다.
16GB짜리 data.npy는 읽지 않는다.

사용: python ml/data_prep/mh1m_labels.py ~/data/mh1m/amex-1M-inner.npz ml/data/mh1m/labels.csv.gz
"""
import io
import sys
import zipfile

import numpy as np
import pandas as pd


def load_metadata(npz_path):
    z = zipfile.ZipFile(npz_path)
    read = lambda name: np.load(io.BytesIO(z.read(name)), allow_pickle=True)
    return pd.DataFrame(read("metadata.npy"), columns=list(read("metadata_columns.npy")))


def label_table(meta):
    threat = meta["VT_SCANNERS_SUGGESTED_THREAT_LABEL"].astype("string")
    category = threat.str.split(".", n=1).str[0]
    family = threat.str.split(".", n=1).str[1].str.split("/").str[0]
    return pd.DataFrame({
        "sha256": meta["SHA256"].str.upper(),
        "class": meta["CLASS"].astype(int),  # 데이터셋 원래 이진 라벨 (1 = 악성)
        "vt_malicious": pd.to_numeric(meta["VT_SCANNERS_MALICIOUS"]),
        "threat_label": threat,
        "category": category,
        "family": family,
        "is_adware": category.eq("adware").fillna(False),
    })


def _check():
    meta = pd.DataFrame({
        "SHA256": ["aa", "bb", "cc"],
        "CLASS": [1, 1, 0],
        "VT_SCANNERS_MALICIOUS": [20, 30, 0],
        "VT_SCANNERS_SUGGESTED_THREAT_LABEL": ["adware.dowgin/kuguo", "trojan.smsreg", None],
    })
    t = label_table(meta)
    assert t["is_adware"].tolist() == [True, False, False]
    assert t["family"].tolist()[:2] == ["dowgin", "smsreg"]
    assert t["sha256"].tolist()[0] == "AA"


if __name__ == "__main__":
    _check()
    src, dst = sys.argv[1], sys.argv[2]
    table = label_table(load_metadata(src))
    table.to_csv(dst, index=False, compression="gzip")
    print(f"{len(table):,} rows → {dst}")
    print("class:", table["class"].value_counts().to_dict())
    print("adware:", int(table["is_adware"].sum()), "| top families:",
          table.loc[table["is_adware"], "family"].value_counts().head(10).to_dict())
