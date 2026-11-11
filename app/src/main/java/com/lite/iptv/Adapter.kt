package com.lite.iptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class VH(v: View) : RecyclerView.ViewHolder(v)

/** One tiny adapter reused by every list in the app. */
class Adapter<T>(private val layout: Int, private val binder: (View, T, Int) -> Unit) : RecyclerView.Adapter<VH>() {
    var items: List<T> = emptyList()
        private set
    var selected = -1
        private set
    var onClick: ((T, Int) -> Unit)? = null
    var onLong: ((T, Int) -> Boolean)? = null
    var onFocus: ((T, Int) -> Unit)? = null

    fun submit(l: List<T>) { items = l; notifyDataSetChanged() }

    fun select(i: Int) {
        val old = selected
        selected = i
        if (old in 0 until itemCount) notifyItemChanged(old)
        if (i in 0 until itemCount) notifyItemChanged(i)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(layout, p, false)
        val h = VH(v)
        v.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i >= 0) onClick?.invoke(items[i], i)
        }
        v.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            i >= 0 && (onLong?.invoke(items[i], i) ?: false)
        }
        v.setOnFocusChangeListener { _, focused ->
            if (focused) {
                val i = h.bindingAdapterPosition
                if (i >= 0) onFocus?.invoke(items[i], i)
            }
        }
        return h
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        binder(h.itemView, items[i], i)
        h.itemView.isSelected = i == selected
    }

    override fun getItemCount() = items.size
}
