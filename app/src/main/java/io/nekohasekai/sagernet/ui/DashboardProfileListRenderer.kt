package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.ItemDashboardProfileBinding

internal class DashboardProfileListRenderer(
    private val container: LinearLayout,
    private val colorOf: (Int) -> Int,
    private val actions: Actions,
) {

    fun render(profiles: List<ProxyEntity>, activeId: Long, isConnected: Boolean) {
        container.removeAllViews()

        val inflater = LayoutInflater.from(container.context)
        profiles.forEachIndexed { index, profile ->
            val itemBinding = ItemDashboardProfileBinding.inflate(inflater, container, false)
            val isActive = profile.id == activeId

            itemBinding.profileDivider.isVisible = index != 0
            itemBinding.profileName.text = profile.displayName()
            itemBinding.profileProtocol.text = profile.displayType()
            itemBinding.profileItemRoot.setOnClickListener {
                actions.onSelect(profile)
            }
            itemBinding.profileEdit.setOnClickListener {
                actions.onEdit(profile)
            }
            itemBinding.profileMenu.setOnClickListener {
                actions.onMenu(it, profile)
            }

            itemBinding.root.setTag(R.id.profile_active_bar, isActive)
            applyRowTheme(itemBinding, isActive, isConnected)

            container.addView(itemBinding.root)
        }
    }

    fun applyTheme(isConnected: Boolean, dividerColor: Int) {
        for (index in 0 until container.childCount) {
            val itemView = container.getChildAt(index)
            val isActive = itemView.getTag(R.id.profile_active_bar) as? Boolean ?: false
            val itemBinding = ItemDashboardProfileBinding.bind(itemView)
            itemBinding.profileDivider.setBackgroundColor(dividerColor)
            applyRowTheme(itemBinding, isActive, isConnected)
        }
    }

    private fun applyRowTheme(
        itemBinding: ItemDashboardProfileBinding,
        isActive: Boolean,
        isConnected: Boolean,
    ) {
        val primaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_primary else R.color.dashboard_text_primary
        )
        val tertiaryColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_tertiary
        )
        val actionColor = colorOf(
            if (isConnected) R.color.dashboard_space_text_secondary else R.color.dashboard_text_quaternary
        )
        val activeColor = colorOf(
            if (isConnected) R.color.dashboard_green else R.color.dashboard_accent
        )

        itemBinding.profileActiveBar.backgroundTintList = ColorStateList.valueOf(
            if (isActive) activeColor else Color.TRANSPARENT
        )
        itemBinding.profileName.setTextColor(primaryColor)
        itemBinding.profileProtocol.setTextColor(tertiaryColor)
        itemBinding.profileEdit.imageTintList = ColorStateList.valueOf(actionColor)
        itemBinding.profileMenu.imageTintList = ColorStateList.valueOf(actionColor)
    }

    data class Actions(
        val onSelect: (ProxyEntity) -> Unit,
        val onEdit: (ProxyEntity) -> Unit,
        val onMenu: (View, ProxyEntity) -> Unit,
    )
}
