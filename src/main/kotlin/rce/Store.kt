package rce

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OptimisticLockException(val expected: Int, val actual: Int) :
    RuntimeException("乐观锁冲突：期望当前版本号 $expected，实际 $actual")

class PublishConflictException(val conflicts: List<ConflictPair>) :
    RuntimeException("存在 ${conflicts.size} 个未登记例外的规则冲突，禁止发布")

class NotFoundException(msg: String) : RuntimeException(msg)

class Store(private val dir: Path, var clock: () -> LocalDate = { LocalDate.now() }) {

    @Serializable
    data class State(
        val versions: MutableList<RuleSetVersion> = mutableListOf(),
        val facts: MutableList<SavedFact> = mutableListOf(),
        val runs: MutableList<RunRecord> = mutableListOf(),
        var draftSeq: Int = 0,
        var factSeq: Int = 0,
        var runSeq: Int = 0,
        var exceptionSeq: Int = 0,
    )

    @Serializable
    data class ExportBundle(
        val format: String = "rce-export",
        val formatVersion: Int = 1,
        val state: State,
    )

    private val lock = ReentrantLock()
    private val file: Path = dir.resolve("state.json")
    private var state: State = load()

    private fun load(): State {
        if (!Files.exists(file)) return State()
        return AppJson.decodeFromString(State.serializer(), Files.readString(file))
    }

    /** 原子落盘：先写临时文件再原子改名，失败不会留下半个版本。 */
    private fun persist() {
        Files.createDirectories(dir)
        val tmp = dir.resolve("state.json.tmp")
        Files.writeString(tmp, AppJson.encodeToString(State.serializer(), state))
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun snapshot(): State = lock.withLock {
        AppJson.decodeFromJsonElement(AppJson.encodeToJsonElement(State.serializer(), state))
    }

    fun currentPublishedSeq(): Int = lock.withLock {
        state.versions.filter { it.status == VersionStatus.PUBLISHED }.maxOfOrNull { it.seq } ?: 0
    }

    fun getVersion(idOrSeq: String): RuleSetVersion = lock.withLock {
        state.versions.firstOrNull { it.id == idOrSeq || (it.status == VersionStatus.PUBLISHED && it.seq.toString() == idOrSeq) }
            ?: throw NotFoundException("版本不存在：$idOrSeq")
    }

    fun createDraft(baseSeq: Int?): RuleSetVersion = lock.withLock {
        val base = baseSeq?.let { s ->
            state.versions.firstOrNull { it.status == VersionStatus.PUBLISHED && it.seq == s }
                ?: throw NotFoundException("基线版本不存在：$s")
        }
        state.draftSeq += 1
        val draft = RuleSetVersion(
            id = "draft-${state.draftSeq}",
            seq = 0,
            status = VersionStatus.DRAFT,
            baseSeq = base?.seq,
            rules = base?.rules ?: emptyList(),
            exceptions = base?.exceptions ?: emptyList(),
            createdAt = Instant.now().toString(),
        )
        state.versions += draft
        persist()
        draft
    }

    private fun draftOf(id: String): RuleSetVersion {
        val d = state.versions.firstOrNull { it.id == id }
            ?: throw NotFoundException("版本不存在：$id")
        if (d.status != VersionStatus.DRAFT) throw IllegalStateException("只有草稿可以修改：$id")
        return d
    }

    private fun replace(version: RuleSetVersion) {
        val i = state.versions.indexOfFirst { it.id == version.id }
        state.versions[i] = version
    }

    fun updateRules(draftId: String, rules: List<Rule>): RuleSetVersion = lock.withLock {
        val d = draftOf(draftId)
        val ids = rules.map { it.id }
        require(ids.size == ids.toSet().size) { "规则 ID 重复" }
        val nd = d.copy(rules = rules)
        replace(nd); persist(); nd
    }

    fun addException(draftId: String, ruleA: String, ruleB: String, reason: String, expiresOn: String): RuleSetVersion = lock.withLock {
        val d = draftOf(draftId)
        LocalDate.parse(expiresOn)
        state.exceptionSeq += 1
        val ex = CoexistException("ex-${state.exceptionSeq}", ruleA, ruleB, reason, expiresOn)
        val nd = d.copy(exceptions = d.exceptions + ex)
        replace(nd); persist(); nd
    }

    fun removeException(draftId: String, exceptionId: String): RuleSetVersion = lock.withLock {
        val d = draftOf(draftId)
        val nd = d.copy(exceptions = d.exceptions.filterNot { it.id == exceptionId })
        replace(nd); persist(); nd
    }

    fun conflictsOf(idOrSeq: String, asOf: LocalDate = clock()): List<ConflictPair> {
        val v = getVersion(idOrSeq)
        return Conflict.detect(v.rules, v.exceptions, asOf)
    }

    /** 乐观锁发布：expectedSeq 必须等于当前已发布最大版本号；冲突未登记例外则拒绝。 */
    fun publish(draftId: String, expectedSeq: Int): RuleSetVersion = lock.withLock {
        val d = draftOf(draftId)
        val exposed = Conflict.detect(d.rules, d.exceptions, clock())
            .filter { it.coveredByException == null }
        if (exposed.isNotEmpty()) throw PublishConflictException(exposed)
        val current = state.versions.filter { it.status == VersionStatus.PUBLISHED }.maxOfOrNull { it.seq } ?: 0
        if (expectedSeq != current) throw OptimisticLockException(expectedSeq, current)
        val published = d.copy(
            seq = current + 1,
            status = VersionStatus.PUBLISHED,
            fingerprint = fingerprintOf(d.rules, d.exceptions),
        )
        replace(published)
        persist()
        published
    }

    fun saveFact(name: String, payload: Map<String, JsonElement>): SavedFact = lock.withLock {
        state.factSeq += 1
        val f = SavedFact("fact-${state.factSeq}", name, payload.toSortedMap())
        state.facts += f
        persist()
        f
    }

    fun deleteFact(id: String) = lock.withLock {
        state.facts.removeIf { it.id == id }
        persist()
    }

    fun run(versionIdOrSeq: String, factName: String?, fact: Map<String, JsonElement>, asOf: LocalDate): RunRecord = lock.withLock {
        val v = getVersion(versionIdOrSeq)
        val result = Engine.run(v, fact, asOf)
        state.runSeq += 1
        val rec = RunRecord(
            id = "run-${state.runSeq}",
            versionSeq = v.seq,
            versionFingerprint = v.fingerprint,
            factName = factName,
            fact = fact.toSortedMap(),
            asOf = asOf.toString(),
            result = result,
            createdAt = Instant.now().toString(),
        )
        state.runs += rec
        persist()
        rec
    }

    fun getRun(id: String): RunRecord = lock.withLock {
        state.runs.firstOrNull { it.id == id } ?: throw NotFoundException("运行记录不存在：$id")
    }

    /** 历史重放：直接返回持久化的运行记录，不受后续版本与例外过期影响。 */
    fun replay(id: String): RunRecord = getRun(id)

    fun impact(fromSeq: Int, toSeq: Int, asOf: LocalDate): List<ImpactEntry> = lock.withLock {
        val from = getVersion(fromSeq.toString())
        val to = getVersion(toSeq.toString())
        state.facts.map { f ->
            val r1 = Engine.run(from, f.payload, asOf)
            val r2 = Engine.run(to, f.payload, asOf)
            val cause = when {
                r1.conclusion == r2.conclusion -> "结论未变化"
                r2.winnerRuleId == null -> "新版本下无任何规则命中（旧胜者：'${r1.winnerRuleId}'）"
                r1.winnerRuleId == null -> "规则 '${r2.winnerRuleId}' 在新版本命中并胜出（旧版本无命中）"
                r2.winnerRuleId != r1.winnerRuleId ->
                    "规则 '${r2.winnerRuleId}' 取代 '${r1.winnerRuleId}' 成为胜者"
                else -> "规则 '${r2.winnerRuleId}' 的结论在新版本中被修改"
            }
            ImpactEntry(f.id, f.name, r1.conclusion, r2.conclusion, r1.winnerRuleId, r2.winnerRuleId, cause)
        }
    }

    fun export(): ExportBundle = lock.withLock { ExportBundle(state = snapshot()) }

    fun import(bundle: ExportBundle) = lock.withLock {
        require(bundle.format == "rce-export") { "无法识别的导出格式：${bundle.format}" }
        // 指纹确定性校验：导入后重算必须一致
        for (v in bundle.state.versions.filter { it.status == VersionStatus.PUBLISHED }) {
            val fp = fingerprintOf(v.rules, v.exceptions)
            require(fp == v.fingerprint) { "版本 v${v.seq} 指纹校验失败" }
        }
        state = bundle.state
        persist()
    }
}
