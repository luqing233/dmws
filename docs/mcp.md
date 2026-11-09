# MCP 调用与交互检查（2026-11-04）

当前香港生产端已于 2026-11-05 02:20（Asia/Shanghai）上线下载器 v0.3.0 适配，DMW 共 29 个工具；独立下载器保持 24 个工具。此前的经济工具、昵称回执与已确认热力图继续保留。各阶段旧工具数量不代表当前状态。

新增工具 `dmw_send_forward_message`，复用现有 `/mcp` 和 Authorization 配置。
群聊示例：

```json
{"name":"dmw_send_forward_message","arguments":{"group_id":123456,"nodes":["第一条消息","第二条消息"]}}
```

私聊把 group_id 换成 user_id，二者只能提供一个。nodes 为 1–100 条非空纯文本，总长最多 50000 字符。每条作为一个转发节点，使用配置的机器人身份，不解析 CQ 码。工具返回 ok、目标 ID、节点数和 receipt（含 messageId）。配置的机器人必须在线，平台限制仍可能导致发送失败。超时或结果不明时不要自动重发，应先核对聊天记录。

检查范围：11 月 2–4 日提交涉及的 MCP、经济指令与番茄正文交互，以及当前工作区相关修改。并非每个业务分支的线上验证。

已修正：
- MCP 缺少 ping，客户端保活请求会报不支持。
- 经济指令合并转发遇到非零返回码没有普通消息反馈。
- 番茄章节纯文本没有 HTML p 标签时，原先只发送标题和字数，丢失正文。
- MCP 章节分页 offset 接近整数上限时加法溢出。

仍需留意：
- 整书更新已改为后台任务，使用下面的任务查询流程。
- 已补充商店、余额和背包查询，可先查询再购买。
- 转账和抢劫默认使用机器人自己的账户，代办显式传入请求者 user_id；购买使用请求者账户。经济写入必须带可信消息签名。
- 本次没有连接实际 QQ 群发送测试，避免产生未经请求的消息；真实转发显示需部署后验证。
## 后台整书更新

`dmw_update_book` 的参数保持 book_id、operator、confirm=true，返回改为任务快照，包含 job_id、status、done、poll_after_seconds。同一本书的未完成任务会复用，不会重复下载。

调用 `dmw_get_update_job`，参数 `{"job_id":"返回的任务 ID"}`，建议每 5 秒查询一次。status 为 queued、running、completed、partial 或 failed；done=true 时停止轮询。目录同步完成后提供 total、processed、success、fail、exist，并在每批章节完成后更新。partial 表示部分章节未获取成功，可再次提交补齐。

任务队列最多等待 10 个，单线程执行，最多保留最近 100 条任务记录。任务和进度保存在内存，应用重启后不保留；遇到未知任务请重新提交。原先依赖同步返回统计的客户端需要改用 job_id 查询。

## 经济查询与身份

- `dmw_economy_shop`：参数 `{}`，返回上架道具代码、名称、说明和精确十进制价格。
- `dmw_economy_balance`：参数 `{"user_id":123456}`，返回钱包、主银行和两者合计。金额为精确十进制字符串；不含私人银行余额。
- `dmw_economy_backpack`：参数 `{"user_id":123456}`，返回持有道具和数量，空背包返回空数组。

以上查询在账号 ID 可确定时无需 QQ 在线。余额和背包省略 user_id 时查询机器人自己的账户；查询聊天用户时显式传入该用户 QQ ID。转账、抢劫省略 user_id 也使用机器人；购买以可信请求者账户扣款。

## MCP 使用道具

2026-11-05 道具扩展：`golden-basin`（金盆）每次一个清零全部犯罪值，每自然日最多2个，不缩短服刑/住院。`electric-baton`（便携电棍，也接受“电棍”）改为保留在背包的装备，每根100电，抢劫自动耗20；手动使用只查询状态，不激活旧增益。`electric-baton-charger`（电棍充电器，也接受“充电器”）可重复使用，每人只需购入一个，每次给电量最低的一根充电，每分钟1电，空电100分钟充满；抢劫时该根实际电量达到20就结算已充电量、停止充电并扣20，不足20则继续充电并可选用其他电棍或旧增益；停止后不会自行续充。`dmw_economy_backpack` 新增 `equipment.charger_owned` 与 `equipment.batons[]`（`energy`、`charging`、`minutes_to_full`），查询不写数据。使用回执的 `data.quantity` 仍是请求数量，新字段 `data.consumed_quantity` 对电棍/充电器为0，其余是实际消耗数量。抢劫回执新增 `data.electric_baton_used`、`data.electric_baton_energy`（未使用时为null），电棍优先于旧增益且不叠加；空口袋没有成功判定，不消耗两类增益。以上写操作继续强制可信消息HMAC验证，不能借新道具替换操作者。

新增 `dmw_economy_use_item`，复用群聊“使用”指令的事务服务，不通过 QQ 发送模拟指令。

代当前请求用户使用（QQ ID 必须来自当前消息上下文）：

```json
{"name":"dmw_economy_use_item","arguments":{"group_id":123456,"user_id":234567,"item":"便携电棍","quantity":1,"request_id":"qq-98765-use-1"}}
```

AI 使用机器人自己的道具时省略 `user_id`。账号取 `dmws.bot-id`，未配置时只允许唯一已连接的 OneBot 账号，绝不回退到主人或 MCP 本地操作者。多机器人环境必须配置 bot-id，并使用该机器人验证群成员；指定机器人离线时不会借用其他在线机器人。

```json
{"name":"dmw_economy_backpack","arguments":{"as_bot":true}}
{"name":"dmw_economy_use_item","arguments":{"group_id":123456,"item":"fishing-luck-bait","request_id":"qq-98766-self-use-1"}}
```

余额/背包新增可选 `as_bot=true`（不可同时传 user_id），在配置 bot-id 时可离线查询机器人账号。省略参数同样查询机器人，不再查询管理员账户。商店和背包新增 `effect_type`、`manual_usable`，供 AI 判断被动道具；签到加成卡应在签到时自动消耗，手动使用返回 `PASSIVE_ITEM`。

使用工具必须提供正整数 group_id、道具编码或准确名称、稳定 request_id；quantity 默认 1，范围 1–999，称号卡和补签卡只能使用 1 个。所有效果作用于道具所有者，不支持将别人的道具效果转给另一账号。机器人在线且所有者在该群中才执行。MCP Bearer 是可信调用方凭据，群成员校验不是最终用户身份认证；调用方必须从可信消息上下文提取用户 ID，只能在该用户请求后代用其道具，不得凭模型猜测或擅自使用其他成员的背包。

成功返回 `ok=true`，data 含 user_id/user_name、item_code/item_name、quantity、effect_type、effect_message、request_id。失败返回明确业务 code，例如 `ITEM_NOT_ENOUGH`、`PASSIVE_ITEM`、`NO_BROKEN_STREAK`、`INVALID_QUANTITY`，并设置 MCP `isError=true`。消耗、效果和去重记录在同一事务内，效果失败会回滚；相同 request_id 重试返回 `DUPLICATE_REQUEST`，不会再次消耗（即使上次已用完该道具）。AI 不应为重试更换请求号或未经请求自动购买。

道具接口最初本地 `mvnw.cmd clean verify` 85 项 Java 测试和维护脚本 18 项 Python 测试通过，覆盖代用户/机器人自身使用、离线/非群成员、多机器人选择、参数校验、被动道具、去重及并发、事务回滚；后续已随本轮经济玩法与图表一起部署，最终全量结果为 105 项 Java、24 项 Python。

## 玩家状态、出院与保释（已部署）

人物提示统一使用昵称。余额、背包、状态、位置响应新增 user_name；保释响应含 helper_name、target_name 和简洁 display_text。user_id/target_user_id 等保留给机器定位，AI 不应在普通回复中展示。查询可选传 group_id，以优先显示该群名片；无法实时查询时只读回退已保存昵称，再无名称时显示“这位玩家”，不输出数字QQ号。

操作结果采用简洁回执：出院仅返回“出院成功、已缴纳医药费”，当前主银行仍有医疗欠款时才在 display_text 中显示欠款；重复办理显示“你已出院、医药费已结算”，不暗示再次收费。data 保留既有状态/账单字段，并新增 medical_debt、discharged_now；AI 只有在用户主动查询状态时才展开这些信息。保释成功只显示被保释人昵称及费用，查价时只显示费用或“当前未服刑，无需保释”。

新增 `dmw_economy_get_location`，可选 user_id，省略查机器人自己。返回 location（HOSPITAL/JAIL/FREE）、location_name、hospital_until/jail_until 及中文 display_text。游戏位置来自当前住院/服刑状态，到期会结算更新，不显示钱包余额。所有伤势统一由本人出院，只有入院满24小时才强制出院。

- `dmw_economy_status`：可选 user_id；省略时为机器人账号。查询 crime、injury、hospital_until、jail_until、current_hospital_fee、bail_fee 和最近出院账单。无需 QQ 在线即可查询已知账号，但会结算已到期住院费用，因此并非完全无写入的读取。
- `dmw_economy_discharge`：group_id、request_id，可选 user_id；仅在用户明确要求出院时使用。省略 user_id 为机器人本人；传入当前请求用户的 ID 才可代其操作。返回最近医疗费及新增医疗欠款，不重复结算同次住院。
- `dmw_economy_bail`：group_id、target_user_id、request_id，可选 user_id、confirm。user_id 为付款者，省略时用机器人的钱。confirm=false/省略仅查费，不支付；用户明确要求支付后才用 confirm=true。付款者与目标必须同在该群，付款者须符合低犯罪值等条件，不能自保，不允许保释透支。

以上金额均返回两位十进制字符串，时间采用经济系统配置时区（默认 Asia/Shanghai）。仍由可信 MCP 调用方从消息上下文确认发起者身份，不得根据模型猜测选择消费他人的金币。抢劫响应保留旧字段，新增 rob_boost_used、victim_assaulted、injured_user_id（0 表示无人受伤）、injury、hospital_until、jail_until、crime，AI 应按实际受伤者和截止时间回复；不要把反打误说成目标被打。抢劫新增 display_text 提供幽默且不带双方余额/原始QQ号的回复，AI 应优先使用，不复述旧 target_balance_after/wallet_balance；结构化 ISO 时间应转成“今天/明天 HH:mm”一类可读格式，hospital_until 是24小时强制出院截止而非提前自动恢复时间。预期业务异常如 IN_HOSPITAL/IN_JAIL 以结构化 code 和 MCP isError=true 返回。

```json
{"name":"dmw_economy_status","arguments":{"user_id":234567}}
{"name":"dmw_economy_bail","arguments":{"group_id":123456,"user_id":234567,"target_user_id":345678,"request_id":"qq-bail-1","confirm":false}}
```

## 订阅与热力图超时修复记录（2026-11-04）

`dmw_subscribe_book` 对新订阅立即返回 `job_id`（以 `sub-` 开头）、`status=queued`、`done=false`，这只表示已受理，不表示订阅成功。使用现有 `dmw_get_update_job` 查询，直到 `done=true`；只有 `ok=true` 且 `status=completed` 才能确认订阅成功，`subscription_id` 为实际回执。已有订阅直接返回原回执，未完成的同群同书请求复用同一任务，避免超时重试创建重复订阅。

小说保护插件v1.0.2保留完整 `job_id`/`batch_id` 于工具参数、结构化结果和内部状态键，群聊回执不显示UUID，也不以截短编号查询或重试；界面仅显示书名、登记/补全状态。只有 `done=true` 才采用完成回执，订阅登记完成不代表正文补全完成。同一原消息中相同的排队进度仅发一次，后续真实完成/失败状态仍可更新。应用重启会清除内存任务，遇到过期任务先按群与书查询已保存订阅，不应重新认定未订阅或盲目创建新写请求。

书籍详情获取在后台完成，订阅写入后立即确认；新书的目录和正文补全另行提交，`data_job_id` 指向数据更新任务，也使用 `dmw_get_update_job` 查询。补全任务未提交时会返回 `data_update_required=true`，可以显式调用 `dmw_update_book`。订阅任务不持有图表/章节的全局写锁。任务记录仍为内存状态，服务重启后应先查询本群订阅，再决定是否重新提交。

`dmw_get_update_heatmap` 只读取现有数据库统计并返回 PNG，不触发正文下载，也不等待其他章节任务的写锁。返回摘要包含：

- `source=dmw_cached_statistics`
- `data_complete`：当前统计窗口内字数是否齐全。
- `missing_word_count_chapters`：有目录记录但字数未知的章节数。

`dmw_get_update_stats` 同样返回缺失字数的总数及每日数量。字数未知不能解释为当天没有更新；需要补齐时另行调用后台更新。图上也会标注字数缺失，避免将不完整统计当作完整统计展示。

图片先在内存中完成编码，保存 `temp/images` 缓存仅为可选步骤；缓存写入失败记录完整警告，仍返回已生成的 PNG。QQ 图表命令会记录生成/发送异常及群号、书号，便于定位问题。

AI 必须区分官方图片与替代图：工具失败时不能声称官方热力图生成成功；如另行用 Python 绘图，应说明这是基于已获取统计生成的替代图，并说明数据覆盖范围。工具目录已明确这些调用与回复规则。


生产验证结果：已部署到香港 DMWS，修正缓存目录归属为运行用户 www；AstrBot 已重新连接 DMW MCP 并加载新工具说明。从 AstrBot 容器内使用其已安装的 MCP SDK 调用 dmw_get_update_heatmap，0.833 秒返回 18,215 字节 PNG（1000×340），data_complete=true，missing_word_count_chapters=0；重复订阅返回原 subscription_id=8，数据库仍只有一条对应订阅。没有向 QQ 群发送测试消息。

本轮 57 项 Java 测试及 18 项 Python 测试通过；生产 health/liveness/readiness 均 UP，账本净额校验一致，退役旧表未重建。服务器回滚资料位于 `/root/dmws-backups/heatmap-fix-20261104-185854`，保存旧 Jar、配置、数据库与验证结果；数据库和验证资料已另存至本机用户目录 `.codex/backups/dmws/heatmap-fix-20261104-185854.tar.gz`，校验和一致。


## 颜色与正文字符数口径修正

新版热力图按确认反馈改为 1000×292 紧凑布局：书名与日期一行，章节/正文字符数/更新天数一行，采用 GitHub 暗色标准四档绿色，移除分栏卡片和重复标题，图例合并为“少 + 五个方格 + 多”，不另加底色或底部说明。长标题自动缩小并在必要时省略，避免越界；字符数暂缺仍在顶部统计中提示。正文字符数含标点和空白，不等同于平台官方字数。

颜色改为仅按当前 365 天窗口内、有更新且字符数完整的每日正文字符数计算四分位数（排序后线性插值）。四档绿色依次对应 `字符数 ≤ Q25`、`Q25 < 字符数 ≤ Q50`、`Q50 < 字符数 ≤ Q75`、`字符数 > Q75`；章节数不再参与强度计算。相同字符数始终同色，重复阈值允许空档，所有更新量相同时使用第一档绿，不为凑齐四档拆散相同值。等级 0 只用于没有章节更新的日期；字符数全部或部分暂缺的日期保留一档绿色，不参与阈值计算，顶部统计及 MCP 摘要仍提示不完整状态。颜色表示本书窗口内的相对更新量，不适合跨书比较绝对更新量，窗口变化时阈值可能变化。

统计 MCP 新增 `total_characters`、每日 `character_count`，同时保留 `total_words`/`word_count` 兼容旧客户端；`count_metric=body_utf16_characters` 明确计量口径。图片摘要也带同一标记。

测试使用下载 MCP 与生产库逐章核对过的《摇曳同行》每日统计快照（80 章、165,792 个正文字符、24 个更新日），其 Q25/Q50/Q75 分别为 6,184.25、6,201、6,263.75，验证四档颜色及 2026-10-15、10-16 等最低更新量日期仍为绿色；另覆盖四分位边界、相同字符数不受章节数影响、异常高值、全部/部分字符数未知、无更新、单日/均匀数据、长标题和旧 MCP 字段兼容。用户确认后已上线，通过 AstrBot 内的 MCP SDK 调用生产工具，0.466 秒返回 16,151 字节 PNG（1000×292），统计与上述快照一致、data_complete=true；逐像素验证五格图例、四档绿色、无更新底色以及底部说明已移除。图片 SHA-256 为 5b23b26f2fbc05bc758d966e1e992345b75f2257c6853623d9f1365bca11c2e6。

上一批（20:45）发布 Jar SHA-256：86730e9dd1975db47481790c02188c1d76fbd0f1bb5d3b6b23a496d7d102a0f8。回滚及精确恢复资料见 docs/economy.md 的发布记录，不能只替换不兼容的旧 Jar 或忽略历史 JSON 浮点恢复补充脚本。

21:16 抢劫文案/位置/手动出院版本 Jar SHA-256：25e422cf0f152185caad89f0858af362eb87dd5dd816955e3ae7569c8f569877。本地 Java clean verify 110 项、Python 24 项通过；真实 MySQL 演练覆盖 4 小时仍住院、24 小时强制出院及重复查询不重扣费。AstrBot 已重新连接并加载23工具，生产验证查询机器人与示例玩家位置均成功，无余额或原始 ISO 时间出现在位置 display_text。

22:13 昵称与简洁回执版本 Jar SHA-256：5ec7bf76f7570981a84293cba1488dcf107e1bfe15c936381efd3cad3bfc5f8c。本地119项Java、24项Python测试通过，生产验证四类查询均能解析昵称，结构化ID保持兼容。没有为验证支付保释费或办理玩家出院。


## 群聊经济操作的可信身份与回执

经济写工具（buy/use_item/transfer/rob/discharge/bail）必须由 `scripts/astrbot_dmw_guard` 在调用前注入 `_trusted_context`。它是 base64url(JSON) + `.` + HMAC-SHA256 十六进制签名；JSON 包含 group_id、bot_id、sender_id、message_id、issued_at、tool。签名仅有效五分钟，服务器检查机器人、群、工具及执行者，执行者只能是本次发送者或机器人。没有签名、签名过期或试图操作第三者均拒绝执行。密钥来自 `dmws.mcp.economy.context-secret` 与 AstrBot `/AstrBot/data/dmw-economy-context.key`，必须保持一致；不得把密钥放入提示词、MCP Schema 或 Git。

转账、抢劫、道具使用、出院、保释省略 user_id 均使用机器人自己的账户；代办传入真实消息发送者。购买默认本次发送者。查询省略 user_id 也使用机器人。原 operator-id 已弃用，不再给 AI 使用管理员账户的权限。

原生 QQ 与 MCP 使用共同的原始消息/群/执行者/动作/道具去重键，数据库只允许 INSERT 预留操作，不能以 merge 覆盖已有操作。一个消息可以购买不同道具，但同一道具不能靠更换 request_id 或数量重复执行；需要再次购买应发新消息。超时不能认定失败未扣款，也不能认定成功，重试必须保留原消息上下文。

所有经济业务错误统一 ok=false、MCP isError=true，提供 structuredContent。AstrBot 保护插件记录本次调用的成功、失败与未知状态，最终发送真实 display_text/message 回执；部分成功逐项显示，失败与超时不能被模型改写成成功。插件只改变正常回复的内容，不另行主动发送群消息。


## 下载器 v0.3.0 适配（2026-11-05）

接入依据：[固定版本客户端契约](https://github.com/luqing233/tomato-downloader-mcp/blob/5abe455b964115bbc69e614223f17d208872a603/docs/CLIENT_INTEGRATION.md)。下载器本身的 24 个 `tnd_*` 工具与 DMW 的 29 个工具是两套服务，不要混用数量和任务 ID。

新增 `dmw_get_chapters`、`dmw_check_chapters`、`dmw_get_download_batch`、`dmw_cancel_download_batch`、`dmw_retry_download_batch`、`dmw_get_downloader_diagnostics`。正文统一由 `TomatoReadingService` 处理：先读 DMW 完整缓存，再按 item_id 读取下载器本地正文；交互调用不轮询等待。`download_missing` 默认 false，仅在用户明确允许下载时置 true；缺失项入队返回批次 ID。`dmw_get_chapter_content` 保留旧字段，但默认只读，新增 download_missing；缺失不再同步等待三分钟。QQ 指令为 `获取章节内容 <订阅ID> <真实目录序号> [下载]`，省略“下载”不启动任务。

```json
{"name":"dmw_get_chapters","arguments":{"book_id":"7468131969230982206","item_ids":["7468207965556720190","7468210797106168382"],"download_missing":false,"max_total_chars":10000,"include_forward_nodes":true}}
```

检查 `status`、`complete`、`missing_item_ids`、`download_error`、`refresh_required`、`truncated` 及每章截断标记，不能只看 MCP isError。可用数量与实际返回字符数不同；可用但预算耗尽的章节仍列出截断标记，不能当作未下载。预算 max_total_chars 为 1–1000000，禁止用 0 表示无限制；单章 0–200000，0 只表示没有单独限制。DMW 已知完整正文的字符数口径仍为 UTF-16 字符数，含标点空白；上游已截断时完整字符数未知，不把片段保存进完整正文缓存。

`include_forward_nodes=true` 只准备可用正文的纯文本节点，不发送 QQ。节点最多 100 个，总文本最多 49000 字符，检查 forward_truncated/forward_complete；只有用户要求合并转发时才调用现有发送工具，并将缺失、排队与发送未获确认分别说明。原生 QQ 获取章节使用同一读取与格式化服务，合并转发以 text 消息段传递字面正文，不解析正文中的 CQ 指令；发送结果不明确时不自动重发。

下载跟踪始终使用明确 batch_id；终态后按原 item_ids 重读，不逐章创建任务，不把目录 order、下载器 index 或数值 ID 排序当成同一定位方式。后台补全仍可以等待一个批次，但不会再逐章下载兜底或逐章查询发布时间；目录元数据只使用已读取的映射。更新任务的 partial 现在同时返回 missing_item_ids。

取消和重试保留现有小说写操作的 master + confirm=true 要求。canceling 是请求已保存，canceled 才确认批次停止；cancel_error 保留。重试后以新 batch_id 跟踪并保留 retry_of；BATCH_ACTIVE/BATCH_BUSY/BATCH_NOT_FOUND 不自动重试。诊断返回 diagnostics_ok 与原始 diagnostics，负结果表示检查异常，不当作 HTTP 故障。

`scripts/astrbot_tomato_guard` 独立安装到 AstrBot，保护 `tnd_*` 与 DMW 小说读取/批次工具的最终回执：部分内容、排队、取消中、取消信号失败、超时及新重试 ID 均按工具真实结果显示。该插件不处理经济工具，也不主动发送群消息。McpClient 兼容无状态无 session header 的服务及旧的有状态服务；HTTP/SSE 只接受匹配 JSON-RPC ID 的响应，保持串行保护与递增唯一 ID。对结果未知的写请求不自动重放；只有旧会话被 404 明确拒绝时才重建会话。

小说回执插件同时从 `/AstrBot/data/dmw-tomato-policy.json` 读取与 DMW 配置同步的 masters，按真实消息发送者约束取消/重试；模型传入的 operator 会被真实发送者覆盖。非主人直接调用下载器取消/重试也会在请求发出前移除必要参数，使服务端拒绝写入，最终显示权限回执。该策略只处理小说批次；独立持有下载器 Bearer 凭据的客户端仍须自行约束其调用者。

修改 DMW master 名单后，需要同步 AstrBot 的 dmw-tomato-policy.json 并重载小说插件；独立下载器 Bearer 凭据仅应交给可信客户端。


生产验证记录：2026-11-05，158 项 Java 与 41 项 Python 测试全部通过。发布 Jar SHA-256 `dfd2feac37cf80cdec461635464de6b15b5cfe67af224f06ad2873d30118698e`；123 个经济相关 class 文件与上一包逐字节一致。停写备份恢复后 34 张表精确校验一致，启动时及验证后的经济快照未变，应用与 MCP 连接配置未变。生产两章缓存正文返回 4142 个字符，首次读取约 245 毫秒；旧单章字段、截断标记、缺失只读、可用性检查、诊断与错误批次 ID 均通过实际请求验证。真实发布包的 Java 客户端已直连无状态下载器验证本地正文与错误回执；AstrBot 小说插件 v1.0.1 的实际钩子验证了取消中、部分失败和真实发送者权限。没有向 QQ 发送验证消息，也没有取消或重试任何生产下载批次。DMW 29 工具与下载器 24 工具均正常连接，worker 最近心跳 online。备份目录 `/root/dmws-backups/tomato-v030-20261105021821`，保留历史 JSON 精度恢复伴随脚本。


2026-11-08战斗规则更新：电棍与红牛可同次各生效一次，合计增加40个百分点，同种不可重复叠加。
rob_boost_used现在明确表示本次是否消耗红牛增益，electric_baton_used/electric_baton_energy仍表示电棍使用和剩余电量；
两者可以同时为true。原工具名、输入字段与HMAC消息校验保持不变，空钱包不消耗两种增益。
武力在抢劫与鳄鱼搏斗共用；警察条件介入率随犯罪值上升。犯罪值每完整60分钟自然减少5至0，
离线计时、锚点持久化，旧按日衰减配置不再使用。医疗与保释工具继续操作同一持久住院/局子状态。
钓鱼目前由原生QQ入口提供；四行钓鱼回执由共享EconomyFishingText格式化，未新增未受保护的MCP写入口。


## 钓鱼商店分类（2026-11-08）

`dmw_economy_shop`增加可选字符串参数category：general（默认，普通道具，不含鱼饵）、fishing（六档幸运鱼饵）。
结果新增category，items/count及既有字段保留。原生“钓鱼商店”另列鱼竿购买、升级入口。
示例：`{"name":"dmw_economy_shop","arguments":{"category":"fishing"}}`。
购买仍使用dmw_economy_buy，item可传“幸运鱼饵（红）”或编码，quantity单位为包，每包5次。
无品质的“幸运鱼饵”名称默认白色；旧编码fishing-luck-bait指蓝色。原库存保留，旧激活次数只按原次数转移。
使用鱼饵会启用对应包数×5次并设为默认品质；正常钓鱼也会自动拆包。钓鱼不另扣固定费用。
本次不增加钓鱼写工具；原生支持“设置鱼饵 蓝”“当前鱼饵”“钓鱼 红”。HMAC身份、原消息去重及购买默认请求者规则保持。
## 模拟外汇工具（初版已部署，补仓扩展待部署）

新增的add_margin工具、margin_call_deadline返回字段与风险提醒流程目前仅在本地完成，部署需同步数据库迁移和AstrBot guard。

所有行情为服务器模拟，四个货币对 EUR/USD、GBP/USD、USD/JPY、USD/CHF 每10分钟统一更新。
`fresh=false`表示当前时段没有可用新行情，普通开仓/平仓暂停；已有补仓通知的仓位仍按触发快照处理，查询不生成行情。游戏规则见[economy.md](economy.md)。

| 工具 | 参数 | 返回data |
|---|---|---|
| `dmw_economy_fx_market` | 无 | `source=SIMULATED, interval_minutes=10, sequence, quoted_at, fresh, pairs`；pairs含`pair,name,price,change`，change为本轮收益比例 |
| `dmw_economy_fx_positions` | 可选`user_id`，省略用bot | `user_id,quoted_at,fresh,margin,floating_pnl,trading_debt,trading_equity,positions` |
| `dmw_economy_fx_history` | 可选`user_id`，省略用bot | `user_id,records`，最近20笔结算 |
| `dmw_economy_fx_open` | `group_id,pair,side,margin,leverage,request_id`；可选`user_id` | `position_id,amount,trading_debt`；amount为本次保证金+手续费 |
| `dmw_economy_fx_close` | `group_id,position_id,request_id`；可选`user_id` | `position_id,amount,trading_debt`；amount为返还钱包金额 |
| `dmw_economy_fx_repay` | `group_id,amount,request_id`；可选`user_id` | `amount,trading_debt`；amount为实际还款金额 |
| `dmw_economy_fx_add_margin` | `group_id,position_id,amount,request_id`；可选`user_id` | `position_id,amount,trading_debt`；amount为本次追加保证金，逾期未扣补仓款时为0 |

写入默认actor为bot，只有明确替当前请求人操作时才传可信消息对应的`user_id`，不能使用master或其他群员。
`side`只能为`long/short`；`leverage`为1～100整数；`margin`为最多两位小数的正数字符串；还款`amount`还支持`all`。
读结果中的资金、价格、盈亏和涨跌比例均为十进制字符串。仓位字段：`position_id,pair,side,status,margin,leverage,notional,opening_fee,entry_price,current_price,pnl,net_profit,opened_at,closed_at,close_receipt,margin_call_deadline`。
`status`为`open/closed/liquidated`；`net_profit=pnl-opening_fee`。历史记录`current_price`是平仓价，历史close_receipt不重算。
`trading_equity`不含钱包和银行；交易欠款与医疗欠款分开，不得把负债账户余额说成可花金币。

四项写工具都必须经`EconomyMcpContext`验证HMAC，由`scripts/astrbot_dmw_guard`注入真实消息来源。
原生和MCP共用`fx-open`（空detail，每条来源消息每个操作者最多开一仓）、`fx-close`（仓位编号）、`fx-repay`和`fx-margin`（空detail）的原消息幂等键。
改变request_id、货币对或方向不能重复利用同一来源消息开仓。操作号命中但规范化业务参数不同返回`OPERATION_CONFLICT`，不会把旧回执说成新参数成功。
不接受模型价格、种子或另一个人的仓位。成功直接使用`display_text`；失败不改写成成功，超时先查询持仓/记录，不盲目重试。
同消息同参数重复写返回已保存动作回执，不代表又执行一次；另一个请求关闭已结算仓位返回明确的“未重复收付”，本次`amount=0`。
输入仍是保证金，名义仓位=保证金×杠杆，补仓只增加保证金，notional/entry_price/opening_fee保持不变。
60%亏损提醒，80%亏损进入最多5分钟补仓期。`margin_call_deadline`非null时使用锁定的触发价格计算本仓浮盈亏；
补仓超时、拒绝或不足按触发价强平。仓位状态仍为open直到真正结算。新开仓暂停时补仓仍可执行，限额不变。
开仓保存可信group_id作为风险提醒群；通知发送在资金事务提交后独立执行，不能用发送失败推断交易失败。
常见错误：`MARKET_DISABLED, MARKET_NOT_READY, MARKET_STALE, MARKET_OPEN_PAUSED, MARKET_DEBT_OUTSTANDING, MARKET_POSITION_LIMIT, MARKET_DAILY_LIMIT, INVALID_PAIR, INVALID_MARGIN, INVALID_LEVERAGE, POSITION_NOT_FOUND, NO_MARKET_DEBT, INSUFFICIENT_BALANCE`。
