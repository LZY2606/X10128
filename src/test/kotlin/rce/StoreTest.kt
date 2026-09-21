package rce

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTest {

    @TempDir lateinit var dir: Path

    private fun conflictRules(): List<Rule> = listOf(
        Rule("r1", "规则1", Condition.NumRange("x", 0.0, true, 200.0, true),
            "2026-01-01", null, 2, "A", "t"),
        Rule("r2", "规则2", Condition.NumRange("x", 50.0, true, 200.0, true),
            "2026-01-01", null, 1, "B", "t"),
    )

    private fun publishFirst(store: Store, rules: List<Rule> = conflictRules()): RuleSetVersion {
        val d = store.createDraft(null)
        store.updateRules(d.id, rules)
        store.addException(d.id, "r1", "r2", "业务确认可并存", "2026-12-31")
        return store.publish(d.id, 0)
    }

    @Test
    fun `有未覆盖冲突时禁止发布`() {
        val store = Store(dir) { LocalDate.of(2026, 6, 1) }
        val d = store.createDraft(null)
        store.updateRules(d.id, conflictRules())
        assertFailsWith<PublishConflictException> { store.publish(d.id, 0) }
        // 登记有效例外后可发布
        store.addException(d.id, "r1", "r2", "业务确认", "2026-12-31")
        val v = store.publish(d.id, 0)
        assertEquals(1, v.seq)
    }

    @Test
    fun `例外过期后新运行重新暴露冲突但历史运行原样重放`() {
        var today = LocalDate.of(2026, 6, 1)
        val store = Store(dir) { today }
        val d = store.createDraft(null)
        store.updateRules(d.id, conflictRules())
        store.addException(d.id, "r1", "r2", "业务确认", "2026-06-30")
        store.publish(d.id, 0)

        val rec = store.run("1", "样例", mapOf("x" to JsonPrimitive(75)), LocalDate.of(2026, 6, 1))
        assertEquals("A", rec.result.conclusion)

        // 过期后：新版本基于 v1（例外随之复制），发布应被拒绝
        today = LocalDate.of(2026, 7, 1)
        val d2 = store.createDraft(1)
        assertFailsWith<PublishConflictException> { store.publish(d2.id, 1) }

        // 历史运行按当时记录原样重现
        val replay = store.replay(rec.id)
        assertEquals(rec.result.winnerRuleId, replay.result.winnerRuleId)
        assertEquals(rec.result.steps, replay.result.steps)
        assertEquals(rec.versionFingerprint, replay.versionFingerprint)
    }

    @Test
    fun `版本影响预览定位结论变化及导致规则`() {
        val store = Store(dir) { LocalDate.of(2026, 6, 1) }
        publishFirst(store)
        store.saveFact("低值", mapOf("x" to JsonPrimitive(10)))
        store.saveFact("高值", mapOf("x" to JsonPrimitive(150)))

        // v2：r2 优先级提高，结论 B 在高值场景胜出
        val d = store.createDraft(1)
        store.updateRules(d.id, listOf(
            conflictRules()[0],
            conflictRules()[1].copy(priority = 9),
        ))
        store.publish(d.id, 1)

        val impact = store.impact(1, 2, LocalDate.of(2026, 6, 1))
        assertEquals(2, impact.size)
        val changed = impact.first { it.factName == "高值" }
        assertEquals("A", changed.fromConclusion)
        assertEquals("B", changed.toConclusion)
        assertEquals("r2", changed.toWinner)
        assertTrue(changed.cause.contains("r2"), changed.cause)
        val same = impact.first { it.factName == "低值" }
        assertEquals("A", same.toConclusion)
    }

    @Test
    fun `并发发布只有一个成功且不留半个版本`() {
        val store = Store(dir) { LocalDate.of(2026, 6, 1) }
        val d1 = store.createDraft(null)
        val d2 = store.createDraft(null)
        store.updateRules(d1.id, listOf(
            Rule("r1", "", Condition.Exists("x"), "2026-01-01", null, 1, "A", "t")))
        store.updateRules(d2.id, listOf(
            Rule("r2", "", Condition.Exists("y"), "2026-01-01", null, 1, "B", "t")))

        val exec = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val wins = AtomicInteger()
        val fails = AtomicInteger()
        listOf(d1.id, d2.id).forEach { id ->
            exec.submit {
                barrier.await()
                try {
                    store.publish(id, 0); wins.incrementAndGet()
                } catch (e: OptimisticLockException) {
                    fails.incrementAndGet()
                }
            }
        }
        exec.shutdown(); exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, wins.get(), "恰有一个发布成功")
        assertEquals(1, fails.get(), "恰有一个乐观锁失败")
        assertEquals(1, store.currentPublishedSeq())

        // 磁盘上状态自洽：只有一个已发布版本，失败者仍是草稿
        val reloaded = Store(dir) { LocalDate.of(2026, 6, 1) }
        val snap = reloaded.snapshot()
        val published = snap.versions.filter { it.status == VersionStatus.PUBLISHED }
        assertEquals(1, published.size)
        assertEquals(1, published[0].seq)
        assertEquals(1, snap.versions.count { it.status == VersionStatus.DRAFT })
    }

    @Test
    fun `导出再导入指纹冲突事实与解释顺序一致`() {
        val store = Store(dir) { LocalDate.of(2026, 6, 1) }
        val d = store.createDraft(null)
        store.updateRules(d.id, conflictRules())
        store.addException(d.id, "r2", "r1", "理由", "2026-12-31")
        val v1 = store.publish(d.id, 0)
        store.saveFact("样例", mapOf("x" to JsonPrimitive(75)))
        val run = store.run("1", "样例", mapOf("x" to JsonPrimitive(75)), LocalDate.of(2026, 6, 1))

        val bundle = store.export()

        val other = java.nio.file.Files.createTempDirectory("rce-import-")
        val store2 = Store(other) { LocalDate.of(2026, 6, 1) }
        store2.import(bundle)

        val vImported = store2.getVersion("1")
        assertEquals(v1.fingerprint, vImported.fingerprint, "指纹一致")

        val c1 = store.conflictsOf("1", LocalDate.of(2026, 6, 1))
        val c2 = store2.conflictsOf("1", LocalDate.of(2026, 6, 1))
        assertEquals(c1, c2, "最小冲突事实一致")

        val run2 = store2.run("1", "样例", mapOf("x" to JsonPrimitive(75)), LocalDate.of(2026, 6, 1))
        assertEquals(run.result.steps, run2.result.steps, "解释/决胜顺序一致")
        assertEquals(run.result.winnerRuleId, run2.result.winnerRuleId)
    }
}
