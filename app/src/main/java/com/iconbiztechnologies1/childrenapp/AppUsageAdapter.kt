package com.iconbiztechnologies1.childrenapp

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val usageTimeMs: Long,
    val appIcon: Drawable?,
    val usagePercentage: Float
)

// RecyclerView Adapter for app usage items
class AppUsageAdapter(private val items: List<AppUsageItem>) :
    RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val usageTime: TextView = itemView.findViewById(R.id.usageTime)
        val usagePercentage: TextView = itemView.findViewById(R.id.usagePercentage)
        val usageProgressBar: ProgressBar = itemView.findViewById(R.id.usageProgressBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.appIcon.setImageDrawable(item.appIcon)
        holder.appName.text = item.appName

        // Format usage time
        val hours = TimeUnit.MILLISECONDS.toHours(item.usageTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(item.usageTimeMs) % 60

        holder.usageTime.text = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }

        holder.usagePercentage.text = "${item.usagePercentage.toInt()}%"
        holder.usageProgressBar.progress = item.usagePercentage.toInt()
    }

    override fun getItemCount(): Int = items.size
}