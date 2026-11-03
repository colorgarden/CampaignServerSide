# Campaign Server Side

给 Mindustry 专用服务端（dedicated server）使用的 Java 插件，让**战役模式**可以在服务端运行，
并给客户端模组提供一套网络接口。

- 服务端生成/加载战役区块（sector），像单人战役一样保存区块进度
- 管理员可切换区块、解锁科技；也可关掉“仅管理员”，让所有玩家直接操作
- 玩家可以**投票**切换区块 / 投票解锁科技（“仅管理员”开启时自动禁用对应投票）
- 定时自动保存、关服保存、区块被攻陷时保存，重启后从存档继续
- 自定义网络包 + Java API，客户端模组可直接调用（见 [`PROTOCOL.md`](PROTOCOL.md)）
- 所有文案走语言文件（`bundles/bundle.properties` + `bundle_zh_CN.properties`）

目标版本：Mindustry **v160.x** 专用服务端（`server-release.jar`），JDK 17+。
许可证：**AGPL-3.0-or-later**（见 [`LICENSE`](LICENSE)）。
仓库：`https://github.com/colorgarden/CampaignServerSide`

---

## 1. 安装

1. 构建出 `CampaignServerSide.jar`（见第 7 节），或直接用 `build/CampaignServerSide.jar`。
2. 放到服务端目录的 `config/mods/`：
   ```
   <server>/
     server-release.jar
     config/mods/CampaignServerSide.jar
   ```
3. 启动服务端：`java -jar server-release.jar`
4. 控制台执行 `campaign-help`，或直接 `campaign-host groundZero`。
5. 想开机自动回到上次区块，把配置文件里的 `"autoStart"` 设为 `true`。

> 战役地图（`maps/serpulo/*.msav`、`maps/erekir/*.msav`）已打包在 `server-release.jar` 里，
> **不需要**额外的 `assets.jar`。`campaign-diag` 会打印 `Sector preset maps available: 78/78`
> 之类的信息，低于总数说明服务端 jar 不完整。

## 2. 控制台命令

| 命令 | 说明 |
| --- | --- |
| `campaign-help` | 列出所有命令 |
| `campaign-host [sector]` | 进入战役（默认上次区块/起始区块） |
| `campaign-goto <sector>` | 切换区块（先保存当前区块） |
| `campaign-reset <sector>` | 删除该区块存档并重新生成 |
| `campaign-save` | 立即保存当前区块 |
| `campaign-stop` | 保存并停止托管 |
| `campaign-status` | 当前区块、波次、核心、存档文件等 |
| `campaign-sectors [page]` | 列出该星球全部区块及状态 |
| `campaign-capture` | 强制判定当前区块通关（管理/测试） |
| `campaign-research <name>` | 解锁一项科技（含前置） |
| `campaign-research-all` | 解锁全部科技 |
| `campaign-relock <name>` | 重新锁上某项科技 |
| `campaign-vote [info\|cancel]` | 查看/取消进行中的玩家投票 |
| `campaign-destination [sector\|clear]` | 查看/设置当前区块的发射台目标（仅同星球） |
| `campaign-redirect` | 一键把所有向外运送的区块重定向到当前区块（原版接收台的 ↓ 按钮） |
| `campaign-why <sector>` | 解释某区块现在能不能进入、原因是什么 |
| `campaign-joininfo` | 预览玩家进服时看到的提示 |
| `campaign-logic [on|off|probe]` | 查看世界处理器状态、切换是否保持运行、用累加器探针验证真实执行 |
| `campaign-pause [on|off]` | 无人在线时暂停模拟 |
| `campaign-planets` | 列出参与战役的星球与占领进度 |
| `campaign-planet [name]` | 查看或切换星球（进入该星球上次或起始区块） |
| `campaign-uncapture <sector>` | 清除某区块的“已占领”标记（重玩用） |
| `campaign-config` | 打印当前配置与文件路径 |
| `campaign-reload` | 重新读取 `config.json` |
| `campaign-diag` | 诊断（存档管理器、地图可用性、API 状态、快照往返） |

区块写法：预设名 `groundZero`、ID `170`、`id:170`、跨星球 `erekir:onset`。

## 3. 游戏内命令

前缀 `/`，`/help` 可见。

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/sector <name\|id>` | 见配置 | 切换区块（在线玩家自动重载世界） |
| `/sectors [page]` | 所有人 | 区块列表与状态 |
| `/campaign` | 所有人 | 当前区块、波次、剩余科技 |
| `/research <name\|all>` | 见配置 | 解锁科技，立即同步给在线玩家 |
| `/campaignsave` | 管理员 | 保存当前区块 |
| `/capture` | 管理员 | 强制判定通关 |
| `/cvote sector <name>` | 所有人 | 发起“切换区块”投票 |
| `/cvote research <name>` | 所有人 | 发起“解锁科技”投票 |
| `/cvote planet <name>` | 所有人 | 发起“切换星球”投票 |
| `/cvote yes` / `/cvote no` | 所有人 | 投票 |
| `/cvote cancel` | 管理员 | 取消投票 |
| `/cvote info` | 所有人 | 当前投票状态 |
| `/destination <name\|clear>` | 管理员 | 设置本区块的发射台目标 |
| `/redirect` | 管理员 | 一键重定向所有对外运送 |
| `/planets` | 所有人 | 列出参与战役的星球与占领进度 |
| `/planet <name>` | 见配置 | 切换星球 |

“见配置”指 `sectorSwitchAdminOnly` / `researchAdminOnly`：为 `true` 时只有管理员能用。

## 4. 投票

- 判定模式由 `voteDecision` 决定：
  - `threshold`（默认）：同意票 ≥ `ceil(在线人数 × votePercent)`（至少 2 票）通过；反对票达到同样数量即失败。
  - `veto`：**一票否决**——只要有一张反对票立刻失败；同意票仍需达到阈值。
  - `majority`：**多数决**——在线玩家都投完后立即比较，同意多于反对才通过，平票算失败。
- `voteTimeout`（默认 10 秒）：最后一张票之后若无人再投，超时即按上述模式结算，不必等满 `voteDuration`；
  设为 `0` 关闭超时。`voteDuration` 仍是硬上限，到期按模式结算。
- 管理员可随时取消投票。
- 同一玩家两次发起之间要等 `voteCooldown` 秒（冷却从上一次投票结束算起）。
- 在线人数不足 `voteMinPlayers` 时不能发起。
- **“仅管理员”优先级最高**：`sectorSwitchAdminOnly` / `researchAdminOnly` 为 `true` 时，
  对应投票直接被禁用，并提示 `campaign.vote.sector-admin-only` / `campaign.vote.research-admin-only`。
- 投票结果会立即执行（切换区块会重载世界），并广播到聊天栏。

## 5. 配置文件

路径：`<server>/config/mods/Campaign Server Side/config.json`（首次启动创建，**所有键都会写出**，可直接改）。
运行期插件只改 `planet`/`sector`，写回时会和你改过的其它字段合并；改完执行 `campaign-reload` 立即生效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `planet` | `"serpulo"` | 战役星球，切区块时自动更新 |
| `sector` | `-1` | 上次区块 ID，`-1` 自动 |
| `language` | `"auto"` | 文案语言：`auto`（JVM 区域）/ `en` / `zh_CN` |
| `autoStart` | `false` | 启动后自动进入上次区块 |
| `autosave` | `true` | 定时保存 |
| `autosaveSeconds` | `180` | 自动保存间隔（秒） |
| `saveOnExit` | `true` | 关服保存 |
| `clearSectorOnLose` | `false` | 区块丢失时删除该区块存档 |
| `allowSectorSwitch` | `true` | 区块切换总开关 |
| `allowResearch` | `true` | 科技解锁总开关 |
| `sectorSwitchAdminOnly` | `true` | 只有管理员能切换区块（`false` 时所有人可用，投票随之可用） |
| `researchAdminOnly` | `true` | 只有管理员能解锁科技（同上） |
| `voting` | `true` | 投票总开关 |
| `voteSector` | `true` | 允许区块切换投票 |
| `voteResearch` | `true` | 允许科技解锁投票 |
| `votePercent` | `0.6` | 通过所需的在线玩家比例 |
| `voteMinPlayers` | `2` | 发起投票的最少在线人数 |
| `voteDuration` | `60` | 投票时长（秒），硬上限 |
| `voteDecision` | `"threshold"` | 判定模式：`threshold` 阈值 / `veto` 一票否决 / `majority` 多数决 |
| `voteTimeout` | `10` | 最后一张票之后多少秒未再投票即结算，`0` 关闭 |
| `allowPlanetSwitch` | `true` | 星球切换总开关 |
| `planetSwitchAdminOnly` | `true` | 只有管理员能切换星球（独立于区块权限） |
| `votePlanet` | `true` | 允许“切换星球”投票；`planetSwitchAdminOnly` 为 `true` 时自动禁用 |
| `voteCooldown` | `30` | 同一玩家再次发起的冷却（秒） |
| `announce` | `true` | 区块攻陷/丢失时公告 |
| `resyncClients` | `true` | 切区块后给在线玩家重发世界数据（否则踢出） |
| `enforceSectorUnlock` | `true` | 按原版规则限制只能进入已解锁区块 |
| `adminBypassLock` | `true` | 管理员可无视解锁限制切区块（控制台始终可） |
| `launchPadLinking` | `true` | 新区块自动把发射台/接收台链路指向来源区块 |
| `joinInfo` | `true` | 玩家进服时发送一条带指令提示的说明 |
| `worldProcessors` | `true` | 始终保持世界处理器（逻辑方块）执行 |
| `pauseWhenEmpty` | `true` | 无人在线时暂停模拟（有人进入自动恢复） |

存档：`config/saves/sector-<planet>-<id>.msav`；区块经济信息在 `config/settings.bin`
（键 `<planet>-s-<id>-info`）。彻底重开某区块用 `campaign-reset <sector>`。

## 6. 网络接口（客户端模组）

服务端通过 `state.rules.tags["campaign-api"]` 广播协议版本，客户端模组据此判断是否可用；
握手后即可调用切换区块、解锁科技、保存、投票、获取区块信息。
完整包格式、JSON 字段和 Java 示例见 [`PROTOCOL.md`](PROTOCOL.md)。

## 7. 原版战役还原程度

**区块解锁**（`enforceSectorUnlock`，默认开）按原版 `PlanetDialog#canSelect` 的规则判定能否进入：

1. 被护盾保护的区块不能进（`Sector#isShielded`）；
2. 有基地的区块、星球起始区块随时可进；
3. 预设区块看科技树节点：`preset.unlocked()`，或父节点已解锁且（父节点是区块时）其区块已有基地；
4. 编号区块按 `PlanetGenerator#allowLanding`（Serpulo：相邻区块有基地且核心 ≥4 级）。

控制台命令永远可以强制切换（会打警告）；游戏内管理员在 `adminBypassLock` 开启时可无视限制；
投票不允许指向未解锁区块。`campaign-why <sector>` 可随时查询判定结果。

**科技自动解锁**：原版 `Control#checkAutoUnlocks` 只在客户端跑，服务端没有这一步。插件在区块攻陷、
进入区块、手动研究之后各跑一遍同样的判定：父节点已解锁 + 无物品需求 + 目标全部完成的内容自动解锁。
这就是“打下区块 → 解锁后续区块/科技”的链路。

**发射台 / 接收台**：原版的发射台目标靠客户端 UI 设置（`LaunchPad#buildConfiguration` 在联机时被禁用），
服务端由插件接管：新区块生成时把 `origin`/`destination` 指向来源区块（`launchPadLinking`，等同原版
发射进入新区块的语义），并可用 `campaign-destination` / `/destination` 手动指定、
`campaign-redirect` / `/redirect` 一键重定向，目标只允许同星球（与原版一致）。
Serpulo 的发射台通过 `Universe#runTurn` 每回合结算运送，Erekir 的接收台按 import 速率拉取，
两者都依赖这里的 `destination` 与 `export` 统计，链路一通即可正常工作。

**世界处理器（逻辑方块）**：原版 `Logic#sectorCapture()` 会置 `state.rules.disableWorldProcessors = true`，
而 `LogicBlock#updateTile` 一旦看到该标志就直接返回、不再执行逻辑。`Rules` 通过 `JsonIO` 写进区块存档，
所以**只要该区块被通关过一次，它的世界处理器就永久不执行**，之后每次加载都是如此。原版不在乎，因为
通关的区块不会再玩；专用服务器会反复进入同一区块，于是表现为“世界处理器不工作”。
插件在 `WorldLoadEvent` / `RulesLoadEvent` 与每次攻陷后把该标志清回 `false`（配置 `worldProcessors`），
且**不触碰** `allowEditWorldProcessors`——那个标志只管“能不能建造/编辑”，与是否执行无关。

**尚未还原**：跨区块“携带物品/发射载具”的开局对话框（原版 `LaunchLoadoutDialog`）没有实现，
新区块按地图默认核心开局；区块入侵沿用原版 `Universe#runTurn` 的服务端逻辑。

## 8. 工作原理（为什么原版不行）

1. `Vars.control` 在 headless 下为 `null`，而区块存档管理 `Saves#saveSector` / `Saves$SaveSlot`
   挂在它上面 → 插件自建 `Saves` 实例；headless 下 `Core.assets` 为空，构造时临时补一个
   `AssetManager`，用完还原。
2. `Logic#sectorCapture()` 里保存区块的调用被 `if(!headless && !net.client())` 挡掉 →
   插件监听 `SectorCaptureEvent` 自己保存。
3. `ServerControl.gameOverListener` 会“换下一张地图”，战役里等于毁档 → 通过反射替换该监听器
   （`server` 模块不参与 mod 编译），战役内 game over 改为暂停 + 公告，等管理员决定。
4. 进入区块照抄客户端 `Control#playSector` 的服务端可用子集：有存档就
   `SaveIO.load(world.makeSectorContext(sector))`，没有就 `world.loadSector` + `logic.play()`，
   然后 `SaveIO.save` 落盘；核心被打光的区块会在原位置重开。
5. 在线玩家切换世界复用原版 `/sync` 的路径：`Call.worldDataBegin(con)` + `NetServer#sendWorldData`。
6. 科技解锁走原版通道：`unlock()` → `UnlockEvent` → `Logic` 发 `Call.researched`；
   新连入的玩家由 `NetworkIO#writeWorld` 自动带上 `rules.researched`。

## 9. 代码结构

```
src/main/java/top/colorgarden/
├── CampaignServerMod.java     入口（Plugin），客户端/服务端共用
├── command/                   CampaignCommands（控制台 + 游戏内命令）
├── config/                    CampaignConfig
├── core/                      SectorService / ResearchService / CampaignHooks / ServerControlHook
├── net/                       CampaignNetwork / CampaignClientApi / CampaignInfo / CampaignProtocol
│   └── packet/                Hello / HelloAck / Request / Reply 四个包
├── vote/                      CampaignVote
└── util/                      I18n / CampaignException
bundles/                       bundle.properties（英文兜底）+ bundle_zh_CN.properties
deploy/                        测试服启停脚本与部署说明
```

## 10. 构建

**Gradle**（需要联网拉 JitPack）

```bash
gradle jar                 # build/libs/CampaignServerSide.jar
gradle installToServer -PserverDir=/path/to/server
```

**只用 JDK（无需 Gradle）**

```powershell
./build.ps1 -MindustryJar /path/to/server-release.jar
```

`build.ps1` 用 `javac --release 17` 编译，并把 `mod.hjson`、`bundles/` 打到 jar 根目录。
注意 `mod.hjson` 不能带 UTF-8 BOM，否则游戏会静默跳过该 mod（脚本已做防护）。

## 11. 已知限制

- 客户端没有“星球地图”界面：跨区块移动要靠 `/sector` 或投票，或自己写客户端模组调用 API。
- 客户端上的 `LaunchPad`/`Accelerator` 发射流程依赖单机存档，不要依赖它们换区块。
- 区块经济模拟（`Universe#runTurn`，每 2 分钟一次）在服务端照常运行，只在有基地的区块间联动。
- 区块丢失后服务端会暂停并公告，不自动重开，需要管理员 `/sector <name>` 决定下一步。
- 服务端不校验“玩家是否已解锁某个方块”（原版联机也不校验），需要严格限制请配合白名单类插件。
- 服务端日志默认按 UTF-8 输出；如果机器 `LANG` 为空，请用 `-Dfile.encoding=UTF-8`
  `-Dstdout.encoding=UTF-8` 启动（`deploy/start.sh` 已带）。

## 12. 自测清单

```text
campaign-diag                      # 存档管理器 ok、78/78 地图、API advertised: true、快照往返 ok
campaign-host groundZero           # 新生成区块 "Started new sector"
campaign-status                    # hasBase: true、存档 exists: true
runwave runwave campaign-save      # 波次 3 并落盘
campaign-goto facility32m          # 切到别的区块
campaign-status                    # facility32m，波次 1
campaign-goto groundZero           # 切回来
campaign-status                    # 波次仍是 3
campaign-capture                   # "captured (saved: true)"，waves 变 false
gameover                           # "Sector ... lost."，不会再换图
campaign-research conveyor         # 解锁一项科技
campaign-research-all              # 剩余科技清零
campaign-vote info                 # 无投票时提示
campaign-goto erekir:onset         # 跨星球
```

## 13. 测试服

`deploy/` 下是本项目固定测试服的启停脚本与说明（Arch Linux ARM，JRE 21，**不使用 systemd**，
`server.sh start|stop|restart|status|console`）。详见 [`deploy/README.md`](deploy/README.md)。
