# DeepHarness Logo — 戴缰绳的小鲸鱼（单色版）

按 ip-as-logo skill 规范生成的 IP 吉祥物 logo。

## 交付文件

| 文件 | 尺寸 | 用途 |
|---|---|---|
| `deepharness-logo-whale-mono-1536.png` | 1536×1536 | 主文件（skill 要求的原生平方输出） |
| `deepharness-logo-whale-mono-1024.png` | 1024×1024 | 高清 / 营销 |
| `deepharness-logo-whale-mono-512.png` | 512×512 | 应用图标 / 桌面 |
| `deepharness-logo-whale-mono.svg.html` | — | 矢量源文件（SVG，可改色/缩放，Chrome 截图渲染） |

## 设计规格（对应 skill 的每个约束）

- **主体**：面向右侧的圆润鲸鱼 —— 喷水珠（3 颗圆滴）、月牙尾鳍（上裂片上扬 + 下裂片横伸，带 V 形深缺口，成对完整可见）、圆头、胸鳍、鲸鱼式微笑；缰绳宽带 + 扣环
- **色彩模式**：单色（monochrome），1 个 IP 色 + 同色相微调
  - 基色 `#586EF5`（取自参考 icon.icns 主色）
  - 高光 `#687CF6`（约 +11% 亮度，唯一漫射高光，左上光源）
  - 阴影/缰绳带 `#4F63DC`（约 −10% 亮度，唯一内部宽阴影，兼作缰绳）
  - 背景纯白 `#FFFFFF`，不透明铺满，无边框/圆角蒙版
- **复杂度**：剪影 = 5 个基础形状（头+身 2 圆合并 + 尾鳍 2 裂片 + 胸鳍 1）+ 喷水 3 圆滴；负空间特征 = 2 眼 + 1 嘴 + 扣环
- **特征约束**：成对特征（尾鳍两裂片、双眼）全部完整可见；喷水为粗钝圆滴（无细线）
- **构图**：从下左角浮现，左下 + 底边被画布裁切（有意为之），占画布约 87% 宽 × 86% 高；整体保持直立
- **造型**：全圆角粗钝轮廓，无尖角/细线；嘴为粗圆头弧形（非细微笑）
- **可读性**：32×32 下仍可识别（喷水 + 尾鳍 + 圆体 + 两眼 + 微笑，已验证像素级缩样）
- **透明度**：100% 不透明，无透明通道

## v2 重设计说明

v1 为「圆 blob + 小尾巴」，鲸鱼特征不足；v2 重做：增加喷水珠、重新设计月牙尾鳍（双裂片 + 深 V 缺口）、鲸鱼式微笑（前端上扬），头部保持圆润并面向右侧。

## 与参考 icon.icns 的关系

- 沿用其主色 `#586EF5` 与浅色背景
- 参考 icon 是居中的单色「D」字标；本 logo 按 skill 要求改为左下角浮现的吉祥物构图
- 扣环的白色负空间与参考 icon「D」的内部留白同一种语言

## 生成方式说明（重要）

本版为**程序化矢量渲染**（SVG → Chrome headless 截图，属 apikey-image-gen skill 文档化的 PIL/SVG 兜底路径），
不是 AI 生图模型输出。原因：Hermes Web UI 已运行（127.0.0.1:8748，media 端点可通），
但当前 profile 的 `config.yaml` 缺少 `fun-codex` 图片 provider，AI 生图接口返回
`missing_fun_codex_provider`。

如需 AI 生成版对比，在 Web UI 当前 profile 的 config.yaml 中加入：

```yaml
custom_providers:
  - name: fun-codex
    base_url: https://api.apikey.fun/v1
    api_key: <你的 api.apikey.fun key>
    model: gpt-5.5
    api_mode: codex_responses
```

配置好后可直接按 ip-as-logo skill 的 prompt 骨架重新生成 AI 版。
