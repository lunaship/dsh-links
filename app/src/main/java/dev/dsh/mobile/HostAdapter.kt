package dev.dsh.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** name -> (online, latencyMs)；null = 检测中 */
typealias HostStatus = Map<String, Pair<Boolean, Long?>>

/**
 * 首页网格适配器：设备卡片 / 添加卡片（末尾常驻）/ 错误条（整行）。
 */
class HostAdapter(
    private val onOpen: (Host) -> Unit,
    private val onDelete: (Host) -> Unit,
    private val onAdd: () -> Unit,
    private val onRetry: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DEVICE = 0
        private const val TYPE_ADD = 1
        private const val TYPE_ERROR = 2
    }

    private var hosts: List<Host> = emptyList()
    private var statuses: HostStatus = emptyMap()
    private var error: String? = null

    /** 当前网格列数（错误条占满整行用），由 MainActivity 按屏幕宽度设置。 */
    var gridSpan: Int = 1

    fun submit(list: List<Host>) {
        hosts = list
        notifyDataSetChanged()
    }

    fun setStatus(map: HostStatus) {
        statuses = map
        notifyDataSetChanged()
    }

    fun setError(message: String?) {
        error = message
        notifyDataSetChanged()
    }

    private val hasError: Boolean get() = error != null

    /** 供 GridLayoutManager.spanSizeLookup 使用：错误条占满整行。 */
    fun spanFor(position: Int): Int =
        if (getItemViewType(position) == TYPE_ERROR) gridSpan else 1

    override fun getItemViewType(position: Int): Int = when {
        hasError && position == 0 -> TYPE_ERROR
        position == itemCount - 1 -> TYPE_ADD
        else -> TYPE_DEVICE
    }

    override fun getItemCount(): Int = hosts.size + 1 + (if (hasError) 1 else 0)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ERROR -> ErrorVH(inflater.inflate(R.layout.item_error, parent, false))
            TYPE_ADD -> AddVH(inflater.inflate(R.layout.item_add_device, parent, false))
            else -> DeviceVH(inflater.inflate(R.layout.item_host, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ErrorVH -> {
                holder.error.text = error ?: ""
                holder.retry.setOnClickListener { onRetry() }
            }
            is AddVH -> holder.itemView.setOnClickListener { onAdd() }
            is DeviceVH -> {
                val offset = if (hasError) 1 else 0
                bindDevice(holder, hosts[position - offset])
            }
        }
    }

    private fun bindDevice(holder: DeviceVH, h: Host) {
        val ctx = holder.itemView.context
        holder.name.text = h.name
        holder.system.text = h.baseUrl
        holder.footer.text = ctx.getString(R.string.chip_local) + " · " + DeviceName.of(ctx)

        val status = statuses[h.name]
        val pill = holder.status
        val pillText = holder.statusText
        val pillDot = holder.statusDot
        when {
            status == null -> {
                pill.setBackgroundResource(R.drawable.bg_pill)
                pillText.setTextColor(ContextCompat.getColor(ctx, R.color.ink_text_secondary))
                pillDot.background.setTint(ContextCompat.getColor(ctx, R.color.ink_text_secondary))
                pillText.text = ctx.getString(R.string.status_checking)
            }
            status.first -> {
                pill.setBackgroundResource(R.drawable.bg_pill_online)
                pillText.setTextColor(ContextCompat.getColor(ctx, R.color.ink_online))
                pillDot.background.setTint(ContextCompat.getColor(ctx, R.color.ink_online))
                pillText.text = if (status.second != null)
                    ctx.getString(R.string.status_online) + " · " + status.second + " ms"
                else
                    ctx.getString(R.string.status_online)
            }
            else -> {
                pill.setBackgroundResource(R.drawable.bg_pill)
                pillText.setTextColor(ContextCompat.getColor(ctx, R.color.ink_text_muted))
                pillDot.background.setTint(ContextCompat.getColor(ctx, R.color.ink_text_muted))
                pillText.text = ctx.getString(R.string.status_offline)
            }
        }

        holder.itemView.setOnClickListener { onOpen(h) }
        holder.itemView.setOnLongClickListener { onDelete(h); true }
        holder.open.setOnClickListener { onOpen(h) }
        holder.remove.setOnClickListener { onDelete(h) }
    }

    class DeviceVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.host_name)
        val system: TextView = v.findViewById(R.id.device_system)
        val footer: TextView = v.findViewById(R.id.host_url)
        val status: View = v.findViewById(R.id.status_pill)
        val statusText: TextView = v.findViewById(R.id.status_text)
        val statusDot: View = v.findViewById(R.id.status_dot)
        val open: View = v.findViewById(R.id.action_open)
        val remove: View = v.findViewById(R.id.action_remove)
    }

    class AddVH(v: View) : RecyclerView.ViewHolder(v)

    class ErrorVH(v: View) : RecyclerView.ViewHolder(v) {
        val error: TextView = v.findViewById(R.id.error_text)
        val retry: View = v.findViewById(R.id.error_retry)
    }
}
