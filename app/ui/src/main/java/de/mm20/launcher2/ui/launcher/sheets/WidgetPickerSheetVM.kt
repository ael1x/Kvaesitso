package de.mm20.launcher2.ui.launcher.sheets

import WidgetsService
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.mm20.launcher2.ktx.normalize
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.widgets.FavoritesWidget
import de.mm20.launcher2.widgets.Widget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class WidgetPickerSheetVM(
    private val widgetsService: WidgetsService,
    private val context: Context,
) : ViewModel() {

    private val packageManager = context.packageManager

    val searchQuery = MutableStateFlow("")

    private val enabledWidgets = widgetsService.getWidgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(100), emptyList())

    private val allBuiltInWidgets = enabledWidgets.map { w ->
        widgetsService.getBuiltInWidgets().filter { b -> !w.any { it::class == b::class } }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    val builtInWidgets = allBuiltInWidgets
        .combine(searchQuery) { widgets, query ->
            if (query.isBlank()) return@combine widgets
            withContext(Dispatchers.IO) {
                val normalizedQuery = query.normalize()
                widgets.filter {
                    it.label.normalize().contains(normalizedQuery)
                }
            }
        }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    private val allAppWidgets = flow {
        val widgets = widgetsService.getAppWidgetProviders()
        emit(widgets)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    private val filteredAppWidgets = allAppWidgets
        .combine(searchQuery) { widgets, query ->
            if (query.isBlank()) return@combine widgets
            withContext(Dispatchers.IO) {
                val normalizedQuery = query.normalize()
                widgets.filter {
                    if (it.loadLabel(packageManager).normalize().contains(normalizedQuery)) {
                        return@filter true
                    }
                    val pkg = it.provider.packageName
                    val appInfo = try {
                        packageManager.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        return@filter false
                    }
                    appInfo.loadLabel(packageManager).toString().normalize()
                        .contains(normalizedQuery)
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    val expandAllGroups = filteredAppWidgets.map {
        it.size < 10
    }

    val appWidgetGroups = filteredAppWidgets.map { widgets ->
        withContext(Dispatchers.Default) {
            widgets
                .sortedBy { it.loadLabel(packageManager).normalize() }
                .groupBy {
                    it.provider.packageName
                }
                .map {
                    val pkg = it.key
                    val appInfo = try {
                        packageManager.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        return@map AppWidgetGroup("", pkg, emptyList())
                    }
                    AppWidgetGroup(appInfo.loadLabel(packageManager).toString(), pkg, it.value)
                }
                .sortedBy { it.appName.normalize() }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(100))

    val expandedGroup = mutableStateOf<String?>(null)

    fun pickWidget(widget: Widget) {
        val position = enabledWidgets.value.size
        widgetsService.addWidget(widget, position)
    }

    fun toggleGroup(group: String) {
        expandedGroup.value = if (expandedGroup.value == group) null else group
    }

    fun search(query: String) {
        searchQuery.value = query
    }

    companion object : KoinComponent {
        val Factory = viewModelFactory {
            initializer {
                WidgetPickerSheetVM(get(), get())
            }
        }
    }

}

data class AppWidgetGroup(
    val appName: String,
    val packageName: String,
    val widgets: List<AppWidgetProviderInfo>
)

data class BuiltInWidgetInfo(
    val type: String,
    @StringRes val label: Int,
    val icon: ImageVector
)