package com.tgproxy.model

/** 代理类型：SOCKS5 / MTProto（桌面版仅保留这两种 Telegram 可用类型）。 */
enum class ProxyType { SOCKS5, MTPROTO }

/** 代理连通性状态。 */
enum class ProxyStatus { AVAILABLE, UNAVAILABLE }

/**
 * 代理条目（与桌面版 parse_proxy_text 产出的字典字段一一对应）。
 *
 * @property link 完整链接：SOCKS5 为 `socks5://host:port`；MTProto 为数据源原样重建的完整分享链接
 * @property host 严格校验后的 IPv4 文本（保留原始形态，如含前导零）
 * @property port 端口（1~65535）
 * @property type 代理类型
 * @property latencyMs TCP 连接耗时（毫秒）；失败为 null
 * @property status 连通性状态
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
    val location: String = "",
    val isp: String = "",
    val addedAt: String = "",
)

/**
 * 统计卡片四项（与桌面版 get_state()["stats"] 字段一致）。
 *
 * 列表只保留测试可用的代理；不通的在每轮测试后直接剔除。
 *
 * @property available 测试通过并保留在列表中的条数
 * @property dropped 本轮测试不通被剔除的条数
 * @property excluded 已排除数 = 非 Telegram 类型合法条目 + 非法行（excluded_count + dropped_count）
 * @property tested 本轮参与连通性测试的总条数
 */
data class Stats(
    val available: Int = 0,
    val dropped: Int = 0,
    val excluded: Int = 0,
    val tested: Int = 0,
)
