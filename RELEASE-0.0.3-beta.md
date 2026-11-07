## Campaign Server Side 0.0.3-beta

第三个预发布版本。相比 0.0.2-alpha，主要是把天气与昼夜、发射台链路、投票细节和服务器列表文案补齐，并修掉一批会静默失效的问题。

仍然**不兼容客户端模组**：所有界面都是原版 `followUpMenu` 弹窗。

### 新增

- **天气系统**：每次载入世界按地形重建 `rules.weather`（原版 `PlanetGenerator#addWeather`），服务端驱动 `Logic#updateWeather`
- **昼夜循环**：按原版 `Universe#update` 的公式算 `ambientLight`，并每 5 秒向客户端广播一次 `setRules`（可在 `dayNightSyncSeconds` 关闭）
- **发射台目标 / 一键重定向**进入投票面板与管理员面板（不再是"开发中"占位）
- **模组图标**：手写 SVG + 256×256 PNG + 六档 ICO，构建时打进 jar 根目录
- 诊断命令：`campaign-pads`、`campaign-exports`、`campaign-weather`、`campaign-daynight`、`campaign-cores`、`campaign-fix-spawn`
- 聊天投票接受 `y` / `n` / `yes` / `no`（原先是 `1` / `0`）

### 修复

- 投票与挂机播报的 `[[Vote]][]` / `[[挂机]][]` 多一个右括号
- 跳波播报把波次显示成"第 1 波"（占位符数量与实参不匹配）
- 一键重定向对齐原版 `LandingPad#canRedirectExports`：只重定向**已有出口统计**的区块，不再把全星球路由一次冲掉
- 重定向无改动时改报"没有需要改动的区块"
- 失守重建的建筑还原移进 `playNewSector` 的 `beforePlay`（`logic.play()` 之前），核心蓝图在重建**之后**应用
- 备份恢复只在存档**载入失败**时触发，不再劫持失守区块
- 自动保存间隔对齐原版 `saveinterval`（默认 60s），暂停时照常保存，失守后停止
- 暂停意图持久化，重启不会清掉刻意的暂停
- `rules.reactorExplosions` 暴露为战役规则并在载入时强制，修掉"核反应堆不炸"
- 蓝图文件名用 ASCII slug + `.name` 边车，修掉中文名变 `??`
- 蓝图在 `logic.play()` 之后放置，修掉"蓝图建筑变成废墟"
- 蓝图库支持重命名 / 删除（自带核心预设只读）
- 服务器 `name` / `desc` 支持热改（`config name|desc`）

### 已知限制

- 蓝图与负载是全局状态，不是每次发射各自携带
- 失守重建应用的是"当前选中"的蓝图，与当初发射该区块时用的无关
- 丢弃更新：本版本之后仍会有破坏性改动，预发布不保证存档兼容

### 安装

把 `CampaignServerSide.jar` 放进服务端 `config/mods/` 并重启。最低游戏版本 v160。
