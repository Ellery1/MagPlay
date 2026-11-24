# MagPlay 迅雷存量视频迁移方案

> 状态：可执行（已实测验证通道与单文件复制）
> 适用设备：小米15（24129PN74C / Android 15 / HyperOS）
> 编写日期：2026-11-23

## 一、现状

- 迅雷下载目录：`/sdcard/Android/data/com.xunlei.downloadprovider/files/ThunderDownload/`，是迅雷应用的私有目录。Android 11+ 的 Scoped Storage 封死了普通 App 与 SAF 对该目录的访问，MagPlay 装好后也扫不到。
- 存量盘点：43 个子目录，视频 **72 个**（65 mp4 + 6 mkv + 1 rmvb），合计约 **91 GB**；另有 42 个 `.js`、33 个 `.torrent`、2 个 `.jpg` 不迁。
- 手机内部存储：总 479 GB，已用 280 GB，剩余约 199 GB——复制后双份占用仍放得下。
- 目标目录：`/sdcard/Download/ThunderDownload/`（公共目录，MediaStore 可索引，MagPlay 可扫到）。

## 二、问题

需要把 91 GB 存量视频从迅雷私有目录搬到公共目录，且满足：

1. 不破坏"零隐私"定位——不引入 Root/Shizuku/系统签名等特权方案；
2. 不依赖不稳定的 USB 链路——实测 USB `adb pull` 传 234 KB 文件返回 0 字节，transport_id 反复跳变；
3. 原文件暂时保留——迁移期间和验证完成前不做任何删除；
4. 可中断可续传——91 GB 一次性传输风险高，必须逐文件校验、失败可重试。

## 三、解决方案

### 3.1 策略选择：设备端直接复制

已实测三条通道：

| 通道 | 小文件 | 中等文件 | 结论 |
|---|---|---|---|
| USB adb pull | 468 B ✅ | 234 KB ❌（0 字节） | 链路不稳，弃用 |
| 无线 adb pull | 468 B ✅ | 234 KB / 2.6 MB ✅ | 通道稳定，但数据要过 WiFi |
| **设备端 cp（adb shell）** | 468 B ✅（大小一致，源文件未动） | 无需传输 | **推荐**：数据在手机内部流动，无掉线风险，速度最快 |

**最终策略**：通过无线 adb 下发 `cp` 命令，在手机内部逐文件复制，逐个校验大小，保留原文件。

### 3.2 前置条件

1. 手机与电脑连同一 WiFi；无线 adb 已连通：`adb devices -l` 中出现 `192.168.31.39:5555  device`。
   - 若掉线：手机「开发者选项 → 无线调试」确认开启，电脑上重连 `adb connect 192.168.31.39:5555`；IP 变了就用 `adb shell ip addr show wlan0` 重新查。
2. 手机亮屏、解锁、关闭省电限制；电量 > 50% 或接充电器。
3. 电脑端关闭"此电脑"里的小米手机 MTP 窗口（避免 MTP 与 adb 抢占）。
4. 公共目录剩余空间 ≥ 95 GB（`adb shell df -h /sdcard/Download` 确认）。
5. **当前阶段不迁移**——本文档供过几天重启时照抄执行。

### 3.3 执行步骤

#### 阶段 0：小文件验证（每次重启迁移都先做）

**执行步骤**：

```powershell
$adb = "C:\Users\54351\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "192.168.31.39:5555"
$src = "/sdcard/Android/data/com.xunlei.downloadprovider/files/ThunderDownload/.0805-20.torrent.js"

# 只读确认设备在线
& $adb -s $dev devices

# 复制一个 468B 的测试文件到公共目录
& $adb -s $dev shell "mkdir -p /sdcard/Download/ThunderDownload"
& $adb -s $dev shell "cp '$src' /sdcard/Download/ThunderDownload/_probe.js"

# 校验：源 468 / 目标应 468
& $adb -s $dev shell "stat -c %s /sdcard/Download/ThunderDownload/_probe.js"

# 清理测试文件
& $adb -s $dev shell "rm -f /sdcard/Download/ThunderDownload/_probe.js"
```

**预期**：设备在线；`stat` 输出 `468`；测试文件已删除；迅雷源文件未改动（只读 `ls -l` 复核大小与时间戳）。

#### 阶段 1：生成迁移脚本到手机

把 `migrate_xunlei.sh`（随本文档一同放在项目根目录）推到手机可执行位置：

```powershell
$adb = "C:\Users\54351\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "192.168.31.39:5555"
& $adb -s $dev push "E:\python\MagPlay\migrate_xunlei.sh" /data/local/tmp/migrate_xunlei.sh
& $adb -s $dev shell "chmod 755 /data/local/tmp/migrate_xunlei.sh"
```

**预期**：`push` 成功；`chmod` 无报错。

#### 阶段 2：dry-run 列清单（只列不复制）

```powershell
& $adb -s $dev shell "sh /data/local/tmp/migrate_xunlei.sh dry"
```

**预期**：输出 72 行 `PLAN <相对路径> <大小>` 和一行 `PLAN total=72 files`。核对数量与 91 GB 量级是否吻合。

#### 阶段 3：正式迁移

```powershell
& $adb -s $dev shell "sh /data/local/tmp/migrate_xunlei.sh run"
```

**预期**：逐行输出 `OK <相对路径>`；若中途中断，重跑同一命令会自动 `SKIP` 已完成且大小一致的文件（断点续传）。失败文件输出 `FAIL <相对路径>`，可单独重试。

#### 阶段 4：完整性校验

```powershell
& $adb -s $dev shell "sh /data/local/tmp/migrate_xunlei.sh verify"
```

**预期**：每个视频输出 `MATCH` 或 `MISMATCH`；`FAIL/MISMATCH` 计数为 0 才可进入下一步。同时确认原目录文件数量未减少：

```powershell
& $adb -s $dev shell "find /sdcard/Android/data/com.xunlei.downloadprovider/files/ThunderDownload -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.rmvb' \) | wc -l"
```

**预期**：输出 `72`。

#### 阶段 5：触发媒体索引 + MagPlay 验证

1. 手机文件管理器打开一次 `/sdcard/Download/ThunderDownload/`（触发 MediaStore 扫描）。
2. 打开 MagPlay → 授予"视频和照片"权限 → 列表应出现这些视频；若未出现，点列表页的刷新按钮（重新扫描 MediaStore）。
3. 随机点开 2–3 个视频（含 mp4 / mkv / rmvb 各一个）确认可播放。

**预期**：MagPlay 能列出并播放迁过去的视频；迅雷原目录仍在，91 GB 原文件一个没少。

### 3.4 迁移脚本行为约定

`migrate_xunlei.sh` 支持三个参数：

| 参数 | 行为 |
|---|---|
| `dry` | 只列出待迁移文件及大小，不复制、不删任何文件 |
| `run` | 逐文件 `cp -p` 保留时间戳 → 校验目标大小 → 记录日志；目标已存在且大小一致则 `SKIP` |
| `verify` | 逐个比对源文件与目标文件大小，输出 MATCH / MISMATCH |

脚本硬约束：

- 只处理 `*.mp4 / *.mkv / *.rmvb` 三种扩展名（大小写不敏感），其他文件一律跳过；
- 保留原目录层级（如 `SSIS-698_LTX/xxx.mp4` → `/sdcard/Download/ThunderDownload/SSIS-698_LTX/xxx.mp4`）；
- **绝不删除源文件**，任何阶段都不动迅雷目录；
- 日志写入 `/sdcard/Download/ThunderDownload/_migration.log`，记录每个文件的 OK/SKIP/FAIL；
- 单文件失败不中断整体，最后汇总失败数。

### 3.5 回滚（可逆性说明）

迁移只做复制，不做删除，因此回滚极其简单：

```powershell
# 删除公共目录中的迁移副本（保留迅雷原目录）
& $adb -s $dev shell "rm -rf /sdcard/Download/ThunderDownload"
```

**注意**：这条命令只删除本次迁移创建的目标目录。执行前务必确认没有放入其他个人文件；如不放心，改为在手机文件管理器中手动删除该目录。迅雷原目录全程未被修改，无需回滚。

## 四、预期落地效果

1. MagPlay 安装后可直接从公共目录扫描到 72 个存量视频，无需任何特权权限，保持"零隐私"定位。
2. 迁移过程可中断、可续传、可校验；91 GB 逐文件复制，单文件失败可单独重试。
3. 迅雷原目录 91 GB 完整保留，验证通过后由用户自行决定是否清理（清理属不可逆操作，需单独确认）。
4. 未来迅雷新下载的文件仍需走"改下载路径到公共目录"的方案（见 TECHNICAL_DESIGN.md 5.4.3），adb 迁移只解决存量。
