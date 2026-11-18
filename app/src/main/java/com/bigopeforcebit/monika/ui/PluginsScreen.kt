package com.bigopeforcebit.monika.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bigopeforcebit.monika.R
import com.bigopeforcebit.monika.i18n.trPlural
import com.bigopeforcebit.monika.plugin.PluginErrors
import com.bigopeforcebit.monika.plugin.PluginInstallException
import com.bigopeforcebit.monika.plugin.PluginInstaller
import com.bigopeforcebit.monika.plugin.PluginManifest
import com.bigopeforcebit.monika.plugin.PluginRecord
import com.bigopeforcebit.monika.plugin.PluginRegistry
import com.bigopeforcebit.monika.ui.components.FooterNote
import com.bigopeforcebit.monika.ui.components.GroupCard
import com.bigopeforcebit.monika.ui.components.GroupLabel
import com.bigopeforcebit.monika.ui.components.ListRow
import com.bigopeforcebit.monika.ui.components.MonikaSwitch
import com.bigopeforcebit.monika.ui.components.PageScaffold
import com.bigopeforcebit.monika.ui.components.RowChevron
import com.bigopeforcebit.monika.ui.theme.MonikaIcons
import com.bigopeforcebit.monika.ui.theme.MonikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置 › 插件:安装 zip、启停、卸载,只看详情(技能/权限),不提供工具和页面入口。 */
@Composable
fun PluginsScreen(registry: PluginRegistry, onBack: () -> Unit) {
    val plugins by registry.plugins.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { PluginInstaller(context.filesDir) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                installer.install(uri, context.contentResolver)
            }
            result.onSuccess { registry.install(it) }.onFailure { failure = it }
            installing = false
        }
    }

    val selected = selectedId?.let { id -> plugins.firstOrNull { it.id == id } }
    if (selected != null) {
        BackHandler { selectedId = null }
        PluginDetail(
            record = selected,
            registry = registry,
            onBack = { selectedId = null },
            onRemoved = { selectedId = null },
        )
    } else {
        PageScaffold(stringResource(R.string.plugins_title), onBack) {
            GroupCard {
                ListRow(
                    MonikaIcons.Plus,
                    if (installing) stringResource(R.string.plugins_installing) else stringResource(R.string.plugins_install),
                    first = true,
                    onClick = if (installing) null else ({ launcher.launch(arrayOf("application/zip")) }),
                ) { if (!installing) RowChevron() }
            }
            if (plugins.isEmpty()) {
                FooterNote(stringResource(R.string.plugins_empty) + "\n\n" + stringResource(R.string.plugins_empty_sub))
            } else {
                GroupLabel(stringResource(R.string.plugins_title))
                GroupCard {
                    plugins.forEachIndexed { i, record ->
                        ListRow(
                            MonikaIcons.Spark,
                            record.name,
                            subtitle = "${stringResource(R.string.plugins_version, record.version)} · " +
                                trPlural(R.plurals.plugins_skill_count, record.skillNames.size),
                            first = i == 0,
                            onClick = { selectedId = record.id },
                        ) {
                            MonikaSwitch(record.enabled, null)
                            RowChevron()
                        }
                    }
                }
            }
        }
    }

    failure?.let { e ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.plugins_title)) },
            text = { Text(installErrorText(e)) },
            confirmButton = { TextButton({ failure = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun PluginDetail(record: PluginRecord, registry: PluginRegistry, onBack: () -> Unit, onRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmUninstall by remember { mutableStateOf(false) }
    PageScaffold(record.name, onBack) {
        GroupCard {
            ListRow(
                null,
                stringResource(R.string.plugins_version, record.version),
                subtitle = record.description?.takeIf { it.isNotBlank() },
                first = true,
            )
            ListRow(null, stringResource(R.string.plugins_enabled)) {
                MonikaSwitch(record.enabled, { on ->
                    scope.launch(Dispatchers.IO) { registry.setEnabled(record.id, on) }
                }, Modifier.testTag("plugin-enabled"))
            }
            ListRow(
                null,
                stringResource(R.string.plugins_permissions),
                subtitle = if (record.permissions.isEmpty()) stringResource(R.string.plugins_none)
                else record.permissions.joinToString(),
            )
        }
        GroupLabel(stringResource(R.string.plugins_skills))
        GroupCard {
            if (record.skillNames.isEmpty()) {
                ListRow(null, stringResource(R.string.plugins_no_skills), first = true)
            } else {
                record.skillNames.forEachIndexed { i, name ->
                    ListRow(MonikaIcons.Doc, name, first = i == 0)
                }
            }
        }
        GroupCard {
            ListRow(
                MonikaIcons.Trash,
                stringResource(R.string.plugins_uninstall),
                titleColor = MonikaTheme.colors.bad,
                first = true,
                onClick = { confirmUninstall = true },
            )
        }
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.plugins_uninstall)) },
            text = { Text(stringResource(R.string.plugins_uninstall_body)) },
            confirmButton = {
                TextButton({
                    confirmUninstall = false
                    scope.launch {
                        withContext(Dispatchers.IO) { registry.uninstall(record.id) }
                        onRemoved()
                    }
                }) { Text(stringResource(R.string.plugins_uninstall)) }
            },
            dismissButton = { TextButton({ confirmUninstall = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun installErrorText(e: Throwable): String {
    val code = (e as? PluginInstallException)?.code
    return when (code) {
        PluginManifest.ERR_PERMISSIONS, PluginManifest.ERR_ONLY_SKILLS -> stringResource(R.string.plugins_unsupported_in_version)
        PluginManifest.ERR_MIN_APP_TOO_HIGH -> stringResource(R.string.plugins_error_too_new)
        PluginErrors.UNSAFE_PATH -> stringResource(R.string.plugins_error_unsafe)
        PluginErrors.TOO_LARGE -> stringResource(R.string.plugins_error_too_large)
        PluginErrors.MISSING_MANIFEST -> stringResource(R.string.plugins_error_missing_manifest)
        PluginErrors.INVALID_MANIFEST -> stringResource(R.string.plugins_error_invalid)
        else -> stringResource(R.string.plugins_install_failed, e.message ?: code ?: e.javaClass.simpleName)
    }
}
