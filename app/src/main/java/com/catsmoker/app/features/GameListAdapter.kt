package com.catsmoker.app.features

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.catsmoker.app.R
import java.lang.ref.WeakReference

class GameListAdapter(
    activity: GameFeaturesActivity,
    private val gameList: List<GameInfo>
) : RecyclerView.Adapter<GameListAdapter.GameViewHolder>() {

    private val activityRef: WeakReference<GameFeaturesActivity> = WeakReference(activity)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return GameViewHolder(v)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val info = gameList[position]

        holder.gameName.text = info.appName
        holder.gameIcon.setImageDrawable(info.icon)
        holder.gameIcon.contentDescription = ctx.getString(R.string.game_icon_description, info.appName)

        // --- BATTERY & TIME LOGIC ---
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isUnrestricted = pm?.isIgnoringBatteryOptimizations(info.packageName) == true

        // Status Text
        val statusText = if (isUnrestricted) ctx.getString(R.string.unrestricted) else ctx.getString(
            R.string.optimized)

        // Append Time if available
        if (info.playTime != null) {
            holder.gamePlayTime.text = ctx.getString(R.string.play_time_status_format, info.playTime, statusText)
        } else {
            holder.gamePlayTime.text = statusText
        }

        // Colors based on battery status
        if (isUnrestricted) {
            holder.batteryOptimizationButton.setColorFilter(-0xb350b0) // Green
            holder.gamePlayTime.setTextColor(-0xb350b0)
        } else {
            holder.batteryOptimizationButton.setColorFilter(-0x3ef9) // Orange
            holder.gamePlayTime.setTextColor(-0x3ef9)
        }

        holder.batteryOptimizationButton.setOnClickListener {
            val activity = activityRef.get()
            activity?.requestIgnoreBatteryOptimizationsWrapper()
        }

        holder.launchButton.setOnClickListener {
            val launchIntent = info.packageName?.let { pkg ->
                ctx.packageManager.getLaunchIntentForPackage(pkg)
            }
            if (launchIntent != null) {
                ctx.startActivity(launchIntent)
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.cannot_launch_game), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int = gameList.size

    class GameViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val gameIcon: ImageView = v.findViewById(R.id.game_icon)
        val gameName: TextView = v.findViewById(R.id.game_name)
        val gamePlayTime: TextView = v.findViewById(R.id.game_play_time)
        val batteryOptimizationButton: ImageButton = v.findViewById(R.id.battery_optimization_button)
        val launchButton: Button = v.findViewById(R.id.launch_game_button)
    }
}

data class GameInfo(
    val appName: String?,
    val packageName: String?,
    val icon: Drawable?,
    val playTime: String?
)



