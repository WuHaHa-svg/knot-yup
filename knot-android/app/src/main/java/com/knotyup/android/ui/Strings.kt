package com.knotyup.android.ui

/** 三语文案，与桌面端 ui/i18n.js 对齐。 */
object Strings {
    val langs = listOf("zh-Hans", "zh-Hant", "en")
    val langLabels = mapOf("zh-Hans" to "简", "zh-Hant" to "繁", "en" to "EN")

    /** 当前语言（进程内状态，切换时循环）。 */
    var currentLang: String = "zh-Hans"

    fun nextLang(): String {
        currentLang = langs[(langs.indexOf(currentLang) + 1) % langs.size]
        return currentLang
    }

    private val dict = mapOf(
        "zh-Hans" to mapOf(
            "tagline" to "把设备打成一个结",
            "tagline_sub" to "在同一局域网内共享剪贴板",
            "join_group" to "加入群",
            "join_desc" to "输入群主的地址与 PIN 码",
            "btn_join" to "加入",
            "addr_hint" to "192.168.1.100:35217",
            "pin_hint" to "PIN 码",
            "discovered" to "局域网发现的群",
            "no_discovered" to "未发现附近的群",
            "devices" to "设备",
            "clipboard" to "剪贴板",
            "click_hint" to "点击条目即可复制",
            "leave" to "退出群",
            "you" to "本机",
            "owner" to "群主",
            "empty_1" to "暂无同步记录",
            "empty_2" to "在任一设备上复制点什么试试",
            "send_hint" to "输入要发送到群的内容…",
            "send" to "发送",
            "joined" to "已加入群",
            "left" to "已退出群",
            "kicked" to "你已被群主移出",
            "disconnected" to "连接已断开",
            "fill_addr_pin" to "请填写地址和 PIN 码",
            "wrong_pin" to "PIN 码不正确",
            "join_failed" to "连接失败，请检查地址和网络",
            "copied" to "已复制",
            "sent" to "已发送到群",
            "fg_mode_hint" to "前台模式：App 在前台时自动同步剪贴板",
            "shizuku_mode_hint" to "Shizuku 模式：支持后台同步",
            "shizuku_activate" to "激活 Shizuku 以支持后台同步",
        ),
        "zh-Hant" to mapOf(
            "tagline" to "把裝置打成一個結",
            "tagline_sub" to "在同一區域網路內共享剪貼板",
            "join_group" to "加入群",
            "join_desc" to "輸入群主的位址與 PIN 碼",
            "btn_join" to "加入",
            "addr_hint" to "192.168.1.100:35217",
            "pin_hint" to "PIN 碼",
            "discovered" to "區域網路發現的群",
            "no_discovered" to "未發現附近的群",
            "devices" to "裝置",
            "clipboard" to "剪貼板",
            "click_hint" to "點擊條目即可複製",
            "leave" to "退出群",
            "you" to "本機",
            "owner" to "群主",
            "empty_1" to "暫無同步記錄",
            "empty_2" to "在任何一台裝置上複製點什麼試試",
            "send_hint" to "輸入要傳送到群的內容…",
            "send" to "傳送",
            "joined" to "已加入群",
            "left" to "已退出群",
            "kicked" to "你已被群主移出",
            "disconnected" to "連線已斷開",
            "fill_addr_pin" to "請填寫位址和 PIN 碼",
            "wrong_pin" to "PIN 碼不正確",
            "join_failed" to "連線失敗，請檢查位址和網路",
            "copied" to "已複製",
            "sent" to "已傳送到群",
            "fg_mode_hint" to "前景模式：App 在前景時自動同步剪貼板",
            "shizuku_mode_hint" to "Shizuku 模式：支援背景同步",
            "shizuku_activate" to "啟用 Shizuku 以支援背景同步",
        ),
        "en" to mapOf(
            "tagline" to "Tie your devices into one knot",
            "tagline_sub" to "Share clipboard across your local network",
            "join_group" to "Join a Group",
            "join_desc" to "Enter the owner’s address and PIN",
            "btn_join" to "Join",
            "addr_hint" to "192.168.1.100:35217",
            "pin_hint" to "PIN",
            "discovered" to "Nearby groups",
            "no_discovered" to "No nearby groups found",
            "devices" to "Devices",
            "clipboard" to "Clipboard",
            "click_hint" to "Tap an item to copy",
            "leave" to "Leave Group",
            "you" to "This device",
            "owner" to "Owner",
            "empty_1" to "Nothing synced yet",
            "empty_2" to "Copy something on any device to try it out",
            "send_hint" to "Type something to send to the group…",
            "send" to "Send",
            "joined" to "Joined group",
            "left" to "Left group",
            "kicked" to "You were removed by the owner",
            "disconnected" to "Connection lost",
            "fill_addr_pin" to "Please enter address and PIN",
            "wrong_pin" to "Incorrect PIN",
            "join_failed" to "Connection failed — check address and network",
            "copied" to "Copied",
            "sent" to "Sent to group",
            "fg_mode_hint" to "Foreground mode: syncs while the app is open",
            "shizuku_mode_hint" to "Shizuku mode: background sync enabled",
            "shizuku_activate" to "Activate Shizuku for background sync",
        ),
    )

    fun t(lang: String, key: String): String {
        currentLang = lang
        return dict[lang]?.get(key) ?: dict["en"]?.get(key) ?: key
    }
}
