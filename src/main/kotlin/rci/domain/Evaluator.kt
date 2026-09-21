package rci.domain

/**
 * 解释树节点。terminal 取三个终态之一：
 *  TRUE / FALSE / UNKNOWN。
 *  UNKNOWN 专门用于「字段缺失」导致无法判定的叶子（配合 [Condition.Not] 做三值逻辑）。
 */
enum class Ternary { TRUE, FALSE, UNKNOWN }

data class ExplainNode(
    val label: String,
    val terminal: Ternary?,
    val children: List<ExplainNode> = emptyList(),
) {
    val isTrue: Boolean get() = terminal == Ternary.TRUE
    companion object {
        fun leaf(label: String, t: Ternary): ExplainNode = ExplainNode(label, t)
        fun node(label: String, children: List<ExplainNode>, t: Ternary): ExplainNode =
            ExplainNode(label, t, children)
    }
}

object Evaluator {

    fun eval(c: Condition, fact: Fact): ExplainNode = when (c) {
        is Condition.And -> {
            val kids = c.parts.map { eval(it, fact) }
            val t = when {
                kids.any { it.terminal == Ternary.FALSE } -> Ternary.FALSE
                kids.all { it.terminal == Ternary.TRUE } -> Ternary.TRUE
                else -> Ternary.UNKNOWN
            }
            ExplainNode.node("AND：所有子条件都需成立（${kids.count { it.isTrue }}/${kids.size} 成立）", kids, t)
        }
        is Condition.Or -> {
            val kids = c.parts.map { eval(it, fact) }
            val t = when {
                kids.any { it.terminal == Ternary.TRUE } -> Ternary.TRUE
                kids.all { it.terminal == Ternary.FALSE } -> Ternary.FALSE
                else -> Ternary.UNKNOWN
            }
            ExplainNode.node("OR：任一子条件成立即可（${kids.count { it.isTrue }}/${kids.size} 成立）", kids, t)
        }
        is Condition.Not -> {
            val kid = eval(c.inner, fact)
            val t = when (kid.terminal) {
                Ternary.TRUE -> Ternary.FALSE
                Ternary.FALSE -> Ternary.TRUE
                Ternary.UNKNOWN -> Ternary.UNKNOWN
                null -> Ternary.UNKNOWN
            }
            ExplainNode.node("NOT：取反", listOf(kid), t)
        }
        is Condition.NumBetween -> numBetween(c, fact)
        is Condition.DateBetween -> dateBetween(c, fact)
        is Condition.StrIn -> strIn(c, fact)
        is Condition.FieldPresent -> fieldPresent(c, fact)
        is Condition.FieldNotNull -> fieldNotNull(c, fact)
        is Condition.Compare -> compare(c, fact)
    }

    fun matches(c: Condition, fact: Fact): Boolean = eval(c, fact).terminal == Ternary.TRUE

    private fun numBetween(c: Condition.NumBetween, fact: Fact): ExplainNode {
        val v = fact.get(c.field)
        val label = "数字区间 ${c.field} ∈ [${fmt(c.min)}, ${fmt(c.max)}]（两端都包含）"
        return when {
            v == null -> ExplainNode.leaf("$label → 未知：字段「${c.field}」缺失", Ternary.UNKNOWN)
            v is VNull -> ExplainNode.leaf("$label → 不成立：字段「${c.field}」是显式 null，不是数字", Ternary.FALSE)
            v !is VNum -> ExplainNode.leaf("$label → 不成立：字段「${c.field}」不是数字（实际 $v）", Ternary.FALSE)
            v.value < c.min || v.value > c.max ->
                ExplainNode.leaf("$label → 不成立：实际值 ${fmt(v.value)} 在闭区间外", Ternary.FALSE)
            else -> {
                val atEdge = v.value == c.min || v.value == c.max
                val edge = if (atEdge) "，命中端点（端点包含，成立）" else ""
                ExplainNode.leaf("$label → 成立：实际值 ${fmt(v.value)}$edge", Ternary.TRUE)
            }
        }
    }

    private fun dateBetween(c: Condition.DateBetween, fact: Fact): ExplainNode {
        val v = fact.get(c.field)
        val label = "日期区间 ${c.field} ∈ [${c.from}, ${c.to}]（两端都包含）"
        return when {
            v == null -> ExplainNode.leaf("$label → 未知：字段「${c.field}」缺失", Ternary.UNKNOWN)
            v is VNull -> ExplainNode.leaf("$label → 不成立：字段「${c.field}」是显式 null", Ternary.FALSE)
            v !is VStr -> ExplainNode.leaf("$label → 不成立：日期字段必须是 yyyy-MM-dd 字符串", Ternary.FALSE)
            else -> try {
                val d = Dates.parse(v.value)
                when {
                    d.isBefore(c.from) || d.isAfter(c.to) ->
                        ExplainNode.leaf("$label → 不成立：${d} 在闭区间外", Ternary.FALSE)
                    else -> {
                        val atEdge = d == c.from || d == c.to
                        val edge = if (atEdge) "，命中端点（端点包含，成立）" else ""
                        ExplainNode.leaf("$label → 成立：${d}$edge", Ternary.TRUE)
                    }
                }
            } catch (e: IllegalArgumentException) {
                ExplainNode.leaf("$label → 不成立：无法解析日期「${v.value}」", Ternary.FALSE)
            }
        }
    }

    private fun strIn(c: Condition.StrIn, fact: Fact): ExplainNode {
        val v = fact.get(c.field)
        val label = "字符串集合 ${c.field} ∈ {${c.values.joinToString(", ") { "“$it”" }}}"
        return when {
            v == null -> ExplainNode.leaf("$label → 未知：字段「${c.field}」缺失", Ternary.UNKNOWN)
            v is VNull -> ExplainNode.leaf("$label → 不成立：字段「${c.field}」是显式 null", Ternary.FALSE)
            v !is VStr -> ExplainNode.leaf("$label → 不成立：字段不是字符串", Ternary.FALSE)
            v.value !in c.values -> ExplainNode.leaf("$label → 不成立：实际为“${v.value}”", Ternary.FALSE)
            else -> ExplainNode.leaf("$label → 成立：实际为“${v.value}”", Ternary.TRUE)
        }
    }

    private fun fieldPresent(c: Condition.FieldPresent, fact: Fact): ExplainNode {
        val label = "字段存在 ${c.field} != 缺失"
        return if (c.field in fact)
            ExplainNode.leaf("$label → 成立：键存在（值为显式 null 也算存在）", Ternary.TRUE)
        else
            ExplainNode.leaf("$label → 不成立：键缺失", Ternary.FALSE)
    }

    private fun fieldNotNull(c: Condition.FieldNotNull, fact: Fact): ExplainNode {
        val v = fact.get(c.field)
        val label = "字段非空 ${c.field} != null"
        return when {
            v == null -> ExplainNode.leaf("$label → 不成立：字段缺失（缺失既不是非空）", Ternary.FALSE)
            v is VNull -> ExplainNode.leaf("$label → 不成立：值是显式 null", Ternary.FALSE)
            else -> ExplainNode.leaf("$label → 成立：实际值为 $v", Ternary.TRUE)
        }
    }

    private fun compare(c: Condition.Compare, fact: Fact): ExplainNode {
        val label = "跨字段比较 ${c.left} ${c.op.symbol} ${c.right}"
        val l = fact.get(c.left)
        val r = fact.get(c.right)
        if (l == null || r == null) {
            val missing = buildList {
                if (l == null) add(c.left); if (r == null) add(c.right)
            }.joinToString("、")
            return ExplainNode.leaf("$label → 未知：字段缺失（$missing）", Ternary.UNKNOWN)
        }
        if (l is VNull || r is VNull) {
            return ExplainNode.leaf("$label → 不成立：至少一侧是显式 null（${l.javaClass.simpleName}）", Ternary.FALSE)
        }
        val cmp = compareValues0(l, r)
            ?: return ExplainNode.leaf("$label → 不成立：两侧类型不可比较（$l vs $r）", Ternary.FALSE)
        val ok = when (c.op) {
            Condition.Op.GT -> cmp > 0; Condition.Op.GTE -> cmp >= 0
            Condition.Op.LT -> cmp < 0; Condition.Op.LTE -> cmp <= 0
            Condition.Op.EQ -> cmp == 0; Condition.Op.NEQ -> cmp != 0
        }
        return if (ok) ExplainNode.leaf("$label → 成立：$l ${c.op.symbol} $r", Ternary.TRUE)
        else ExplainNode.leaf("$label → 不成立：$l 不满足 ${c.op.symbol} $r", Ternary.FALSE)
    }

    /** 数字之间按数值比较；日期字符串按日历日期比较；其余同类型按字符串比较。 */
    private fun compareValues0(a: VValue, b: VValue): Int? = when {
        a is VNum && b is VNum -> a.value.compareTo(b.value)
        a is VBool && b is VBool -> a.value.compareTo(b.value)
        a is VStr && b is VStr -> {
            val da = runCatching { Dates.parse(a.value) }.getOrNull()
            val db = runCatching { Dates.parse(b.value) }.getOrNull()
            when {
                da != null && db != null -> da.compareTo(db)
                (da != null) != (db != null) -> null
                else -> a.value.compareTo(b.value)
            }
        }
        else -> null
    }

    private fun fmt(d: Double): String =
        if (d % 1.0 == 0.0 && d.isFinite()) d.toLong().toString() else d.toString()
}
