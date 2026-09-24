package com.example.baseballmotionanalyzer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.baseballmotionanalyzer.R
import com.example.baseballmotionanalyzer.db.SwingRecordEntity

class SwingHistoryAdapter(
    private var recordList: List<SwingRecordEntity>,
    private val onDeleteClickListener: (SwingRecordEntity) -> Unit
) : RecyclerView.Adapter<SwingHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPlayerBadge: TextView = view.findViewById(R.id.tvItemPlayerBadge)
        val tvTimestamp: TextView = view.findViewById(R.id.tvItemTimestamp)
        val btnDelete: Button = view.findViewById(R.id.btnItemDelete)
        val tvSpeed: TextView = view.findViewById(R.id.tvItemSpeed)
        val tvAngle: TextView = view.findViewById(R.id.tvItemAngle)
        val tvDistance: TextView = view.findViewById(R.id.tvItemDistance)
        val tvAngularVelocity: TextView = view.findViewById(R.id.tvItemAngularVelocity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_swing_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = recordList[position]
        val badgeText = "👤 #${item.jerseyNumber} ${item.playerName} (${item.heightCm.toInt()}cm / ${item.stance})"
        holder.tvPlayerBadge.text = badgeText
        holder.tvTimestamp.text = item.timestamp
        holder.tvSpeed.text = String.format("%.1f km/h", item.speedKmh)
        holder.tvAngle.text = String.format("%.1f°", item.launchAngleDeg)
        holder.tvDistance.text = String.format("%.1f m", item.distanceMeters)
        holder.tvAngularVelocity.text = String.format("%d deg/s", item.angularVelocityDegSec.toInt())

        holder.btnDelete.setOnClickListener {
            onDeleteClickListener(item)
        }
    }

    override fun getItemCount(): Int = recordList.size

    fun updateData(newList: List<SwingRecordEntity>) {
        this.recordList = newList
        notifyDataSetChanged()
    }
}
