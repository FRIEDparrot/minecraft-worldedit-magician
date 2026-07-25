package com.magician.worldedit.client.config

import java.net.URI

/**
 * Configuration held for a future GitHub Copilot integration.
 *
 * GitHub Copilot does not expose a stable public API-key model-discovery endpoint. The optional
 * endpoint is therefore reserved for a user-managed compatible gateway; this object never probes it.
 */
data class CopilotProviderSettings(
	val endpoint: String = CopilotProviderSupport.DEFAULT_ENDPOINT,
	val accessToken: String = "",
	val selectedModel: String = CopilotProviderSupport.DEFAULT_SELECTED_MODEL,
)

data class CopilotConfigurationValidation(
	val errors: List<String> = emptyList(),
	val warnings: List<String> = emptyList(),
) {
	val isValid: Boolean
		get() = errors.isEmpty()
}

sealed interface CopilotModelCatalogResult {
	/** Copilot models must be selected from a supported authenticated integration or entered manually. */
	data class ManualSelectionRequired(val message: String) : CopilotModelCatalogResult
}

data class CopilotSetupGuidance(
	val title: String,
	val message: String,
	val requiresOAuth: Boolean,
)

object CopilotProviderSupport {
	/** Blank deliberately: no public Copilot API endpoint is assumed. */
	const val DEFAULT_ENDPOINT = ""
	const val DEFAULT_SELECTED_MODEL = ""

	fun validate(settings: CopilotProviderSettings): CopilotConfigurationValidation {
		val errors = mutableListOf<String>()
		val warnings = mutableListOf<String>()
		val endpoint = settings.endpoint.trim()

		if (endpoint.isNotEmpty()) {
			val uri = runCatching { URI(endpoint) }.getOrNull()
			if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
				errors += "Copilot endpoint must be a valid http:// or https:// URL with a host name."
			} else if (uri.userInfo != null) {
				errors += "Put Copilot credentials in the access-token field, not in the endpoint URL."
			}
		}

		if (settings.accessToken.isBlank()) {
			warnings += "A GitHub OAuth access token will be required when Copilot connection support is added."
		}
		if (settings.selectedModel.isBlank()) {
			warnings += "Select or enter a Copilot model after completing supported authentication."
		}

		return CopilotConfigurationValidation(errors, warnings)
	}

	fun modelCatalogResult(): CopilotModelCatalogResult =
		CopilotModelCatalogResult.ManualSelectionRequired(
			"GitHub Copilot does not provide a stable public API-key model catalog. Sign in through a supported GitHub OAuth flow when available, then select a model exposed by that integration or enter its model ID manually.",
		)

	fun setupGuidance(settings: CopilotProviderSettings): CopilotSetupGuidance {
		val customEndpointMessage = if (settings.endpoint.isBlank()) {
			"No endpoint is configured."
		} else {
			"A custom endpoint is configured and will require a compatible user-managed gateway."
		}

		return CopilotSetupGuidance(
			title = "GitHub Copilot setup",
			message = "GitHub Copilot requires supported GitHub authentication rather than a public API key. $customEndpointMessage Automatic model discovery is unavailable.",
			requiresOAuth = true,
		)
	}
}
