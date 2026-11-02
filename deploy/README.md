# 远程测试服部署（umi / 192.168.3.178）

Mindustry 战役插件（`CampaignServerSide`）的固定测试服。**没有** systemd 单元，全部手动脚本控制。

## 机器

| 项 | 值 |
| --- | --- |
| 地址 | `192.168.3.178`（LAN） |
| SSH | `ssh root@192.168.3.178`（root 用公钥，直接可用；`colorgarden` 账户暂时不接受这把公钥） |
| 系统 | Arch Linux ARM，aarch64，8 核 / 7.3 GiB RAM / 204 GiB 可用 |
| Java | `jre21-openjdk-headless 21.0.13`（pacman 安装，`/usr/bin/java`） |
| 运行用户 | `colorgarden`（uid 1001），home `/home/colorgarden` |

## 目录

```
/home/colorgarden/campaign-server/
├── server-release.jar                 # Mindustry v160.7 专用服务端
├── start.sh                           # 前台启动（由 server.sh 调用）
├── server.sh                          # start|stop|restart|status|console|attach
├── console.sh                         # 单条控制台命令（server.sh console 的旧入口）
├── console.in                         # 控制台 FIFO（服务器运行时存在）
├── server.pid
├── logs/server.log                    # 服务端输出
├── logs/start.log                     # 启动记录
└── config/                            # Mindustry 数据目录
    ├── settings.bin                   # 区块信息 <planet>-s-<id>-info 等
    ├── saves/sector-<planet>-<id>.msav
    └── mods/
        ├── CampaignServerSide.jar
        └── Campaign Server Side/config.json
```

## 常用操作

```bash
# 以 root 登录后，用 colorgarden 身份执行脚本
S=/home/colorgarden/campaign-server/server.sh

runuser -u colorgarden -- $S status
runuser -u colorgarden -- $S start
runuser -u colorgarden -- $S stop            # 通过控制台 "exit" 优雅退出并保存区块
runuser -u colorgarden -- $S restart
runuser -u colorgarden -- $S console 'campaign-status'
runuser -u colorgarden -- $S console 'campaign-goto facility32m'
runuser -u colorgarden -- $S console 'campaign-research-all'
runuser -u colorgarden -- $S attach          # 跟踪日志
```

日志：`journalctl` 不可用（没有 systemd 单元），看 `logs/server.log`。
`start.sh` 用 `setsid nohup` 启动，关掉 SSH 不会影响服务端。

## 端口

- `6567/tcp`：游戏端口（Mindustry 客户端直连 `192.168.3.178:6567`）
- 只有一个世界在跑，切换区块会踢掉/重同步在线玩家

## 当前状态（2026-11-02 部署）

- 插件版本：本仓库构建的 `CampaignServerSide.jar`
- `config.json`：`planet=serpulo`、`sector=170`（groundZero）、`autoStart=true`、`autosaveSeconds=180`
- 服务端已托管 `groundZero`，监听 `6567`
- 重启机器后需要手动执行一次 `runuser -u colorgarden -- $S start`（因为不使用 systemd），
  启动后插件会凭 `autoStart` 自动回到上次区块

## 注意

- **不要**在这台机器上 `pacman -Syu` 全量升级来“顺便修东西”；只装/删需要的包。
- 本机（Windows 开发机）的 git 全局配置里有一个 `http.proxy=http://127.0.0.1:7890`，代理没开时
  `git push` 会报 TLS `unexpected eof`。推送本仓库用：
  `git -c http.proxy= -c https.proxy= push`
- 服务端 JVM 需要 UTF-8 输出（`deploy/start.sh` 已带 `-Dstdout.encoding=UTF-8`），
  否则中文日志会变成 `?`。
- 改 `config.json`：先停服再改，或改完立刻执行 `server.sh restart`（插件在切换区块时会重写该文件，
  虽然会保留你改过的字段，但停服改最稳）。
- 想完全重开某个区块：`console 'campaign-reset <sector>'`；想清空整个战役：停服后删
  `config/saves/sector-*.msav` 与 `config/settings.bin`。
- 插件 jar 更新流程：本地 `./build.ps1` → `scp build/CampaignServerSide.jar root@192.168.3.178:/home/colorgarden/campaign-server/config/mods/` → `chown colorgarden:colorgarden` → `server.sh restart`。
