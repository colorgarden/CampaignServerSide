# 战役网络接口（给客户端模组用）

服务端插件对外提供一组自定义网络包，客户端模组可以直接调用：切换区块、解锁科技、保存、发起/参与投票、获取区块信息。

前提：**同一个 jar 同时装在服务端和客户端**（`config/mods/`）。自定义包的 id 是按注册顺序编号的，
所以服务端与客户端的模组集合必须一致。

## 1. 能力发现（不会污染原版客户端）

服务端托管战役区块时，会把协议版本写进世界规则的 tag：

```
state.rules.tags["campaign-api"] = "1"
```

客户端在世界加载后读 `Vars.state.rules.tags.containsKey("campaign-api")` 即可判断服务端是否支持。
只有在支持时才发自定义包——原版客户端从不发送，所以不会因为未知包被踢；服务端也只会给完成握手的
连接发自定义包。

## 2. 包一览

| 包 | 方向 | 字段 | 说明 |
| --- | --- | --- | --- |
| `CampaignHelloPacket` | C→S | `int protocol`, `String gameVersion` | 客户端声明支持该协议；服务端记录该连接为“可推送” |
| `CampaignHelloAckPacket` | S→C | `int protocol`, `String gameVersion`, `boolean campaign`, `boolean admin`, `boolean allowSwitch`, `boolean allowResearch` | 握手结果 + 该玩家权限 |
| `CampaignRequestPacket` | C→S | `int requestId`, `byte action`, `String arg` | 一次调用 |
| `CampaignReplyPacket` | S→C | `int requestId`, `byte action`, `boolean success`, `String message`, `String json` | 调用结果；`requestId == 0` 表示服务端主动推送 |

注册顺序固定为 Hello → HelloAck → Request → Reply。

### actions

| 值 | 名称 | `arg` | 权限 |
| --- | --- | --- | --- |
| 0 | `actionInfo` | 空 / `all` / 区块查询串 | 所有人 |
| 1 | `actionSwitch` | 区块查询串 | `sectorSwitchAdminOnly` 为真时需管理员 |
| 2 | `actionResearch` | 内容内部名 | `researchAdminOnly` 为真时需管理员 |
| 3 | `actionResearchAll` | 空 | 同上 |
| 4 | `actionSave` | 空 | 管理员 |
| 5 | `actionVoteSector` | 区块查询串 | 所有人（投票可用时） |
| 6 | `actionVoteResearch` | 内容内部名 | 所有人（投票可用时） |
| 7 | `actionVoteCast` | `yes` / `no` / `cancel` | 所有人（cancel 需管理员） |
| 8 | `actionVoteCancel` | 空 | 管理员 |
| 9 | `actionSetDestination` | 区块查询串 / `clear` | 管理员（仅同星球，原版发射台限制） |
| 10 | `actionRedirect` | 空 | 管理员（一键重定向所有对外运送到当前区块） |

区块查询串与命令一致：`groundZero`、`170`、`id:170`、`erekir:onset`。

切换区块会按原版规则校验解锁（`enforceSectorUnlock`）：未解锁返回 `campaign.error.sector-locked`，
护盾区块返回 `campaign.error.sector-shielded`；管理员在 `adminBypassLock` 开启时可无视。

## 3. 回复里的 JSON

`json` 字段是一个 `CampaignInfo`（`top.colorgarden.net.CampaignInfo`）：

```json
{
  "protocol": 1, "campaign": true,
  "planet": "serpulo", "planetName": "Serpulo",
  "sectorId": 170, "sectorName": "groundZero", "presetName": "groundZero",
  "status": "playing",            // 稳定标识：playing/base/captured/cleared/available/locked/unknown
  "captured": false, "hasBase": true,
  "waves": true, "wave": 1, "winWave": 10,
  "worldWidth": 256, "worldHeight": 256, "cores": 1, "players": 0,
  "lockedResearch": 217, "saveFile": "sector-serpulo-170.msav",
  "originId": 65, "originName": "facility32m",
  "destinationId": 65, "destinationName": "facility32m", "destinationHasBase": true,
  "admin": true, "allowSwitch": true, "allowResearch": true,
  "voteKind": "", "voteTarget": "", "voteStarter": "",
  "voteYes": 0, "voteNo": 0, "voteRequired": 0, "voteSecondsLeft": 0,
  "sectors": [ { "id": 170, "preset": "groundZero", "name": "groundZero", "status": "playing",
                 "captured": false, "hasBase": true, "locked": false, "captureWave": 10 } ]
}
```

`*` 不带 `sectors` 时表示只查了单个区块。78 个预设的完整快照约 11 KB，远低于包大小上限。

## 4. Java 调用（客户端模组）

```java
import top.colorgarden.net.CampaignClientApi;
import top.colorgarden.net.CampaignInfo;

// 1) 世界加载后自动握手；也可以手动触发
if(CampaignClientApi.isServerSupported()){
    CampaignClientApi.handshake();
}

// 2) 取当前区块 + 全部区块列表
CampaignClientApi.requestInfo("", info -> {
    if(info == null) return;                       // 失败
    ui.showInfo(info.sectorName + " wave " + info.wave);
    for(CampaignInfo.Entry e : info.sectors){
        // e.preset, e.status, e.captured, e.locked, e.captureWave ...
    }
});

// 3) 切换区块（管理员；或服务端关掉 adminOnly）
CampaignClientApi.switchSector("erekir:onset", result ->
    ui.showInfo(result.success ? result.message : "失败: " + result.message));

// 4) 解锁科技 / 全部解锁 / 保存
CampaignClientApi.research("conveyor", result -> {});
CampaignClientApi.researchAll(result -> {});
CampaignClientApi.save(result -> {});

// 5) 投票
CampaignClientApi.startSectorVote("facility32m", result -> {});
CampaignClientApi.startResearchVote("conveyor", result -> {});
CampaignClientApi.castVote("yes", result -> {});    // yes / no / cancel
CampaignClientApi.cancelVote(result -> {});

// 6) 发射台/接收台：设置本区块的发射目标、一键重定向所有对外运送
CampaignClientApi.setDestination("facility32m", result -> {});
CampaignClientApi.clearDestination(result -> {});
CampaignClientApi.redirectExports(result -> {});    // 对应原版接收台的 ↓ 按钮

// 7) 服务端主动推送（换区块、区块失守、投票变化时）
CampaignClientApi.setPushListener(info -> { /* 刷新界面 */ });
```

约定：

- 所有回调都在**主线程**执行，且**最多执行一次**（本机失败、超时 20 秒都会回调，参数为 `null` 或
  `Result.success == false`，所以 UI 不会卡住）。
- `CampaignClientApi.isAdmin()` / `serverProtocol()` 来自握手，可用于提前隐藏按钮。
- 返回值是 `requestId`，`-1` 表示请求根本没发出去（回调仍会执行）。
- 服务端限流：同一连接 100 ms 一次调用；超出的请求会被丢弃（客户端会走超时）。

## 5. 兼容性

- 协议版本在 `CampaignProtocol.version`（当前 `1`）。服务端与客户端版本不同时握手仍会完成，
  但会在日志里提示 `version mismatch`，请两边一起更新。
- 服务端 `hidden: true` 的插件不带任何内容，所以客户端装不装这个 mod 都能连服；
  只有需要调用 API 的客户端才需要装。
- 相关开关：`sectorSwitchAdminOnly` / `researchAdminOnly` / `voting` / `voteSector` / `voteResearch`。
  当某项设为“仅管理员”时，对应的投票会被直接禁用（`campaign.vote.sector-admin-only`）。
- 投票判定模式见 `voteDecision`（`threshold` / `veto` / `majority`）与 `voteTimeout`。
- 跨星球：区块查询串支持 `planet:name`（如 `erekir:onset`），也可以走 `/planet <name>`；
  发射台/接收台不允许跨星球，`actionSetDestination` 会返回 `campaign.destination.other-planet`。

## 6. 客户端为什么能直接看研究界面

不用客户端模组。原版 world data 里已经带了两样东西：

- `Rules.sector`：`JsonIO` 为 `Sector` 注册了专用序列化器（写成 `planet-id`，如 `serpulo-170`），
  读回时用 `content.getByName(planet).sectors.get(id)` 还原。所以客户端 `state.rules.sector` 非空、
  `state.isCampaign()` 为 true，研究界面与战役 HUD 都正常。
- `Rules.researched`：`NetworkIO.writeWorld` 在发送前把服务端全部已解锁内容填进该集合，
  客户端 `UnlockableContent.unlocked()` 在多人时读它，因此解锁状态与服务端一致。

所以房客（局域网或专用服务器）可以直接打开研究界面查看进度，这一点不需要插件额外处理。
插件要保证的只有两条：解锁真的发生在服务端（`ResearchService`），切区块后世界数据要重新下发
（`SectorService` 的 `resyncAll`）。

与原版唯一一致的小差异：科技树里“占领某区块”这类目标读的是**客户端本地** `Sector.info`，可能与服务端
进度不同；解锁状态本身不受影响。