package fr.arichard.lastlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.apps.AppEntry
import fr.arichard.lastlauncher.apps.AppRepository
import fr.arichard.lastlauncher.command.CommandProcessor
import fr.arichard.lastlauncher.databinding.ItemAppBinding

/**
 * The results list: quick-command rows first (nearest the keyboard in the reversed
 * layout), then matching apps, then a "search the web" row when nothing matches.
 */
class AppAdapter(
    private val repo: AppRepository,
    private val onAppClick: (AppEntry, View) -> Unit,
    private val onAppLongClick: (AppEntry, View) -> Unit,
    private val onCommand: (CommandProcessor.Command) -> Unit,
    private val onWebSearch: (String) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    sealed interface Row
    data class AppRow(val entry: AppEntry) : Row
    data class CommandRow(val command: CommandProcessor.Command) : Row
    data class WebRow(val query: String) : Row

    private var rows: List<Row> = emptyList()

    fun submit(
        apps: List<AppEntry>,
        commands: List<CommandProcessor.Command> = emptyList(),
        webSearchQuery: String? = null,
    ) {
        val list = ArrayList<Row>(commands.size + apps.size + 1)
        commands.forEach { list.add(CommandRow(it)) }
        apps.forEach { list.add(AppRow(it)) }
        if (apps.isEmpty() && commands.isEmpty() && !webSearchQuery.isNullOrBlank()) {
            list.add(WebRow(webSearchQuery))
        }
        rows = list
        notifyDataSetChanged()
    }

    /** The row Enter should trigger: top command if any, else the best app, else web. */
    fun primaryRow(): Row? = rows.firstOrNull()

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val row = rows[position]) {
            is AppRow -> holder.bindApp(row.entry)
            is CommandRow -> holder.bindCommand(row.command)
            is WebRow -> holder.bindWebSearch(row.query)
        }
    }

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindApp(entry: AppEntry) {
            binding.appLabel.text = entry.label
            binding.appSubtitle.visibility = View.GONE
            binding.appIcon.setImageDrawable(repo.icon(entry))
            binding.root.setOnClickListener { onAppClick(entry, binding.appIcon) }
            binding.root.setOnLongClickListener {
                onAppLongClick(entry, binding.root)
                true
            }
        }

        fun bindCommand(command: CommandProcessor.Command) {
            binding.appLabel.text = command.title
            binding.appSubtitle.text = command.subtitle.orEmpty()
            binding.appSubtitle.visibility =
                if (command.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            binding.appIcon.setImageResource(command.iconRes)
            binding.root.setOnClickListener { onCommand(command) }
            binding.root.setOnLongClickListener(null)
        }

        fun bindWebSearch(query: String) {
            binding.appLabel.text =
                binding.root.context.getString(R.string.no_match_web_search, query)
            binding.appSubtitle.visibility = View.GONE
            binding.appIcon.setImageResource(R.drawable.ic_search)
            binding.root.setOnClickListener { onWebSearch(query) }
            binding.root.setOnLongClickListener(null)
        }
    }
}
