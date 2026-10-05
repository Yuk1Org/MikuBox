> 已被同日第二轮取代:代理组改为独立界面(卡片双击 / 左滑 / 再点规则滑块进入),
> 不再使用模式栏下方的一行。界面与入口见 [proxy-groups-screen-2026-10-05.md](proxy-groups-screen-2026-10-05.md)。
> 本文保留为当时那版(已随 v0.2.6 发布)的记录。

# 主页代理组面板(2026-10-05)

对应 issue [Yuk1Org/MikuBox#2](https://github.com/Yuk1Org/MikuBox/issues/2)「订阅链接导入未显示分组」。

## 问题

导入订阅链接后,配置里的 `proxy-groups` 在规则模式下没有任何入口:模式栏第二行只在全局模式展开,展开后选的是 `GLOBAL` 的出口。想在「🚀 节点选择」这类策略组里换节点,用户只能先切到全局模式 —— 而全局模式会绕过配置自身的规则。上一代 MikuBox(UwU 线)有一个「节点」页(`ProxiesActivity`,抽屉入口,按策略组分页、列节点、可测速排序),MikuRay UI 重写时整屏被删除,`docs/inspection-2026-09-20.md` 也记录了「规则模式下配置内部各策略组的成员切换界面仍需单独适配」。本次把这个缺口补上。

## 改动

- 契约 `MikuRouting` 增加 `groups()` / `selectGroupMember()` / `delay()`。[`groups()`](../app/src/main/java/com/mikubox/mihomo/core/MikuRayRoutingMode.kt) 在线时取核心的实时数据(组顺序用核心声明的顺序,成员含嵌套组与 provider 节点),离线时解析配置里的 `proxy-groups`;`GLOBAL` 不入列,它仍归全局模式那一行。provider(`use:`)成员离线无法得知,面板如实说明而不是编造名字。
- 新增面板 [`ProxyGroupPanel`](../app/src/main/java/com/miku/ray/ui/main/ProxyGroupPanel.kt):组名胶囊 + 成员列表(旗标、名称、延迟、选中勾)。选中成员立即写入核心;自动组(url-test 等)额外提供「自动选择」行,清空钉选回到自动。在线时提供「测速」(并发探测、逐个回填)与「按延迟排序」;离线时两者隐藏,并在列表上方说明「未连接:以下为配置声明的成员,选择会在连接后生效」。
- 选择按「配置 + 组」保存,连接时由 `onStarted` 重新下发(`applyStoredGroupChoices`);组或成员在新配置里已不存在时丢弃该记录并在日志里说明。
- 模式栏第二行改为:规则模式显示「代理组」(新入口),全局模式仍是当前出口(行为不变),直连模式不展开 —— 直连不经过任何代理。
- 面板与出口选择弹窗共用同一套弹窗外壳(主题、圆角、高度上限),由 `RoutingModeView.present` 提供。
- 测速地址统一走 `MihomoCoreSettings.testUrl()`(主页卡片的 `pref_delay_test_url`,缺省 `https://cp.cloudflare.com`),`MikuRayProfileSync` 里重复的同款表达式一并收拢,测量条件保持一致。
- 新增 7 个字符串,在 `values`、`values-zh-rCN`、`values-ru`、`values-in` 四个语言目录同步落地。

## 验证

- `:app:testDebugUnitTest` 69 项通过(新增 `offlineGroupsListDeclaredMembersAndDefaultSelection`、`chosenGroupMemberIsPerProfileAndOnlyAutomaticGroupsGoBackToAutomatic`);`:app:lintDebug` 0 错误;`tools/locale-coverage.py` 两个资源根各语言 0 缺失;三 ABI debug 包构建通过。
- API 34 x86_64 模拟器,导入含三个策略组的 Clash 订阅后实测:
  - 规则模式第二行显示「Proxy groups」,离线打开可见 `🚀 节点选择`、`♻️ 自动选择`、`🌍 国外媒体` 三组及成员(嵌套组带组图标),持久化的选择打勾;直连模式该行收起,全局模式仍显示当前出口。
  - 离线选中 `JP-01` 后连接,核心日志出现 `using 🚀 节点选择[JP-01]`,证明选择在启动时确实下发。
  - 在线选中 `SG-01`,核心日志出现 `using 🚀 节点选择[SG-01]`;把外层组指向嵌套组 `♻️ 自动选择` 并把该自动组钉到 `SG-01` 后,日志为 `using 🚀 节点选择[SG-01]`,钉选链路生效。
  - 「测速」对成员逐个回填真实延迟(经本机 SOCKS5 中继测得 214–428 ms),「按延迟排序」按升序重排;搜索无结果时显示「No matches」。

## 说明

- `MihomoConfigPreview`(离线按行解析节点/组/规则,仓库内无调用者)本次未接线:面板离线用 snakeyaml,能同时读流式写法(`- {name: X, type: select, proxies: [a, b]}`),与实际机场订阅更相符。
- 面板内的延迟仅表示「核心最近一次测量」;自动组的实际出口由核心在运行时决定,离线无法预知,因此离线不显示成员延迟。
