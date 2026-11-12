package com.lite.iptv

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class Cat(val key: String, val label: String, val count: Int)

/**
 * Shared screen for Live / Sports / Movies / Series: category column on one side, items on the other,
 * category switches as focus moves (no extra OK press), search, favorites, remembers the last category.
 */
abstract class BrowseBase<T : Any> : AppCompatActivity() {
    protected abstract val screenKey: String
    protected abstract val titleRes: Int
    protected abstract val itemLayout: Int
    protected abstract fun makeLayoutManager(): RecyclerView.LayoutManager
    protected abstract suspend fun load(): List<T>
    protected abstract fun groupOf(t: T): String
    protected abstract fun nameOf(t: T): String
    protected abstract fun favKey(t: T): String
    protected abstract fun bind(v: View, t: T, i: Int)
    protected abstract fun open(list: List<T>, index: Int)
    protected open fun acceptGroup(g: String) = true
    protected open fun recent(): List<T>? = null
    /** Position of a category as the provider lists it; lower comes first. */
    protected open fun groupRank(g: String): Int = 0
    /** Long press on an item. Return true if handled. */
    protected open fun onLongItem(t: T): Boolean { toggleFav(t); return true }
    /** Live screen shows a preview pane on the right. */
    protected open val hasPreview = false

    private val h = Handler(Looper.getMainLooper())
    private lateinit var rvCats: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbLoad: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var btnRetry: Button
    private lateinit var catAd: Adapter<Cat>
    private lateinit var itemAd: Adapter<T>

    private var all: List<T> = emptyList()
    private var groups: Map<String, List<T>> = emptyMap()
    private var cats: List<Cat> = emptyList()
    private var cur: List<T> = emptyList()
    private var curKey = "*all"
    private var query = ""
    private var focusIdx = -1
    private var ready = false
    private var pendingSel: Runnable? = null
    private var pendingSearch: Runnable? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_browse)
        findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        wireBack()
        rvCats = findViewById(R.id.rvCats); rvItems = findViewById(R.id.rvItems)
        tvCount = findViewById(R.id.tvCount); tvStatus = findViewById(R.id.tvStatus)
        pbLoad = findViewById(R.id.pbLoad); etSearch = findViewById(R.id.etSearch); btnRetry = findViewById(R.id.btnRetry)

        catAd = Adapter<Cat>(R.layout.item_cat) { v, c, _ ->
            v.findViewById<TextView>(R.id.tvName).text = c.label
            v.findViewById<TextView>(R.id.tvCount).text = c.count.toString()
        }
        itemAd = Adapter<T>(itemLayout) { v, t, i -> bind(v, t, i) }
        itemAd.holdEdges = true
        rvCats.setup(LinearLayoutManager(this), catAd)
        rvItems.setup(makeLayoutManager(), itemAd)

        catAd.onFocus = { c, _ ->
            pendingSel?.let { h.removeCallbacks(it) }
            pendingSel = Runnable { if (curKey != c.key) select(c.key, false) }.also { h.postDelayed(it, 120) }
        }
        catAd.onClick = { c, _ -> select(c.key, true) }
        itemAd.onFocus = { _, i -> focusIdx = i }
        itemAd.onClick = { _, i -> open(cur, i) }
        itemAd.onLong = { t, _ -> onLongItem(t) }
        findViewById<View>(R.id.previewPane).visibility = if (hasPreview) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnFav).setOnClickListener {
            if (focusIdx in cur.indices) toggleFav(cur[focusIdx]) else toast(R.string.hint_select)
        }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { openSearch() }
        btnRetry.setOnClickListener { startLoad() }
        etSearch.addTextChangedListener(afterTextChanged = { s ->
            pendingSearch?.let { h.removeCallbacks(it) }
            pendingSearch = Runnable { query = s?.toString()?.trim() ?: ""; applyFilter() }.also { h.postDelayed(it, 300) }
        })
        etSearch.setOnEditorActionListener { _, _, _ -> hideIme(); rvItems.requestFocus(); true }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (etSearch.visibility == View.VISIBLE) closeSearch()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        startLoad()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (query.isEmpty() && (curKey == "*recent" || curKey == "*fav")) {
            val n = baseList()
            if (n != cur) { cur = n; itemAd.submit(n); updateCount() }
        }
        itemAd.notifyItemRangeChanged(0, itemAd.itemCount)   // refresh star badges, focus is kept (no animator)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun startLoad() {
        btnRetry.visibility = View.GONE
        tvStatus.visibility = View.GONE
        pbLoad.visibility = View.VISIBLE
        if (Store.active() == null) { toast(R.string.no_playlist); finish(); return }
        lifecycleScope.launch {
            try {
                build(load())
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                tvStatus.text = getString(R.string.error_load, e.message ?: e.javaClass.simpleName)
                tvStatus.visibility = View.VISIBLE
                btnRetry.visibility = View.VISIBLE
                btnRetry.requestFocus()
            } finally { pbLoad.visibility = View.GONE }
        }
    }

    protected fun groupNames(): List<String> = groups.keys.toList()
    /** Reload the list (after a move) and keep the cursor near the same row. */
    protected fun rebuild(keepItem: Int) { lifecycleScope.launch { try { build(load(), keepItem) } catch (e: Exception) { /* keep old list */ } } }

    protected fun curIndex(t: T) = cur.indexOf(t)

    /** Delete in place: the list keeps its scroll position and the cursor stays on the same row. */
    protected fun removeItem(t: T) {
        val i = cur.indexOf(t)
        all = all.filterNot { it === t }
        val g = LinkedHashMap<String, List<T>>()
        for ((k, v) in groups) { val n = v.filterNot { it === t }; if (n.isNotEmpty()) g[k] = n }
        groups = g
        cats = makeCats()
        catAd.submit(cats)
        if (cats.none { it.key == curKey }) curKey = "*all"
        catAd.select(cats.indexOfFirst { it.key == curKey })
        cur = cur.filterNot { it === t }
        if (i >= 0) itemAd.removeAt(i) else itemAd.submit(cur)
        focusIdx = -1
        updateCount()
        val n = i.coerceIn(0, (cur.size - 1).coerceAtLeast(0))
        rvItems.postDelayed({
            if (cur.isNotEmpty()) (rvItems.findViewHolderForAdapterPosition(n)?.itemView ?: rvItems).requestFocus()
            else rvCats.requestFocus()
        }, 80)
    }

    private fun makeCats(): List<Cat> {
        val c = ArrayList<Cat>()
        c.add(Cat("*all", getString(R.string.all), all.size))
        c.add(Cat("*fav", getString(R.string.favorites), all.count { Store.isFav(favKey(it)) }))
        recent()?.let { c.add(Cat("*recent", getString(R.string.continue_watching), it.size)) }
        groups.forEach { (k, v) -> c.add(Cat(k, k.ifBlank { "-" }, v.size)) }
        return c
    }

    private fun build(l: List<T>, keepItem: Int = -1) {
        all = l.filter { acceptGroup(groupOf(it)) }
        val g = LinkedHashMap<String, ArrayList<T>>()
        for (t in all) g.getOrPut(groupOf(t)) { ArrayList() }.add(t)
        groups = g.entries.sortedBy { groupRank(it.key) }.associateTo(LinkedHashMap()) { it.key to it.value }
        val c = makeCats()
        cats = c
        catAd.submit(c)
        val last = Store.getStr("cat_$screenKey", "*all")
        val key = if (c.any { it.key == last }) last else "*all"
        val idx = c.indexOfFirst { it.key == key }.coerceAtLeast(0)
        ready = true
        select(key, false)
        rvCats.scrollToPosition(idx)
        rvCats.postDelayed({
            if (keepItem >= 0) {
                val n = keepItem.coerceIn(0, (cur.size - 1).coerceAtLeast(0))
                rvItems.scrollToPosition(n)
                rvItems.post { (rvItems.findViewHolderForAdapterPosition(n)?.itemView ?: rvItems).requestFocus() }
            } else (rvCats.findViewHolderForAdapterPosition(idx)?.itemView ?: rvCats).requestFocus()
        }, 60)
    }

    private fun select(key: String, moveFocus: Boolean) {
        curKey = key
        Store.setStr("cat_$screenKey", key)
        catAd.select(cats.indexOfFirst { it.key == key })
        applyFilter()
        if (moveFocus) rvItems.postDelayed({ rvItems.requestFocus() }, 80)
    }

    private fun baseList(): List<T> = when (curKey) {
        "*all" -> all
        "*fav" -> all.filter { Store.isFav(favKey(it)) }
        "*recent" -> recent() ?: emptyList()
        else -> groups[curKey] ?: emptyList()
    }

    private fun applyFilter() {
        val src = if (query.isNotEmpty()) all else baseList()
        cur = if (query.isEmpty()) src else src.filter { nameOf(it).contains(query, true) }
        itemAd.submit(cur)
        focusIdx = -1
        updateCount()
    }

    private fun updateCount() {
        tvCount.text = cur.size.toString()
        tvStatus.visibility = if (cur.isEmpty() && pbLoad.visibility != View.VISIBLE && btnRetry.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (tvStatus.visibility == View.VISIBLE) tvStatus.setText(R.string.empty)
    }

    protected fun toggleFav(t: T) {
        val on = Store.toggleFav(favKey(t))
        toast(if (on) R.string.add_fav else R.string.remove_fav)
        if (curKey == "*fav" && query.isEmpty()) {
            val i = focusIdx
            applyFilter()
            rvItems.postDelayed({
                rvItems.scrollToPosition(i.coerceIn(0, (cur.size - 1).coerceAtLeast(0)))
                rvItems.requestFocus()
            }, 80)
        } else {
            itemAd.notifyItemChanged(cur.indexOf(t).coerceAtLeast(0))
        }
    }

    private fun openSearch() {
        etSearch.visibility = View.VISIBLE
        etSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etSearch, 0)
    }

    private fun closeSearch() {
        etSearch.setText("")
        etSearch.visibility = View.GONE
        hideIme()
        query = ""
        applyFilter()
        rvItems.requestFocus()
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}
