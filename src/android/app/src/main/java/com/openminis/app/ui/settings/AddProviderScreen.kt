package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.auth.OpenRouterOAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import kotlinx.coroutines.launch
import java.util.UUID
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField

private enum class AddProviderStep {
    CHOOSE_TYPE,
    CHOOSE_CREDENTIAL,
    CONFIGURE,
}

@Composable
fun AddProviderScreen(
    providerRepository: ProviderRepository,
    openAIDeviceLoginViewModel: OpenAIDeviceLoginViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(AddProviderStep.CHOOSE_TYPE) }
    var selectedType by rememberSaveable { mutableStateOf<ProviderType?>(null) }
    var selectedCredential by rememberSaveable { mutableStateOf<ProviderCredential?>(null) }
    // [T-android-provider-voice] Non-null when the flow was entered from a
    // Voice Chat Provider template row — preseeds type/base URL/label/appendV1
    // on the configure step (mirrors iOS applyVoiceTemplate).
    var selectedVoiceTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedVoiceTemplate = remember(selectedVoiceTemplateId) {
        com.openminis.app.data.model.VoiceProviderTemplate.all
            .firstOrNull { it.id == selectedVoiceTemplateId }
    }

    // Unified back handler: reuse each step's onBack so predictive-back gesture
    // and the top-bar arrow behave identically (go back to prior step, not exit).
    val handleBack: () -> Unit = {
        if (
            step == AddProviderStep.CONFIGURE &&
            selectedType == ProviderType.openAI &&
            selectedCredential == ProviderCredential.oauth
        ) {
            openAIDeviceLoginViewModel.cancel()
        }
        when (step) {
            AddProviderStep.CHOOSE_TYPE -> onBack()
            AddProviderStep.CHOOSE_CREDENTIAL -> {
                step = AddProviderStep.CHOOSE_TYPE
                selectedType = null
            }
            AddProviderStep.CONFIGURE -> {
                // Voice-template entry skipped the credential step entirely —
                // back returns straight to the type/template list.
                if (selectedVoiceTemplate != null) {
                    step = AddProviderStep.CHOOSE_TYPE
                    selectedType = null
                    selectedVoiceTemplateId = null
                } else {
                    val creds = availableCredentials(selectedType!!)
                    if (creds.size == 1) {
                        step = AddProviderStep.CHOOSE_TYPE
                        selectedType = null
                    } else {
                        step = AddProviderStep.CHOOSE_CREDENTIAL
                    }
                }
                selectedCredential = null
            }
        }
    }

    // Intercept system back on inner steps; let outer handler pop the route on step 0.
    BackHandler(enabled = step != AddProviderStep.CHOOSE_TYPE) { handleBack() }

    when (step) {
        AddProviderStep.CHOOSE_TYPE -> ChooseProviderScreen(
            onBack = handleBack,
            onSelect = { type ->
                selectedType = type
                val creds = availableCredentials(type)
                if (creds.size == 1) {
                    // Skip credential picker if only one option
                    selectedCredential = creds.first()
                    step = AddProviderStep.CONFIGURE
                } else {
                    step = AddProviderStep.CHOOSE_CREDENTIAL
                }
            },
            onSelectVoiceTemplate = { template ->
                // Mirror iOS applyVoiceTemplate: pick the underlying protocol,
                // force API-key credential, jump straight to configure.
                selectedVoiceTemplateId = template.id
                selectedType = template.providerType
                selectedCredential = ProviderCredential.apiKey
                step = AddProviderStep.CONFIGURE
            },
        )
        AddProviderStep.CHOOSE_CREDENTIAL -> ChooseCredentialScreen(
            providerType = selectedType!!,
            onBack = handleBack,
            onSelect = { credential ->
                selectedCredential = credential
                step = AddProviderStep.CONFIGURE
            },
        )
        AddProviderStep.CONFIGURE -> ConfigureProviderScreen(
            providerType = selectedType!!,
            credentialType = selectedCredential!!,
            providerRepository = providerRepository,
            openAIDeviceLoginViewModel = openAIDeviceLoginViewModel,
            voiceTemplate = selectedVoiceTemplate,
            onBack = handleBack,
            onSaved = onSaved,
        )
    }
}

/** Display order matching iOS. */
private val providerDisplayOrder = listOf(
    ProviderType.openAI,
    ProviderType.anthropic,
    ProviderType.gemini,
    ProviderType.xAI,
    ProviderType.kimiCode,
    ProviderType.openRouter,
)

/** Icon and color per provider type, matching iOS SF Symbols. */
private fun providerIcon(type: ProviderType): Pair<ImageVector, Color> = when (type) {
    ProviderType.openAI -> Icons.Default.Hub to Color(0xFF4CAF50)           // green
    ProviderType.anthropic -> Icons.Default.AutoAwesome to Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Icons.Default.Diamond to Color(0xFF42A5F5)        // blue
    ProviderType.openRouter -> Icons.Default.AltRoute to Color(0xFF00BCD4)    // cyan
    ProviderType.xAI -> Icons.Default.FlashOn to Color(0xFFFF7043)           // orange — Grok visual cue
    // [T-kimi-oauth] Indigo — matches iOS's Kimi accent.
    ProviderType.kimiCode -> Icons.Default.Terminal to Color(0xFF5C6BC0)
}

/** Returns available credential types per provider. */
private fun availableCredentials(type: ProviderType): List<ProviderCredential> {
    return when (type) {
        ProviderType.openRouter -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.anthropic,
        ProviderType.openAI -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.gemini -> listOf(ProviderCredential.apiKey)
        // xAI Grok primarily targets SuperGrok / X Premium+ OAuth, but also
        // offers a plain API key path (api.x.ai). Mirror OpenAI's pattern
        // of exposing both so users on tiers without OAuth API access can
        // still configure manually.
        ProviderType.xAI -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        // [T-kimi-oauth] Primary target is the Coding Plan device-code
        // sign-in; a manual Moonshot API key remains available.
        ProviderType.kimiCode -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
    }
}

// -- Step 1: Choose Provider Type --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseProviderScreen(
    onBack: () -> Unit,
    onSelect: (ProviderType) -> Unit,
    onSelectVoiceTemplate: (com.openminis.app.data.model.VoiceProviderTemplate) -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(R.string.provider_list_add_provider),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_choose_provider),
            footer = stringResource(R.string.add_provider_you_can_add_multiple_instances_of_the_sa),
        ) {
            providerDisplayOrder.forEachIndexed { index, type ->
                val displayTitle = when (type) {
                    ProviderType.openAI -> "OpenAI / Compatible API"
                    ProviderType.anthropic -> "Anthropic / Compatible API"
                    ProviderType.gemini -> "Google Gemini"
                    ProviderType.openRouter -> "OpenRouter"
                    ProviderType.xAI -> "xAI (Grok)"
                    ProviderType.kimiCode -> "Kimi Code"
                }
                // Describe which vendors each protocol supports, rather than a
                // raw built-in model count.
                val subtitleRes = when (type) {
                    ProviderType.openAI -> R.string.add_provider_subtitle_openai
                    ProviderType.anthropic -> R.string.add_provider_subtitle_anthropic
                    ProviderType.gemini -> R.string.add_provider_subtitle_gemini
                    ProviderType.openRouter -> R.string.add_provider_subtitle_openrouter
                    ProviderType.xAI -> R.string.add_provider_subtitle_xai
                    ProviderType.kimiCode -> R.string.add_provider_subtitle_kimi
                }
                val (icon, iconColor) = providerIcon(type)
                SettingsRow(
                    title = displayTitle,
                    subtitle = stringResource(subtitleRes),
                    icon = icon,
                    iconColor = iconColor,
                    onClick = { onSelect(type) },
                    showDivider = index < providerDisplayOrder.size - 1,
                )
            }
        }

        // [T-android-provider-voice] Voice Chat Providers — one row per voice
        // vendor template. Tapping prefills the underlying protocol + base URL
        // and jumps to configure (mirrors iOS voiceProviderSection).
        val templates = com.openminis.app.data.model.VoiceProviderTemplate.all
        val templateNotes = templates.mapNotNull { it.note }
        SettingsSection(
            header = stringResource(R.string.add_provider_voice_chat_providers),
            footer = (
                listOf(stringResource(R.string.add_provider_voice_templates_footer)) + templateNotes
                ).joinToString("\n"),
        ) {
            templates.forEachIndexed { index, template ->
                val capabilityRes = when (template.capability) {
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.TTS ->
                        R.string.add_provider_voice_capability_tts
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.ASR ->
                        R.string.add_provider_voice_capability_asr
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.BOTH ->
                        R.string.add_provider_voice_capability_both
                }
                SettingsRow(
                    title = template.name,
                    subtitle = stringResource(capabilityRes),
                    icon = Icons.Outlined.GraphicEq,
                    onClick = { onSelectVoiceTemplate(template) },
                    showDivider = index < templates.size - 1,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// -- Step 2: Choose Credential Type --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseCredentialScreen(
    providerType: ProviderType,
    onBack: () -> Unit,
    onSelect: (ProviderCredential) -> Unit,
) {
    val credentials = availableCredentials(providerType)

    SettingsScaffold(
        title = stringResource(R.string.add_provider_auth_method),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_choose_authentication),
            footer = stringResource(R.string.add_provider_pick_the_auth_method_that_matches_your_a),
        ) {
            credentials.forEachIndexed { index, credential ->
                val (title, description, icon) = when (credential) {
                    ProviderCredential.apiKey -> Triple(
                        stringResource(R.string.provider_list_api_key),
                        apiKeyDescription(providerType),
                        Icons.Default.Key,
                    )
                    ProviderCredential.oauth -> Triple(
                        "OAuth",
                        oauthDescription(providerType),
                        Icons.Default.Person,
                    )
                }
                SettingsRow(
                    title = title,
                    subtitle = description,
                    icon = icon,
                    onClick = { onSelect(credential) },
                    showDivider = index < credentials.size - 1,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun apiKeyDescription(type: ProviderType): String = when (type) {
    ProviderType.openAI -> "Supports OpenAI official API and compatible third-party endpoints"
    ProviderType.anthropic -> "Use an API key from your Anthropic account"
    ProviderType.gemini -> "Use an API key from your Google Gemini account"
    ProviderType.openRouter -> "Use an API key from your OpenRouter account"
    ProviderType.xAI -> "Use an API key from your xAI Console (api.x.ai)"
    ProviderType.kimiCode -> "Use an API key from your Moonshot account"
}

private fun oauthDescription(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "Sign in with your Claude account"
    ProviderType.gemini -> "Sign in with Google for Cloud Code Assist"
    ProviderType.openAI -> "Sign in with OpenAI Codex"
    ProviderType.xAI -> "Sign in with xAI (requires SuperGrok or X Premium+)"
    ProviderType.openRouter -> "Sign in with OpenRouter"
    ProviderType.kimiCode -> "Sign in with your Kimi account (Coding Plan)"
}

// -- Step 3: Configure & Save --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureProviderScreen(
    providerType: ProviderType,
    credentialType: ProviderCredential,
    providerRepository: ProviderRepository,
    openAIDeviceLoginViewModel: OpenAIDeviceLoginViewModel,
    voiceTemplate: com.openminis.app.data.model.VoiceProviderTemplate? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    // Compute default label with auto-increment (e.g. "OpenAI", "OpenAI 2", ...)
    // A voice template preseeds its vendor name instead of the protocol name.
    val config by providerRepository.config.collectAsState()
    val defaultLabel = remember(config) {
        val baseName = voiceTemplate?.name ?: providerType.displayName
        val existingLabels = config.instances.map { it.label }.toSet()
        if (baseName !in existingLabels) baseName
        else {
            var n = 2
            while ("$baseName $n" in existingLabels) n++
            "$baseName $n"
        }
    }

    var label by rememberSaveable { mutableStateOf(defaultLabel) }
    // [T-provider-name-chinese-34602, port iOS 7b283951] Flips true the
    // first time the user types into the label field. While `false`, the
    // LaunchedEffect below keeps `label` glued to the auto-incremented
    // default, so adding a second OpenAI instance picks up "OpenAI 2"
    // automatically. Once the user edits the label (even just to clear
    // it for typing — including non-ASCII like CJK / kana / emoji),
    // the seed is suppressed so further provider-list changes never
    // clobber what they typed. Without this gate the `remember(config)`
    // recomputation, combined with Compose tearing down + recreating
    // ConfigureProviderScreen on step navigation, makes the field
    // appear to reject Chinese — the iOS root cause Telegram 34602
    // reported, with the same Android equivalent here.
    var labelEdited by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(defaultLabel, labelEdited) {
        if (!labelEdited) label = defaultLabel
    }
    var apiKey by remember { mutableStateOf("") }
    var customBaseURL by rememberSaveable { mutableStateOf(voiceTemplate?.baseURL ?: "") }

    SettingsScaffold(
        title = stringResource(R.string.add_provider_configure_provider, providerType.displayName),
        onBack = onBack,
    ) {
        // Identity section — Label only. Each provider auto-suggests a
        // unique label so users don't have to type one for the common case.
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_provider_the_label_is_shown_in_the_provider_list_),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.provider_detail_label))
                SectionTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        labelEdited = true
                    },
                    placeholder = providerType.displayName,
                    singleLine = true,
                )
            }
        }

        when (credentialType) {
            ProviderCredential.apiKey -> ApiKeyConfigSection(
                providerType = providerType,
                label = label,
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                customBaseURL = customBaseURL,
                onCustomBaseURLChange = { customBaseURL = it },
                providerRepository = providerRepository,
                initialAppendV1 = voiceTemplate?.appendV1,
                onSaved = onSaved,
            )
            ProviderCredential.oauth -> OAuthConfigSection(
                providerType = providerType,
                label = label,
                providerRepository = providerRepository,
                openAIDeviceLoginViewModel = openAIDeviceLoginViewModel,
                onSaved = onSaved,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.ApiKeyConfigSection(
    providerType: ProviderType,
    label: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customBaseURL: String,
    onCustomBaseURLChange: (String) -> Unit,
    providerRepository: ProviderRepository,
    // [T-android-provider-voice] Voice templates carry their own v1-suffix
    // policy (e.g. MiMo appends /v1, ElevenLabs must not). null = type default.
    initialAppendV1: Boolean? = null,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showApiKeyPlaintext by remember { mutableStateOf(false) }
    var appendV1Suffix by remember {
        mutableStateOf(initialAppendV1 ?: (providerType != ProviderType.gemini))
    }
    // OpenAI API Format: false = Chat Completions, true = Responses API
    var useResponsesAPI by remember { mutableStateOf(false) }

    // ── Credential ──────────────────────────────────────────────────────
    val keyPlaceholder = when (providerType) {
        ProviderType.anthropic -> "sk-ant-..."
        ProviderType.openAI -> "sk-..."
        ProviderType.gemini -> "Gemini API Key..."
        ProviderType.openRouter -> "sk-or-..."
        ProviderType.xAI -> "xai-..."
        ProviderType.kimiCode -> "sk-..."
    }
    SettingsSection(
        header = stringResource(R.string.add_provider_credential),
        footer = stringResource(R.string.add_provider_your_key_is_stored_securely_in_encrypted),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.provider_list_api_key))
            SectionTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = keyPlaceholder,
                singleLine = true,
                visualTransformation = if (showApiKeyPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKeyPlaintext = !showApiKeyPlaintext }) {
                        Icon(
                            if (showApiKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKeyPlaintext) "Hide" else "Show",
                        )
                    }
                },
            )
        }
    }

    // ── Endpoint (skip for OpenRouter — fixed base URL) ─────────────────
    if (providerType != ProviderType.openRouter) {
        val defaultUrl = when (providerType) {
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.openAI -> "https://api.openai.com"
            else -> "https://api.example.com"
        }
        // T-mimo-anthropic-endpoint-android: Anthropic third-party
        // compatible services (e.g. Mimo) frequently host the Anthropic
        // surface under a path suffix like "/anthropic" rather than at
        // the host root. Users routinely paste just the host
        // (https://token-plan-cn.xiaomimimo.com) and hit 404 because
        // /v1/messages then resolves to the wrong route. Surface a hint
        // on the Anthropic endpoint footer so this can be discovered
        // without scraping issue threads. Default Anthropic + other
        // provider types keep their original footer copy.
        val baseUrlFooter = if (providerType == ProviderType.gemini) {
            "Leave empty to use the default Google endpoint. Enter the full base URL including version path."
        } else if (providerType == ProviderType.anthropic) {
            stringResource(R.string.add_provider_endpoint_anthropic_hint)
        } else if (appendV1Suffix) {
            "Leave empty to use the default endpoint. \"/v1\" is appended automatically — enter the base host only."
        } else {
            "The URL is used verbatim. Include the full path up to (but not including) the endpoint."
        }
        SettingsSection(
            header = stringResource(R.string.add_provider_endpoint),
            footer = baseUrlFooter,
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
                SectionTextField(
                    value = customBaseURL,
                    onValueChange = onCustomBaseURLChange,
                    placeholder = defaultUrl,
                    singleLine = true,
                )
            }
            // Auto Append "/v1" toggle (not for Gemini — Gemini uses full path)
            if (providerType != ProviderType.gemini) {
                SettingsSwitchRow(
                    title = stringResource(R.string.add_provider_auto_append_v1_quoted),
                    checked = appendV1Suffix,
                    onCheckedChange = { appendV1Suffix = it },
                    showDivider = false,
                )
            }
        }
    }

    // ── API Format (OpenAI only) ────────────────────────────────────────
    if (providerType == ProviderType.openAI) {
        SettingsSection(
            header = stringResource(R.string.provider_detail_api_format),
            footer = if (useResponsesAPI) {
                "Uses /v1/responses endpoint format. Required for some Responses-API-only services."
            } else {
                "Standard /v1/chat/completions format. Compatible with most OpenAI-compatible services."
            },
        ) {
            SettingsCardBlock {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useResponsesAPI,
                        onClick = { useResponsesAPI = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                    SegmentedButton(
                        selected = useResponsesAPI,
                        onClick = { useResponsesAPI = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                }
            }
        }
    }

    // ── Save button (outside any section — terminal action) ────────────
    Spacer(Modifier.height(20.dp))
    MinisButton(
        onClick = {
            val trimmedBase = customBaseURL.trim()
            val instance = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { providerType.displayName },
                providerType = providerType,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = trimmedBase.ifEmpty { null },
                appendV1Suffix = appendV1Suffix,
                // Only OpenAI-family providers expose the Responses API toggle.
                useResponsesAPI = providerType == ProviderType.openAI && useResponsesAPI,
            )
            providerRepository.addInstance(instance)
            providerRepository.saveApiKey(instance.id, apiKey.trim())
            // Auto-refresh models in background (fetches from API or falls back to models.dev)
            scope.launch { providerRepository.refreshModels(instance) }
            onSaved()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        enabled = apiKey.isNotBlank(),
    ) {
        Text(stringResource(R.string.provider_list_add_provider))
    }
}

@Composable
private fun ColumnScope.OAuthConfigSection(
    providerType: ProviderType,
    label: String,
    providerRepository: ProviderRepository,
    openAIDeviceLoginViewModel: OpenAIDeviceLoginViewModel,
    onSaved: () -> Unit,
) {
    if (providerType == ProviderType.openAI) {
        OpenAIOAuthConfigSection(
            label = label,
            providerRepository = providerRepository,
            viewModel = openAIDeviceLoginViewModel,
            onSaved = onSaved,
        )
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingInstanceId = remember { UUID.randomUUID().toString() }
    var isAuthenticating by remember { mutableStateOf(false) }
    var isAuthenticated by remember { mutableStateOf(false) }
    var maskedToken by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Manual OAuth token entry state
    var manualToken by remember { mutableStateOf("") }
    var customBaseURL by remember { mutableStateOf("") }
    var appendV1Suffix by remember { mutableStateOf(providerType != ProviderType.gemini) }

    val signInLabel = when (providerType) {
        ProviderType.anthropic -> "Sign in with Claude"
        ProviderType.gemini -> "Sign in with Google"
        ProviderType.openAI -> "Sign in with OpenAI"
        ProviderType.openRouter -> "Sign in with OpenRouter"
        ProviderType.xAI -> "Sign in with xAI"
        ProviderType.kimiCode -> "Sign in with Kimi Code"
    }

    // [T-kimi-oauth] Device-code dialog state: non-null while a Kimi login is
    // waiting for the user to authorize on auth.kimi.com. Set by the login
    // coroutine's onDeviceCode callback and RENDERED below — the iOS lesson
    // was a write-only state nobody displayed ("button does nothing").
    var kimiDeviceAuth by remember {
        mutableStateOf<com.openminis.app.auth.KimiDeviceFlow.DeviceAuthorization?>(null)
    }
    var kimiLoginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    kimiDeviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.openUrl,
            onCancel = {
                kimiLoginJob?.cancel()
                kimiDeviceAuth = null
            },
        )
    }

    if (isAuthenticated) {
        // ── Authenticated state — Token + Save ─────────────────────────
        SettingsSection(
            header = stringResource(R.string.add_provider_authentication),
            footer = stringResource(R.string.add_provider_sign_in_succeeded_the_token_is_stored_in),
        ) {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_provider_authenticated), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                if (maskedToken != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.add_provider_token), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        maskedToken!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                val instance = ProviderInstance(
                    id = pendingInstanceId,
                    label = label.ifBlank { providerType.displayName },
                    providerType = providerType,
                    credentialType = ProviderCredential.oauth,
                )
                providerRepository.addInstance(instance)
                // Auto-refresh models (fetches from API or falls back to models.dev)
                scope.launch { providerRepository.refreshModels(instance) }
                onSaved()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.add_provider_save_provider))
        }
    } else {
        // ── Sign In ────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.provider_detail_sign_in),
            footer = stringResource(R.string.add_provider_opens_the_provider_s_web_sign_in_flow_af),
        ) {
            SettingsCardBlock {
                MinisButton(
                    onClick = {
                        isAuthenticating = true
                        errorMessage = null
                        kimiLoginJob = scope.launch {
                            try {
                                when (providerType) {
                                    ProviderType.kimiCode -> {
                                        // [T-kimi-oauth] Device-code flow: the
                                        // onDeviceCode callback flips the dialog
                                        // state as soon as the code is issued;
                                        // login() keeps polling underneath and
                                        // returns once the user authorizes.
                                        val key = com.openminis.app.auth.KimiOAuthManager.login(
                                            context, pendingInstanceId, providerRepository,
                                            onDeviceCode = { auth -> kimiDeviceAuth = auth },
                                        )
                                        kimiDeviceAuth = null
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.openRouter -> {
                                        val key = OpenRouterOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.anthropic -> {
                                        val key = com.openminis.app.auth.ClaudeOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.openAI -> {
                                        val key = OpenAIOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.xAI -> {
                                        val key = com.openminis.app.auth.XAIOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    else -> {
                                        throw Exception("OAuth not yet implemented for ${providerType.displayName}")
                                    }
                                }
                                isAuthenticated = true
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // [T-kimi-oauth] User dismissed the device-code
                                // dialog — not an error; just reset the button.
                                kimiDeviceAuth = null
                                throw e
                            } catch (e: Exception) {
                                kimiDeviceAuth = null
                                // T-android-codex-oauth-dns: surface a
                                // localized "check network / proxy" hint
                                // when the OAuth manager flags a DNS /
                                // socket-timeout failure. Other failures
                                // (4xx, token format) keep their raw
                                // message so we don't lose diagnostic
                                // signal.
                                errorMessage = if (e is com.openminis.app.auth.OAuthNetworkUnreachableException) {
                                    context.getString(R.string.add_provider_oauth_network_unreachable)
                                } else {
                                    e.message ?: "Authentication failed"
                                }
                            } finally {
                                isAuthenticating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAuthenticating,
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_provider_signing_in))
                    } else {
                        Text(signInLabel)
                    }
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ── Or Configure Manually ──────────────────────────────────────
        val defaultUrl = when (providerType) {
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.openAI -> "https://api.openai.com"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            // [T-kimi-oauth] /v1 is load-bearing (…/coding/… 404s without it).
            ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        }
        SettingsSection(
            header = stringResource(R.string.add_provider_or_configure_manually),
            footer = stringResource(R.string.add_provider_for_third_party_coding_plans_e_g_minimax),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
                SectionTextField(
                    value = customBaseURL,
                    onValueChange = { customBaseURL = it },
                    placeholder = defaultUrl,
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.add_provider_bearer_token))
                SectionTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (providerType != ProviderType.gemini) {
                SettingsSwitchRow(
                    title = stringResource(R.string.add_provider_auto_append_v1_quoted),
                    checked = appendV1Suffix,
                    onCheckedChange = { appendV1Suffix = it },
                    showDivider = false,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                val trimmedBase = customBaseURL.trim()
                val instance = ProviderInstance(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { providerType.displayName },
                    providerType = providerType,
                    credentialType = ProviderCredential.oauth,
                    customBaseURL = trimmedBase.ifEmpty { null },
                    appendV1Suffix = appendV1Suffix,
                )
                providerRepository.addInstance(instance)
                // Store the manual token as API key — ProviderFactory uses loadApiKey() for all credential types
                providerRepository.saveApiKey(instance.id, manualToken.trim())
                scope.launch { providerRepository.refreshModels(instance) }
                onSaved()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            enabled = manualToken.isNotBlank(),
        ) {
            Text(stringResource(R.string.provider_list_add_provider))
        }
    }
}

/**
 * OpenAI 新建 Provider 的三条明确路径：设备码（推荐）、原浏览器回调、手动 Bearer。
 * 设备码状态与提交完全由路由级 ViewModel 持有；此 UI 不接触 Token 租约。
 */
@Composable
private fun ColumnScope.OpenAIOAuthConfigSection(
    label: String,
    providerRepository: ProviderRepository,
    viewModel: OpenAIDeviceLoginViewModel,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceState by viewModel.state.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val browserInstanceId = remember { UUID.randomUUID().toString() }
    var browserAuthenticating by remember { mutableStateOf(false) }
    var browserAuthenticated by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var manualToken by remember { mutableStateOf("") }
    var customBaseURL by rememberSaveable { mutableStateOf("") }
    var appendV1Suffix by rememberSaveable { mutableStateOf(true) }
    val devicePathOwnsScreen =
        deviceState is com.openminis.app.auth.OpenAIDeviceLoginState.RequestingCode ||
            deviceState is com.openminis.app.auth.OpenAIDeviceLoginState.WaitingForUser ||
            deviceState is com.openminis.app.auth.OpenAIDeviceLoginState.ExchangingToken ||
            deviceState is com.openminis.app.auth.OpenAIDeviceLoginState.Authenticated ||
            saveState == OpenAIDeviceProviderSaveState.Saving ||
            saveState == OpenAIDeviceProviderSaveState.Saved ||
            saveState == OpenAIDeviceProviderSaveState.SavedWithModelLoadFailure
    val browserPathOwnsScreen = browserAuthenticating || browserAuthenticated

    androidx.compose.runtime.LaunchedEffect(customBaseURL) {
        if (customBaseURL.isNotBlank()) viewModel.cancel()
    }
    androidx.compose.runtime.LaunchedEffect(saveState) {
        if (saveState == OpenAIDeviceProviderSaveState.Saved) onSaved()
    }

    (deviceState as? com.openminis.app.auth.OpenAIDeviceLoginState.WaitingForUser)
        ?.let { waiting ->
            OpenAIDeviceLoginDialog(
                attemptId = waiting.attemptId,
                userCode = waiting.userCode,
                verificationUrl = waiting.verificationUrl.toString(),
                claimAutomaticBrowserOpen = viewModel::claimAutomaticBrowserOpen,
                onCancel = viewModel::cancel,
            )
        }

    SettingsSection(
        header = stringResource(R.string.openai_device_sign_in),
        footer = if (customBaseURL.isBlank()) {
            stringResource(R.string.openai_device_recommended)
        } else {
            stringResource(R.string.openai_device_official_only)
        },
    ) {
        SettingsCardBlock {
            if (customBaseURL.isBlank()) {
                when (deviceState) {
                    com.openminis.app.auth.OpenAIDeviceLoginState.Idle,
                    com.openminis.app.auth.OpenAIDeviceLoginState.Cancelled,
                    com.openminis.app.auth.OpenAIDeviceLoginState.Expired,
                    com.openminis.app.auth.OpenAIDeviceLoginState.Unsupported,
                    is com.openminis.app.auth.OpenAIDeviceLoginState.NetworkError,
                    is com.openminis.app.auth.OpenAIDeviceLoginState.AuthFailed,
                    -> {
                        val messageRes = when (saveState) {
                            is OpenAIDeviceProviderSaveState.Failed ->
                                R.string.openai_device_save_failed
                            OpenAIDeviceProviderSaveState.StaleAttempt ->
                                R.string.openai_device_save_stale
                            else -> when (deviceState) {
                            com.openminis.app.auth.OpenAIDeviceLoginState.Cancelled ->
                                R.string.openai_device_cancelled
                            com.openminis.app.auth.OpenAIDeviceLoginState.Expired ->
                                R.string.openai_device_expired
                            com.openminis.app.auth.OpenAIDeviceLoginState.Unsupported ->
                                R.string.openai_device_unsupported
                            is com.openminis.app.auth.OpenAIDeviceLoginState.NetworkError ->
                                R.string.openai_device_network_error
                            is com.openminis.app.auth.OpenAIDeviceLoginState.AuthFailed ->
                                R.string.openai_device_auth_failed
                            else -> null
                            }
                        }
                        messageRes?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        MinisButton(
                            onClick = { viewModel.start() },
                            enabled = !browserPathOwnsScreen,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (deviceState == com.openminis.app.auth.OpenAIDeviceLoginState.Idle) {
                                        R.string.openai_device_sign_in
                                    } else {
                                        R.string.openai_device_retry
                                    },
                                ),
                            )
                        }
                    }

                    is com.openminis.app.auth.OpenAIDeviceLoginState.RequestingCode -> {
                        InlineOpenAIDeviceProgress(
                            stringResource(R.string.openai_device_requesting),
                            onCancel = viewModel::cancel,
                        )
                    }

                    is com.openminis.app.auth.OpenAIDeviceLoginState.WaitingForUser -> {
                        InlineOpenAIDeviceProgress(
                            stringResource(R.string.openai_device_waiting),
                            onCancel = viewModel::cancel,
                        )
                    }

                    is com.openminis.app.auth.OpenAIDeviceLoginState.ExchangingToken -> {
                        InlineOpenAIDeviceProgress(
                            stringResource(R.string.openai_device_exchanging),
                            onCancel = viewModel::cancel,
                        )
                    }

                    is com.openminis.app.auth.OpenAIDeviceLoginState.Authenticated -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.add_provider_authenticated),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        when (saveState) {
                            OpenAIDeviceProviderSaveState.Saving ->
                                InlineOpenAIDeviceProgress(
                                    stringResource(R.string.add_provider_save_provider),
                                )

                            is OpenAIDeviceProviderSaveState.Failed -> {
                                Text(
                                    stringResource(R.string.openai_device_save_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                MinisButton(
                                    onClick = { viewModel.start() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.openai_device_retry))
                                }
                            }

                            OpenAIDeviceProviderSaveState.StaleAttempt -> {
                                Text(
                                    stringResource(R.string.openai_device_save_stale),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                MinisButton(
                                    onClick = { viewModel.start() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.openai_device_retry))
                                }
                            }

                            OpenAIDeviceProviderSaveState.SavedWithModelLoadFailure -> {
                                Text(
                                    stringResource(R.string.openai_device_model_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                MinisButton(
                                    onClick = { viewModel.retryModelLoad() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.openai_device_retry_models))
                                }
                                Spacer(Modifier.height(8.dp))
                                MinisButton(
                                    onClick = onSaved,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.add_provider_save_provider))
                                }
                            }

                            else -> {
                                MinisButton(
                                    onClick = { viewModel.saveProvider(label) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.add_provider_save_provider))
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.openai_device_official_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SettingsSection(
        header = stringResource(R.string.openai_browser_sign_in),
        footer = stringResource(R.string.openai_browser_sign_in_description),
    ) {
        SettingsCardBlock {
            if (browserAuthenticated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_provider_authenticated))
                }
                Spacer(Modifier.height(10.dp))
                MinisButton(
                    onClick = {
                        val instance = ProviderInstance(
                            id = browserInstanceId,
                            label = label.ifBlank { ProviderType.openAI.displayName },
                            providerType = ProviderType.openAI,
                            credentialType = ProviderCredential.oauth,
                        )
                        providerRepository.addInstance(instance)
                        scope.launch { providerRepository.refreshModels(instance) }
                        onSaved()
                    },
                    enabled = !devicePathOwnsScreen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_provider_save_provider))
                }
            } else {
                MinisButton(
                    onClick = {
                        viewModel.cancel()
                        browserAuthenticating = true
                        browserError = null
                        scope.launch {
                            try {
                                OpenAIOAuthManager.login(
                                    context,
                                    browserInstanceId,
                                    providerRepository,
                                )
                                browserAuthenticated = true
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                browserError =
                                    if (error is com.openminis.app.auth.OAuthNetworkUnreachableException) {
                                        context.getString(
                                            R.string.add_provider_oauth_network_unreachable,
                                        )
                                    } else {
                                        error.message
                                            ?: context.getString(R.string.openai_device_auth_failed)
                                    }
                            } finally {
                                browserAuthenticating = false
                            }
                        }
                    },
                    enabled = !browserAuthenticating && !devicePathOwnsScreen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (browserAuthenticating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (browserAuthenticating) {
                                R.string.add_provider_signing_in
                            } else {
                                R.string.openai_browser_sign_in
                            },
                        ),
                    )
                }
                browserError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    SettingsSection(
        header = stringResource(R.string.add_provider_or_configure_manually),
        footer = stringResource(R.string.add_provider_for_third_party_coding_plans_e_g_minimax),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
            SectionTextField(
                value = customBaseURL,
                onValueChange = { customBaseURL = it },
                placeholder = "https://api.openai.com",
                singleLine = true,
                enabled = !devicePathOwnsScreen && !browserPathOwnsScreen,
            )
            Spacer(Modifier.height(12.dp))
            RowLabel(text = stringResource(R.string.add_provider_bearer_token))
            SectionTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                singleLine = true,
                enabled = !devicePathOwnsScreen && !browserPathOwnsScreen,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        SettingsSwitchRow(
            title = stringResource(R.string.add_provider_auto_append_v1_quoted),
            checked = appendV1Suffix,
            onCheckedChange = { appendV1Suffix = it },
            enabled = !devicePathOwnsScreen && !browserPathOwnsScreen,
            showDivider = false,
        )
    }
    Spacer(Modifier.height(20.dp))
    MinisButton(
        onClick = {
            viewModel.cancel()
            val instance = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { ProviderType.openAI.displayName },
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.oauth,
                customBaseURL = customBaseURL.trim().ifBlank { null },
                appendV1Suffix = appendV1Suffix,
            )
            providerRepository.addInstance(instance)
            providerRepository.saveApiKey(instance.id, manualToken.trim())
            scope.launch { providerRepository.refreshModels(instance) }
            onSaved()
        },
        enabled =
            manualToken.isNotBlank() &&
                !devicePathOwnsScreen &&
                !browserPathOwnsScreen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(stringResource(R.string.provider_list_add_provider))
    }
}

@Composable
private fun InlineOpenAIDeviceProgress(
    message: String,
    onCancel: (() -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onCancel?.let {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = it) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

private fun maskOAuthToken(token: String): String {
    if (token.length <= 4) return "*".repeat(token.length)
    val edge = minOf(10, token.length / 3)
    return token.take(edge) + "*".repeat(10) + token.takeLast(edge)
}
