# 钓鱼规则与数据库维护

鱼种与特殊事件也可通过[投稿与审核网页](fishing-web.md)管理：公开投稿，管理员审核后入池；管理员可编辑现有鱼种、尼罗鳄、尸体等事件及其文案。网页功能已完成本地验证，尚未部署生产。下文仍保留数据库手动维护方式。

发送 `钓鱼`、`抛竿` 或 `钓鱼 红` 后，系统随机安排在 20～60 秒后收竿。同一玩家跨群只能同时等一竿。第一竿就使用完整的事件池。收竿结算后可马上抛下一竿，`钓鱼状态` 可查看等待状态或最近一次回执。

抛竿保存鱼竿等级、鱼饵品质、是否允许自动购饵及原消息。收竿才抽取结果和扣鱼饵，读取当时生效的鱼种、事件、鱼饵规则和实际商品价格。如果等待中资金、库存或玩家状态发生变化，导致无法结算，本次不扣鱼饵。收竿任务可在重启后继续处理，账本提交后才发送群回执。

每竿同时保存私有随机种子。事务失败后自动重试使用同一组随机输入，进程重启也不会重新抽签。种子不进入用户回执或日志。升级迁移会为已有的待收竿任务补齐种子。规则参数和玩家状态仍在收竿时读取，维护期间修改这些值会影响尚未完成的任务。

抛竿成功随机回复“这个池子里面真的有鱼吗？”、“抛得挺远，鱼能看见吗？”或“这次谁来咬钩？”。等待中重复抛竿回复“你已经在钓鱼了。”。脱钩时选取本群最近12小时内、除本人以外最近一次完成出钓的玩家，以其名片、昵称或档案名给出估价区间内的随机报价。没有符合条件的钓友时显示“你四周张望，应该没有人看到吧”。报价仅为叙事，不产生金币收付，最终文案随收竿回执保存。

抛竿、收竿、错误原因和状态提示直接回复原消息。图鉴和较长的历史记录使用合并转发，按条目保留尺寸、价格、介绍或钓获时间。转发失败时按有标题的页面回复，字段和说明完整保留。鱼饵状态等短查询直接显示，所有钓鱼查询都不触发开户。

## 手动新增鱼种

升级步骤见下文“旧数据升级”。下面示例使用 `dmw` 库。新增鱼种只需要写 `fish`，无需新建事件或加入每种鱼饵的池表。建议用唯一名称，`id` 由数据库分配。

```sql
INSERT INTO dmw.fish
    (name, description, level, price, difficulty, dimensions_min, dimensions_max, special, enabled)
SELECT
    '银鳞鲤', '{player}遇到了{fish}，本竿售鱼收入{reward}金币。', 3, 120, 45, 20, 45, FALSE, TRUE
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM dmw.fish WHERE name = '银鳞鲤');

SELECT id, name, level, price, difficulty, dimensions_min, dimensions_max, special, enabled
FROM dmw.fish
WHERE name = '银鳞鲤';
```

- `level` 至少为 1。3 级鱼从 2 级鱼竿起进入抽取池。一般新增给所有玩家可解锁的鱼种时，建议不要高于 101 级。
- `price` 填正整数基础价。实际售价还乘以尺寸倍率与鱼竿倍率，尺寸固定时尺寸倍率为 1。
- `difficulty` 填非负整数。越高越难钓，普通鱼的基础抽中权重也越低。
- `dimensions_min`、`dimensions_max` 是正整数厘米，最大值不低于最小值。两值相同就是固定尺寸。
- `special = TRUE` 表示进入彩蛋分布。`enabled = FALSE` 会从出钓和图鉴中停用，历史渔获仍保留。默认种子会补回被删除的内置条目，停用请改 `enabled`。

提交后，新配置会在下次读图鉴或收竿时生效。请用查询返回的 ID 精确修改或停用鱼种。

## 三类事件与参数

`economy_fishing_event` 只使用以下三种 `kind`。事件替代这一竿的普通鱼，不额外叠抽另一事件。纯剧情使用金额为零的 `MONEY`。

| kind | 效果 | 参数 |
| --- | --- | --- |
| `MONEY` | 正数发金币、负数扣金币、零只显示剧情 | `amount_min`、`amount_max`、`amount_step` |
| `COMBAT` | 战斗，胜利获奖，失败或胜利受伤时入院 | 金额上下限、武力范围、胜利受伤概率、失败伤势权重 |
| `JAIL` | 进入监狱/警局调查，不额外增减犯罪值 | `jail_minutes`，1～30分钟 |

金币区间两端均包含。比如 `[-20,80]`、步长 `1` 有101个等概率结果；`[-0.02,0.02]`、步长 `0.01` 有5个结果。两端相同就是固定金额。步长必须为正、最多两位小数，区间宽度须为步长的整数倍，最多1000万个间隔。金额不能超过系统单笔金额上限。扣款先钱包、再主银行；不足部分计入统一欠款，钱包不变成负数。

战斗的 `strength_min`、`strength_max` 在1～100且下限不大于上限。`amount_min`、`amount_max` 为非负胜利奖金；随本次随机敌方武力线性计算到分，同武力上下限时取最高奖金。`amount_step` 仅用于区间校验，战斗奖金不按金币抽签步长抽取。`win_injury_percent` 是胜利受轻伤的百分比；失败时 `light_percent`、`medium_percent` 是轻、中伤百分比，重伤为 `100-轻-中`。三者范围合法且轻中之和不超过100。医疗账单仍出院另结。红牛、电棍和共用武力成长规则沿用原规则。

## 事件抽取权重

- `pool_code` 是分组名，和效果类型独立。旧 `FUN`、`REWARD` 继续作为分组名，里面的事件类型现在都是 `MONEY`。
- `tickets` 是非负整数权重。`enabled=FALSE` 或 `tickets=0` 不参与抽取。
- `economy_fishing_pool_rule` 决定各鱼饵抽哪些组；每种鱼饵的 `pool_order` 从0连续编号。组内顺序由事件 `code` 排序。
- `ticket_budget` 非空：固定占每竿 `ticket_budget/10000` 的区间，组内再按各事件的 `tickets` 比例抽取。空组回落到普通鱼。
- `ticket_budget` 为空：每条事件的绝对票数为 `round(tickets*ticket_scale_bps/10000)`；每竿分母10000。各组占用总票数不得超过10000，剩余概率抽普通鱼。
- 默认白～金饵只抽 `NEGATIVE`、`COMBAT`，缩放100%/90%/80%/70%/60%。红饵固定 `FUN`800票、`REWARD`600票、`COMBAT`400票、`NEGATIVE`200票。新增 `FUN` 事件默认只被红饵抽取。
- `economy_fishing_bait_rule` 存成功率、高级鱼权重及彩蛋倍率，10000基点等于100%。鱼饵价格由 `economy_item_definition` 管理。

新增事件用已有分组最简单。自建 `pool_code` 必须在所需品质的池规则中加一行，否则不会被抽中。不要为了统一 `kind` 把所有金币事件并到一个分组，这会改变原有鱼饵的概率。

## 手动添加事件和随机文案

先选择应用使用的经济数据库。以下示例为一个可赚可亏的金币事件，加入所有鱼饵都支持的 `NEGATIVE` 分组（组名不限制金额正负）：

```sql
START TRANSACTION;
INSERT INTO economy_fishing_event
  (code,name,description,kind,pool_code,tickets,amount_min,amount_max,amount_step,enabled)
VALUES ('wet-purse','湿钱袋','一只沉甸甸的钱袋。','MONEY','NEGATIVE',10,-20,80,1,TRUE);

INSERT INTO economy_fishing_text (code,owner_code,scene,template,weight,enabled) VALUES
  ('wet-purse.intro.1','wet-purse','ENCOUNTER','{player}捞起了{event}。',3,TRUE),
  ('wet-purse.intro.2','wet-purse','ENCOUNTER','鱼没钓到，倒捞到了{event}。',1,TRUE),
  ('wet-purse.desc.1','wet-purse','DESCRIPTION','钱袋的主人似乎就在附近。',1,TRUE),
  ('wet-purse.gain.1','wet-purse','MONEY_GAIN','袋子里有{reward}金币，归你了。',1,TRUE),
  ('wet-purse.loss.1','wet-purse','MONEY_LOSS','失主要求赔偿，你损失{penalty}金币。',1,TRUE),
  ('wet-purse.zero.1','wet-purse','MONEY_ZERO','钱袋是空的。',1,TRUE);
COMMIT;
```

上面两句开场的概率是3:1，与事件 `tickets=10` 相互独立。如需只有奖励，改 `amount_min=20,amount_max=80`；只有扣款可用 `-80,-20`；纯剧情用 `0,0`。已存在的 `code` 用 `UPDATE`，不要重复执行这段 `INSERT`。

[可重复执行的完整示例](../scripts/fishing_event_examples.sql)包含金币、战斗、监狱三个事件及文案。执行会真实新增三个启用事件，请按需选取。示例 SQL 已在测试数据库执行两次并验证三类结算。

`economy_fishing_text` 字段：`code` 是唯一文案编号；`owner_code` 填事件编号，`*` 是全局通用；`scene` 是下面的场景码；`template` 是正文（非空，最多4000字符）；`weight` 是非负整数相对权重；`enabled` 决定是否启用。选中事件自己的同场景启用文案时，只在这些文案里抽签；没有任何启用行才使用全局同场景文案。启用行的权重全部为0属于配置错误，不会悄悄回退。停用请改 `enabled=FALSE`，不要删除内置行，以免启动时补回。

| scene | 使用场景 |
| --- | --- |
| `ENCOUNTER`、`DESCRIPTION` | 事件开场、描述，可各配多条 |
| `MONEY_GAIN`、`MONEY_LOSS`、`MONEY_ZERO` | 金币事件正、负、零结果 |
| `COMBAT_WIN`、`COMBAT_LOSS` | 战斗成功、失败的结果文案 |
| `INJURY`、`INJURY_WIN`、`INJURY_LOSS` | 通用、成功住院、失败住院文案，只有实际受伤时显示 |
| `JAIL`、`DEBT` | 入狱/调查、附加欠款 |
| `BOOST_RED_BULL`、`BOOST_BATON` | 实际消耗的战斗增益通用文案；追加 `_WIN`/`_LOSS` 配置成功/失败专属内容 |
| `COMBAT_FLOW`、`COMBAT_WIN_FLOW`、`COMBAT_LOSS_FLOW` | 通用/成功/失败叙事顺序，4个片段各出现一次，可含一次 `{branch}` 边界，网页支持拖动 |
| `BAIT_PURCHASE`、`BAIT_EMPTY_DEFAULT`、`BAIT_EMPTY_OTHER` | 当竿自动购买、两种耗尽提示 |
| `RECEIPT` | 最终四行布局，必须包含 `{encounter}`、`{description}`、`{outcome}`、`{net}` |
| `CATCH_ENCOUNTER`、`CATCH_RESULT`、`ESCAPE_ENCOUNTER` | 普通鱼上钩、卖鱼、脱钩 |
| `ESCAPE_ESTIMATE`、`ESCAPE_ALONE` | 有近12小时钓友报价、无人见证 |
| `CAST`、`BUSY`、`WAITING`、`NO_CAST` | 抛竿、重复抛竿、等待、无记录（全局） |
| `REEL_FAILED`、`REEL_REJECTED` | 收竿失败、因状态等原因拒绝结算（全局） |
| `ROD_REQUIRED`、`POOL_EMPTY`、`INVALID_EVENT`、`INVALID_BAIT_RULE`、`INVALID_TEXT` | 对应配置/前置条件错误（全局）；文案错误提示自身不可用时使用保底提示 |

修改现有描述要改 `economy_fishing_text` 的 `DESCRIPTION` 行。事件旧 `description`、`encounter_template` 只作兼容来源；启动时仅在事件完全没有对应场景行时迁入，不覆盖管理员文案。普通鱼介绍仍直接编辑 `fish.description`。

```sql
-- 查看某事件及其文案
SELECT * FROM economy_fishing_event WHERE code='wet-purse';
SELECT code,scene,template,weight,enabled FROM economy_fishing_text
WHERE owner_code IN ('wet-purse','*') ORDER BY owner_code,scene,code;
-- 调整开场比例或停用某句
UPDATE economy_fishing_text SET weight=5 WHERE code='wet-purse.intro.1';
UPDATE economy_fishing_text SET enabled=FALSE WHERE code='wet-purse.intro.2';
-- 修改全局抛竿文案；若只保留一句，停用另外两句
UPDATE economy_fishing_text SET template='这次会钓到什么呢？' WHERE code='global.cast.1';
-- 停用事件，保留历史记录
UPDATE economy_fishing_event SET enabled=FALSE WHERE code='wet-purse';
```

战斗结果前的红牛、电棍、住院片段只读取通用场景；结果后才读取对应的 `_WIN`/`_LOSS` 专属场景，缺少专属场景时回退通用。判断基于实际布局里 `{combat_result}` 的位置，`{branch}` 不改变这一规则。旧专属文案不会因移动到前方而删除。

配置在下次读取/收竿时生效，不需要改 Java 或重启。已完成回执、抛竿提示保存原文，修改模板不会改写历史。文案随机序列与数值随机序列隔离；增删句子、改变文案权重不会改变这一竿的金额或战斗随机输入。

## 占位符

只替换一次，不执行表达式、SQL 或模板里的代码。玩家名字中的 `$1`、反斜线或 `{reward}` 均按名字原样处理，不会再次替换。未知占位符原样保留，拼写错误不会被吞掉；请用下表检查。回执的单行内容会清理换行，最终布局必须正好四行。金额来自结算实际值，改文字不会改账本。

| 占位符 | 取值 |
| --- | --- |
| `{player}` | 抛竿保存的显示名；`CAST` 也可使用 |
| `{fish}`、`{event}` | 鱼种或事件名称 |
| `{size}` | 实际厘米数；脱钩/事件无尺寸为 `—` |
| `{reward}`、`{penalty}` | 非负奖励、即时损失，保留两位小数 |
| `{amount}`、`{change}` | 奖励或损失的绝对金额、带符号的事件金额变化 |
| `{bait_cost}`、`{net}` | 实际自动购饵花费、奖励减购饵减损失的带符号净额；零为 `0.00` |
| `{minutes}` | 监狱/调查分钟数，其他为0 |
| `{injury}`、`{injury_score}` | 轻伤/中伤/重伤、伤势分值；无伤为留空/0 |
| `{borrowed}` | 本次扣款新增欠款；不是累计总债务 |
| `{baton_energy}` | 本次使用后电量，未使用为 `—` |
| `{bait}`、`{bait_color}` | 鱼饵完整名称、品质中文名 |
| `{witness}`、`{estimate}` | 近12小时其他钓友显示名、随机估价 |
| `{min_value}`、`{max_value}` | 该鱼尺寸范围对应的实际售价上下限，事件为 `—` |
| `{description}` | 原始说明（描述模板中）、已渲染说明（最终布局中） |
| `{encounter}`、`{outcome}` | 已渲染开场、完整结果，仅用于 `RECEIPT` |
| `{combat_result}`、`{injury_notice}`、`{red_bull}`、`{baton}` | 仅用于 `COMBAT_FLOW`；结果、实际伤势、实际使用红牛/电棍的文案片段，各保留一次，未发生的片段为空 |
| `{reason}` | 结算被拒原因，仅用于 `REEL_REJECTED` |
| `{scene}` | 出错场景码，仅用于 `INVALID_TEXT`，默认不展示 |

普通鱼图鉴无当竿数值，金额等已知动态字段显示 `—`，玩家显示“钓鱼人”。支持的完整默认模板见 [fishing-texts.json](../src/main/resources/economy/fishing-texts.json)，它只用于首次补缺，运行时从数据库读取。

## 旧数据升级

1. 停止机器人，备份当前经济数据库。迁移不连接退休的旧经济库，不重新导入旧账本。
2. 在应用实际使用的经济库执行 [migrate_fishing_cast.sql](../scripts/migrate_fishing_cast.sql)，再执行 [migrate_fishing_events.sql](../scripts/migrate_fishing_events.sql)。MySQL DDL 会自动提交，备份应在执行前完成。
3. 检查第二个脚本最后的异常查询应无结果，然后启动新版。初始化事务补齐缺失的默认文案和鱼饵规则，也兼容已经由 JPA 增列、尚未转换的旧事件。脚本与启动迁移均可重复运行，不覆盖已转换的正负区间、自定义模板和权重。
4. 不要把 `fishing_event_examples.sql` 当升级脚本自动执行；它是可选新增内容。

| 旧 kind | 新 kind | 金额迁移 |
| --- | --- | --- |
| `FINE` | `MONEY` | `amount_min=amount_max=-penalty` |
| `FUN`、`REWARD` | `MONEY` | 原 `reward_min`～`reward_max` |
| `TEXT` | `MONEY` | 0～0 |
| `CROCODILE`、旧 `COMBAT` | `COMBAT` | 原胜利奖励范围 |
| `JAIL` | `JAIL` | 0～0，调查时长保留 |

整数上下限沿用步长1，小数范围用0.01。保存 `legacy_kind` 和旧金额列作为迁移前定义备份；运行时只读取新类型、新金额字段。已有 `pool_code`、票数、停用状态、战斗/监狱参数均保留。原描述、开场迁入独立文案行，保留占位符；已有同场景行（即使停用）不会被覆盖。历史鱼竿、渔获、事件记录、回执、账本均不改写；历史事件码仍可追溯。

2026-11-14 11:19:31（Asia/Shanghai）已部署。369项 Java、53项 Python 测试通过；MySQL 8.0.45 恢复副本两次迁移/启动一致，正负及跨零区间、20～60秒等待、重复抛竿、单次结算、战斗、调查和统一还款验证通过。正式停写备份的51张原表精确恢复核对通过，生产现有数据除预期事件类型转换外保持一致。当前55张表，金币11种、战斗1种、监狱1种，49条文案、6档鱼饵规则；health/liveness/readiness均UP，账本差额0，外汇交易仍关闭。没有向群发送测试消息或操作真实玩家。

发布 Jar SHA-256：`e57c309573e41dd20447ceebdbc113c71f897848c2cabe0f67930de31ef21bc8`。服务器备份为 `/root/dmws-backups/fishing-events-20261114111659`，包含原Jar、配置、数据库、历史JSON精确恢复补充脚本和演练/部署证据。迁移前的旧Jar不认识 `MONEY`：若上线后已有正常活动，不要直接换回旧Jar或覆盖恢复数据库，应先停写核对新增流水并使用兼容版本。

异机备份已下载到本机用户目录 `.codex/backups/dmws/fishing-events-20261114111659.tar.gz`，68,487,165字节，SHA-256为 `0639e51bf878e581194c6007d8bf6f0c5e75a35de411a071d8ed226810f047c1`，与服务器一致；包内30个原始文件逐一复核通过。MCP的36个工具可读取，AstrBot初始化连接成功，无连接失败记录。发布程序另存服务器 `/root/dmws-releases/dmws-fishing-events-20261114111931.jar` 和本机 `temp/releases/dmws-fishing-events-20261114.jar`。
