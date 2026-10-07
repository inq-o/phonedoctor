# 직접 수집 (T025)

Play에서 한국 회색지대 장르 앱과 시니어가 자주 쓰는 앱의 APK를 받는다. MH-1M(학습 본체)과 병행해 한국 앱 평가셋과 광고 SDK 검증에 쓴다.

## 1. 후보 목록

```bash
python ml/collect/play_search.py ml/collect/queries.csv ml/collect/packages.csv
```

로그인 없이 공개 검색 페이지만 읽는다. `group`은 검색 의도이고 라벨이 아니다.

## 2. APK 다운로드

[apkeep](https://github.com/EFForg/apkeep) 1.1.0(`brew install apkeep`)과 익명 공용 계정 토큰(Aurora Store 디스펜서)을 쓴다. 개인 구글 계정은 쓰지 않는다.

```bash
tail -n +2 ml/collect/packages.csv | cut -d, -f1 > ml/data/apk_ids.csv
apkeep -c ml/data/apk_ids.csv -d google-play -i ml/data/apkeep.ini --accept-tos \
  -o locale=ko_KR,timezone=Asia/Seoul -s 1000 -r 2 ml/data/apk/
```

- `ml/data/apkeep.ini`에 `[google]` 아래 `email`·`auth_token`을 둔다. 토큰은 레포에 넣지 않고, 만료되면 다시 받는다.
- APK는 `ml/data/apk/`에만 두고 재배포하지 않는다. 공개하는 것은 특징값과 SHA-256뿐이다.
- 지역 제한 앱은 한국 IP에서 받아야 한다.

## 3. 특징 추출 (T022)

```bash
pip install androguard==4.1.4
python ml/extract/extract.py ml/data/apk ml/data/apk_features.csv.gz
```
