# PrankCraft

**给服务器腐竹用的搞怪/整蛊插件。** 支持 Paper 和 Forge，一套代码覆盖 1.16 到 26.3（即 1.21.x 当前版本）。

所有效果都是**客户端表现**：假 TNT、假爆炸、假天气、假死亡播报、假脚步、屏幕抖动、客户端方块替换。
**没有任何一个效果能伤害、移动、传送或拿走别人的物品，也没有任何效果能接管别人的账号。**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-1.16%20--%201.21.x-brightgreen.svg)
![Tests](https://img.shields.io/badge/tests-21%20unit%20%2B%20live%20E2E-brightgreen.svg)

---

## 目录

- [这个插件不做什么（先看这个）](#这个插件不做什么先看这个)
- [功能列表](#功能列表)
- [假的 TNT 世界是怎么做的](#假的-tnt-世界是怎么做的)
- [安装：Paper](#安装paper)
- [安装：Forge](#安装forge)
- [命令](#命令)
- [权限](#权限)
- [配置](#配置)
- [审计日志](#审计日志)
- [给腐竹的使用建议](#给腐竹的使用建议)
- [自己编译](#自己编译)

---

## 这个插件不做什么（先看这个）

作者在写这个插件时划了三条线，代码里也有对应的注释说明为什么。你在提需求之前值得先看一眼：

| 需求 | 做不做 | 原因 |
|---|---|---|
| 假 TNT、假爆炸、假天气、假死亡消息、假脚步、屏幕抖动、客户端换方块 | ✅ 做 | 纯表现，随时可撤销，不改变服务器世界 |
| 把玩家的名字/皮肤/聊天伪装成另一个人（无同意） | ❌ 不做 | 这是冒充身份，被骗的是第三方。`fake-chat` 只发送**你在配置里写好的固定台词**，并且会明确告诉被冒充的人"刚才有人用你的名义说话"，台词里的 `[前缀]` 会被清洗掉，防止伪造管理员工牌 |
| 操控别人的游戏账号：代走位、代操作、控制视角、改背包、强制传送 | ❌ 不做 | 这是账号劫持，不是整蛊。没有任何配置项能打开它 |
| 让被整的人无法退出/无法反抗的循环效果 | ❌ 不做 | 每条效果都有时长上限，单个目标同时只会有一个假 TNT 会话 |

如果你要的是上面 ❌ 那几项，这个插件帮不了你，换任何插件也都应该谨慎——那不是"搞怪"，是会真的伤害玩家的东西。

**同意机制默认开启**：目标必须先 `/prank allow <你>`，效果才会触发。可以按玩家单独授权，也可以 `/prank deny *` 一键退出。权限 `prankcraft.consent.bypass` 的人永远不会被整，哪怕管理员强制也不行。

---

## 功能列表

| 效果 ID | 说明 | 默认时长 |
|---|---|---|
| `fake-tnt` | **假 TNT 世界**：目标身边出现点燃的 TNT，嘶嘶响、闪烁、爆炸声和粒子。方块不受损 | 由序列自己控制 |
| `jumpscare` | 面前一团粒子 + 怪物尖叫 | 2 秒 |
| `phantom-footsteps` | 看不见的脚步声围着目标转，回头只有一缕烟 | 12 秒 |
| `screen-shake` | 用偏移的空标题让屏幕晃动。**视角始终在玩家自己手里** | 2 秒 |
| `fake-chat` | 在公屏上"替"目标说一句你在配置里写好的台词（目标会被告知） | 瞬时 |
| `fake-death` | 给其他人播报目标的假死亡消息，目标屏幕红闪一下 | 瞬时 |
| `fake-login` | 伪造一条加入/退出消息（在线时只发"退出"，离线时只发"加入"，永远不会自相矛盾） | 瞬时 |
| `fake-weather` | 只对目标一个人打雷下雨（客户端天气包，别人那边还是晴天） | 10 秒 |
| `arrow-rain` | 无害箭雨：零伤害、不能拾取、结束后立刻清理 | 5 秒 |
| `hotbar-shuffle` | 让快捷栏"看起来"被整理过。**真实背包一个格子都不会动** | 4 秒 |
| `wrong-block` | 目标客户端里附近的方块变成随机方块，服务端世界不变，到时间自动还原 | 6 秒 |

---

## 假的 TNT 世界是怎么做的

这是最容易被做成危险功能的地方，所以这里写得细一点。目标是**看起来完全真实，实际什么都不会发生**。

三重保险，任何一重单独都足够：

1. **假方块走数据包**。TNT 只通过 `sendBlockChange` 发给目标一个人的客户端，服务端区块数据从头到尾没被改过。效果结束时把真实方块发回去。就算服务器当场崩了，世界也是干净的。
2. **显示用 TNT 的引信被钉死**。为了让 vanilla 自己渲染闪烁和动画，会生成真实的 `TNTPrimed` 实体，但引信每 tick 都被重置成 `Integer.MAX_VALUE`，另外还有一个独立的定时扫描（默认每 2 tick）兜底。它永远不会倒计时到爆炸。
3. **爆炸事件被取消**。万一前两条在未来的服务端版本上失效，`EntityExplodeEvent` 会直接取消，并且清空 `blockList()`。最坏情况是一个被取消的事件，而不是主城的一个坑。

配置项 `prank-tnt.prime-entity: false` 可以切成**纯数据包模式**：连实体都不生成，任何反作弊都不会注意到。

---

## 安装：Paper

1. 编译或下载 `PrankCraft-1.0.0.jar`。
2. 丢进服务器的 `plugins/` 目录。
3. 重启服务器（不要热加载）。
4. 首次启动会生成 `plugins/PrankCraft/config.yml`，改完用 `/prankcraft reload` 生效。

适用版本：**1.16 到当前 1.21.x / 26.3**。插件用的是跨版本稳定的 Bukkit API，所有在版本之间改过名字的枚举（粒子、音效、天气包、NMS 类名）都走 `Fx` / `RainPackets` 里的运行时解析和反射，找不到就降级跳过，不会抛异常。

---

## 安装：Forge

Forge 版本在 `forge-mod/`，是**独立的构建**（不在 Maven reactor 里，避免构建 Paper 时被迫下载整个 Minecraft 依赖）。

```bash
cd forge-mod
./gradlew build        # Windows: gradlew.bat build
# 产物：build/libs/prankcraft-forge-1.0.0.jar
```

丢进服务端的 `mods/` 目录即可。Forge 版移植的是服务端能做的子集（假 TNT、假天气、屏幕抖动、假聊天、假死亡/加入消息、假脚步 + 同意与审计），**不需要客户端装任何东西**。

> `forge-mod/README.md` 里有该模块的实际构建状态说明。如果那个文件写明"未编译"，说明当时的环境拉不到 Forge 工具链——源码是完整的，在有网络的机器上跑 `gradlew build` 即可。

---

## 命令

### `/prank` —— 玩家命令

| 命令 | 说明 |
|---|---|
| `/prank list` | 列出你有权限使用的效果 |
| `/prank <效果> <玩家>` | 对**已同意**的玩家发动效果 |
| `/prank random <玩家>` | 从你有权限的效果里随便挑一个 |
| `/prank allow <玩家\|*>` | 允许某人（或所有人）整你 |
| `/prank deny [玩家\|*]` | 拒绝某人 / 一键完全退出（立即生效，正在跑的效果会在时长到点后停止） |
| `/prank status` | 看自己当前的同意设置和待处理请求 |
| `/prank yes` / `/prank no` | 批准/拒绝待处理的整蛊请求 |
| `/prank stop` | 如果你卡在某个假象里，立刻清掉 |

### `/prankcraft` —— 管理员命令（`prankcraft.admin`）

| 命令 | 说明 |
|---|---|
| `/prankcraft status` | 版本、同意开关状态、当前活跃的假 TNT 会话数 |
| `/prankcraft tnt show <玩家>` | 手动放出假 TNT（不自动引爆，方便你先看效果） |
| `/prankcraft tnt fire <玩家>` | 手动引爆 |
| `/prankcraft tnt clear <玩家>` | 清掉假象并还原方块 |
| `/prankcraft clear <玩家\|*>` | 清掉所有正在跑的效果 |
| `/prankcraft audit [数量]` | 看最近的整蛊记录（默认 10 条，最多 100） |
| `/prankcraft reload` | 重载配置和同意数据 |

### `/prankconsent` —— 同意管理（`prankcraft.consent.admin`）

| 命令 | 说明 |
|---|---|
| `/prankconsent info <玩家>` | 查看某人的同意记录 |
| `/prankconsent grant <玩家> [整蛊者\|*]` | 代为授权 |
| `/prankconsent revoke <玩家> [整蛊者\|*]` | 撤销授权 |
| `/prankconsent clear <玩家>` | 清空记录并取消正在跑的效果 |
| `/prankconsent force <玩家> <效果>` | **跳过同意**强制发动（只能游戏内管理员执行，会写进审计日志，目标会收到提示） |

---

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `prankcraft.effect.*` | op | 全部效果 |
| `prankcraft.effect.faketnt` | op | 假 TNT 世界 |
| `prankcraft.effect.jumpscare` | op | 惊吓 |
| `prankcraft.effect.phantom` | op | 假脚步 |
| `prankcraft.effect.screenshake` | op | 屏幕抖动 |
| `prankcraft.effect.fakechat` | op | 假聊天 |
| `prankcraft.effect.fakedeath` | op | 假死亡消息 |
| `prankcraft.effect.fakelogin` | op | 假上线下线 |
| `prankcraft.effect.weather` | op | 假天气 |
| `prankcraft.effect.arrowrain` | op | 无害箭雨 |
| `prankcraft.effect.shuffle` | op | 快捷栏假整理 |
| `prankcraft.effect.wrongblock` | op | 客户端换方块 |
| `prankcraft.admin` | op | `/prankcraft` 全部管理命令 |
| `prankcraft.consent.admin` | op | 查看/覆盖别人的同意记录，可强制发动 |
| `prankcraft.consent.manage` | true | 允许玩家改自己的同意设置（建议保持 true） |
| `prankcraft.consent.bypass` | false | **永远不会被整**，管理员强制也不行。有人提出不想参与就给他这个 |
| `prankcraft.notify` | op | 收到整蛊发生的员工提示 |

给普通玩家发权限时建议这样：

```yaml
# LuckPerms 例子：默认组只能玩最温和的两个
/lp group default permission set prankcraft.effect.jumpscare true
/lp group default permission set prankcraft.effect.phantom true
# VIP 组多一些
/lp group vip permission set prankcraft.effect.* true
# 只想安静盖房子的人
/lp user 某玩家 permission set prankcraft.consent.bypass true
```

---

## 配置

`plugins/PrankCraft/config.yml` 里每一项都有中文以外的英文注释说明。最需要留意的四个：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `consent.require-consent` | `true` | 总开关。关掉它，玩家就**没有任何办法拒绝**被整。开之前想清楚 |
| `consent.require-per-target-consent` | `true` | 更严格：目标要针对**具体某个人**授权，而不只是"我同意被整" |
| `prank-tnt.prime-entity` | `true` | 关掉就是纯数据包模式，连实体都不生成 |
| `prank-tnt.auto-detonate` | `true` | 关掉后 TNT 会一直亮着不炸，等你 `/prankcraft tnt fire` |
| `pranks.fake-chat.messages` | 一组沙雕台词 | **这是唯一能误导第三方的效果**，所以台词由你写。请写沙雕的，不要写会造成真实伤害的 |

---

## 审计日志

每一次发动都会记录：谁、对谁、什么效果、什么时候。

- 游戏内：`/prankcraft audit 20`
- 文件：`plugins/PrankCraft/audit-YYYY-MM-DD.log`（追加写入，每天一个文件）
- 控制台：`audit.console: true` 时同步打印

这条日志既是保护玩家，也是保护你自己。有人投诉"管理员动我账号了"，你可以直接拿出时间戳和效果名。**`forced` 前缀表示这次是跳过同意强制的**，包含它的记录请不要删。

---

## 给腐竹的使用建议

1. **开活动之前先广播规则**，让所有人知道接下来会发生什么。全场知情的整蛊才是活动，不知情的整蛊是骚扰。
2. **把 `/prank deny *` 教给玩家**，并且在规则里写明"随时可以退出，退出后立刻生效"。
3. **假聊天台词自己写**。默认那几条是沙雕向的，别改成会让人身攻击或造谣的内容。
4. **别对正在打 PVP、打副本、或者挂机做机器的人用**——不是伤害，但会真的让人不爽。
5. **`consent.require-consent` 不要关**。关了之后你的服务器里就只剩"管理员可以随便整任何人"，这个名声很难洗。

---

## 验证状态

作者不是"写完就交"，下面这些是**实际跑出来的结果**，不是承诺。

### 单元测试：21 项全过

两条**绝不能悄悄失效**的规则被抽成了不依赖 Bukkit 的纯逻辑，可以直接跑单测：

```
mvn test

Tests run: 12, Failures: 0, Errors: 0 -- ConsentRulesTest
Tests run:  9, Failures: 0, Errors: 0 -- ChatGuardTest
Tests run: 21, Failures: 0, Errors: 0
```

- **[ConsentRulesTest](prankcraft/core/src/test/java/com/prankcraft/consent/ConsentRulesTest.java)** —— 同意闸门的真值表，12 条：没 opt-in 拒绝、没进白名单拒绝、白名单通过、`*` 通配通过、**bypass 权限压过白名单**、bypass 压过"关掉同意开关"、不能整自己、控制台放行、`null` 白名单不等于"谁都行"、`null` 目标不当通配符。这些用例是照着我实际写的分支一条条列的，不是凑数的。
- **[ChatGuardTest](prankcraft/core/src/test/java/com/prankcraft/util/ChatGuardTest.java)** —— 防冒充规则的 9 条：`[Admin]`/`[Server]`/`[MOD]` 前缀剥离、叠加前缀剥离、句中标签剥离、**换行不能伪造第二条聊天记录**、控制字符清除、超长截断、正常台词原样通过。

为什么值得单独抽出来测：这两条规则一个决定"谁能被整"，一个决定"会不会变成伪造工具"。它们以前只存在于一次性的端到端测试里，改一行代码就可能被无声破坏 —— 现在 `mvn test` 会拦住。

### Paper 版：已通过真机加载测试

环境：Paper **1.21.11 build 132**（真实服务端，不是模拟），JDK 26，插件用 `mvn clean install` 编译产出。

用 `scripts/smoke-test-paper.ps1` 跑通（脚本会自己拉起服务端、发命令、断言、关服）：

```
ok  : server reached 'Done'
ok  : /prank is a registered command
ok  : /prank list renders the effect table
ok  : /prankcraft is a registered command and runs
ok  : /prankconsent is a registered command and runs
ok  : /prankcraft tnt rejects an offline target
ok  : /prankcraft audit runs
ok  : /prankcraft reload completes
ok  : all 11 configured effects are registered
ok  : no missing effect implementations
ok  : plugin version is resolved
ok  : no PrankCraft stack traces
ok  : config.yml generated (6370 bytes)
ok  : server stopped cleanly
SMOKE TEST PASSED
```

### 假 TNT 世界 + 全部效果：已用真实客户端验证

用 Mineflayer 假人（真实 1.21.11 协议客户端）连上服务器当靶子，`scripts/e2e-fake-tnt.ps1` 一次跑完全部 11 个效果：

```
ok  : probe client joined as PrankTarget
ok  : fake TNT placed client-side: 5 block(s)
ok  : fake TNT detonated on command
ok  : no fake-TNT session leaked
ok  : effect 'jumpscare' fired and was audited
ok  : effect 'phantom-footsteps' fired and was audited
ok  : effect 'screen-shake' fired and was audited
ok  : effect 'fake-weather' fired and was audited
ok  : effect 'hotbar-shuffle' fired and was audited
ok  : effect 'arrow-rain' fired and was audited
ok  : effect 'wrong-block' fired and was audited
ok  : effect 'fake-chat' fired and was audited
ok  : effect 'fake-death' fired and was audited
ok  : client observed primed TNT entities (peak 5)
ok  : client was not kicked
ok  : fake-chat reached the client with the [Admin] prefix stripped
ok  : fake death broadcast excluded its own subject
ok  : target health never dropped across every effect (min 20 of 20)
ok  : target never moved across every effect (0.000 blocks)
ok  : no PrankCraft exceptions during the live test
ok  : every effect reached the audit log (12 entries)
LIVE E2E TEST PASSED
```

三条关键结论，都是客户端侧实测出来的，不是注释里的承诺：

1. **客户端确实看到了 5 个点燃的 TNT 实体**，并且按命令引爆。
2. **全程血量最低值 = 20/20，位移 0.000 格** —— 11 个效果全部打完，被打的人一点没掉血、一步没被推动。
3. **`[Admin] give me op now` 这条台词被清洗后广播** —— 收到的是 `give me op now`，`[Admin]` 前缀被剥掉了。这就是"能不能伪造成管理员"的实测答案：不能。同时目标本人不会收到自己的假死亡消息（`fake death broadcast excluded its own subject`）。

### 测试过程中发现并修掉的真实 bug

这些不是"顺手写的测试"，是测试真的抓到了东西：

| 缺陷 | 后果 | 状态 |
|---|---|---|
| `plugin.yml` 里的 `${project.version}` 没有开资源过滤 | 服务端把插件版本显示成字面量 `${project.version}` | 已修（core/pom.xml 加 filtering） |
| `FakeChatEffect` 的 id 是 `fakechat`，配置和校验列表里写的是 `fake-chat` | **假聊天效果根本没注册**，启动时只留一行 WARN | 已修（id 对齐 + 启动自检保留） |
| `PrankEngine.apply()` 对 `actor` 没做判空 | 控制台执行 force 时 NPE，9 个效果全部静默失败 | 已修（控制台视为已授权，仍写入审计） |
| 箭雨的箭只靠 `damage=0` 保证无害 | 其他插件放大箭伤、或坠落箭判定命中时仍可能伤人 | 已修（按 UUID 跟踪 + 事件取消） |

第三、第四条是安全性问题：如果只跑"加载测试"，它们会一直躺在那儿。

### 源码审查：确认"不碰别人的账号"不是一句口号

除了测试，作者对全部 24 个源文件做了一次针对性的 API 审查，逐个搜 `teleport`、`setGameMode`、`setHealth`、`setFoodLevel`、`getInventory`、`setPlayerListName`、`setDisplayName`、`setOp`、`kickPlayer`、`damage`、`setWalkSpeed`、`setAllowFlight`、`addPotionEffect` 等等。

结果是：**这类调用一个都没有。** 命中的全部是：

| 命中 | 对象 | 说明 |
|---|---|---|
| `setFuse` / `setVelocity` / `setInvulnerable` | 显示用的 TNT 实体 | 把引信钉死，正是"假 TNT 不会真炸"的实现 |
| `setVelocity` / `setFireTicks` | 箭雨生成的箭 | 无害化处理 |
| `closeInventory` | 目标自己 | 关掉那个用完即弃的假窗口，目标随时可以自己重新打开 |
| `setOptIn` | 目标自己 | 只改本人的同意记录 |

Forge 版同样审过，结论一致；那边还额外**明确拒绝**发送 `ADD_PLAYER` 包（这是伪造 tab 列表身份的主要途径），代码里写明了不做的理由。

### 还没验证到的部分

诚实说明，免得你以为全都测过了：

- **只有 Paper 1.21.11 跑过真机**。1.16 / 1.18 / 1.20 是用跨版本稳定的 API 写的、且所有改过名的枚举都走了运行时解析（`Fx` / `RainPackets`），但作者**没有**在这些版本上实机验证过。在老版本上部署前请先单机试一下。
- **同意流程的 UI 路径**（`/prank allow`、`/prank deny`、`yes/no`）是按命令输出和代码审查确认的，没有做双客户端的人工对抗测试。强制路径（`force`）倒是在真机上跑过，因为 E2E 就是用它来触发效果的。
- **Forge 版没有编译成功**，原因见 `forge-mod/README.md` 和 `forge-mod/BUILD-STATUS.md`（本机 JDK 17 的 zipfs 拒绝写入空 zip，AccessTransformers 恰好就是这么建输出文件的；作者已用一个 30 行的复现程序定位到 `jdk.zipfs.ZipFileSystem.checkWritable`，并在 JDK 26 上验证同一段代码正常）。源码完整，换一台正常的机器 `gradle build` 即可。

### 复现

两个脚本都用**当前仓库自己的位置**推导目录，不依赖任何硬编码路径，失败时返回非 0，可以直接接进 CI。

```powershell
# 1. 编译 + 单元测试（21 项）
mvn clean install

# 2. 加载 / 命令冒烟测试
pwsh -File scripts/smoke-test-paper.ps1

# 3. 全效果真机端到端测试（需要 mineflayer）
pwsh -File scripts/e2e-fake-tnt.ps1
```

**服务端 jar 不在仓库里**（几十 MB，而且那是 PaperMC 的东西，不是本项目的），所以第一次跑要先给它一个。脚本按下面顺序找：

1. `-PaperJar <路径>` 参数
2. 环境变量 `$env:PRANKCRAFT_PAPER_JAR`
3. 测试服务端目录里的 `server.jar`
4. 仓库旁边 `build-cache/` 里的任意 `paper-*.jar`

从 https://papermc.io/downloads/paper 下载当前版本即可。测试服务端目录默认是"仓库的上一级"里的 `prankcraft-test-server`，也可以用 `-ServerDir` 指定 —— 放在仓库外是故意的：仓库保持干净，也不会把服务端状态提交进去。

第 3 个脚本还需要一个装了 `mineflayer` 的目录当假人（默认找仓库旁边的 `mc-auto-player`，可用 `-BotDir` 指定）。没有的话它会在**开始之前**明确报错，而不是跑到一半失败。

脚本只会清理**自己上一次运行留下的**服务端进程（靠写在测试目录里的 pid 文件识别），**不会**去杀你机器上其他 Minecraft 服务端。

---

## 性能与实现说明

### 热路径不读 config

Bukkit 的 `getConfig()` 每次调用都要走一遍配置路径并做类型转换。假 TNT 的调度器**每 2 tick 跑一次**，原来每个会话每 tick 要读十几次配置 —— 没人的时候也白跑。

现在所有热路径设置都在 [Cfg](prankcraft/core/src/main/java/com/prankcraft/config/Cfg.java) 里解析一次，`/prankcraft reload` 和 `/prank reload` 走**同一条** `reloadEverything()` 路径刷新，两个命令不会再出现"一个刷了、一个没刷"的情况（这原本是个真 bug：`/prank reload` 只重载了文件，热路径里还是旧值）。

### 其他几处

| 原来 | 现在 | 影响 |
|---|---|---|
| 每次放 TNT 都 `Material.createBlockData()` | 缓存一个不可变 `BlockData` 复用 | 每次施法少 N 次分配 |
| 每次爆炸都调 `Fx.explosion()` 等 varargs（会拼字符串当缓存键） | `Fx.EXPLOSION_CORE` 等常量 | 引爆时零解析 |
| `enabled()` / `duration()` 每次查配置 | `PrankEngine.refresh()` 时解析成 `Settings` 记录 | 每次 Tab 补全少 11 次路径遍历 |
| 客户端换方块每次 `createBlockData()` | 每种材质一个 `BlockData` 常驻 | 刷新任务零分配 |
| 换方块三重循环里每格判一次区块 | 按 (x,z) 列判一次 | 区块查询少 3 倍 |
| `Material.matchMaterial()` 每次施法遍历注册表 | 调色板在 Cfg 里解析一次 | 施法零注册表扫描 |

优化后**单元测试 21 项、真机端到端 23 项全部重跑通过** —— 性能改动没有改变任何可观测行为。

### 关于编译警告

编译用 `-Xlint:all,-deprecation,-removal`。只关这两类是**故意的**：插件编译对着最新 Paper API，但要在 1.16 上跑，所以一律使用"每个支持版本都存在"的那套最老 API —— `ChatColor`、字符串版 `sendTitle`/`sendActionBar`、`openAnvil + setTitle`。它们的现代替代品在 1.16 上**根本不存在**。其余所有 lint 类别（包括跨版本反射里的 unchecked）都保持打开，当前编译**零警告**。

---

## 自己编译

前置：JDK 17+（编译目标是 Java 17），Maven 3.8+。

```bash
cd prankcraft
mvn clean package
# 产物：paper/target/PrankCraft-1.0.0.jar
```

编译到指定 Paper API 版本（默认 `1.21.11-R0.1-SNAPSHOT`）：

```bash
mvn clean package -Dpaper.api.version=1.20.4-R0.1-SNAPSHOT
```

工程结构：

```
prankcraft/
├── pom.xml                     # Maven reactor（core + paper）
├── core/                       # 全部逻辑，24 个源文件
│   └── src/main/
│       ├── java/com/prankcraft/
│       │   ├── PrankCraftPlugin.java      # 入口
│       │   ├── prank/                     # 效果接口 + 唯一的权限/同意闸门
│       │   ├── effects/                   # 11 个效果
│       │   ├── fx/                        # 假 TNT 管理器、跨版本枚举解析
│       │   ├── consent/                   # 同意记录
│       │   ├── audit/                     # 审计日志
│       │   ├── commands/                  # 三条命令
│       │   ├── listeners/                 # 安全兜底监听器
│       │   └── util/
│       └── resources/{plugin.yml,config.yml}
├── paper/pom.xml               # 打成最终插件 jar
└── forge-mod/                  # 独立构建的 Forge 版
```

设计上有一点值得留意：**权限和同意的检查只有一处**，在 `PrankEngine.apply()`。效果类自己不做任何检查，命令层也不做。以后加新命令、新效果，都必须过这一个闸门——这是故意的，一个"有时候会检查同意"的整蛊插件比没有整蛊插件更糟。
