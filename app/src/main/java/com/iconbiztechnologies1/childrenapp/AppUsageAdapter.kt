package com.iconbiztechnologies1.childrenapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

// The AppUsageItem data class has been REMOVED from this file.

class AppUsageAdapter(
    // The internal list is now a 'var' so it can be reassigned, or mutable.
    private var appUsageList: MutableList<AppUsageItem>
) : RecyclerView.Adapter<AppUsageAdapter.AppUsageViewHolder>() {

    class AppUsageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val usageTime: TextView = itemView.findViewById(R.id.usageTime)
        // Note: Your second file had a 'usagePercentage' TextView which your layout doesn't.
        // I am assuming you want to bind to the progress bar, which is more common.
        val usageProgressBar: ProgressBar = itemView.findViewById(R.id.usageProgressBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return AppUsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
        val currentItem = appUsageList[position]

        if (currentItem.appIcon != null) {
            holder.appIcon.setImageDrawable(currentItem.appIcon)
        } else {
            // Set a default icon if the app icon isn't available
            holder.appIcon.setImageResource(R.mipmap.ic_launcher)
        }

        holder.appName.text = currentItem.appName

        val hours = TimeUnit.MILLISECONDS.toHours(currentItem.usageTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(currentItem.usageTimeMs) % 60
        holder.usageTime.text = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else if (currentItem.usageTimeMs > 0) {
            "${minutes}m"
        } else {
            "< 1m" // Handle very small usage times
        }

        holder.usageProgressBar.progress = currentItem.usagePercentage.toInt()
    }

    override fun getItemCount(): Int {
        return appUsageList.size
    }

    // --- ADDED THIS METHOD ---
    // This is the missing 'updateData' method that the Activity calls.
    fun updateData(newAppUsageList: List<AppUsageItem>) {
        appUsageList.clear()
        appUsageList.addAll(newAppUsageList)
        notifyDataSetChanged() // This tells the RecyclerView to refresh its views.
    }
}