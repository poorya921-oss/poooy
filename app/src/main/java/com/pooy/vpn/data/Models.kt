package com.pooy.vpn.data

/** A saved server. [raw] is either a share link (vless://, vmess://, ...) or a JSON config/outbound. */
data class Profile(
    val id: String,
    val name: String,
    val raw: String,
    val subId: String? = null,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
)

data class StoreData(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val activeId: String? = null,
) {
    val active: Profile? get() = profiles.firstOrNull { it.id == activeId }
}
