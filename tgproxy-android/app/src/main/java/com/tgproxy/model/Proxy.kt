package com.tgproxy.model

/** 代理类型：SOCKS5 / MTProto（桌面版仅保留这两种 Telegram 可用类型）。 */
enum class ProxyType { SOCKS5, MTPROTO }

/** 代理连通性状态（TCP 预筛阶段）。 */
enum class ProxyStatus { AVAILABLE, UNAVAILABLE }

/**
 * TG 实测结论：true=两关通过；false=未通过（含超门槛）；
 * null=未参与实测（MTProto）。
 */
enum class TgVerdict { PASS, FAIL, UNVERIFIED }

/**
 * 代理条目（与桌面版 parse_proxy_text 产出的字典字段一一对应）。
 *
 * @property link 完整链接：SOCKS5 为 `socks5://host:port`；MTProto 为数据源原样重建的完整分享链接
 * @property host 严格校验后的 IPv4 文本（保留原始形态，如含前导零）
 * @property port 端口（1~65535）
 * @property type 代理类型
 * @property latencyMs 经代理到 Telegram 数据中心的往返毫秒（TG 实测通过后回填）；失败为 null
 * @property status TCP 预筛状态
 * @property tgVerdict TG 实测结论（UNVERIFIED=MTProto 未参与）
 * @property location 地区（元数据）
 * @property isp ISP（元数据）
 * @property addedAt 入库时间（元数据，形态 `MM-dd HH:mm`）
 */
data class ProxyEntry(
    val link: String,
    val host: String,
    val port: Int,
    val type: ProxyType,
    val latencyMs: Int? = null,
    val status: ProxyStatus = ProxyStatus.UNAVAILABLE,
    val tgVerdict: TgVerdict = TgVerdict.UNVERIFIED,
    val location: String = "",
    val isp: String = "",
    val addedAt: String = "",
)

/**
 * 统计五项（与桌面版 get_state()["stats"] 字段一一对应）。
 *
 * 口径契约：available + unverified + tgFailed + dropped = tested（本轮 TCP 预筛总数），
 * tgFailed 含「两关未过」与「超过 1000ms 门槛」（含超慢口径）。
 *
 * @property available TG 实测通过且延迟 ≤ 门槛，保留在列表中的条数
 * @property unverified 未参与 TG 实测的条数（MTProto）
 * @property tgFailed TG 实测未通过（两关失败或超门槛）
 * @property dropped TCP 预筛不通被剔除的条数
 * @property excluded 已排除数 = 非 Telegram 类型合法条目 + 非法行（excluded_count + dropped_count）
 * @property tested 本轮参与 TCP 预筛的总条数
 */
data class Stats(
    val available: Int = 0,
    val unverified: Int = 0,
    val tgFailed: Int = 0,
    val dropped: Int = 0,
    val excluded: Int = 0,
    val tested: Int = 0,
)
