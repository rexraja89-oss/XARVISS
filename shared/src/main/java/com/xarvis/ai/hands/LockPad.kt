package com.xarvis.ai.hands

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The pattern dots / PIN keys on the controlled phone, found through Accessibility. Android blacks such screens out
 * of pictures and video (Rex, 11 Nov 2026: "Allow restricted settings" asked for his pattern and the benco showed only
 * black, so two blind tries missed), but it doesn't hide them from Accessibility. So the controlled phone says where
 * the 9 dots (or the keys) are, and the viewer draws them over the black picture for Rex to draw on with his own
 * finger. Only positions travel: the pattern itself is never read, kept or entered by XARVIS.
 */
object LockPad {
    /** A screen element in screen pixels (what [scan] reads; plain data so [find] can be tested). */
    data class Node(
        val cls: String,
        val id: String,
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val children: List<Node> = emptyList(),
    ) {
        val empty: Boolean get() = right <= left || bottom <= top
    }

    /** A key or button with its label; its box as 0–1 of the screen. */
    data class Key(val label: String, val l: Double, val t: Double, val r: Double, val b: Double)

    /** What to draw: the 9 dots' centres (0–1 of the screen, row by row) and/or keys. */
    data class Pad(val cells: List<Pair<Double, Double>>, val keys: List<Key>)

    /** The pad on screen [w]×[h] under [root], or null when there's none. */
    fun find(root: Node, w: Int, h: Int): Pad? {
        if (w <= 0 || h <= 0) return null
        val all = ArrayList<Node>()
        fun walk(n: Node, depth: Int) {
            if (all.size >= MAX_NODES || depth > MAX_DEPTH) return
            all += n
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        val pattern = all.firstOrNull { !it.empty && isPattern(it) }
        val cells = pattern?.let { cellsOf(it) }.orEmpty().map { (x, y) -> x / w to y / h }
        val digits = all.filter { !it.empty && it.text.trim().let { t -> t.length == 1 && t[0].isDigit() } }.distinctBy { it.text.trim() }
        val words = all.filter { !it.empty && WORDS.matches(it.text.trim()) }
        val pin = digits.size >= 10
        // A fingerprint screen's "Use pattern" is hidden too: show it (and Cancel) so it can be tapped.
        val switcher = words.any { SWITCH.matches(it.text.trim()) }
        if (cells.isEmpty() && !pin && !switcher) return null
        val keys = (if (pin) digits else emptyList()) + words
        return Pad(
            cells,
            keys.map { Key(it.text.trim(), it.left.toDouble() / w, it.top.toDouble() / h, it.right.toDouble() / w, it.bottom.toDouble() / h) }.distinct(),
        )
    }

    private fun isPattern(n: Node): Boolean {
        val c = n.cls.lowercase()
        val id = n.id.lowercase()
        return "patternview" in c || "lockpattern" in id || "lock_pattern" in id || "pattern_view" in id
    }

    /** The dots: the view's own 9 (Accessibility shows them as children), else its 3×3 grid of equal squares. */
    private fun cellsOf(p: Node): List<Pair<Double, Double>> {
        val kids = p.children.filter { !it.empty }
        if (kids.size >= 9) {
            return kids.take(9)
                .map { (it.left + it.right) / 2.0 to (it.top + it.bottom) / 2.0 }
                .sortedWith(compareBy({ it.second }, { it.first }))
        }
        val cw = (p.right - p.left) / 3.0
        val ch = (p.bottom - p.top) / 3.0
        return (0..2).flatMap { r -> (0..2).map { c -> p.left + cw * (c + 0.5) to p.top + ch * (r + 0.5) } }
    }

    fun toJson(pad: Pad): JSONObject = JSONObject()
        .put("cells", JSONArray().also { a -> pad.cells.forEach { (x, y) -> a.put(JSONArray().put(x).put(y)) } })
        .put("keys", JSONArray().also { a -> pad.keys.forEach { k -> a.put(JSONObject().put("s", k.label).put("l", k.l).put("t", k.t).put("r", k.r).put("b", k.b)) } })

    fun fromJson(o: JSONObject): Pad {
        val cells = o.optJSONArray("cells")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) } } }.orEmpty()
        val keys = o.optJSONArray("keys")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { k -> Key(k.optString("s"), k.optDouble("l"), k.optDouble("t"), k.optDouble("r"), k.optDouble("b")) }
            }
        }.orEmpty()
        return Pad(cells, keys)
    }

    /** Reads [roots] (the windows on screen) and finds the pad; the JSON to send, or null. */
    fun scan(roots: List<AccessibilityNodeInfo>, w: Int, h: Int): JSONObject? {
        val budget = intArrayOf(MAX_NODES)
        val top = Node("", "", "", 0, 0, w, h, roots.map { node(it, 0, budget) })
        return find(top, w, h)?.let { toJson(it) }
    }

    private fun node(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): Node {
        val r = Rect()
        n.getBoundsInScreen(r)
        val kids = ArrayList<Node>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until n.childCount) {
                if (--budget[0] <= 0) break
                n.getChild(i)?.let { kids += node(it, depth + 1, budget) }
            }
        }
        val text = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString().orEmpty()
        return Node(n.className?.toString().orEmpty(), n.viewIdResourceName.orEmpty(), text, r.left, r.top, r.right, r.bottom, kids)
    }

    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 40
    private val WORDS = Regex("(?i)^(ok|enter|done|continue|cancel|delete|confirm|next|use pattern|use pin|use password)$")
    private val SWITCH = Regex("(?i)^use (pattern|pin|password)$")
}
