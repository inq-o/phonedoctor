package io.github.inq_o.phonedoctor.core.usage.detector

import kotlinx.serialization.Serializable

/** 사용 기록 이벤트 1건. [type]은 `UsageEvents.Event`의 종류 번호 그대로 */
data class UsageEvent(val ts: Long, val type: Int, val pkg: String, val cls: String = "")

/** 조회 1회분. [events]는 OS가 돌려준 순서 그대로이고, 잠금 해제 전처럼 조회가 null이면 null */
data class UsageQuery(val wallTo: Long, val elapsed: Long, val bootCount: Int?, val events: List<UsageEvent>?)

/** 판정 시점의 패키지 정보. [since]는 마지막 설치·업데이트 시각(모르면 null) */
data class PackageMeta(val resolved: Set<String>, val system: Boolean, val since: Long?)

enum class FeatureStatus { OBSERVED, NONE_IN_WINDOW, NOT_COVERED, LOOKUP_FAILED, TIED }

/** 특징 값 하나. 경과 시간은 [num], 참·거짓은 [bool]에 담고 둘 다 없으면 값 없음 */
data class Feature(val num: Long? = null, val bool: Boolean? = null, val status: FeatureStatus)

enum class Outcome { AD, NORMAL, HOLD }

enum class MetaValidity { VERIFIED, UNVERIFIED, UPDATED_AFTER_EVENT, ABSENT }

/** RESUMED 1건의 판정 결과 */
data class DetectorRow(
    val segmentId: Int,
    val ingestId: Long,
    val queryId: Long,
    val sourceSeq: Int,
    val ts: Long,
    val pkg: String,
    val cls: String,
    val features: Map<String, Feature>,
    val metaValidity: MetaValidity,
    val outcome: Outcome,
    val failed: List<String>,
    val unknown: List<String>,
)

@Serializable
data class Prev(val pkg: String, val cls: String, val ts: Long)

@Serializable
data class ScreenOff(val ts: Long, val beforePkg: String?, val beforeCls: String?)

/** 같은 ms 묶음의 순서를 한 가지로 정했을 때의 직전 상태 */
@Serializable
data class World(
    val prev: Prev? = null,
    val offAfterPrev: Boolean = false,
    val paused: Map<String, Long> = emptyMap(),
    val ui: Map<String, Long> = emptyMap(),
    val on: Long? = null,
    val unlock: Long? = null,
    val off: ScreenOff? = null,
)

@Serializable
data class Segment(val id: Int, val start: Long, val kind: String)

@Serializable
data class PendingBoundary(val start: Long, val kind: String)

@Serializable
data class UncertainRange(val from: Long, val to: Long?, val kind: String)

/** 끝을 모르는 버릴 범위(재부팅하면서 시계가 뒤로 감). 열려 있는 동안은 행을 만들지 않는다 */
@Serializable
data class OpenQuarantine(val after: Long?, val shift: Long, val floor: Long, val closable: Boolean, val kind: String)

@Serializable
data class LastQuery(val wall: Long, val elapsed: Long, val bootCount: Int?)

/**
 * 조회 사이에 저장하는 상태. 한 조회의 결과(행)와 이 상태를 같은 트랜잭션으로 저장해야 한다.
 * [skipUntil]이 null이면 [openQuarantine]이 열려 있는 것이다.
 */
@Serializable
data class DetectorState(
    val schema: String,
    val cursor: Long,
    val skipUntil: Long?,
    val segment: Segment,
    val worlds: List<World>,
    val pending: List<PendingBoundary> = emptyList(),
    val uncertain: List<UncertainRange> = emptyList(),
    val openQuarantine: OpenQuarantine? = null,
    val bootMaxTs: Long? = null,
    val last: LastQuery,
    val nextQuery: Long = 1,
    val nextIngest: Long = 1,
    val counts: Map<String, Long> = emptyMap(),
)

data class QueryResult(val state: DetectorState, val rows: List<DetectorRow>)
