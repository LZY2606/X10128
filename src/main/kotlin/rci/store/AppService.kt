package rci.store

import rci.domain.*
import java.time.LocalDate
import java.util.UUID

class OptimisticLockException(message: String) : RuntimeException(message)
class ValidationException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
class ConflictBlockedException(val conflicts: List<ConflictPair>) :
    RuntimeException("存在未被例外覆盖的静态冲突，禁止发布")

data class StoreState(
    val versions: MutableMap<String, RuleSetVersion> = linkedMapOf(),
    val exceptions: MutableList<Exception> = mutableListOf(),
    val facts: MutableMap<String, SavedFact> = linkedMapOf(),
    val runs: MutableList<RunResult> = mutableListOf(),
)

class AppService(
    private val store: FileStore,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private fun now() = java.time.Instant.now().toString()
    private fun id(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun readState(): StoreState {
        val raw = store.load()
        @Suppress("UNCHECKED_CAST")
        return StoreState(
            versions = (raw["versions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readVersion(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            exceptions = (raw["exceptions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readException(it as Map<String, Any?>) }.toMutableList(),
            facts = (raw["facts"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readSavedFact(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            runs = (raw["runs"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readRun(it as Map<String, Any?>) }.toMutableList(),
        )
    }

    private fun writeState(s: StoreState): Map<String, Any?> = linkedMapOf(
        "format" to 1,
        "versions" to s.versions.values.map { Codec.writeVersion(it) },
        "exceptions" to s.exceptions.map { Codec.writeException(it) },
        "facts" to s.facts.values.map { Codec.writeSavedFact(it) },
        "runs" to s.runs.map { Codec.writeRun(it) },
    )

    fun listVersions(): List<RuleSetVersion> = readState().versions.values.sortedBy { it.createdAt }
    fun getVersion(vid: String): RuleSetVersion = readState().versions[vid] ?: throw NotFoundException("版本不存在：$vid")

    /** 首个版本（无父版本）。 */
    fun createInitialVersion(name: String): RuleSetVersion {
        val state = readState()
        if (state.versions.isNotEmpty()) throw ValidationException("已存在版本，请基于现有版本创建草稿")
        val v = RuleSetVersion(id("v"), null, name.ifBlank { "v1" }, VersionStatus.DRAFT,
            emptyList(), emptyList(), now(), null, 0, null)
        persist { it.versions[v.id] = v }
        return v
    }

    /** 基于一个已发布版本克隆出可编辑草稿，保留规则 ID，保证影响预览能对齐规则。 */
    fun createDraft(parentId: String, name: String): RuleSetVersion {
        val state = readState()
        val parent = state.versions[parentId] ?: throw NotFoundException("父版本不存在：$parentId")
        if (parent.status != VersionStatus.PUBLISHED) throw ValidationException("只能基于已发布版本创建草稿")
        val v = RuleSetVersion(id("v"), parentId, name.ifBlank { "${parent.name} 的草稿" },
            VersionStatus.DRAFT, parent.rules.map { it.copy() }, emptyList(), now(), null, 0, null)
        persist { it.versions[v.id] = v }
        return v
    }

    /** 乐观锁保存草稿：客户端必须带回期望的 revision。 */
    fun saveDraft(version: RuleSetVersion, expectedRevision: Long): RuleSetVersion {
        version.rules.forEach { validateRule(it) }
        return persist { state ->
            val current = state.versions[version.id]
                ?: throw NotFoundException("版本不存在：${version.id}")
            if (current.status != VersionStatus.DRAFT) throw ValidationException("已发布版本不可修改")
            if (current.revision != expectedRevision)
                throw OptimisticLockException("版本已被他人修改：当前 revision=${current.revision}，你基于的是 $expectedRevision")
            val updated = version.copy(
                status = VersionStatus.DRAFT, exceptions = emptyList(),
                fingerprint = null, revision = current.revision + 1,
                parentId = current.parentId, name = version.name,
                createdAt = current.createdAt, publishedAt = null,
            )
            state.versions[updated.id] = updated
            updated
        }
    }

    /**
     * 发布（乐观锁 + 原子提交）：
     *  - revision 不匹配则整体失败，不写任何内容；
     *  - 静态冲突闸门未通过则整体失败；
     *  - 发布瞬间把当前有效例外快照进版本。
     */
    fun publish(versionId: String, expectedRevision: Long, today: LocalDate = clock()): RuleSetVersion = persist { state ->
        val current = state.versions[versionId] ?: throw NotFoundException("版本不存在：$versionId")
        if (current.status == VersionStatus.PUBLISHED) throw ValidationException("该版本已发布")
        if (current.revision != expectedRevision)
            throw OptimisticLockException("并发发布冲突：当前 revision=${current.revision}，你基于的是 $expectedRevision")
        current.rules.forEach { validateRule(it) }
        val snapshot = current.copy(exceptions = state.exceptions.toList())
        val blocking = Engine(clock).blockingConflicts(snapshot, state.exceptions, today)
        if (blocking.isNotEmpty()) throw ConflictBlockedException(blocking)
        val published = current.copy(
            status = VersionStatus.PUBLISHED,
            exceptions = state.exceptions.filter { e -> current.rules.any { it.id == e.ruleA || it.id == e.ruleB } },
            publishedAt = now(),
            revision = current.revision + 1,
        ).let { it.copy(fingerprint = Fingerprint.of(it)) }
        state.versions[versionId] = published
        published
    }

    fun analyze(versionId: String): ConflictReport {
        val state = readState()
        val v = state.versions[versionId] ?: throw NotFoundException("版本不存在：$versionId")
        return Engine(clock).analyzeConflicts(v, state.exceptions)
    }

    // ---- 例外 --------------------------------------------------------------------

    fun addException(ruleA: String, ruleB: String, reason: String, expiresOn: LocalDate?): Exception {
        if (ruleA == ruleB) throw ValidationException("例外需要两条不同规则")
        if (reason.isBlank()) throw ValidationException("例外必须登记理由")
        val exc = Exception(id("ex"), ruleA, ruleB, reason, expiresOn, clock())
        persist { it.exceptions.add(exc) }
        return exc
    }

    fun listExceptions(includeExpired: Boolean = true, today: LocalDate = clock()): List<Exception> =
        readState().exceptions.let { all -> if (includeExpired) all else all.filter { it.activeOn(today) } }

    // ---- 事实 --------------------------------------------------------------------

    fun saveFact(name: String, fact: Fact, factId: String? = null): SavedFact {
        val sf = SavedFact(factId ?: id("f"), name.ifBlank { "未命名事实" }, fact, now())
        persist { it.facts[sf.id] = sf }
        return sf
    }
    fun listFacts(): List<SavedFact> = readState().facts.values.sortedBy { it.createdAt }

    // ---- 运行 --------------------------------------------------------------------

    fun run(versionId: String, fact: Fact, factId: String?, effectiveAt: LocalDate, persistRun: Boolean = true): RunResult {
        val state = readState()
        val v = state.versions[versionId] ?: throw NotFoundException("版本不存在：$versionId")
        val outcome = Engine(clock).runRules(v.rules, fact, effectiveAt)
        val result = RunResult(
            runId = id("run"), versionId = v.id, versionFingerprint = v.fingerprint,
            factId = factId, fact = fact, effectiveAt = effectiveAt,
            finalConclusion = outcome.finalConclusion,
            winnerRuleIds = outcome.winners,
            ruleResults = outcome.ruleResults,
            comparisonSteps = outcome.steps,
            ranAt = now(),
        )
        if (persistRun) persist { it.runs.add(result) }
        return result
    }

    /** 历史重放：直接返回当时持久化的完整结果（旧版本指纹可校验）。 */
    fun replay(runId: String): RunResult =
        readState().runs.firstOrNull { it.runId == runId } ?: throw NotFoundException("运行记录不存在：$runId")

    fun listRuns(): List<RunResult> = readState().runs.sortedBy { it.ranAt }

    /** 在另一版本上重跑同一事实（用于影响预览之外的手工对照）。 */
    fun rerun(runId: String, versionId: String, effectiveAt: LocalDate = clock()): RunResult {
        val old = replay(runId)
        return run(versionId, old.fact, old.factId, effectiveAt)
    }

    fun previewImpact(fromVersionId: String, toVersionId: String, effectiveAt: LocalDate = clock()): ImpactPreview {
        val state = readState()
        val a = state.versions[fromVersionId] ?: throw NotFoundException("版本不存在：$fromVersionId")
        val b = state.versions[toVersionId] ?: throw NotFoundException("版本不存在：$toVersionId")
        return Engine(clock).previewImpact(a, b, state.facts.values.toList(), effectiveAt)
    }

    // ---- 导入导出 ----------------------------------------------------------------

    fun export(): String = Codec.encode(writeState(readState()))

    /**
     * 导入：整体替换。导入后对所有已发布版本重新计算指纹并校验，
     * 若与文件中自带指纹不一致则拒绝（保证导出→导入后版本指纹不变）。
     */
    fun importBundle(json: String) {
        val raw = @Suppress("UNCHECKED_CAST") (Codec.decode(json) as Map<String, Any?>)
        val state = StoreState(
            versions = (raw["versions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readVersion(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            exceptions = (raw["exceptions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readException(it as Map<String, Any?>) }.toMutableList(),
            facts = (raw["facts"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readSavedFact(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            runs = (raw["runs"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readRun(it as Map<String, Any?>) }.toMutableList(),
        )
        state.versions.values.filter { it.status == VersionStatus.PUBLISHED }.forEach { v ->
            val recomputed = Fingerprint.of(v)
            if (v.fingerprint != null && v.fingerprint != recomputed)
                throw ValidationException("版本 ${v.id} 指纹校验失败：导入数据可能被篡改或来自不兼容版本")
        }
        store.save(writeState(state))
    }

    // ---- 内部 --------------------------------------------------------------------

    private fun validateRule(r: Rule) {
        if (r.id.isBlank()) throw ValidationException("规则 ID 不能为空")
        // 让解析层尽早暴露结构性问题
        Codec.writeCondition(r.condition)
    }

    private fun <T> persist(block: (StoreState) -> T): T = store.mutate { raw ->
        val state = hydrate(raw)
        val result = block(state)
        raw.clear()
        raw.putAll(writeState(state))
        result
    }

    private fun hydrate(raw: MutableMap<String, Any?>): StoreState {
        @Suppress("UNCHECKED_CAST")
        return StoreState(
            versions = (raw["versions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readVersion(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            exceptions = (raw["exceptions"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readException(it as Map<String, Any?>) }.toMutableList(),
            facts = (raw["facts"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readSavedFact(it as Map<String, Any?>) }.associateByTo(linkedMapOf()) { it.id },
            runs = (raw["runs"] as? List<Any?> ?: emptyList<Any?>())
                .map { Codec.readRun(it as Map<String, Any?>) }.toMutableList(),
        )
    }
}
