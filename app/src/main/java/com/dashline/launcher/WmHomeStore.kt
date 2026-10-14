// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import android.content.Intent
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Reader

/** Reads the system's saved rules. Never edits package-restrictions.xml. */
internal object WmHomeStore {
    fun contains(reader: Reader, target: ComponentName, persistent: Boolean): Boolean {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        val section = if (persistent) "persistent-preferred-activities" else "preferred-activities"
        var inSection = false; var inItem = false; var matches = false
        var main = false; var home = false; var defaults = false; var data = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when {
                    parser.name == section -> inSection = true
                    inSection && parser.name == "item" -> {
                        inItem = true
                        matches = ComponentName.unflattenFromString(parser.getAttributeValue(null, "name") ?: "") == target &&
                            (persistent || parser.getAttributeValue(null, "always") == "true")
                        main = false; home = false; defaults = false; data = false
                    }
                    inItem && parser.name == "action" -> main = main || parser.getAttributeValue(null, "name") == Intent.ACTION_MAIN
                    inItem && parser.name == "cat" -> {
                        val name = parser.getAttributeValue(null, "name")
                        home = home || name == Intent.CATEGORY_HOME
                        defaults = defaults || name == Intent.CATEGORY_DEFAULT
                    }
                    inItem && parser.name in setOf("scheme", "type", "ssp", "auth", "path") -> data = true
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                if (inItem && parser.name == "item") {
                    if (matches && main && home && defaults && !data) return true
                    inItem = false
                }
                if (parser.name == section) inSection = false
            }
        }
        return false
    }
}
