package fr.arichard.lastlauncher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.databinding.DialogAppListBinding
import fr.arichard.lastlauncher.databinding.ItemAppChoiceBinding

/**
 * App-selection dialogs that show each app's real icon — used everywhere the launcher
 * asks the user to pick apps (favorites, hidden apps, clock-tap target, gesture target).
 */
object AppPickerDialog {

    /** One selectable row: a label plus an optional icon (null renders blank). */
    data class Item(val label: String, val icon: Drawable?)

    /** Multi-select with checkboxes; [checked] is mutated and returned via [onConfirm]. */
    fun multiChoice(
        context: Context,
        title: CharSequence,
        items: List<Item>,
        checked: BooleanArray,
        onConfirm: (BooleanArray) -> Unit,
    ) {
        val binding = DialogAppListBinding.inflate(LayoutInflater.from(context))
        val adapter = ChoiceAdapter(items, checked, radio = false) { pos ->
            checked[pos] = !checked[pos]
            true
        }
        binding.pickerList.layoutManager = LinearLayoutManager(context)
        binding.pickerList.adapter = adapter
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(checked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Single-select; [onSelect] fires with the chosen index and dismisses the dialog. */
    fun singleChoice(
        context: Context,
        title: CharSequence,
        items: List<Item>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val binding = DialogAppListBinding.inflate(LayoutInflater.from(context))
        val checked = BooleanArray(items.size) { it == selectedIndex }
        binding.pickerList.layoutManager = LinearLayoutManager(context)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        binding.pickerList.adapter = ChoiceAdapter(items, checked, radio = true) { pos ->
            onSelect(pos)
            dialog.dismiss()
            false
        }
        dialog.show()
    }

    private class ChoiceAdapter(
        private val items: List<Item>,
        private val checked: BooleanArray,
        private val radio: Boolean,
        private val onClick: (Int) -> Boolean,
    ) : RecyclerView.Adapter<ChoiceAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemAppChoiceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            if (radio) b.choiceCheck.visibility = View.GONE
            return Holder(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.choiceLabel.text = item.label
            holder.binding.choiceIcon.setImageDrawable(item.icon)
            holder.binding.choiceCheck.isChecked = checked[position]
            holder.binding.root.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                if (onClick(p)) holder.binding.choiceCheck.isChecked = checked[p]
            }
        }

        class Holder(val binding: ItemAppChoiceBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
