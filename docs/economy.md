# DMWS 经济系统

本模块参考 HuYanEconomy 的功能设计，在 `dmws` 内以 Java 21、Spring 和单一 MySQL 数据库重新实现。

## 架构

- 所有经济数据位于主 `dmw` 数据源，事务管理器为 `dmwTransactionManager`。
- 账户、流水和幂等操作分离：
  - `economy_account` 保存当前余额。
  - `economy_ledger_entry` 保存每次余额变化。
  - `economy_operation` 以唯一操作号保证命令幂等。
- 金额统一使用 `BigDecimal`、`DECIMAL(19,2)` 和 `HALF_UP`。
- 转账按账户键排序后加悲观锁，避免死锁和并发丢失更新。
- 资金命令不使用 `@Async`，业务状态和资金变化在同一事务中提交。

## 账户

| 范围 | 用途 |
|---|---|
| `WALLET` | 用户钱包 |
| `MAIN_BANK` | 用户主银行 |
| `PLUGIN` | 私人银行资金等插件账户 |
| `GLOBAL` | 系统级资金账户 |

用户账户键为 `u<QQ号>`，私人银行资金账户键为 `bank:<code>:capital`。

## 指令

### 用户与签到

- `签到`
- `经济信息`
- `我的称号`
- `切换称号 <序号或编码>`

### 银行与转账

- `存款 <金额>`、`存款!`
- `取款 <金额>`、`取款!`
- `转账 @用户 <金额>`
- `富豪榜`

### 道具与称号

- `道具商店`
- `购买 <道具> [数量]`
- `使用 <道具> [数量]`
- `我的背包`
- `称号商店`
- `购买称号 <称号>`

### 红包与抽奖

- `发红包 <金额> <个数> [随机] [口令]`
- `抢红包 <编号> [口令]`
- `红包列表`
- `抽奖`
- `十连`

### 私人银行

- `银行创建 <编码> <名称>`
- `银行列表`
- `银行信息 <编码或名称>`
- `存银行 <编码或名称> <金额>`
- `取银行 <编码或名称> <金额>`
- `银行贷款 <编码或名称> <金额> <天数>`
- `银行还款 <编码或名称> <金额>`

### 钓鱼

- `钓鱼`
- `升级鱼竿`
- `鱼竿等级`
- `钓鱼记录`

### 抢劫

- `抢劫 @用户`

### 农场

- `农场`
- `农场商店`
- `播种 <作物> <地块>`
- `浇水 <地块>`
- `收获 <地块>`

### 管理员

- `发钱 @用户 <金额>`
- `扣钱 @用户 <金额>`

管理员指令只允许 `dmws.master` 中的 QQ 号使用。

## 定时任务

- 每周一 04:00 结算主银行利息。
- 每天 04:00 处理私人银行逾期贷款。
- 每分钟处理过期红包退款。

所有任务按 `Asia/Shanghai` 时区执行，并通过操作号避免同一周期重复结算。

## 配置

配置前缀为 `dmws.economy`，示例：

```yaml
dmws:
  economy:
    enabled: true
    time-zone: Asia/Shanghai
    sign-min: 50
    sign-max: 500
    bank-weekly-rate: 0.005
    raffle-price: 1000
    raffle-ten-price: 10000
```

主数据源配置放在 Git 忽略的 `src/main/resources/application.yaml`。项目不会把它打进
jar，只通过 `src/main/resources/application.properties` 中的可选本地导入供 IDE 和
`spring-boot:run` 使用。生产部署时可通过外部 `application.yaml`、`config/application.yaml`
或环境变量提供数据库配置。

## 测试

运行经济模块测试：

```bash
./mvnw.cmd "-Dtest=Economy*Tests" test
```

覆盖并发转账、新账户创建竞态、重复操作、签到幂等、抽奖库存、红包拆分、私人银行借贷、钓鱼、抢劫和农场。

## 来源说明

功能设计参考 `Moyuyanli/HuYanEconomy`。该项目根目录的 `LICENSE` 文件为 Apache-2.0；如继续复制其具体实现，应保留来源和许可证说明。
