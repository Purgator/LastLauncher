package fr.arichard.lastlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.apps.AppEntry
import fr.arichard.lastlauncher.apps.AppRepository
import fr.arichard.lastlauncher.databinding.ItemAppBinding

/**
 * Search-results / all-apps list. One flat list of app rows plus an optional
 * "search the web" row shown when nothing matches.
 */
class AppAdapter(
    private val repo: AppRepository,
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
    private val onWebSearch: (String) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private var webQuery: String? = null

    init {
        setHasStableIds(true)
    }

    fun submit(apps: List<AppEntry>, webSearchQuery: String? = null) {
        items = apps
        webQuery = webSearchQuery?.takeIf { it.isNotBlank() && apps.isEmpty() }
        notifyDataSetChanged()
    }

    fun firstApp(): AppEntry? = items.firstOrNull()

    fun currentWebQuery(): String? = webQuery

    override fun getItemCount(): Int = items.size + if (webQuery != null) 1 else 0

    override fun getItemId(position: Int): Long =
        if (position < items.size) items[position].componentKey.hashCode().toLong()
        else Long.MIN_VALUE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position < items.size) {
            holder.bindApp(items[position])
        } else {
            holder.bindWebSearch(webQuery.orEmpty())
        }
    }

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindApp(entry: AppEntry) {
            binding.appLabel.text = entry.label
            binding.appIcon.setImageDrawable(repo.icon(entry))
            binding.root.setOnClickListener { onClick(entry, binding.appIcon) }
            binding.root.setOnLongClickListener {
                onLongClick(entry, binding.root)
                true
            }
        }

        fun bindWebSearch(query: String) {
            binding.appLabel.text =
                binding.root.context.getString(R.string.no_match_web_search, query)
            binding.appIcon.setImageResource(R.drawable.ic_search)
            binding.root.setOnClickListener { onWebSearch(query) }
            binding.root.setOnLongClickListener(null)
        }
    }
}
