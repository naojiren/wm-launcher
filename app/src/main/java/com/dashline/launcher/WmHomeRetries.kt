// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import org.json.JSONObject

/** At most five attempts, then stop. A booting local debug daemon can become ready later. */
internal object WmHomeRetries {
    fun run(perform: () -> JSONObject, pause: (Long) -> Unit = { Thread.sleep(it) }): Result<JSONObject> {
        var result: Result<JSONObject> = Result.failure(IllegalStateException("尚未设置默认桌面"))
        val delays = longArrayOf(2000, 5000, 10000, 20000)
        for (attempt in 0..delays.size) {
            result = runCatching(perform)
            if (result.getOrNull()?.optBoolean("complete") == true) return result
            if (attempt < delays.size) pause(delays[attempt])
        }
        return result
    }
}
