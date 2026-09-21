# 规则冲突解释器（Rule Conflict Explainer）

回答“为什么这个对象最后得到这个结论”的本地工具：维护带生效区间、优先级和来源的业务规则集版本，对事实输入给出最终结论、命中/被压/无关规则、逐步决胜过程与解释树；发布前做静态冲突检测与最小冲突事实生成；支持并存例外、历史重放、版本影响预览与导入导出。

## 运行

```bash
./gradlew classes                                      # 准备
./gradlew test                                         # 测试（17 个）
./gradlew run --args='--host 127.0.0.1 --port 5215'    # 启动
# 打开 http://127.0.0.1:5215，页面标题为“规则冲突解释器”
```

仅依赖 JDK 17 内置 HTTP 服务与本地文件存储（默认 `./data/state.json`，可用 `--data <目录>` 覆盖）。存储写入采用临时文件 + 原子改名，崩溃不会留下半个版本。

## 规则 JSON

```json
{
  "id": "r1",
  "description": "高价值客户放行",
  "condition": {
    "type": "and",
    "items": [
      {"type": "numRange", "field": "amount", "min": 100, "minInclusive": true, "max": 1000, "maxInclusive": false},
      {"type": "dateRange", "field": "signup", "min": "2025-01-01", "minInclusive": true, "max": null, "maxInclusive": true},
      {"type": "strIn", "field": "tier", "values": ["gold", "platinum"]},
      {"type": "exists", "field": "vip"},
      {"type": "isNull", "field": "waived"},
      {"type": "cmp", "leftField": "limit", "op": "GE", "rightField": "amount"},
      {"type": "or", "items": []},
      {"type": "not", "item": {"type": "exists", "field": "blocked"}}
    ]
  },
  "effectiveFrom": "2026-01-01",
  "effectiveTo": null,
  "priority": 10,
  "conclusion": "APPROVE",
  "source": "policy.md#L42"
}
```

## 关键语义

- **缺失 vs 显式 null**：键不存在 = 缺失；键存在且值为 `null` = 显式 null。`exists` 对显式 null 命中；`isNull` 只对显式 null 命中；数字/日期/字符串条件对二者均不命中，解释树分别说明。
- **区间端点**：每个端点可配置含/不含（`minInclusive`/`maxInclusive`），解释文本逐端说明“含端点/不含端点”与通过情况；规则生效区间为 `[effectiveFrom, effectiveTo]` 两端含端点，`null` 表示开口。
- **稳定决胜**：优先级降序，相同优先级严格按规则 ID 字典序升序，与存储/返回顺序无关；比较过程逐条输出。
- **静态冲突与最小事实**：发布前对所有结论不同的规则对，求生效区间与条件约束的交集（纯 AND 叶子树精确合并；含 OR/NOT 时保守判定为可能重叠），生成字段最少、取值确定性的见证事实；无有效例外覆盖的冲突阻止发布。
- **并存例外**：例外带理由与失效日期，失效日当天仍有效；过期后静态检查重新暴露冲突，基于旧版本的历史运行记录仍原样重放（运行记录冻结了版本号、指纹与完整结果）。
- **乐观锁发布**：发布时携带 `expectedSeq`；并发发布只有一个成功，另一个得到 409，状态文件保持原子一致。
- **版本影响**：对全部已保存事实分别在两个已发布版本下运行，列出结论变化、新旧胜者与导致变化的规则。
- **指纹与导入导出**：发布时对规则与例外做键排序规范化后取 SHA-256；导出为单个 JSON，导入时重算指纹校验，冲突事实与解释顺序在导入后保持一致。

## API 摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/versions` | 新建草稿（`{"baseSeq": 1}` 可基于已发布版本） |
| PUT | `/api/versions/{id}/rules` | 替换草稿规则 |
| POST/DELETE | `/api/versions/{id}/exceptions[/{exId}]` | 登记/删除并存例外 |
| POST | `/api/versions/{id}/publish` | 乐观锁发布（`{"expectedSeq": 0}`） |
| GET | `/api/versions/{id}/conflicts?asOf=` | 静态冲突与最小冲突事实 |
| POST | `/api/facts` / DELETE `/api/facts/{id}` | 已保存事实 |
| POST | `/api/run` | 对版本运行事实并持久化结果 |
| GET | `/api/runs/{id}` | 历史重放 |
| GET | `/api/impact?from=&to=&asOf=` | 版本影响预览 |
| GET | `/api/export` / POST `/api/import` | 全量导出/导入 |

## 代码结构

- `src/main/kotlin/rce/Model.kt`：领域模型
- `Eval.kt`：条件求值与解释树（null 语义、端点解释）
- `Engine.kt`：生效区间过滤与稳定决胜
- `Conflict.kt`：静态冲突检测、约束合并与最小见证事实
- `Store.kt`：原子持久化、乐观锁发布、影响、导入导出
- `Server.kt` / `Main.kt`：HTTP API 与入口
- `src/main/resources/web/index.html`：单页界面
- `src/test/kotlin/rce/`：null 语义、端点、稳定决胜、例外过期、历史重放、版本影响、并发发布、导入导出一致性等测试
