package com.kiberqalqon

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kiberqalqon.databinding.ItemApkBinding

class ApkAdapter(
    private var items: List<ApkItem>,
    private val onCheck: (ApkItem) -> Unit
) : RecyclerView.Adapter<ApkAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemApkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvName.text = item.name
        holder.binding.tvPath.text = item.path
        holder.binding.tvSize.text = item.sizeFormatted
        // Redesign §3.4: tapping the row itself triggers the scan; the old
        // inline "Tekshirish" button is hidden in the new li-row layout.
        holder.binding.root.setOnClickListener { onCheck(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<ApkItem>) {
        items = newList
        notifyDataSetChanged()
    }

    class VH(val binding: ItemApkBinding) : RecyclerView.ViewHolder(binding.root)
}
