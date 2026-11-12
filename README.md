# Campaign Server Side

**简体中文** | [English](README.en.md)

> [!WARNING]
> 此模组处于**极早期测试阶段**，存在许多尚未发现的问题。如发现问题，欢迎提交 issue 或 PR；
> 也欢迎 vibe coding 的 PR。
>
> 本项目**包含 AI 生成内容**。

让 **Mindustry v160.x 战役模式在专用服务器上持续运行**的服务端插件。

原版战役依附于客户端：区块地图、发射台配置、星球视图、启动负载都在客户端完成，专用服务器上 `Vars.control` 为空，`Logic#sectorCapture()` 也不落盘。本插件在服务端补齐这些环节——生成/加载/保存区块、维护区块经济、判定占领与失守、接管科技解锁与发射台链路——并用原版对话框（`followUpMenu` / `textInput` / `infoPopup`）提供操作界面。

**玩家使用原版客户端即可加入，不需要安装任何模组。** 需要程序化调用的客户端模组可选用自定义协议（见 [`PROTOCOL.md`](PROTOCOL.md)）。

- 当前版本：**0.0.5.1-beta**（[发布页](https://github.com/colorgarden/CampaignServerSide/releases/tag/v0.0.5.1-beta)）
- 运行环境：Mindustry **v160.x** 专用服务器（`server-release.jar`），**Java 17+**
- 许可证：**AGPL-3.0-or-later**（见 [`LICENSE`](LICENSE)）

> [!TIP]
> **测试服地址：`colorgarden.top`**（端口 `6567`，v160 客户端直接加入）
> **QQ 群：`1135085472`**（问题反馈、联机约人、版本通知）

---

## 1. 功能

### 战役运行

- 区块按存档加载；没有存档时按星球生成器生成，随后立即落盘
- 保存时机：定时、关服、区块被攻陷、区块失守
- 区块状态：未发射 / 已发射 / 已占领 / 已失守（废墟）
- 区块经济：核心库存、每分钟产出、每分钟外运、每分钟输入；详情页给出物品来源区块与当前发射目标
- 发射台/接收台链路：进入新区块时自动指向来源区块，也可手动指定或一键重定向（仅同星球）
- 失守处理：默认保存废墟，并按原版流程重建（载入旧档 → 放回核心 → 沿敌方路径摧毁 → 以废墟形式恢复存活建筑）；可改为直接丢弃存档

### 操作界面（纯服务端，无客户端模组）

- `/c` 主菜单：投票 / 管理员 / 查询区块
- `/cv` 投票菜单、`/ca` 管理员面板
- 启动配置：核心蓝图、负载物品、容量校验、确认发射
- 区块详情页：本地化名称、状态、库存、产出、外运、输入、来源与目标，每 1.5 秒原地刷新

### 投票

八类投票，全部带目标列表与二次确认：

`区块切换` `解锁科技` `切换星球` `修改发射目标` `一键重定向` `暂停/恢复` `跳过波次` `遗弃区块`

### 科技

- 服务端研究：按名称查找内容，检查科技树前置、目标（区块完成 / 前置研究）与物品消耗，扣除**全星球库存**后解锁
- 自动解锁：补上原版只在客户端执行的 `Control#checkAutoUnlocks`（父节点已解锁 + 无物品消耗 + 目标全部完成）
- 核心物品解锁：补上原版 `Control#update` 中"核心里有该物品就解锁"的逻辑——埃里克尔的铍等资源依赖这一步
- 解锁通过原版通道广播（`UnlockEvent` → `Call.researched`），新连入玩家由世界数据携带

### 战役规则

以下规则**按星球独立保存**，塞普罗与埃里克尔互不影响：

`难度` `星区入侵` `迷雾` `隐藏出生点` `随机波次 AI` `RTS AI` `禁止暂停` `失守清空` `反应堆爆炸`

### 环境

- 天气：按地形重建天气表（与原版 `PlanetGenerator#addWeather` 同规则），并驱动实际天气事件
- 昼夜：按星球旋转换算时间，映射到环境光照并限速广播给客户端

### 维护

- **物理世界重建**：原版只在单位切换碰撞层时回收刚体，单位直接消失会留下永久刚体；跳波或大规模单位死亡后刚体堆积，服务器会掉到 1 TPS 而 CPU 空闲。插件在区块载入、每 5 分钟以及 `campaign-fixphysics` 时按存活单位重建物理世界
- **世界处理器保持**：原版通关会置 `disableWorldProcessors`，重新进入该区块后逻辑方块永久停跑；插件在载入与攻陷后清除该标志
- 诊断命令覆盖存档、地图、规则、发射台、天气、物理、区块可进入性等

---

## 2. 安装

1. 从 [Releases](https://github.com/colorgarden/CampaignServerSide/releases) 下载 `CampaignServerSide.jar`
2. 放入服务器的 `config/mods/`：

   ```
   <server>/
     server-release.jar
     config/mods/CampaignServerSide.jar
   ```

3. 启动服务器
4. 控制台执行 `campaign-host`（或 `campaign-host <区块>`）进入战役
5. 希望重启后自动回到上次区块，把配置中的 `"autoStart"` 改为 `true`

战役地图（`maps/serpulo/*.msav`、`maps/erekir/*.msav`）已打包在 `server-release.jar` 内，不需要额外的 `assets.jar`。

---

## 3. 游戏内命令

前缀 `/`，`/campaignhelp` 可列出全部。

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/c` `/campaign` | 所有人 | 战役主菜单（投票 / 管理员 / 查询区块） |
| `/cv` `/cvote` | 所有人 | 投票菜单；`menu` 打开图形界面 |
| `/cvote <类型> [目标]` | 所有人 | 发起投票：`sector` `planet` `research` `destination` `redirect` `pause` `wave [数量]` `abandon` |
| `/cvote list <类型> [搜索]` | 所有人 | 列出可投票目标；`--all` 包含未解锁科技 |
| `/cvote yes` `/cvote no` `/cvote cancel` | 所有人 | 投票、取消自己的投票 |
| `/cvote info` | 所有人 | 当前投票状态 |
| `/cvote help` | 所有人 | 投票指令说明 |
| `/ca` `/cadmin` | 管理员 | 管理员面板；子命令见下 |
| `/ca menu` | 管理员 | 打开图形面板 |
| `/ca research <名称\|all>` | 管理员 | 直接解锁科技 |
| `/ca sector <区块>` | 管理员 | 直接切换区块 |
| `/ca planet <星球>` | 管理员 | 直接切换星球 |
| `/ca rule <名称> [on\|off]` | 管理员 | 切换当前星球的战役规则 |
| `/ca difficulty [名称]` | 管理员 | 查看/设置当前星球难度 |
| `/ca sim [pause\|resume]` | 管理员 | 暂停/恢复模拟 |
| `/ca save` `/ca capture` | 管理员 | 保存当前区块 / 强制判定通关 |
| `/cj [数量]` `/cjump [数量]` | 管理员 | 跳过波次（默认 1，最多 50，受冷却限制） |
| `/sector <名称\|ID>` | 见配置 | 切换区块 |
| `/sectors [星球\|页码]` | 所有人 | 区块列表与状态 |
| `/planet <星球>` | 见配置 | 切换星球（进入该星球上次或起始区块） |
| `/planets` | 所有人 | 星球与占领进度 |
| `/research <名称\|all>` | 见配置 | 解锁科技 |
| `/campaign-status` | 所有人 | 当前区块、波次、剩余科技 |
| `/campaignsave` | 管理员 | 保存当前区块 |
| `/capture` | 管理员 | 强制判定通关 |
| `/destination <区块\|clear>` | 管理员 | 设置本区块的发射台目标 |
| `/redirect` | 管理员 | 一键重定向全星球对外运送 |
| `/resources [星球]` | 所有人 | 星球总资源 |
| `/sectorinfo <区块>` | 所有人 | 区块库存、产出与运输 |
| `/sim [pause\|resume\|toggle]` | 管理员 | 暂停/恢复模拟 |
| `/difficulty [名称]` | 管理员 | 查看/设置当前星球难度 |
| `/rule [名称] [on\|off]` | 管理员 | 查看/切换当前星球的规则 |

`见配置` 指 `sectorSwitchAdminOnly` / `researchAdminOnly`：为 `true` 时仅管理员可用，不影响投票。

聊天栏可直接投 `y` 或 `1`（同意）、`n` 或 `0`（反对）、`a` 或 `2`（弃票）。

---

## 4. 控制台命令

`campaign-help` 列出全部。按用途分组：

**运行控制**

| 命令 | 说明 |
| --- | --- |
| `campaign-host [区块]` | 进入战役（默认上次区块或星球起始区块） |
| `campaign-goto <区块>` | 切换区块（先保存当前区块） |
| `campaign-save` | 立即保存当前区块 |
| `campaign-stop` | 保存并停止托管 |
| `campaign-status` | 当前区块、波次、核心、存档文件 |
| `campaign-config` | 打印当前配置与文件路径 |
| `campaign-reload` | 重新读取 `config.json` |

**区块数据**

| 命令 | 说明 |
| --- | --- |
| `campaign-sectors [星球\|页码]` | 列出区块预设与状态 |
| `campaign-reset <区块>` | 删除存档并重新生成 |
| `campaign-reset-sector <区块>` | 清空存档、区块信息并重新锁定预设 |
| `campaign-uncapture <区块>` | 清除"已占领"标记（重玩用） |
| `campaign-capture` | 强制判定当前区块通关 |
| `campaign-why <区块>` | 解释能否进入及原因 |
| `campaign-sectorinfo <区块>` | 库存、产出与运送明细 |
| `campaign-resources [星球]` | 星球总资源 |
| `campaign-destination [区块\|clear]` | 查看/设置当前区块发射目标 |
| `campaign-redirect` | 一键重定向全星球对外运送 |
| `campaign-pads` | 追踪发射台链路：目标、外运均值、输入速率、冷却与每座发射台状态 |
| `campaign-exports` | 列出各区块的目标与外运统计 |
| `campaign-fix-spawn [x] [y]` | 修复被污染的出生点记录 |
| `campaign-respawn [区块\|all]` | 清除出生点，下次生成时重掷 |

**星球与科技**

| 命令 | 说明 |
| --- | --- |
| `campaign-planets` | 列出参与战役的星球与占领进度 |
| `campaign-planet [星球]` | 查看/切换星球 |
| `campaign-research <名称...>` | 解锁科技（含前置） |
| `campaign-research-all` | 解锁全部科技 |
| `campaign-relock <名称...>` / `campaign-relock-all` | 重新锁定科技 |
| `campaign-unlocked` | 列出当前已解锁内容 |
| `campaign-difficulty [名称]` | 查看/设置当前星球难度 |
| `campaign-rule [名称] [on\|off]` | 查看/切换当前星球规则（`invasion` `fog` `hidespawns` `randomwaveai` `rtsai` `pausedisabled` `clearonlose` `reactorexplosions`） |

**投票与模拟**

| 命令 | 说明 |
| --- | --- |
| `campaign-votelist [类型] [搜索]` | 列出此刻可投票目标 |
| `campaign-vote [info\|cancel]` | 查看/取消进行中的投票 |
| `campaign-sim [pause\|resume\|toggle]` | 暂停/恢复模拟 |
| `campaign-skipwave [数量]` | 跳过波次（默认 1，最多 50，受冷却限制） |
| `campaign-autopause [on\|off]` | 查看/设置原版"无人在线时暂停" |

**环境与诊断**

| 命令 | 说明 |
| --- | --- |
| `campaign-weather [list\|floors\|refill\|<名称> <秒>]` | 查看天气计划、地形统计、按生成规则重建、强制天气事件 |
| `campaign-daynight` | 当前光照与映射后的环境黑暗值 |
| `campaign-logic [on\|off\|probe]` | 世界处理器状态、保持运行开关、执行探针 |
| `campaign-fixphysics` | 按存活单位重建物理世界 |
| `campaign-refresh-buildings` | 重建建筑邻近缓存（修复物流/逻辑异常） |
| `campaign-cores` | 列出核心方块、边长与允许的蓝图尺寸 |
| `campaign-joininfo` | 预览玩家进服提示 |
| `campaign-diag` | 存档管理器、地图可用性、API 状态、快照往返 |
| `campaign-dumprules` | 导出客户端收到的规则 JSON（调试） |

区块写法：预设名 `groundZero`、ID `170`、`id:170`、跨星球 `erekir:onset`。

---

## 5. 投票机制

### 发起条件

- 投票总开关 `voting` 与对应类型开关均为开
- 在线且**非挂机**玩家数 ≥ `voteMinPlayers`
- 发起者不在冷却中（`voteCooldown`）
- 目标可用（区块可进入、科技可研究、发射目标合法）

### 计时

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `voteDuration` | `15` | 投票总窗口（秒），到期必结算 |
| `votePushSeconds` | `5` | 提前多少秒向玩家推出选票（须小于总窗口） |
| `voteTimeout` | `10` | 弹窗后无人再投的结算等待，`0` 关闭 |

### 判定

**同意门槛始终生效**：同意票需达到 `ceil(参与者数 × votePercent)`（`votePercent` 默认 `0.6`，最少 1 票）。在门槛之上再按 `voteDecision` 决定：

| 模式 | 通过条件 | 失败条件 |
| --- | --- | --- |
| `majority`（默认） | 达到门槛且同意 > 反对 | 同意 ≤ 反对，或无法达到门槛 |
| `threshold` | 达到门槛 | 反对达到门槛 |
| `veto` | 达到门槛且无反对票 | 出现任意反对票 |

### 沉默者与弃票

- `unvoted`：未投票者的处理方式，`abstain`（默认）/ `yes` / `no`
- 结算时按**参与者**重算门槛：弃票玩家、以及 `abstain` 模式下的未投票玩家都从分母中移除，避免挂机者抬高分母
- 投票进行期间门槛固定在发起时的值，不会中途下降

### 挂机

超过 `afkSeconds`（默认 60）无位移的玩家：不计入分母、不能发起投票、不推送选票；进入/退出挂机会在聊天公告。暂停期间不累计挂机时间。

同一类型可有多个投票并行，各自独立计时。

---

## 6. 战役规则

规则保存在**星球自身的 `campaignRules`** 中（原版机制，随服务器设置持久化），因此两个星球可以有不同的难度与开关。插件额外记录 `reactorExplosions`（`CampaignRules` 没有这个字段），按星球保存在配置文件的 `planetReactorExplosions` 里。

- 第一个被初始化的星球沿用旧版全局配置，之后初始化的星球采用各自的原版默认值（埃里克尔默认迷雾与 RTS AI 为开）
- 修改入口：`/ca rule`、`/ca difficulty`、`/rule`、`/difficulty`、`campaign-rule`、`campaign-difficulty`，以及对应的投票
- 规则改变后立即应用到当前区块，并写入该星球的规则记录

---

## 7. 启动配置与核心蓝图

进入没有基地的区块（或管理员直接切入）时弹出启动配置：

- **核心蓝图库**：按星球隔离，路径 `config/campaign/blueprints/<星球>/`，每个蓝图由 `.msch` 与同名 `.name`（可读名）组成；单核心预设按当前星球已解锁核心自动生成
- **上传**：粘贴游戏导出的 base64 蓝图；校验解码、非空、恰好一个核心、方块均已解锁、边长不超过核心边长 + 10（原版 `maxLoadoutSchematicPad = 5`，每边 5 格）
- **重命名 / 删除**：仅上传的蓝图可改，自带核心预设只读
- **负载**：物品 ±50 / 最大化 / 全部最大化；单项上限为 `min(来源区块库存, 核心容量)`，容量按 `launchCapacityMultiplier × itemCapacity`；从来源区块扣除
- 星球不允许发射蓝图/负载时（如埃里克尔），不弹出这些选项，也不会要求或扣除物品

负载列表保存在 `config/campaign/cargo.txt`。

---

## 8. 配置文件

路径：`<server>/config/mods/Campaign Server Side/config.json`，首次启动生成，改完执行 `campaign-reload` 生效。

### 战役

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `planet` | `"serpulo"` | 当前战役星球 |
| `sector` | `-1` | 上次区块 ID，`-1` 表示自动 |
| `language` | `"auto"` | 文案语言：`auto` / `en` / `zh_CN` |
| `autoStart` | `false` | 启动后自动进入上次区块 |

### 保存与失守

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `autosave` | `true` | 定时保存 |
| `autosaveSeconds` | `0` | `0` 跟随原版 `saveinterval` |
| `saveOnExit` | `true` | 关服保存 |
| `paused` | `false` | 是否被刻意暂停（重启后恢复） |
| `clearSectorOnLose` | `false` | 失守时丢弃存档（`true`）或保存废墟（`false`） |
| `regenerateLostSector` | `false` | 存档无核心时是否强制重建 |
| `blueprintOnRebuild` | `true` | 失守重建时是否应用选中的核心蓝图 |

### 权限

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `allowSectorSwitch` | `true` | 区块切换总开关 |
| `allowResearch` | `true` | 科技解锁总开关 |
| `sectorSwitchAdminOnly` | `true` | 仅管理员可直接切区块 |
| `researchAdminOnly` | `true` | 仅管理员可直接解锁科技 |
| `allowPlanetSwitch` | `true` | 星球切换总开关 |
| `planetSwitchAdminOnly` | `true` | 仅管理员可直接切星球 |
| `enforceSectorUnlock` | `true` | 按原版规则限制可进入的区块 |
| `adminBypassLock` | `true` | 管理员可无视解锁限制 |

### 投票

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `voting` | `true` | 投票总开关 |
| `voteSector` `voteResearch` `votePlanet` | `true` | 对应类型开关 |
| `voteDestination` `voteRedirect` `votePause` `voteWave` `voteAbandon` | `true` | 对应类型开关 |
| `votePercent` | `0.6` | 同意门槛比例 |
| `voteMinPlayers` | `2` | 发起所需最少在线人数 |
| `voteDuration` | `15` | 投票总窗口（秒） |
| `votePushSeconds` | `5` | 提前弹窗（秒） |
| `voteTimeout` | `10` | 空闲结算（秒），`0` 关闭 |
| `voteCooldown` | `2` | 发起冷却（秒） |
| `voteDecision` | `"majority"` | `majority` / `threshold` / `veto` |
| `unvoted` | `"abstain"` | 未投票者：`abstain` / `yes` / `no` |

### 规则与环境

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `difficulty` | `"normal"` | 首个星球初始化时使用的难度种子 |
| `ruleSectorInvasion` `ruleFog` `ruleHideSpawns` | `false` | 初始化种子 |
| `ruleRandomWaveAI` `ruleRtsAI` `rulePauseDisabled` | `false` | 初始化种子 |
| `ruleReactorExplosions` | `true` | 初始化种子 |
| `weatherSystem` | `true` | 启用天气系统 |
| `dayNightCycle` | `true` | 启用昼夜循环 |
| `dayNightSyncSeconds` | `5` | 光照广播间隔（秒） |

### 其他

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `afkEnabled` | `true` | 挂机判定 |
| `afkSeconds` | `60` | 判定阈值（秒） |
| `skipWaveCooldown` | `30` | 跳波冷却（秒） |
| `worldProcessors` | `true` | 保持世界处理器执行 |
| `joinInfo` | `true` | 玩家进服提示 |
| `announce` | `true` | 攻陷/失守公告 |
| `announceUnlocks` | `true` | 科技解锁公告 |
| `launchPadLinking` | `true` | 新区块自动链接来源区块 |
| `promptLaunch` | `true` | 进入无基地区块时弹出启动配置 |
| `resyncClients` | `true` | 切换世界后重发世界数据 |

存档位置：`config/saves/sector-<星球>-<ID>.msav`；区块经济信息随服务器设置保存。

---

## 9. 与原版的差异

- **没有客户端战役 UI**：星球视图、区块选择、发射台配置都由服务端对话框与指令替代；客户端看不到星球地图
- **发射台目标由服务端维护**：原版联机禁用 `LaunchPad#buildConfiguration`，这里由插件设置 `destination` 并结算运送
- **蓝图库在服务端**：原版读取客户端蓝图文件；这里上传到服务器，且**按星球隔离**
- **失守默认保留废墟**：`clearSectorOnLose = false` 时保存并重建废墟；原版是否清空由客户端规则决定
- **补丁式实现**：核心物品解锁、自动解锁轮询、世界处理器复位、物理世界重建，都是把原本只在客户端执行或原版不做的步骤补在服务端
- **地图生成后立即落盘**：原版由客户端持有存档，这里服务端即存档所有者

## 10. 已知限制

- 预发布版本，**不保证存档向后兼容**
- 非当前区块的产出/外运统计停留在它上次离开时的值（原版行为）
- 世界处理器只在当前区块执行（原版行为）
- 客户端 HUD 的 `TPS` 恒显示 1：原版把 `Core.graphics.getFramesPerSecond()` 放进状态快照，headless 下该值固定为 1，与本插件无关
- 蓝图与负载是服务器级设置，多人同时准备发射会互相覆盖
- 失守重建应用的是"当前选中"的蓝图，与当初发射该区块时的蓝图无关
- 服务端不校验玩家是否解锁了某个方块（原版联机同样不校验）

---

## 11. 客户端 API

插件通过 `state.rules.tags["campaign-api"]` 广播协议版本；客户端模组握手后可切换区块/星球、解锁科技、保存、发起投票、读取区块信息。

包格式、字段与调用示例见 [`PROTOCOL.md`](PROTOCOL.md)。

---

## 12. 反馈

- Issue / PR：欢迎，包括 vibe coding 的 PR
- QQ 群：`1135085472`
- 测试服：`colorgarden.top:6567`
