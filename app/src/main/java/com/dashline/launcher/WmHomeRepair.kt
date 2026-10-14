// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import org.json.JSONObject

/** Read before writing, and repair either feature even if the other one is unavailable. */
internal object WmHomeRepair {
    fun ensure(home: () -> Boolean, enableHome: () -> Unit,
               card: () -> Boolean, enableCard: () -> Unit,
               upgradeHome: () -> Boolean = { false }): JSONObject {
        val result = JSONObject()
        val errors = mutableListOf<String>()
        fun repair(key: String, label: String, read: () -> Boolean, enable: () -> Unit,
                   upgrade: () -> Boolean = { false }) {
            try {
                val current = read()
                result.put(key, current)
                if (!current || upgrade()) {
                    enable()
                    check(read()) { "系统没有保存修改" }
                    result.put(key, true)
                }
            } catch (failure: Exception) {
                errors.add("$label：${failure.message ?: failure.javaClass.simpleName}")
            }
        }
        repair("homeEnabled", "默认桌面", home, enableHome, upgradeHome)
        repair("cardEnabled", "威课堂入口", card, enableCard)
        return result.put("complete", errors.isEmpty()).put("message", errors.joinToString("\n"))
    }
}
