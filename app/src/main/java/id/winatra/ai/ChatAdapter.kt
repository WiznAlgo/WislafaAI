package id.winatra.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMsg(val fromUser: Boolean, var text: String)

class ChatAdapter(val items: MutableList<ChatMsg> = mutableListOf()) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(root: FrameLayout, val tv: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val tv = TextView(ctx).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (dm.widthPixels * 0.82).toInt()
        }
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return VH(root, tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tv.text = m.text.ifEmpty { "…" }
        holder.tv.background = GradientDrawable().apply {
            cornerRadius = 36f
            setColor(Color.parseColor(if (m.fromUser) "#C8E0FF" else "#E6E6E6"))
        }
        (holder.tv.layoutParams as FrameLayout.LayoutParams).gravity =
            if (m.fromUser) Gravity.END else Gravity.START
    }

    override fun getItemCount() = items.size

    fun add(m: ChatMsg): Int {
        items.add(m)
        notifyItemInserted(items.lastIndex)
        return items.lastIndex
    }

    fun setAll(list: List<ChatMsg>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}
