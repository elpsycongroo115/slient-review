# 通用关联上下文

跨文件规范经常要求变更与未变更的配套文件保持一致。插件负责收集目标提交中的关联内容，AI 根据规则判断；不在 Java 中固化某个规则 ID、清单名、登记编号或登记语法。

## 已有文字规则

未声明 `contextFiles` 时，会从规则要求和引用中的明确文件路径/文件名提取候选，在变更文件附近、同模块或已绑定版本内查找。明确完整路径优先于同名的简略引用；多个候选不会任意挑一个。

整段 `xxx` 可作为通用路径占位，例如适用路径中的版本目录。建议新的规则使用 `{version}`、`{module}` 等命名占位，关联路径使用相同名称。原用户规则文件不自动改写。

## 明确声明关联文件

文件关系不够明确时，可在任何规则中增加可选字段，仍使用原 schemaVersion 1.0：

```json
{
  "id": "HANDLER-REGISTRATION",
  "title": "新增处理器需登记",
  "group": "configuration",
  "severity": "error",
  "appliesTo": {
    "include": ["modules/{module}/handlers/**/*.java"],
    "exclude": []
  },
  "contextFiles": ["modules/{module}/config/handlers.json"],
  "requirement": "新增处理器必须在同模块 config/handlers.json 中登记。",
  "exceptions": [],
  "source": {
    "document": "项目规范",
    "excerpt": "处理器定义与模块登记配置必须一致。"
  }
}
```

若变更文件是 `modules/orders/handlers/CreateHandler.java`，会读取同一目标提交的 `modules/orders/config/handlers.json`，即使该 JSON 本次没有修改。

其他规范同样可声明配套配置、接口定义、登记文件或模板。登记文件中的内容格式由规则描述并交给 AI 判断；插件不内置某种项目业务语法。

`contextFiles` 是仓库相对路径，可包含有明确目录前缀的 glob；不接受绝对路径、`..` 路径或全仓无限扫描。显式非空列表优先于文字推断。

## 提交一致性

关联内容来自冻结的 Git 目标提交，而不是磁盘工作区。本地补好了登记但未提交，仍会向 AI 明确提供“目标提交中缺失/旧内容”，不会把漏提交掩盖掉。

模型输入区分：

- `present`：该提交中存在文件，提供编号内容；共享文件可复用同路径内容。
- `missing`：该提交中确认没有相应文件，由 AI 按规则判断缺失含义。
- `unavailable`：读取失败、非普通文本或预算不足。
- `ambiguous`：关联候选不唯一或路径变量未绑定。

后两类会强制该项为 `needs_context` / 部分完成；超过请求预算或批次数量限制的检查有 `skipped` 覆盖记录。缺少信息不会作为已完整检查。

## 边界

上下文每仓库读取同一个冻结提交，受文件大小、候选数和总预算约束。它不能凭空得知自然语言没有指出的所有依赖，也不保证模型没有误报或漏报。不够明确的关联请用 `contextFiles` 指定。

报告记录关联路径与读取状态。问题链接默认打开当前项目源码，代码变动时按证据尝试定位；提交快照是单独的可选入口。
