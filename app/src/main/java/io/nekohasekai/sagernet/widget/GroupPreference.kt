package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yokhan.vpn.app.ui.preference.SimpleMenuPreference

class GroupPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    private var scope: CoroutineScope? = null
    private var groupNamesById: Map<Long, String> = emptyMap()

    override fun onAttached() {
        super.onAttached()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                val groups = withContext(Dispatchers.IO) {
                    SagerDatabase.groupDao.allGroups()
                }
                groupNamesById = groups.associate { it.id to it.displayName() }
                entries = groups.map { it.displayName() }.toTypedArray()
                entryValues = groups.map { "${it.id}" }.toTypedArray()
                notifyChanged()
            }
        }
    }

    override fun onDetached() {
        scope?.cancel()
        scope = null
        super.onDetached()
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return groupNamesById[value.toLong()]
                ?: super.getSummary()
        }
        return super.getSummary()
    }

}
