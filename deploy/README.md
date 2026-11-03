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

日志：Mindustry 服务端**没有** systemd 单元（按要求手动用 `server.sh` 管理），所以 `journalctl -u` 看不到它，
看 `logs/server.log`。系统本身有 systemd（262），Wi-Fi 看门狗就是用 systemd timer 跑的。
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

## 无线链路（wlan0）：规范域必须设置

2026-11-03 排查结论：`wlan0` 表现为“网卡老是休眠”——间歇性完全不可达，但从网关侧看一切正常。

根因是 `iw reg get` 显示 `country 00`（规范域未设置）。该状态下 5GHz 信道 149–165 只被标为
`PASSIVE-SCAN`，mac80211 会限制发送。实测：

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 服务器 → 网关丢包 | 87.5% | 0% |
| RTT | 110 ms | 2–6 ms |
| TX 速率 | 48 Mbit/s | 526 Mbit/s |
| `tx failed` | 10406（持续增长） | 不再增长 |

修复（已落盘）：

```bash
iw reg set CN
cat > /etc/modprobe.d/cfg80211.conf <<'EOF'
options cfg80211 ieee80211_regdom=CN
EOF
nmcli connection modify HUAWEI-SVTYH4_5G 802-11-wireless.powersave 2
```

省电本身不是原因：`iw dev wlan0 get power_save` 一直是 `off`；那条只是顺手显式关闭。

### 第二种故障：速率塌陷（2026-11-03 复现）

规范域已正确（`country CN`）、信号 -49 dBm、`beacon loss` 为 0 的情况下，链路仍会在**连上约 60–90 分钟后**
突然劣化：

| 指标 | 正常 | 劣化时 |
| --- | --- | --- |
| `tx/rx bitrate` | 585 / 702 Mbit/s | **固定 48 / 48 Mbit/s** |
| 服务器 → 网关丢包 | 0% | **80%** |
| RTT 平均 | 3.7 ms | **267 ms**（最大 559 ms） |
| 信号强度 | -49 dBm | -49 dBm（没变化） |

即关联本身没断、信号没变，是速率控制和 AP 调度侧卡住了。`nmcli connection down/up` 重连后
**立刻**恢复 0% 丢包和满速率，所以不是硬件或距离问题。

复现判据：`iw dev wlan0 link` 里 `tx bitrate` 掉到 48 Mbit/s 且 `ping 网关` 丢包 >50%。

**已装看门狗**（systemd timer，开机自启）：

```bash
# 单次手动检查：丢包 >50% 时重连，10 分钟内最多重连一次
sh /usr/local/sbin/wifi-watchdog.sh

# systemd 定时器：每 2 分钟跑一次
systemctl list-timers wifi-watchdog.timer
systemctl status wifi-watchdog.service      # 上次执行结果
tail /var/log/wifi-watchdog.log             # 触发过才有内容

# 停用
systemctl disable --now wifi-watchdog.timer
```

单元文件在 `deploy/wifi-watchdog.service` / `deploy/wifi-watchdog.timer`，
安装到 `/etc/systemd/system/` 后 `systemctl daemon-reload && systemctl enable --now wifi-watchdog.timer`。

注意：重连会让局域网地址中断几秒，在线玩家会掉线，所以只有丢包 >50%（链路已经不可用）时才动手。
**Mindustry 服务端本身仍然不用 systemd**，还是 `server.sh start|stop|restart|console` 手动管理。

该机还有一个 **USB 网络口 `usb0`（192.168.42.1）**，由 PC 侧 `以太网 2`（Remote NDIS）直连，
0% 丢包、<1 ms。Wi-Fi 出问题时用它做管理通道：`ssh root@192.168.42.1`。

## 外网访问：端口映射（2026-11-03）

Mindustry 需要**同一个端口同时开 TCP 和 UDP**（服务端 `ss` 里 `*:6567` 两种协议都在听）。

路由器是 `192.168.3.1`（华为，PPPoE 拨号，WAN 类型 `WANPPPConnection`，IP 会随重拨变化）。
它开了 UPnP，所以可以用 IGD 读写端口映射，不需要登录 Web 界面：

```bash
# 列出当前映射（只显示 UPnP 建的，手动加的“虚拟服务器”不一定出现在这里）
python3 /tmp/igd-list.py

# 增加/刷新一条映射
python3 /usr/local/sbin/igd-add.py udp 6567 192.168.3.178 6567
python3 /usr/local/sbin/igd-add.py tcp 6567 192.168.3.178 6567
```

**租期陷阱**：请求 `LeaseDuration=0`（永久）也会被路由器压成 **7 天（604800 秒）**，
到期映射自动消失，表现为"外网突然连不上"。已装每日续期：

```bash
systemctl list-timers mindustry-portmap.timer   # 每天 21:27 跑一次
cat /var/log/mindustry-portmap.log              # 每次续期结果
```

`mindustry-portmap.sh` 每次从 `wlan0` 现读 IP，因此 DHCP 换地址也能跟上。

### 排查结论（2026-11-03 外网连不上）

现象：外网连不上，本机怀疑 MAC 变了。

实测：

| 检查 | 结果 |
| --- | --- |
| MAC | `a6:d7:9d:78:3d:cb`，`addr_assign_type=0`（永久地址），本次开机只有一个 MAC |
| 局域网 IP | `192.168.3.178`，重连前后不变 |
| 服务端监听 | `*:6567` TCP + UDP |
| 公网检测 | 西班牙/印度/哈萨克三节点全部 `Connection timed out` |
| 路由器 WAN IP | `1.62.215.182`（UPnP 自报，与服务器看到的公网 IP 一致，非 CGNAT） |
| 路由器映射表 | 11 条全是 UDP（蒲公英/IPTV），**没有 TCP 6567，也没有任何指向 .178 的条目** |

处理：补 TCP + UDP 映射 → 外网立即恢复（瑞典节点 0.21 s 连通）。
补映射之前还做过一次 `nmcli connection down/up`，之后外网就通了——但重连前后 MAC 和 IP 都没变，
所以更可能是路由器对 `192.168.3.178` 的主机/ARP 绑定卡住，重连让它重新学习了一次。

排查外网连通性的两个外部手段（都是只读查询）：

```bash
# 从多个海外节点做 TCP 连接测试（需要 Accept: application/json）
curl -s -H 'Accept: application/json' 'https://check-host.net/check-tcp?host=<ip>:6567&max_nodes=2'
curl -s -H 'Accept: application/json' 'https://check-host.net/check-result/<request_id>'

# 局域网内测"外网地址"能否连通（需要路由器支持 hairpin/NAT 回环）
Test-NetConnection -ComputerName <wan-ip> -Port 6567
```

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
