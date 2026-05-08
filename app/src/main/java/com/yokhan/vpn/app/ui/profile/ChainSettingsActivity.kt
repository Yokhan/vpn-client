package com.yokhan.vpn.app.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yokhan.vpn.shared.ui.design.yokhanPalette
import com.yokhan.vpn.shared.ui.design.yokhanTypography
import com.yokhan.vpn.shared.ui.form.YokhanFormEvent
import com.yokhan.vpn.shared.ui.form.YokhanFormField
import com.yokhan.vpn.shared.ui.form.YokhanFormSection
import com.yokhan.vpn.shared.ui.form.YokhanFormSectionCard
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import com.yokhan.vpn.app.ui.ProfileSelectActivity
import moe.matsuri.nb4a.Protocols.getProtocolColor

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(includeConfigurationList = true) {

    override fun createEntity() = ChainBean()

    val proxyList = ArrayList<ProxyEntity>()

    override fun ChainBean.init() {
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun ChainBean.serialize() {
        name = DataStore.profileName
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun useComposeProfileForm(): Boolean = true

    @Composable
    override fun ComposeProfileContent() {
        val colors = yokhanPalette(spaceActive = true)
        val typography = yokhanTypography()
        var section by remember { mutableStateOf(chainNameSection()) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            YokhanFormSectionCard(
                section = section,
                colors = colors,
                typography = typography,
                readOnly = false,
                onEvent = { event ->
                    if (event is YokhanFormEvent.TextChanged && event.id == ChainNameField) {
                        DataStore.profileName = event.value
                        DataStore.dirty = true
                        section = chainNameSection(event.value)
                    }
                },
            )
        }
    }

    private fun chainNameSection(value: String = DataStore.profileName): YokhanFormSection {
        return YokhanFormSection(
            id = "profile",
            title = getString(R.string.profile_name).uppercase(),
            fields = listOf(
                YokhanFormField.Text(
                    id = ChainNameField,
                    label = getString(R.string.profile_name),
                    value = value,
                    required = true,
                ),
            ),
        )
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.chain_settings)
        configurationList = findViewById(R.id.configuration_list)
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)

        runOnDefaultDispatcher { configurationAdapter.reload() }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(createAddProfileRow(parent))
            } else {
                ProfileHolder(createProfileRow(parent))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false

        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }

        return true
    }

    fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (profile.type != 8 || anotherProfile.type != 8) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.chainBean!!.proxies
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) {
                    return true
                }
            }
        }
        return false
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true

                val profile = ProfileManager.getProfile(
                    data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    )
                )!!

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                    }
                }
            }
        }

    private inner class AddHolder(root: View) : RecyclerView.ViewHolder(root) {
        fun bind() {
            itemView.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    private inner class ProfileHolder(private val row: ChainProfileRow) :
        RecyclerView.ViewHolder(row.root) {

        val profileName = row.profileName
        val profileType = row.profileType
        val trafficText: TextView = row.trafficText
        val editButton = row.editButton
        val shareLayout = row.shareLayout

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            shareLayout.isVisible = false
        }

    }

    private data class ChainProfileRow(
        val root: View,
        val profileName: TextView,
        val profileType: TextView,
        val trafficText: TextView,
        val editButton: ImageView,
        val shareLayout: View,
    )

    private fun createAddProfileRow(parent: ViewGroup): View {
        return createChainCard(parent).apply {
            addView(LinearLayout(context).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(context).apply {
                    text = getString(R.string.add_profile)
                    typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                    setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            })
        }
    }

    private fun createProfileRow(parent: ViewGroup): ChainProfileRow {
        val card = createChainCard(parent).apply {
            isClickable = true
            isFocusable = true
        }
        val profileName = TextView(card.context).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val profileType = TextView(card.context).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_secondary))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        val trafficText = TextView(card.context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            setTextColor(ContextCompat.getColor(context, R.color.dashboard_space_text_secondary))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
            isVisible = false
        }
        val editButton = ImageView(card.context).apply {
            contentDescription = getString(R.string.edit)
            isFocusable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(R.drawable.ic_image_edit)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.dashboard_space_text_secondary)
            )
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val shareLayout = LinearLayout(card.context).apply {
            contentDescription = getString(R.string.share)
            isFocusable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_social_share)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.dashboard_space_text_secondary)
                )
            })
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        card.addView(LinearLayout(card.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(4), dp(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(profileName)
                addView(profileType)
                addView(trafficText)
            })
            addView(editButton)
            addView(shareLayout)
        })

        return ChainProfileRow(card, profileName, profileType, trafficText, editButton, shareLayout)
    }

    private fun createChainCard(parent: ViewGroup): MaterialCardView {
        return MaterialCardView(parent.context).apply {
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            radius = dp(10).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(ContextCompat.getColor(context, R.color.dashboard_card_border_off))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(10), dp(4), dp(10), dp(4))
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val ChainNameField = "profileName"
    }

}
