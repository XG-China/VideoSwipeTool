package com.autoskip.ad

/** 规则求值结果:上滑,或保持不动。 */
enum class RuleDecision { SWIPE, IGNORE }

/** 规则可读取的当前页面上下文。 */
data class RuleContext(
    val keywordVisible: Boolean,
    val keywordPresent: Boolean,
    val episode: Int?,
    val textCount: Int,
    val strict: Boolean
)

/** 规则文本格式错误。 */
class RuleParseException(message: String) : Exception(message)

/**
 * 类易语言的小型规则引擎(纯 Kotlin,无 Android 依赖,可单元测试)。
 *
 * 语法:
 * ```
 * 如果 可见关键词
 *     不动作
 * 否则如果 存在关键词
 *     上滑
 * 否则如果 集数 >= 2 且 集数 <= 999
 *     上滑
 * 否则
 *     不动作
 * 结束如果
 * ```
 *
 * `如果真` 与 `如果` 等价(不带否则语义的写法也接受):
 * ```
 * 如果真 有集数 且 文字数 >= 10
 *     上滑
 * 结束如果
 * ```
 *
 * 条件:
 *  - 可见关键词 / 存在关键词 / 有集数 / 严格模式 / 宽松模式
 *  - 集数 >= N、集数 <= N、集数 = N、集数 != N(没有集数时数值比较一律为假)
 *  - 文字数 >= N 等
 *  - 且 / 并且 / &&,或 / 或者 / ||,非 / 不 / not / !
 *  - 括号
 *
 * 动作:上滑、不动作(也接受「忽略」「不动」)
 * 注释:# 或 // 开头/行尾注释
 */
object RuleEngine {

    private val OPS = setOf(">", "<", ">=", "<=", "=", "==", "!=", "≠", "≥", "≤")

    fun compile(text: String): CompiledRule {
        val blocks = mutableListOf<CompiledBlock>()
        var block: BlockBuilder? = null
        var branch: BranchBuilder? = null
        var elseSeen = false
        var lineNo = 0

        for (raw in text.lineSequence()) {
            lineNo++
            val line = raw.substringBefore('#').substringBefore("//").trim()
            if (line.isEmpty()) continue

            when {
                line == "结束如果" || line == "如果真结束" || line == "结束" -> {
                    val b = block ?: throw RuleParseException("第 $lineNo 行:多余的「$line」")
                    val br = branch ?: throw RuleParseException("第 $lineNo 行:分支缺少动作")
                    b.branches += br.build(lineNo)
                    blocks += b.build(lineNo)
                    block = null
                    branch = null
                    elseSeen = false
                }

                line.startsWith("否则如果") || line.startsWith("否则若") -> {
                    val b = block ?: throw RuleParseException("第 $lineNo 行:「否则如果」不在如果块内")
                    if (elseSeen) throw RuleParseException("第 $lineNo 行:「否则」之后不能再写「否则如果」")
                    val prefix = if (line.startsWith("否则如果")) "否则如果" else "否则若"
                    val cond = ConditionParser(line.removePrefix(prefix).trim(), lineNo).parse()
                    branch?.let { b.branches += it.build(lineNo) }
                    branch = BranchBuilder(cond, lineNo)
                }

                line == "否则" -> {
                    val b = block ?: throw RuleParseException("第 $lineNo 行:「否则」不在如果块内")
                    if (elseSeen) throw RuleParseException("第 $lineNo 行:重复的「否则」")
                    branch?.let { b.branches += it.build(lineNo) }
                    branch = BranchBuilder(null, lineNo)
                    elseSeen = true
                }

                line.startsWith("如果真") -> {
                    if (block != null) throw RuleParseException("第 $lineNo 行:不支持嵌套如果")
                    val condText = line.removePrefix("如果真").trim()
                    if (condText.isEmpty()) throw RuleParseException("第 $lineNo 行:「如果真」缺少条件")
                    block = BlockBuilder(lineNo)
                    branch = BranchBuilder(ConditionParser(condText, lineNo).parse(), lineNo)
                }

                line.startsWith("如果") -> {
                    if (block != null) throw RuleParseException("第 $lineNo 行:不支持嵌套如果")
                    val condText = line.removePrefix("如果").trim()
                    if (condText.isEmpty()) throw RuleParseException("第 $lineNo 行:「如果」缺少条件")
                    block = BlockBuilder(lineNo)
                    branch = BranchBuilder(ConditionParser(condText, lineNo).parse(), lineNo)
                }

                else -> {
                    val action = parseAction(line)
                        ?: throw RuleParseException("第 $lineNo 行:无法识别「$line」")
                    val br = branch ?: throw RuleParseException("第 $lineNo 行:动作必须写在如果块内")
                    if (br.action != null) throw RuleParseException("第 $lineNo 行:一个分支只能写一个动作")
                    br.action = action
                }
            }
        }

        if (block != null) throw RuleParseException("规则没有以「结束如果」收尾")
        if (blocks.isEmpty()) throw RuleParseException("规则为空")
        val evaluator: (RuleContext) -> RuleDecision = { ctx ->
            var result: RuleDecision? = null
            for (b in blocks) {
                for (branch in b.branches) {
                    if (branch.condition == null || branch.condition.eval(ctx)) {
                        result = branch.action
                        break
                    }
                }
                if (result != null) break
            }
            result ?: RuleDecision.IGNORE
        }
        return CompiledRule(evaluator)
    }

    private fun parseAction(line: String): RuleDecision? = when (line) {
        "上滑", "滑动", "跳过广告" -> RuleDecision.SWIPE
        "不动作", "不动", "忽略", "跳过" -> RuleDecision.IGNORE
        else -> null
    }

    private class BlockBuilder(val line: Int) {
        val branches = mutableListOf<CompiledBranch>()
        fun build(lineNo: Int): CompiledBlock {
            if (branches.isEmpty()) throw RuleParseException("第 $line 行:如果块没有分支")
            return CompiledBlock(branches.toList())
        }
    }

    private class BranchBuilder(val condition: Expr?, val line: Int) {
        var action: RuleDecision? = null
        fun build(lineNo: Int): CompiledBranch {
            val a = action ?: throw RuleParseException("第 $line 行附近:分支缺少动作(上滑/不动作)")
            return CompiledBranch(condition, a)
        }
    }

    private sealed interface Expr {
        fun eval(ctx: RuleContext): Boolean
    }

    private class CompiledBlock(val branches: List<CompiledBranch>)
    private class CompiledBranch(val condition: Expr?, val action: RuleDecision)

    private class Not(private val e: Expr) : Expr {
        override fun eval(ctx: RuleContext): Boolean = !e.eval(ctx)
    }

    private class And(private val l: Expr, private val r: Expr) : Expr {
        override fun eval(ctx: RuleContext): Boolean = l.eval(ctx) && r.eval(ctx)
    }

    private class Or(private val l: Expr, private val r: Expr) : Expr {
        override fun eval(ctx: RuleContext): Boolean = l.eval(ctx) || r.eval(ctx)
    }

    private class Atom(private val f: (RuleContext) -> Boolean) : Expr {
        override fun eval(ctx: RuleContext): Boolean = f(ctx)
    }

    private class Compare(
        private val operand: String,
        private val op: String,
        private val value: Int
    ) : Expr {
        override fun eval(ctx: RuleContext): Boolean {
            val left = when (operand) {
                "集数" -> ctx.episode ?: return false
                "文字数", "文本数" -> ctx.textCount
                else -> return false
            }
            return when (op) {
                ">", "》" -> left > value
                "<", "《" -> left < value
                ">=", "≥" -> left >= value
                "<=", "≤" -> left <= value
                "=", "==" -> left == value
                "!=", "≠" -> left != value
                else -> false
            }
        }
    }

    private class ConditionParser(private val text: String, private val lineNo: Int) {
        private val tokens = tokenize(text)
        private var pos = 0

        fun parse(): Expr {
            if (tokens.isEmpty()) throw err("条件为空")
            val e = parseOr()
            if (pos < tokens.size) throw err("无法解析「${tokens[pos]}」")
            return e
        }

        private fun parseOr(): Expr {
            var left = parseAnd()
            while (peek() in setOf("或", "或者", "||")) {
                advance()
                left = Or(left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Expr {
            var left = parseUnary()
            while (peek() in setOf("且", "并且", "&&")) {
                advance()
                left = And(left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Expr {
            return when (peek()) {
                "非", "不", "not", "!" -> {
                    advance()
                    Not(parseUnary())
                }
                else -> parseAtom()
            }
        }

        private fun parseAtom(): Expr {
            if (peek() == "(") {
                advance()
                val e = parseOr()
                if (next() != ")") throw err("缺少右括号")
                return e
            }
            val t = next() ?: throw err("条件不完整")
            return when (t) {
                "可见关键词", "关键词可见" -> Atom { it.keywordVisible }
                "存在关键词", "关键词存在" -> Atom { it.keywordPresent }
                "有集数", "有集数标题", "集数存在" -> Atom { it.episode != null }
                "严格模式" -> Atom { it.strict }
                "宽松模式" -> Atom { !it.strict }
                "集数", "文字数", "文本数" -> parseNumber(t)
                else -> throw err("未知条件「$t」")
            }
        }

        private fun parseNumber(operand: String): Expr {
            val op = peek()
            if (op == null || op !in OPS) {
                // 「集数」单独出现 = 有集数;文字数必须带比较运算符
                if (operand == "集数") return Atom { it.episode != null }
                throw err("「$operand」后面要写比较运算符")
            }
            advance()
            val num = next()?.toIntOrNull() ?: throw err("「$operand $op」后面要写数字")
            return Compare(operand, op, num)
        }

        private fun peek(): String? = tokens.getOrNull(pos)
        private fun advance() { pos++ }
        private fun next(): String? = tokens.getOrNull(pos++)

        private fun err(msg: String) = RuleParseException("第 $lineNo 行:$msg")

        private fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' || c == ')' -> {
                        out += c.toString()
                        i++
                    }
                    c.isDigit() -> {
                        val start = i
                        while (i < s.length && s[i].isDigit()) i++
                        out += s.substring(start, i)
                    }
                    c == '&' && i + 1 < s.length && s[i + 1] == '&' -> {
                        out += "&&"
                        i += 2
                    }
                    c == '|' && i + 1 < s.length && s[i + 1] == '|' -> {
                        out += "||"
                        i += 2
                    }
                    c in "><=!≥≤≠" -> {
                        val start = i
                        i++
                        if (i < s.length && s[i] == '=') i++
                        out += s.substring(start, i)
                    }
                    else -> {
                        val start = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] !in "()<>=!&|≥≤≠") i++
                        if (start == i) {
                            out += c.toString()
                            i++
                        } else {
                            out += s.substring(start, i)
                        }
                    }
                }
            }
            return out
        }
    }
}

/** 编译后的规则。 */
class CompiledRule internal constructor(
    private val evaluator: (RuleContext) -> RuleDecision
) {
    fun decide(ctx: RuleContext): RuleDecision = evaluator(ctx)
}
