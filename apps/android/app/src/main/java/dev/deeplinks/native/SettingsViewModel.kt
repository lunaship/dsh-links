package dev.deeplinks.native

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.deeplinks.core.Host

/** Retains server-backed settings data across Activity recreation. */
internal class SettingsViewModel(
    host: Host?,
    initialSettings: AppSettings,
) : ViewModel() {
    val client = host?.let(::MobileApiClient)
    val llmGroups = mutableStateOf<List<MobileModelGroup>>(emptyList())
    val llmLoading = mutableStateOf(false)
    val llmError = mutableStateOf<String?>(null)
    val expandedProviders = mutableStateOf<Set<String>>(emptySet())
    val appSettings = mutableStateOf(initialSettings)
    val namespaceRevisions = mutableStateOf<Map<String, Long>>(emptyMap())
    val savingNamespace = mutableStateOf<String?>(null)
    val saveErrors = mutableStateOf<Map<String, String>>(emptyMap())
    val balance = mutableStateOf<MobileBalance?>(null)
    val balanceLoading = mutableStateOf(false)
    val balanceError = mutableStateOf<String?>(null)

    companion object {
        fun factory(host: Host?, initialSettings: AppSettings) = viewModelFactory {
            initializer {
                SettingsViewModel(host, initialSettings)
            }
        }
    }
}
