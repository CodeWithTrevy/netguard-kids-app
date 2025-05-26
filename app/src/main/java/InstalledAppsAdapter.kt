package com.iconbiztechnologies1.childrenapp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InstalledAppsAdapter(
    private val context: Context,
    private val apps: List<ApplicationInfo>
) : RecyclerView.Adapter<InstalledAppsAdapter.AppViewHolder>() {

    private val selectedApps = mutableSetOf<String>()

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val appName: TextView = itemView.findViewById(R.id.appName)
        val appPackage: TextView = itemView.findViewById(R.id.appPackage)
        val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_installed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        val packageManager = context.packageManager

        // Set app icon
        holder.appIcon.setImageDrawable(app.loadIcon(packageManager))

        // Set app name
        holder.appName.text = app.loadLabel(packageManager).toString()

        // Set package name
        holder.appPackage.text = app.packageName

        // Set checkbox state
        holder.checkBox.isChecked = selectedApps.contains(app.packageName)

        // Clear previous listener to avoid issues with view recycling
        holder.checkBox.setOnCheckedChangeListener(null)

        // Handle checkbox clicks
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedApps.add(app.packageName)
            } else {
                selectedApps.remove(app.packageName)
            }
        }

        // Handle item clicks
        holder.itemView.setOnClickListener {
            holder.checkBox.toggle()
        }
    }

    override fun getItemCount(): Int = apps.size

    fun getSelectedApps(): Set<String> = selectedApps.toSet()
}